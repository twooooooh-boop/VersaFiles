package com.versa.files

import androidx.compose.runtime.Immutable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SortBy(val label: String) {
    NAME("name"), SIZE("size"), DATE("date"), TYPE("type")
}

enum class ViewMode { LIST, GRID }

enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, ARCHIVE, TEXT, APK, PDF, OTHER }

@Immutable
data class FileItem(
    val file: File,
    val name: String,
    val nameKey: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val kind: FileKind
)

/** Everything the archive viewer can open (zip based formats and real archives). */
val archiveBrowseExts = setOf(
    "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz2", "tbz", "xz", "txz", "lzma", "z", "iso", "cab",
    "arj", "lzh", "lha", "cpio", "deb", "rpm", "wim", "swm", "dmg", "vhd", "vhdx", "squashfs", "chm",
    "zst", "tzst", "xar", "udf", "love", "jar", "aar", "war", "ear", "cbz", "cbr", "cb7", "cbt", "kmz",
    "xpi", "crx", "apks", "xapk", "apkm", "obb", "apk", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub"
)

fun isExtractable(f: File): Boolean =
    f.isFile && f.extension.lowercase(Locale.ROOT) in archiveBrowseExts

fun kindOfExt(ext: String): FileKind = when (ext) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "ico", "wbmp" -> FileKind.IMAGE
    "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "flv", "wmv", "mpg", "mpeg", "ogv" -> FileKind.VIDEO
    "mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "wma", "amr", "mid", "midi", "oga" -> FileKind.AUDIO
    "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "tbz2", "tbz", "xz", "txz", "lzma", "z", "iso", "cab",
    "arj", "lzh", "lha", "cpio", "deb", "rpm", "wim", "swm", "dmg", "vhd", "vhdx", "squashfs", "chm",
    "zst", "tzst", "xar", "udf", "love", "jar", "aar", "war", "ear", "cbz", "cbr", "cb7", "cbt", "kmz",
    "xpi", "crx", "apks", "xapk", "apkm", "obb" -> FileKind.ARCHIVE
    "txt", "md", "json", "xml", "kt", "java", "py", "lua", "html", "htm", "css", "js", "log", "csv", "tsv",
    "yml", "yaml", "ini", "conf", "sh", "c", "h", "cpp", "hpp", "cs", "go", "rs", "ts", "php", "rb",
    "swift", "gradle", "kts", "properties", "toml", "sql", "bat", "cmd", "svg", "srt", "vtt", "tex",
    "docx", "doc", "xlsx", "xls", "pptx", "ppt", "odt", "ods", "odp", "rtf", "epub" -> FileKind.TEXT
    "apk" -> FileKind.APK
    "pdf" -> FileKind.PDF
    else -> FileKind.OTHER
}

fun kindOf(f: File): FileKind =
    if (f.isDirectory) FileKind.FOLDER else kindOfExt(f.extension.lowercase(Locale.ROOT))

fun toItem(f: File): FileItem {
    val dir = f.isDirectory
    val name = f.name
    return FileItem(
        file = f,
        name = name,
        nameKey = name.lowercase(Locale.ROOT),
        path = f.absolutePath,
        isDir = dir,
        size = if (dir) 0L else f.length(),
        modified = f.lastModified(),
        kind = if (dir) FileKind.FOLDER
        else kindOfExt(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
    )
}

/** Compares strings so that "file2" sorts before "file10". Inputs should be lowercase. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = ei
            j = ej
        } else {
            if (ca != cb) return ca.compareTo(cb)
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

fun formatSize(bytes: Long, binary: Boolean = true): String {
    val base = if (binary) 1024.0 else 1000.0
    if (bytes < base) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= base && i < units.lastIndex) {
        v /= base
        i++
    }
    val r = (v * 100).toLong() / 100.0
    val text = if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
    return "$text ${units[i]}"
}

private object Fmt {
    val detailed: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    val iso: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val short: ThreadLocal<SimpleDateFormat> =
        ThreadLocal.withInitial { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
}

fun formatDate(ms: Long, style: DateStyle = DateStyle.DETAILED): String = when (style) {
    DateStyle.DETAILED -> Fmt.detailed.get()!!.format(Date(ms))
    DateStyle.ISO -> Fmt.iso.get()!!.format(Date(ms))
    DateStyle.RELATIVE -> {
        val diff = System.currentTimeMillis() - ms
        val minute = 60_000L
        val hour = 3_600_000L
        val day = 86_400_000L
        when {
            diff < minute -> "Just now"
            diff < hour -> "${diff / minute} min ago"
            diff < day -> "${diff / hour} h ago"
            diff < 7 * day -> "${diff / day} d ago"
            else -> Fmt.short.get()!!.format(Date(ms))
        }
    }
}
