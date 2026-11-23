package com.versa.files

import android.content.Context
import android.provider.MediaStore
import java.io.File

enum class Category(val label: String) {
    IMAGES("Images"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    DOCUMENTS("Documents"),
    APKS("APKs"),
    ARCHIVES("Archives")
}

object Categories {
    private val docExts = listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "epub", "odt", "rtf")
    private val archiveExts = listOf("zip", "7z", "love", "rar", "tar", "gz", "jar")

    private fun likeAny(column: String, exts: List<String>): Pair<String, Array<String>> =
        exts.joinToString(" OR ", "(", ")") { "$column LIKE ?" } to
            exts.map { "%.$it" }.toTypedArray()

    @Suppress("DEPRECATION")
    fun query(context: Context, c: Category, showHidden: Boolean, limit: Int = 3000): List<FileItem> {
        val uri = MediaStore.Files.getContentUri("external")
        val data = MediaStore.Files.FileColumns.DATA
        val mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE
        val (selection, args) = when (c) {
            Category.IMAGES -> "$mediaType=?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            Category.VIDEOS -> "$mediaType=?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            Category.AUDIO -> "$mediaType=?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())
            Category.DOCUMENTS -> likeAny(data, docExts)
            Category.APKS -> likeAny(data, listOf("apk"))
            Category.ARCHIVES -> likeAny(data, archiveExts)
        }
        val out = ArrayList<FileItem>()
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(data),
            selection,
            args,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        ) ?: return out
        cursor.use {
            val idx = it.getColumnIndexOrThrow(data)
            while (it.moveToNext() && out.size < limit) {
                val path = it.getString(idx) ?: continue
                val f = File(path)
                if (!f.isFile) continue
                if (!showHidden && path.split('/').any { part -> part.length > 1 && part.startsWith(".") }) continue
                out.add(toItem(f, false))
            }
        }
        return out
    }
}
