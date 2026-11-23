package com.versa.files

import java.io.File
import java.util.PriorityQueue

data class AnalyzerResult(
    val total: Long,
    val files: Long,
    val byKind: List<Pair<FileKind, Long>>,
    val largest: List<FileItem>
)

object Analyzer {

    fun scan(root: File, onProgress: (Long) -> Unit, active: () -> Boolean): AnalyzerResult {
        val sizes = LongArray(FileKind.entries.size)
        val heap = PriorityQueue<FileItem>(compareBy<FileItem> { it.size })
        var total = 0L
        var count = 0L
        for (f in root.walkTopDown().maxDepth(30)) {
            if (!active()) break
            if (!f.isFile) continue
            val len = f.length()
            total += len
            count++
            val kind = kindOfExt(f.extension.lowercase())
            sizes[kind.ordinal] += len
            val smallest = heap.peek()?.size ?: 0L
            if (heap.size < 50 || len > smallest) {
                heap.add(toItem(f, false))
                if (heap.size > 50) heap.poll()
            }
            if (count % 500L == 0L) onProgress(count)
        }
        onProgress(count)
        val byKind = FileKind.entries
            .filter { it != FileKind.FOLDER }
            .map { it to sizes[it.ordinal] }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        return AnalyzerResult(total, count, byKind, heap.sortedByDescending { it.size })
    }
}
