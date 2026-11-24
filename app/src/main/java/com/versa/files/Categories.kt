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

    private fun isHiddenPath(path: String): Boolean =
        path.split('/').any { part -> part.length > 1 && part.startsWith(".") }

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
                if (!showHidden && isHiddenPath(path)) continue
                out.add(toItem(f))
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    fun recent(context: Context, limit: Int, showHidden: Boolean): List<FileItem> {
        val uri = MediaStore.Files.getContentUri("external")
        val data = MediaStore.Files.FileColumns.DATA
        val mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE
        val selection = "($mediaType IN (?,?,?) OR $data LIKE ? OR $data LIKE ? OR $data LIKE ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString(),
            "%.pdf", "%.apk", "%.zip"
        )
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
            var scanned = 0
            while (it.moveToNext() && out.size < limit && scanned < 400) {
                scanned++
                val path = it.getString(idx) ?: continue
                if (path.contains("/Android/")) continue
                val f = File(path)
                if (!f.isFile) continue
                if (!showHidden && isHiddenPath(path)) continue
                out.add(toItem(f))
            }
        }
        return out
    }
}
