package com.versa.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val gate = Semaphore(4)
    private val heavyGate = Semaphore(2)
    private var diskDir: File? = null

    @Suppress("DEPRECATION")
    private val webp: Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    fun init(context: Context) {
        if (diskDir != null) return
        val dir = File(context.cacheDir, "thumbs")
        dir.mkdirs()
        diskDir = dir
        thread(isDaemon = true) { prune(dir) }
    }

    private fun prune(dir: File) {
        try {
            val files = dir.listFiles() ?: return
            var total = files.sumOf { it.length() }
            val limit = 150L * 1024 * 1024
            if (total <= limit) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= limit * 2 / 3) break
                total -= f.length()
                f.delete()
            }
        } catch (e: Throwable) {
            // ignore
        }
    }

    fun clear() {
        cache.evictAll()
        failed.clear()
        diskDir?.listFiles()?.forEach { it.delete() }
    }

    fun supports(kind: FileKind): Boolean =
        kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.AUDIO ||
            kind == FileKind.APK || kind == FileKind.PDF

    private fun key(item: FileItem, px: Int) = "${item.path}|${item.modified}|$px"

    fun peek(item: FileItem, px: Int): Bitmap? = cache.get(key(item, px))

    private fun diskFile(k: String): File? {
        val dir = diskDir ?: return null
        val md = MessageDigest.getInstance("MD5").digest(k.toByteArray())
        return File(dir, md.joinToString("") { "%02x".format(it) } + ".webp")
    }

    private fun readDisk(k: String): Bitmap? {
        val f = diskFile(k) ?: return null
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private fun writeDisk(k: String, bmp: Bitmap) {
        try {
            val f = diskFile(k) ?: return
            FileOutputStream(f).use { bmp.compress(webp, 80, it) }
        } catch (e: Throwable) {
            // ignore
        }
    }

    suspend fun load(context: Context, item: FileItem, px: Int): Bitmap? {
        val k = key(item, px)
        cache.get(k)?.let { return it }
        if (failed.contains(k)) return null
        return withContext(Dispatchers.IO) {
            val cached = readDisk(k)
            if (cached != null) {
                cache.put(k, cached)
                return@withContext cached
            }
            val g = if (item.kind == FileKind.VIDEO || item.kind == FileKind.PDF) heavyGate else gate
            g.withPermit {
                val bmp = try {
                    create(context, item.file, item.kind, px)
                } catch (e: Throwable) {
                    null
                }
                if (bmp != null) {
                    cache.put(k, bmp)
                    writeDisk(k, bmp)
                } else {
                    failed.add(k)
                }
                bmp
            }
        }
    }

    private fun create(context: Context, file: File, kind: FileKind, px: Int): Bitmap? =
        when (kind) {
            FileKind.IMAGE -> decodeImage(file, px)
            FileKind.VIDEO -> videoFrame(file, px)
            FileKind.AUDIO -> audioArt(file, px)
            FileKind.APK -> apkIcon(context, file, px)
            FileKind.PDF -> pdfPage(file, px)
            else -> null
        }

    fun decodeImage(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return applyExif(file, raw)
    }

    private fun applyExif(file: File, bmp: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
            else -> return bmp
        }
        return try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (e: Throwable) {
            bmp
        }
    }

    private fun scaleDown(b: Bitmap, px: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= px) return b
        val s = px.toFloat() / m
        return Bitmap.createScaledBitmap(
            b,
            (b.width * s).toInt().coerceAtLeast(1),
            (b.height * s).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun videoFrame(file: File, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0L)
                ?: return null
            return scaleDown(frame, px)
        } finally {
            r.release()
        }
    }

    private fun audioArt(file: File, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val art = r.embeddedPicture ?: return null
            val b = BitmapFactory.decodeByteArray(art, 0, art.size) ?: return null
            return scaleDown(b, px)
        } finally {
            r.release()
        }
    }

    private fun apkIcon(context: Context, file: File, px: Int): Bitmap? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = file.absolutePath
        ai.publicSourceDir = file.absolutePath
        val d: Drawable = ai.loadIcon(pm) ?: return null
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else px
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else px
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return scaleDown(bmp, px)
    }

    private fun pdfPage(file: File, px: Int): Bitmap? {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                if (renderer.pageCount == 0) {
                    null
                } else {
                    val page = renderer.openPage(0)
                    try {
                        val scale = px.toFloat() / maxOf(page.width, page.height)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(AColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
            }
        }
    }
}

/** Lazily counts folder children (only for rows that are actually visible). */
object Counts {
    private val cache = LruCache<String, Int>(3000)

    private fun key(item: FileItem) = item.path + "|" + item.modified

    fun peek(item: FileItem): Int? = cache.get(key(item))

    suspend fun count(item: FileItem): Int? {
        val k = key(item)
        cache.get(k)?.let { return it }
        val n = withContext(Dispatchers.IO) { item.file.list()?.size }
        if (n != null) cache.put(k, n)
        return n
    }
}

@Composable
fun FileIcon(kind: FileKind, size: Dp = 40.dp) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (kind) {
        FileKind.FOLDER -> Icons.Default.Folder to scheme.primary
        FileKind.IMAGE -> Icons.Default.Image to Color(0xFF43A047)
        FileKind.VIDEO -> Icons.Default.VideoFile to Color(0xFFE53935)
        FileKind.AUDIO -> Icons.Default.AudioFile to Color(0xFF8E24AA)
        FileKind.ARCHIVE -> Icons.Default.FolderZip to Color(0xFFF9A825)
        FileKind.TEXT -> Icons.Default.Description to scheme.onSurfaceVariant
        FileKind.APK -> Icons.Default.Android to Color(0xFF3DDC84)
        FileKind.PDF -> Icons.Default.PictureAsPdf to Color(0xFFD32F2F)
        FileKind.OTHER -> Icons.Default.InsertDriveFile to scheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
fun FileThumb(item: FileItem, selected: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val st = LocalSettings.current
    val bit = when (item.kind) {
        FileKind.IMAGE -> 1
        FileKind.VIDEO -> 2
        FileKind.AUDIO -> 4
        FileKind.APK -> 8
        FileKind.PDF -> 16
        else -> 0
    }
    val showThumbs = st.showThumbs && (bit == 0 || (st.thumbMask and bit) != 0)
    val px = ((with(density) { size.roundToPx() } + 63) / 64) * 64
    val bitmap by produceState<Bitmap?>(
        if (showThumbs) Thumbs.peek(item, px) else null,
        item.path, item.modified, px, showThumbs
    ) {
        if (showThumbs && value == null && Thumbs.supports(item.kind)) {
            delay(30)
            value = Thumbs.load(context, item, px)
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            val image = remember(b) { b.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = if (item.kind == FileKind.APK) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
            )
            if (item.kind == FileKind.VIDEO) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size / 2.5f)
                )
            }
        } else {
            FileIcon(item.kind, size)
        }
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size / 2f)
                )
            }
        }
    }
}
