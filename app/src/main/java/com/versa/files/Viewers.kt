package com.versa.files

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MediaPlayerScreen(file: File, isAudio: Boolean, onClose: () -> Unit, onFallback: (File) -> Unit) {
    BackHandler(onBack = onClose)
    var view by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            try {
                view?.stopPlayback()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val controller = MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setVideoPath(file.absolutePath)
                    setOnPreparedListener { start() }
                    setOnErrorListener { _, _, _ ->
                        onFallback(file)
                        true
                    }
                    view = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 0.dp)
        )
        if (isAudio) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(96.dp)
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x88000000))
                .statusBarsPadding()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                file.name,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private class PdfHolder(private val file: File) {
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val mutex = Mutex()

    suspend fun open(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val d = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = d
                val r = PdfRenderer(d)
                renderer = r
                r.pageCount
            } catch (e: Exception) {
                0
            }
        }
    }

    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val r = renderer
            if (r == null || index >= r.pageCount) {
                null
            } else {
                try {
                    val page = r.openPage(index)
                    try {
                        val scale = width.toFloat() / page.width
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(AColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    } finally {
                        page.close()
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    fun close() {
        try {
            renderer?.close()
            pfd?.close()
        } catch (e: Exception) {
            // ignore
        }
        renderer = null
        pfd = null
    }
}

@Composable
fun PdfViewerScreen(file: File, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val holder = remember(file) { PdfHolder(file) }
    DisposableEffect(holder) { onDispose { holder.close() } }
    val pageCount by produceState(-1, holder) { value = holder.open() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF424242))
    ) {
        if (pageCount < 0) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (pageCount == 0) {
            Text(
                "Can't open this PDF",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthPx = constraints.maxWidth
                LazyColumn(Modifier.fillMaxSize()) {
                    item { Spacer(Modifier.height(64.dp)) }
                    items(pageCount) { index ->
                        val bmp by produceState<Bitmap?>(null, holder, index, widthPx) {
                            value = holder.render(index, widthPx)
                        }
                        val b = bmp
                        if (b == null) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(400.dp)
                                    .padding(vertical = 4.dp)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator() }
                        } else {
                            val image = remember(b) { b.asImageBitmap() }
                            Image(
                                bitmap = image,
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0x88000000))
                .statusBarsPadding()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                file.name,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
