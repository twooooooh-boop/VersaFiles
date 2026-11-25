package com.versa.files

import androidx.compose.runtime.Immutable
import java.io.File

@Immutable
data class JunkFile(val file: File, val size: Long)

@Immutable
data class JunkGroup(val title: String, val files: List<JunkFile>) {
    val totalSize: Long get() = files.sumOf { it.size }
}

object Cleaner {
    private val tempExts = setOf("tmp", "temp", "log", "bak", "old", "cache", "crdownload", "part", "dmp")

    fun scan(root: File, onProgress: (Long) -> Unit, active: () -> Boolean): List<JunkGroup> {
        val empty = ArrayList<JunkFile>()
        val temp = ArrayList<JunkFile>()
        val apks = ArrayList<JunkFile>()
        val thumbs = ArrayList<JunkFile>()
        var count = 0L
        val walk = root.walkTopDown().maxDepth(30).onEnter { d ->
            if (d.name == ".thumbnails") {
                thumbs.add(JunkFile(d, FileOps.totalSize(d)))
                false
            } else {
                d.name != "Android"
            }
        }
        for (f in walk) {
            if (!active()) return emptyList()
            count++
            if (count % 500L == 0L) onProgress(count)
            if (f.isDirectory) {
                if (f != root && f.list()?.isEmpty() == true) empty.add(JunkFile(f, 0L))
            } else {
                val ext = f.extension.lowercase()
                val name = f.name.lowercase()
                if (ext in tempExts || name == "thumbs.db" || name == ".ds_store") {
                    temp.add(JunkFile(f, f.length()))
                } else if (ext == "apk") {
                    apks.add(JunkFile(f, f.length()))
                }
            }
        }
        onProgress(count)
        return listOf(
            JunkGroup("Empty folders", empty),
            JunkGroup("Temporary and log files", temp),
            JunkGroup("Thumbnail caches", thumbs),
            JunkGroup("APK installers", apks)
        ).filter { it.files.isNotEmpty() }
    }
}
