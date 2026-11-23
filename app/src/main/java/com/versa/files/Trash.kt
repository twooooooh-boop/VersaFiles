package com.versa.files

import java.io.File

data class TrashEntry(
    val folder: File,
    val item: File,
    val original: String,
    val deletedAt: Long
)

class TrashManager(private val dir: File) {

    init {
        dir.mkdirs()
    }

    fun trash(files: List<File>) {
        files.forEach { f ->
            val folder = File(dir, "${System.currentTimeMillis()}_${(0..9999).random()}")
            folder.mkdirs()
            try {
                File(folder, ".meta").writeText(f.absolutePath + "\n" + System.currentTimeMillis())
                FileOps.move(f, folder)
            } catch (e: Throwable) {
                folder.deleteRecursively()
                throw e
            }
        }
    }

    fun list(): List<TrashEntry> {
        val folders = dir.listFiles() ?: return emptyList()
        return folders
            .filter { it.isDirectory }
            .mapNotNull { folder ->
                val item = folder.listFiles()?.firstOrNull { it.name != ".meta" }
                    ?: return@mapNotNull null
                val meta = File(folder, ".meta")
                val lines = if (meta.exists()) meta.readLines() else emptyList()
                TrashEntry(
                    folder = folder,
                    item = item,
                    original = lines.getOrNull(0) ?: item.absolutePath,
                    deletedAt = lines.getOrNull(1)?.toLongOrNull() ?: folder.lastModified()
                )
            }
            .sortedByDescending { it.deletedAt }
    }

    fun restore(e: TrashEntry) {
        val parent = File(e.original).parentFile
            ?: throw IllegalStateException("Invalid original path")
        parent.mkdirs()
        FileOps.move(e.item, parent)
        e.folder.deleteRecursively()
    }

    fun purge(e: TrashEntry) {
        e.folder.deleteRecursively()
    }

    fun emptyAll() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
