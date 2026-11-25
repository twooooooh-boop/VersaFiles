package com.versa.files

import androidx.compose.runtime.Immutable
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile

@Immutable
data class ArchiveEntryItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long
)

private class RawEntry(val path: String, val isDir: Boolean, val size: Long, val index: Int)

private class SevenExtractCallback(
    private val targets: Map<Int, File>,
    private val p: TaskProgress?
) : IArchiveExtractCallback {
    private var out: FileOutputStream? = null

    private fun closeOut() {
        try {
            out?.close()
        } catch (e: Exception) {
            // ignore
        }
        out = null
    }

    override fun getStream(index: Int, extractAskMode: ExtractAskMode?): ISequentialOutStream? {
        closeOut()
        if (extractAskMode != ExtractAskMode.EXTRACT) return null
        val f = targets[index] ?: return null
        f.parentFile?.mkdirs()
        val o = FileOutputStream(f)
        out = o
        p?.current = f.name
        return ISequentialOutStream { data ->
            p?.check()
            o.write(data)
            p?.add(data.size.toLong())
            data.size
        }
    }

    override fun prepareOperation(extractAskMode: ExtractAskMode?) {}

    override fun setOperationResult(extractOperationResult: ExtractOperationResult?) {
        closeOut()
    }

    override fun setTotal(total: Long) {}

    override fun setCompleted(complete: Long) {}
}

/**
 * Lets the user browse the inside of almost any archive without extracting it.
 * Zip-like files use java.util.zip; everything else (rar, 7z, tar.*, iso, cab, ...) uses 7-Zip-JBinding.
 */
class ArchiveSession(private val archive: File) {
    private var zipMode = true
    private var raw: List<RawEntry> = emptyList()
    private var entries: List<ArchiveEntryItem> = emptyList()

    private fun clean(name: String): String = name.replace('\\', '/').trimEnd('/')

    private fun <T> openSeven(block: (IInArchive) -> T): T {
        val raf = RandomAccessFile(archive, "r")
        var arc: IInArchive? = null
        try {
            arc = SevenZip.openInArchive(null, RandomAccessFileInStream(raf))
            return block(arc)
        } finally {
            try {
                arc?.close()
            } catch (e: Exception) {
                // ignore
            }
            try {
                raf.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun load() {
        val list = ArrayList<RawEntry>()
        var loaded = false
        try {
            ZipFile(archive).use { zf ->
                var i = 0
                zf.entries().asSequence().forEach { e ->
                    val path = clean(e.name)
                    if (path.isNotEmpty()) {
                        list.add(RawEntry(path, e.isDirectory || e.name.endsWith("/"), if (e.size > 0) e.size else 0L, i))
                    }
                    i++
                }
            }
            zipMode = true
            loaded = true
        } catch (e: Exception) {
            list.clear()
        }
        if (!loaded) {
            zipMode = false
            openSeven { arc ->
                for (i in 0 until arc.numberOfItems) {
                    val rawPath = (arc.getProperty(i, PropID.PATH) as? String) ?: archive.nameWithoutExtension
                    val path = clean(rawPath)
                    if (path.isEmpty()) continue
                    val isDir = (arc.getProperty(i, PropID.IS_FOLDER) as? Boolean) ?: false
                    val size = (arc.getProperty(i, PropID.SIZE) as? Long) ?: 0L
                    list.add(RawEntry(path, isDir, if (size > 0) size else 0L, i))
                }
            }
        }
        raw = list
        val items = ArrayList<ArchiveEntryItem>()
        val dirs = HashSet<String>()
        for (r in list) {
            items.add(ArchiveEntryItem(r.path, r.path.substringAfterLast('/'), r.isDir, r.size))
            if (r.isDir) dirs.add(r.path)
        }
        for (r in list) {
            var p = r.path.substringBeforeLast('/', "")
            while (p.isNotEmpty() && dirs.add(p)) {
                items.add(ArchiveEntryItem(p, p.substringAfterLast('/'), true, 0L))
                p = p.substringBeforeLast('/', "")
            }
        }
        entries = items
    }

    /** prefix is "" for the root, or a folder path that ends with "/". */
    fun list(prefix: String): List<ArchiveEntryItem> {
        val seen = HashMap<String, ArchiveEntryItem>()
        for (e in entries) {
            if (!e.path.startsWith(prefix)) continue
            val rest = e.path.substring(prefix.length)
            if (rest.isEmpty()) continue
            val first = rest.substringBefore('/')
            if (rest.contains('/')) {
                if (!seen.containsKey(first)) {
                    seen[first] = ArchiveEntryItem(prefix + first, first, true, 0L)
                }
            } else {
                seen[first] = e
            }
        }
        return seen.values.sortedWith(
            compareByDescending<ArchiveEntryItem> { it.isDir }.thenBy { it.name.lowercase() }
        )
    }

    /**
     * Extracts the selected entries (or everything when selected is null) into dest.
     * basePrefix is removed from the start of each path so a selected file lands directly in dest.
     */
    fun extract(selected: Set<String>?, basePrefix: String, dest: File, p: TaskProgress?) {
        dest.mkdirs()
        fun wanted(path: String): Boolean =
            selected == null || selected.any { path == it || path.startsWith("$it/") }

        val chosen = raw.filter { wanted(it.path) }
        p?.total = chosen.filter { !it.isDir }.sumOf { it.size }

        if (zipMode) {
            ZipFile(archive).use { zf ->
                val byIndex = zf.entries().asSequence().toList()
                val buf = ByteArray(64 * 1024)
                for (r in chosen) {
                    p?.check()
                    val rel = r.path.removePrefix(basePrefix)
                    if (rel.isEmpty()) continue
                    val target = Archiver.safeTarget(dest, rel)
                    if (r.isDir) {
                        target.mkdirs()
                    } else {
                        val e = byIndex.getOrNull(r.index) ?: continue
                        p?.current = target.name
                        target.parentFile?.mkdirs()
                        zf.getInputStream(e).use { input ->
                            target.outputStream().use { out ->
                                while (true) {
                                    p?.check()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    p?.add(n.toLong())
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val targets = HashMap<Int, File>()
            val indices = ArrayList<Int>()
            for (r in chosen) {
                val rel = r.path.removePrefix(basePrefix)
                if (rel.isEmpty()) continue
                val target = Archiver.safeTarget(dest, rel)
                if (r.isDir) {
                    target.mkdirs()
                } else {
                    targets[r.index] = target
                    indices.add(r.index)
                }
            }
            if (indices.isNotEmpty()) {
                openSeven { arc ->
                    arc.extract(indices.toIntArray(), false, SevenExtractCallback(targets, p))
                }
            }
        }
    }

    /** Extracts a single file to a temporary folder and returns it. */
    fun extractToCache(cacheRoot: File, path: String): File {
        val dir = File(cacheRoot, System.nanoTime().toString())
        val parent = path.substringBeforeLast('/', "")
        val base = if (parent.isEmpty()) "" else "$parent/"
        extract(setOf(path), base, dir, null)
        return File(dir, path.substringAfterLast('/'))
    }
}
