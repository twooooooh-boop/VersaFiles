package com.versa.files

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ArchiveFormat(val label: String, val ext: String) {
    ZIP("ZIP", "zip"),
    SEVEN_Z("7-Zip", "7z"),
    LOVE("LÖVE", "love")
}

object Archiver {

    fun compress(sources: List<File>, out: File, format: ArchiveFormat, p: TaskProgress? = null) {
        // .love: a single folder becomes the archive root so main.lua sits at the top
        val flatten = format == ArchiveFormat.LOVE
        val entries = collect(sources, flatten, out)
        p?.total = entries.filter { it.first.isFile }.sumOf { it.first.length() }
        when (format) {
            ArchiveFormat.SEVEN_Z -> writeSevenZ(entries, out, p)
            else -> writeZip(entries, out, p)
        }
    }

    private fun collect(sources: List<File>, flatten: Boolean, out: File): List<Pair<File, String>> {
        val outPath = out.canonicalPath
        val list = ArrayList<Pair<File, String>>()
        fun walk(f: File, rel: String) {
            if (f.canonicalPath == outPath) return
            list.add(f to rel)
            if (f.isDirectory) {
                f.listFiles()?.sortedBy { it.name }?.forEach { walk(it, "$rel/${it.name}") }
            }
        }
        if (flatten && sources.size == 1 && sources[0].isDirectory) {
            sources[0].listFiles()?.sortedBy { it.name }?.forEach { walk(it, it.name) }
        } else {
            sources.forEach { walk(it, it.name) }
        }
        return list
    }

    private fun writeZip(entries: List<Pair<File, String>>, out: File, p: TaskProgress?) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            val buf = ByteArray(64 * 1024)
            for ((file, rel) in entries) {
                p?.check()
                if (file.isDirectory) {
                    zos.putNextEntry(ZipEntry("$rel/"))
                    zos.closeEntry()
                } else {
                    p?.current = file.name
                    val e = ZipEntry(rel)
                    e.time = file.lastModified()
                    zos.putNextEntry(e)
                    FileInputStream(file).use { input ->
                        while (true) {
                            p?.check()
                            val n = input.read(buf)
                            if (n < 0) break
                            zos.write(buf, 0, n)
                            p?.add(n.toLong())
                        }
                    }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun writeSevenZ(entries: List<Pair<File, String>>, out: File, p: TaskProgress?) {
        SevenZOutputFile(out).use { sz ->
            val buf = ByteArray(64 * 1024)
            for ((file, rel) in entries) {
                p?.check()
                val entry = sz.createArchiveEntry(file, rel)
                sz.putArchiveEntry(entry)
                if (file.isFile) {
                    p?.current = file.name
                    FileInputStream(file).use { input ->
                        while (true) {
                            p?.check()
                            val n = input.read(buf)
                            if (n < 0) break
                            sz.write(buf, 0, n)
                            p?.add(n.toLong())
                        }
                    }
                }
                sz.closeArchiveEntry()
            }
            sz.finish()
        }
    }

    /** Extracts any supported archive (zip, 7z, rar, tar.*, iso, cab, ...) into destDir. */
    fun extract(archive: File, destDir: File, p: TaskProgress? = null) {
        val session = ArchiveSession(archive)
        session.load()
        session.extract(null, "", destDir, p)
    }

    fun safeTarget(destDir: File, name: String): File {
        val target = File(destDir, name)
        if (!target.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
            throw SecurityException("Blocked unsafe path: $name")
        }
        return target
    }
}
