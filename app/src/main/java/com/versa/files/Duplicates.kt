package com.versa.files

import androidx.compose.runtime.Immutable
import java.io.File

@Immutable
data class DupGroup(val key: String, val size: Long, val files: List<File>)

object Duplicates {
    private const val MIN_SIZE = 64L * 1024

    fun scan(root: File, onProgress: (Long) -> Unit, active: () -> Boolean): List<DupGroup> {
        val bySize = HashMap<Long, MutableList<File>>()
        var count = 0L
        for (f in root.walkTopDown().maxDepth(30).onEnter { it.name != "Android" }) {
            if (!active()) return emptyList()
            if (!f.isFile) continue
            count++
            if (count % 500L == 0L) onProgress(count)
            val len = f.length()
            if (len >= MIN_SIZE) bySize.getOrPut(len) { ArrayList() }.add(f)
        }
        onProgress(count)

        val result = ArrayList<DupGroup>()
        for ((size, list) in bySize) {
            if (list.size < 2) continue
            if (!active()) return emptyList()
            val byQuick = HashMap<String, MutableList<File>>()
            for (f in list) {
                val h = try {
                    Hashes.hash(f, "MD5", 65536L)
                } catch (e: Exception) {
                    continue
                }
                byQuick.getOrPut(h) { ArrayList() }.add(f)
            }
            for (group in byQuick.values) {
                if (group.size < 2) continue
                val byFull = HashMap<String, MutableList<File>>()
                for (f in group) {
                    val h = try {
                        Hashes.hash(f, "MD5")
                    } catch (e: Exception) {
                        continue
                    }
                    byFull.getOrPut(h) { ArrayList() }.add(f)
                }
                for ((h, files) in byFull) {
                    if (files.size >= 2) {
                        result.add(DupGroup(h, size, files.sortedBy { it.absolutePath }))
                    }
                }
            }
        }
        return result.sortedByDescending { it.size * (it.files.size - 1) }.take(300)
    }
}
