package com.versa.files

import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SortBy(val label: String) {
    NAME("name"), SIZE("size"), DATE("date"), TYPE("type")
}

enum class ViewMode { LIST, GRID }

enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, ARCHIVE, TEXT, APK, PDF, OTHER }

data class FileItem(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val kind: FileKind,
    val childCount: Int
) {
    val path: String get() = file.absolutePath
}

private val extractableExts = setOf("zip", "7z", "love", "jar")

fun isExtractable(f: File): Boolean =
    f.isFile && f.extension.lowercase(Locale.ROOT) in extractableExts

fun kindOf(f: File): FileKind {
    if (f.isDirectory) return FileKind.FOLDER
    return when (f.extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> FileKind.IMAGE
        "mp4", "mkv", "webm", "avi", "mov", "3gp" -> FileKind.VIDEO
        "mp3", "wav", "flac", "ogg", "m4a", "aac", "opus" -> FileKind.AUDIO
        "zip", "7z", "love", "jar", "rar", "tar", "gz" -> FileKind.ARCHIVE
        "txt", "md", "json", "xml", "kt", "java", "py", "lua", "html", "css", "js",
        "log", "csv", "yml", "yaml", "ini", "conf", "sh" -> FileKind.TEXT
        "apk" -> FileKind.APK
        "pdf" -> FileKind.PDF
        else -> FileKind.OTHER
    }
}

fun toItem(f: File, withCount: Boolean): FileItem {
    val dir = f.isDirectory
    return FileItem(
        file = f,
        name = f.name,
        isDir = dir,
        size = if (dir) 0L else f.length(),
        modified = f.lastModified(),
        kind = kindOf(f),
        childCount = if (dir && withCount) (f.list()?.size ?: 0) else 0
    )
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return DecimalFormat("#.##").format(v) + " " + units[i]
}

fun formatDate(ms: Long): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))
