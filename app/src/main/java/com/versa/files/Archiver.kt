package com.versa.files

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class ArchiveFormat(val label: String, val ext: String) {
    ZIP("ZIP", "zip"),
    SEVEN_Z("7-Zip", "7z"),
    LOVE("LÖVE", "love")
}

object Archiver {

    fun compress(sources: List<File>, out: File, format: ArchiveFormat) {
        // .love: a single folder becomes the archive root so main.lua sits at the top
        val flatten = format == ArchiveFormat.LOVE
        val entries = collect(sources, flatten, out)
        when (format) {
            ArchiveFormat.SEVEN_Z -> writeSevenZ(entries, out)
            else -> writeZip(entries, out)
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

    private fun writeZip(entries: List<Pair<File, String>>, out: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            for ((file, rel) in entries) {
                if (file.isDirectory) {
                    zos.putNextEntry(ZipEntry("$rel/"))
                    zos.closeEntry()
                } else {
                    val e = ZipEntry(rel)
                    e.time = file.lastModified()
                    zos.putNextEntry(e)
                    FileInputStream(file).use { it.copyTo(zos, 64 * 1024) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun writeSevenZ(entries: List<Pair<File, String>>, out: File) {
        SevenZOutputFile(out).use { sz ->
            val buf = ByteArray(64 * 1024)
            for ((file, rel) in entries) {
                val entry = sz.createArchiveEntry(file, rel)
                sz.putArchiveEntry(entry)
                if (file.isFile) {
                    FileInputStream(file).use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sz.write(buf, 0, n)
                        }
                    }
                }
                sz.closeArchiveEntry()
            }
            sz.finish()
        }
    }

    fun extract(archive: File, destDir: File) {
        destDir.mkdirs()
        when (archive.extension.lowercase()) {
            "7z" -> extractSevenZ(archive, destDir)
            else -> extractZip(archive, destDir)
        }
    }

    private fun safeTarget(destDir: File, name: String): File {
        val target = File(destDir, name)
        if (!target.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
            throw SecurityException("Blocked unsafe path: $name")
        }
        return target
    }

    private fun extractZip(archive: File, destDir: File) {
        ZipFile(archive).use { zf ->
            zf.entries().asSequence().forEach { e ->
                val target = safeTarget(destDir, e.name)
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zf.getInputStream(e).use { input ->
                        target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                }
            }
        }
    }

    private fun extractSevenZ(archive: File, destDir: File) {
        SevenZFile.builder().setFile(archive).get().use { sz ->
            val buf = ByteArray(64 * 1024)
            var e = sz.nextEntry
            while (e != null) {
                val target = safeTarget(destDir, e.name)
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        while (true) {
                            val n = sz.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                e = sz.nextEntry
            }
        }
    }
}
