package com.versa.files

import androidx.compose.runtime.Immutable
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.util.zip.ZipFile

@Immutable
data class ArchiveEntryItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long
)

/** Lets the user browse the inside of a zip / 7z / love / jar / apk without extracting it. */
class ArchiveSession(private val archive: File) {
    private val is7z = archive.extension.equals("7z", ignoreCase = true)
    private var entries: List<ArchiveEntryItem> = emptyList()

    private fun clean(name: String): String = name.replace('\\', '/').trimEnd('/')

    private fun make(name: String, isDir: Boolean, size: Long): ArchiveEntryItem? {
        val path = clean(name)
        if (path.isEmpty()) return null
        return ArchiveEntryItem(
            path = path,
            name = path.substringAfterLast('/'),
            isDir = isDir || name.endsWith("/"),
            size = if (size > 0) size else 0L
        )
    }

    fun load() {
        val raw = ArrayList<ArchiveEntryItem>()
        if (is7z) {
            SevenZFile.builder().setFile(archive).get().use { sz ->
                for (e in sz.entries) {
                    make(e.name, e.isDirectory, e.size)?.let { raw.add(it) }
                }
            }
        } else {
            ZipFile(archive).use { zf ->
                zf.entries().asSequence().forEach { e ->
                    make(e.name, e.isDirectory, e.size)?.let { raw.add(it) }
                }
            }
        }
        val dirs = HashSet<String>()
        raw.filter { it.isDir }.forEach { dirs.add(it.path) }
        val extra = ArrayList<ArchiveEntryItem>()
        for (r in raw) {
            var p = r.path.substringBeforeLast('/', "")
            while (p.isNotEmpty() && dirs.add(p)) {
                extra.add(ArchiveEntryItem(p, p.substringAfterLast('/'), true, 0L))
                p = p.substringBeforeLast('/', "")
            }
        }
        entries = raw + extra
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

        if (is7z) {
            SevenZFile.builder().setFile(archive).get().use { sz ->
                p?.total = sz.entries
                    .filter { !it.isDirectory && wanted(clean(it.name)) }
                    .sumOf { if (it.size > 0) it.size else 0L }
                val buf = ByteArray(64 * 1024)
                var e = sz.nextEntry
                while (e != null) {
                    p?.check()
                    val path = clean(e.name)
                    if (path.isNotEmpty() && wanted(path)) {
                        val rel = path.removePrefix(basePrefix)
                        if (rel.isNotEmpty()) {
                            val target = Archiver.safeTarget(dest, rel)
                            if (e.isDirectory) {
                                target.mkdirs()
                            } else {
                                p?.current = target.name
                                target.parentFile?.mkdirs()
                                target.outputStream().use { out ->
                                    while (true) {
                                        p?.check()
                                        val n = sz.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        p?.add(n.toLong())
                                    }
                                }
                            }
                        }
                    }
                    e = sz.nextEntry
                }
            }
        } else {
            ZipFile(archive).use { zf ->
                val list = zf.entries().asSequence().filter { wanted(clean(it.name)) }.toList()
                p?.total = list.filter { !it.isDirectory }.sumOf { if (it.size > 0) it.size else 0L }
                val buf = ByteArray(64 * 1024)
                for (e in list) {
                    p?.check()
                    val path = clean(e.name)
                    val rel = path.removePrefix(basePrefix)
                    if (rel.isEmpty()) continue
                    val target = Archiver.safeTarget(dest, rel)
                    if (e.isDirectory || e.name.endsWith("/")) {
                        target.mkdirs()
                    } else {
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
