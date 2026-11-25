package com.versa.files

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password based file encryption: AES-256 (CTR) + HMAC-SHA256, key from PBKDF2.
 * File layout: "VFENC1" | salt(16) | iv(16) | ciphertext | hmac(32)
 */
object Crypto {
    private val MAGIC = "VFENC1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 120_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 16
    private const val MAC_LEN = 32

    private fun deriveKeys(password: String, salt: ByteArray): Pair<SecretKeySpec, SecretKeySpec> {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 512)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes.copyOfRange(0, 32), "AES") to
            SecretKeySpec(bytes.copyOfRange(32, 64), "HmacSHA256")
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    fun encrypt(src: File, dest: File, password: String, p: TaskProgress?) {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN)
        rnd.nextBytes(salt)
        val iv = ByteArray(IV_LEN)
        rnd.nextBytes(iv)
        val (aesKey, macKey) = deriveKeys(password, salt)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, IvParameterSpec(iv))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(macKey)
        mac.update(salt)
        mac.update(iv)
        p?.current = src.name
        FileOutputStream(dest).use { out ->
            out.write(MAGIC)
            out.write(salt)
            out.write(iv)
            FileInputStream(src).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    p?.check()
                    val n = input.read(buf)
                    if (n < 0) break
                    val enc = cipher.update(buf, 0, n)
                    if (enc != null) {
                        mac.update(enc)
                        out.write(enc)
                    }
                    p?.add(n.toLong())
                }
            }
            val last = cipher.doFinal()
            if (last != null && last.isNotEmpty()) {
                mac.update(last)
                out.write(last)
            }
            out.write(mac.doFinal())
        }
    }

    fun decrypt(src: File, dest: File, password: String, p: TaskProgress?) {
        val headerLen = MAGIC.size + SALT_LEN + IV_LEN
        val total = src.length()
        if (total < headerLen + MAC_LEN) throw IllegalArgumentException("This is not an encrypted file")
        val cipherLen = total - headerLen - MAC_LEN
        p?.total = cipherLen * 2
        p?.current = src.name

        val salt = ByteArray(SALT_LEN)
        val iv = ByteArray(IV_LEN)
        FileInputStream(src).use { input ->
            val magic = ByteArray(MAGIC.size)
            readFully(input, magic)
            if (!magic.contentEquals(MAGIC)) throw IllegalArgumentException("This is not an encrypted file")
            readFully(input, salt)
            readFully(input, iv)
        }
        val (aesKey, macKey) = deriveKeys(password, salt)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(macKey)
        mac.update(salt)
        mac.update(iv)

        // pass 1: verify the password / integrity before writing anything
        FileInputStream(src).use { input ->
            input.channel.position(headerLen.toLong())
            val buf = ByteArray(64 * 1024)
            var remaining = cipherLen
            while (remaining > 0) {
                p?.check()
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw IOException("Unexpected end of file")
                mac.update(buf, 0, n)
                remaining -= n
                p?.add(n.toLong())
            }
            val stored = ByteArray(MAC_LEN)
            readFully(input, stored)
            if (!MessageDigest.isEqual(mac.doFinal(), stored)) {
                throw IllegalArgumentException("Wrong password or damaged file")
            }
        }

        // pass 2: decrypt
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(iv))
        FileInputStream(src).use { input ->
            input.channel.position(headerLen.toLong())
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var remaining = cipherLen
                while (remaining > 0) {
                    p?.check()
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("Unexpected end of file")
                    val dec = cipher.update(buf, 0, n)
                    if (dec != null) out.write(dec)
                    remaining -= n
                    p?.add(n.toLong())
                }
                val last = cipher.doFinal()
                if (last != null && last.isNotEmpty()) out.write(last)
            }
        }
    }
}
