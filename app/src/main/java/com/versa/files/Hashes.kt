package com.versa.files

import java.io.File
import java.security.MessageDigest

object Hashes {
    fun hash(file: File, algo: String, limit: Long = Long.MAX_VALUE): String {
        val md = MessageDigest.getInstance(algo)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var remaining = limit
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                md.update(buf, 0, n)
                remaining -= n
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
