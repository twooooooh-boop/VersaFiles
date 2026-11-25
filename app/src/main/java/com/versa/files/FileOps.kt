package com.versa.files

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

data class StorageRoot(val name: String, val dir: File)

fun storageRoots(context: Context): List<StorageRoot> {
    val list = ArrayList<StorageRoot>()
    val primary = Environment.getExternalStorageDirectory()
    list.add(StorageRoot("Internal storage", primary))
    context.getExternalFilesDirs(null).filterNotNull().forEach {
        val root = File(it.absolutePath.substringBefore("/Android/data"))
        if (root.absolutePath != primary.absolutePath && root.exists()) {
            list.add(StorageRoot("SD card / USB (${root.name})", root))
        }
    }
    return list
}

object FileOps {

    fun cleanName(name: String): String = name.replace('/', '_').trim()

    fun list(
        dir: File,
        showHidden: Boolean,
        sort: SortBy,
        ascending: Boolean,
        foldersFirst: Boolean = true
    ): List<FileItem> {
        val children = dir.listFiles() ?: return emptyList()
        val items = ArrayList<FileItem>(children.size)
        for (c in children) {
            if (!showHidden && c.name.startsWith(".")) continue
            items.add(toItem(c))
        }
        val base = Comparator<FileItem> { a, b -> naturalCompare(a.nameKey, b.nameKey) }
        val cmp: Comparator<FileItem> = when (sort) {
            SortBy.NAME -> base
            SortBy.SIZE -> compareBy<FileItem> { it.size }.then(base)
            SortBy.DATE -> compareBy<FileItem> { it.modified }.then(base)
            SortBy.TYPE -> compareBy<FileItem> { it.nameKey.substringAfterLast('.', "") }.then(base)
        }
        val ordered = if (ascending) cmp else cmp.reversed()
        return if (foldersFirst) {
            items.sortedWith(compareByDescending<FileItem> { it.isDir }.then(ordered))
        } else {
            items.sortedWith(ordered)
        }
    }

    fun search(
        root: File,
        query: String,
        showHidden: Boolean,
        kinds: Set<FileKind>,
        limit: Int = 500,
        active: () -> Boolean = { true }
    ): List<FileItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty() && kinds.isEmpty()) return emptyList()
        val out = ArrayList<FileItem>()
        root.walkTopDown()
            .maxDepth(12)
            .onEnter { f -> f == root || showHidden || !f.name.startsWith(".") }
            .forEach { f ->
                if (!active()) return out
                if (f == root) return@forEach
                if (!showHidden && f.name.startsWith(".")) return@forEach
                val name = f.name.lowercase()
                if (q.isNotEmpty() && !name.contains(q)) return@forEach
                if (kinds.isNotEmpty()) {
                    val k = if (f.isDirectory) FileKind.FOLDER
                    else kindOfExt(name.substringAfterLast('.', ""))
                    if (k !in kinds) return@forEach
                }
                out.add(toItem(f))
                if (out.size >= limit) return out
            }
        return out
    }

    fun uniqueFile(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val hasExt = dot > 0
        val base = if (hasExt) name.substring(0, dot) else name
        val ext = if (hasExt) name.substring(dot) else ""
        var i = 1
        while (target.exists()) {
            target = File(dir, "$base ($i)$ext")
            i++
        }
        return target
    }

    private fun isInside(child: File, parent: File): Boolean {
        val c = child.canonicalPath + File.separator
        val p = parent.canonicalPath + File.separator
        return c.startsWith(p)
    }

    /** mode: 0 = keep both, 1 = replace, 2 = skip (returns null) */
    private fun resolveTarget(src: File, destDir: File, mode: Int): File? {
        val t = File(destDir, src.name)
        if (!t.exists()) return t
        if (t.canonicalPath == src.canonicalPath) return uniqueFile(destDir, src.name)
        return when (mode) {
            1 -> {
                t.deleteRecursively()
                t
            }
            2 -> null
            else -> uniqueFile(destDir, src.name)
        }
    }

    fun copyFileTo(src: File, target: File, p: TaskProgress?) {
        p?.current = src.name
        try {
            src.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        p?.check()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        p?.add(n.toLong())
                    }
                }
            }
            target.setLastModified(src.lastModified())
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    fun copy(src: File, destDir: File, p: TaskProgress? = null, mode: Int = 0): File? {
        if (src.isDirectory && isInside(destDir, src)) {
            throw IllegalArgumentException("Cannot copy a folder into itself")
        }
        val target = resolveTarget(src, destDir, mode)
        if (target == null) {
            p?.add(totalSize(src))
            return null
        }
        if (src.isDirectory) {
            target.mkdirs()
            try {
                src.listFiles()?.forEach { copy(it, target, p, 0) }
            } catch (e: Throwable) {
                target.deleteRecursively()
                throw e
            }
        } else {
            copyFileTo(src, target, p)
        }
        return target
    }

    fun move(src: File, destDir: File, p: TaskProgress? = null, mode: Int = 0): File? {
        if (src.parentFile?.canonicalPath == destDir.canonicalPath) return src
        if (src.isDirectory && isInside(destDir, src)) {
            throw IllegalArgumentException("Cannot move a folder into itself")
        }
        val target = resolveTarget(src, destDir, mode) ?: return null
        if (src.renameTo(target)) return target
        val copied = copy(src, destDir, p, 0)
        src.deleteRecursively()
        return copied
    }

    fun rename(file: File, newName: String) {
        val name = cleanName(newName)
        if (name.isEmpty()) throw IllegalArgumentException("Invalid name")
        val target = File(file.parentFile, name)
        if (target.exists()) throw IllegalArgumentException("Name already exists")
        if (!file.renameTo(target)) throw IllegalStateException("Rename failed")
    }

    fun totalSize(f: File): Long =
        if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
}

fun mimeOf(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

fun openFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, "Open with")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

fun shareFiles(context: Context, files: List<File>) {
    val list = files.filter { it.isFile }
    if (list.isEmpty()) {
        Toast.makeText(context, "Folders can't be shared. Compress them first.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uris = ArrayList(list.map {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_STREAM, uris[0])
                .setType(mimeOf(list[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .setType("*/*")
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, "Share")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
