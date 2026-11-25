package com.versa.files

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Tiny HTTP file server so a PC on the same Wi-Fi can browse, download and upload files. */
class FileServer(
    private val root: File,
    private val password: String,
    private val allowUploads: () -> Boolean,
    port: Int
) {
    private val serverSocket = ServerSocket(port)

    @Volatile
    private var running = true

    fun start() {
        thread(name = "versa-http", isDaemon = true) {
            while (running) {
                try {
                    val client = serverSocket.accept()
                    thread(isDaemon = true) { handle(client) }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun authorized(header: String?): Boolean {
        if (password.isEmpty()) return true
        if (header == null || !header.startsWith("Basic ")) return false
        return try {
            val decoded = String(Base64.decode(header.removePrefix("Basic ").trim(), Base64.DEFAULT))
            decoded.substringAfter(':', "") == password
        } catch (e: Exception) {
            false
        }
    }

    private fun respond(
        out: OutputStream,
        code: Int,
        status: String,
        type: String,
        body: ByteArray,
        extra: List<String> = emptyList(),
        headOnly: Boolean = false
    ) {
        val head = StringBuilder("HTTP/1.1 $code $status\r\n")
        head.append("Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
        extra.forEach { head.append(it).append("\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body)
    }

    private fun text(out: OutputStream, code: Int, status: String, extra: List<String> = emptyList()) {
        respond(out, code, status, "text/plain; charset=utf-8", status.toByteArray(), extra)
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val out = BufferedOutputStream(socket.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1].substringBefore('?')
            var auth: String? = null
            var contentLength = 0L
            var range: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx < 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                when (name) {
                    "authorization" -> auth = value
                    "content-length" -> contentLength = value.toLongOrNull() ?: 0L
                    "range" -> range = value
                }
            }
            if (!authorized(auth)) {
                text(out, 401, "Unauthorized", listOf("WWW-Authenticate: Basic realm=\"VersaFiles\""))
                out.flush()
                return
            }
            val decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
            val rootCanon = root.canonicalFile
            val target = File(rootCanon, decoded.trimStart('/')).canonicalFile
            if (target.path != rootCanon.path && !target.path.startsWith(rootCanon.path + File.separator)) {
                text(out, 403, "Forbidden")
                out.flush()
                return
            }
            when (method) {
                "GET", "HEAD" -> serve(out, target, rootCanon, rawPath, method == "HEAD", range)
                "PUT" -> upload(input, out, target, contentLength)
                else -> text(out, 405, "Method Not Allowed")
            }
            out.flush()
        } catch (e: Exception) {
            // connection dropped
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun enc(name: String): String = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun serve(
        out: OutputStream,
        target: File,
        rootCanon: File,
        rawPath: String,
        headOnly: Boolean,
        range: String?
    ) {
        if (!target.exists()) {
            text(out, 404, "Not Found")
            return
        }
        if (target.isDirectory) {
            if (!rawPath.endsWith("/")) {
                respond(out, 301, "Moved Permanently", "text/plain", ByteArray(0), listOf("Location: $rawPath/"))
                return
            }
            val sb = StringBuilder()
            sb.append("<!doctype html><html><head><meta charset=utf-8>")
            sb.append("<meta name=viewport content='width=device-width,initial-scale=1'>")
            sb.append("<title>VersaFiles</title><style>body{font-family:sans-serif;margin:16px;max-width:900px}")
            sb.append("a{text-decoration:none;color:#1565c0}li{margin:10px 0;list-style:none}small{color:#777}</style></head><body>")
            sb.append("<h2>VersaFiles</h2><p><small>").append(esc(target.path.removePrefix(rootCanon.path).ifEmpty { "/" })).append("</small></p>")
            if (target.path != rootCanon.path) sb.append("<p><a href=\"../\">&#8679; Up</a></p>")
            if (allowUploads()) {
                sb.append("<p><input type=file id=f multiple> <button onclick=up()>Upload here</button> <span id=s></span></p>")
                sb.append("<script>async function up(){const fs=document.getElementById('f').files;const s=document.getElementById('s');")
                sb.append("for(const f of fs){s.textContent='Uploading '+f.name+'...';")
                sb.append("const r=await fetch(encodeURIComponent(f.name),{method:'PUT',body:f});")
                sb.append("if(!r.ok){s.textContent='Failed: '+f.name;return}}s.textContent='Done';location.reload()}</script>")
            }
            sb.append("<ul>")
            val children = (target.listFiles() ?: emptyArray<File>())
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            for (c in children) {
                if (c.name.startsWith(".")) continue
                val suffix = if (c.isDirectory) "/" else ""
                sb.append("<li><a href=\"").append(enc(c.name)).append(suffix).append("\">")
                sb.append(esc(c.name)).append(suffix).append("</a>")
                if (c.isFile) sb.append(" <small>").append(formatSize(c.length())).append("</small>")
                sb.append("</li>")
            }
            sb.append("</ul></body></html>")
            respond(out, 200, "OK", "text/html; charset=utf-8", sb.toString().toByteArray(Charsets.UTF_8), headOnly = headOnly)
            return
        }

        val len = target.length()
        var start = 0L
        var end = len - 1
        var partial = false
        if (range != null && range.startsWith("bytes=") && len > 0) {
            val spec = range.removePrefix("bytes=").split("-")
            val s = spec[0].toLongOrNull()
            val e = spec.getOrNull(1)?.toLongOrNull()
            if (s != null) {
                start = s
                if (e != null) end = minOf(e, len - 1)
                partial = true
            } else if (e != null) {
                start = maxOf(0L, len - e)
                partial = true
            }
            if (start > end || start >= len) {
                text(out, 416, "Range Not Satisfiable", listOf("Content-Range: bytes */$len"))
                return
            }
        }
        val sendLen = if (len == 0L) 0L else end - start + 1
        val head = StringBuilder()
        head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: ${mimeOf(target)}\r\n")
        head.append("Content-Length: $sendLen\r\n")
        head.append("Accept-Ranges: bytes\r\n")
        head.append("Connection: close\r\n")
        if (partial) head.append("Content-Range: bytes $start-$end/$len\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly && sendLen > 0) {
            RandomAccessFile(target, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var remaining = sendLen
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }

    private fun upload(input: InputStream, out: OutputStream, target: File, length: Long) {
        if (!allowUploads()) {
            text(out, 403, "Uploads are turned off")
            return
        }
        val parent = target.parentFile
        if (parent == null || !parent.isDirectory) {
            text(out, 404, "Not Found")
            return
        }
        val dest = FileOps.uniqueFile(parent, target.name)
        var remaining = length
        FileOutputStream(dest).use { fos ->
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                fos.write(buf, 0, n)
                remaining -= n
            }
        }
        if (remaining > 0) {
            dest.delete()
            text(out, 400, "Upload interrupted")
        } else {
            text(out, 201, "Created")
        }
    }
}

object ShareManager {
    var running by mutableStateOf(false)
        private set
    var url by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var folder by mutableStateOf<File?>(null)
        private set
    var allowUploads by mutableStateOf(false)

    private var server: FileServer? = null

    fun localIp(): String? {
        try {
            val all = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val sorted = all.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            for (ni in sorted) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    /** Returns an error message, or null when the server started. */
    fun start(root: File, port: Int, pass: String, uploads: Boolean): String? {
        stop()
        return try {
            allowUploads = uploads
            val s = FileServer(root, pass, { allowUploads }, port)
            s.start()
            server = s
            folder = root
            password = pass
            url = "http://${localIp() ?: "your-phone-ip"}:$port"
            running = true
            null
        } catch (e: Exception) {
            "Could not start: ${e.message}"
        }
    }

    fun stop() {
        server?.stop()
        server = null
        running = false
    }
}
