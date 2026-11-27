package com.versa.files

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes Android binary XML (AndroidManifest.xml inside an APK, res/layout/*.xml, ...) to readable text. */
object Axml {
    fun isAxml(b: ByteArray): Boolean =
        b.size >= 8 && (b[0].toInt() and 0xFF) == 0x03 && b[1].toInt() == 0 &&
            (b[2].toInt() and 0xFF) == 0x08 && b[3].toInt() == 0

    /** Returns null when the file is not binary XML or can't be decoded. Never throws. */
    fun decodeFile(file: File): String? = try {
        if (file.length() > 30_000_000L) null else decode(file.readBytes())
    } catch (e: Throwable) {
        null
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    private fun readStrings(buf: ByteBuffer, pos: Int): List<String> {
        val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
        val count = buf.getInt(pos + 8)
        val flags = buf.getInt(pos + 16)
        val stringsStart = buf.getInt(pos + 20)
        val utf8 = (flags and 0x100) != 0
        val offsetsBase = pos + headerSize
        val dataBase = pos + stringsStart
        val list = ArrayList<String>(count.coerceIn(0, 200_000))
        for (i in 0 until count) {
            var off = dataBase + buf.getInt(offsetsBase + 4 * i)
            if (utf8) {
                var len = buf.get(off).toInt() and 0xFF
                off++
                if ((len and 0x80) != 0) {
                    len = ((len and 0x7F) shl 8) or (buf.get(off).toInt() and 0xFF)
                    off++
                }
                var blen = buf.get(off).toInt() and 0xFF
                off++
                if ((blen and 0x80) != 0) {
                    blen = ((blen and 0x7F) shl 8) or (buf.get(off).toInt() and 0xFF)
                    off++
                }
                val bytes = ByteArray(blen)
                for (k in 0 until blen) bytes[k] = buf.get(off + k)
                list.add(String(bytes, Charsets.UTF_8))
            } else {
                var len = buf.getShort(off).toInt() and 0xFFFF
                off += 2
                if ((len and 0x8000) != 0) {
                    len = ((len and 0x7FFF) shl 16) or (buf.getShort(off).toInt() and 0xFFFF)
                    off += 2
                }
                val sb = StringBuilder(len)
                for (k in 0 until len) sb.append(buf.getShort(off + 2 * k).toInt().toChar())
                list.add(sb.toString())
            }
        }
        return list
    }

    private fun hex(v: Int): String = Integer.toHexString(v)

    private fun fmt(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    private fun value(raw: Int, type: Int, data: Int, strings: List<String>): String {
        if (raw >= 0 && raw < strings.size) return esc(strings[raw])
        return when (type) {
            0x03 -> esc(strings.getOrElse(data) { "" })
            0x10 -> data.toString()
            0x11 -> "0x" + hex(data)
            0x12 -> if (data != 0) "true" else "false"
            0x01 -> if (data == 0) "@null" else "@0x" + hex(data)
            0x02 -> "?0x" + hex(data)
            0x04 -> java.lang.Float.intBitsToFloat(data).toString()
            0x05 -> {
                val mult = floatArrayOf(0.00390625f, 3.051758E-5f, 1.192093E-7f, 4.656613E-10f)
                val v = (data and -256) * mult[(data shr 4) and 3]
                val units = arrayOf("px", "dip", "sp", "pt", "in", "mm")
                fmt(v) + units[(data and 0xF).coerceAtMost(5)]
            }
            0x06 -> {
                val mult = floatArrayOf(0.00390625f, 3.051758E-5f, 1.192093E-7f, 4.656613E-10f)
                val v = (data and -256) * mult[(data shr 4) and 3] * 100f
                fmt(v) + (if ((data and 0xF) == 0) "%" else "%p")
            }
            0x1C, 0x1D, 0x1E, 0x1F -> "#" + String.format("%08X", data)
            else -> "0x" + hex(data)
        }
    }

    fun decode(data: ByteArray): String? {
        if (!isAxml(data)) return null
        try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 8
            var strings: List<String> = emptyList()
            val out = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            val nsPrefix = HashMap<Int, Int>()
            val pendingNs = ArrayList<Pair<Int, Int>>()
            var depth = 0
            var lastWasStart = false

            fun str(i: Int): String = strings.getOrElse(i) { "?" }
            fun qname(ns: Int, name: Int): String {
                val prefixIdx = nsPrefix[ns]
                return if (ns >= 0 && prefixIdx != null) str(prefixIdx) + ":" + str(name) else str(name)
            }
            fun indent(n: Int) {
                for (i in 0 until n) out.append("  ")
            }

            while (pos + 8 <= data.size) {
                val type = buf.getShort(pos).toInt() and 0xFFFF
                val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
                val size = buf.getInt(pos + 4)
                if (size < 8 || pos + size > data.size) break
                when (type) {
                    0x0001 -> strings = readStrings(buf, pos)
                    0x0100 -> {
                        val prefix = buf.getInt(pos + 16)
                        val uri = buf.getInt(pos + 20)
                        nsPrefix[uri] = prefix
                        pendingNs.add(prefix to uri)
                    }
                    0x0102 -> {
                        val nsIdx = buf.getInt(pos + 16)
                        val nameIdx = buf.getInt(pos + 20)
                        val attrStart = buf.getShort(pos + 24).toInt() and 0xFFFF
                        val attrSize = (buf.getShort(pos + 26).toInt() and 0xFFFF).coerceAtLeast(20)
                        val attrCount = buf.getShort(pos + 28).toInt() and 0xFFFF
                        val attrs = ArrayList<String>()
                        for ((prefix, uri) in pendingNs) {
                            attrs.add("xmlns:" + str(prefix) + "=\"" + esc(str(uri)) + "\"")
                        }
                        pendingNs.clear()
                        val base = pos + headerSize + attrStart
                        for (i in 0 until attrCount) {
                            val a = base + i * attrSize
                            val ans = buf.getInt(a)
                            val aname = buf.getInt(a + 4)
                            val raw = buf.getInt(a + 8)
                            val dtype = buf.get(a + 15).toInt() and 0xFF
                            val ddata = buf.getInt(a + 16)
                            attrs.add(qname(ans, aname) + "=\"" + value(raw, dtype, ddata, strings) + "\"")
                        }
                        indent(depth)
                        out.append('<').append(qname(nsIdx, nameIdx))
                        if (attrs.size <= 1) {
                            attrs.forEach { out.append(' ').append(it) }
                        } else {
                            attrs.forEach {
                                out.append('\n')
                                indent(depth + 2)
                                out.append(it)
                            }
                        }
                        out.append(">\n")
                        depth++
                        lastWasStart = true
                    }
                    0x0103 -> {
                        val nsIdx = buf.getInt(pos + 16)
                        val nameIdx = buf.getInt(pos + 20)
                        depth = (depth - 1).coerceAtLeast(0)
                        if (lastWasStart && out.length >= 2) {
                            out.setLength(out.length - 2)
                            out.append("/>\n")
                        } else {
                            indent(depth)
                            out.append("</").append(qname(nsIdx, nameIdx)).append(">\n")
                        }
                        lastWasStart = false
                    }
                    0x0104 -> {
                        val textIdx = buf.getInt(pos + 16)
                        val t = esc(str(textIdx)).trim()
                        if (t.isNotEmpty()) {
                            indent(depth)
                            out.append(t).append('\n')
                        }
                        lastWasStart = false
                    }
                }
                pos += size
            }
            return out.toString()
        } catch (e: Throwable) {
            return null
        }
    }
}
