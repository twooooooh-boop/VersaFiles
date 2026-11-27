package com.versa.files

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScaffold(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    BackHandler(onBack = onClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) {
                            Text(subtitle, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        content = content
    )
}

// ---------------- hex viewer (works for literally any file) ----------------

private fun hexChunk(file: File, offset: Long, len: Long): String {
    return try {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(len.toInt())
            val n = raf.read(buf)
            if (n <= 0) {
                ""
            } else {
                val sb = StringBuilder()
                var i = 0
                while (i < n) {
                    val lineLen = minOf(16, n - i)
                    sb.append(String.format("%08X  ", offset + i))
                    for (j in 0 until 16) {
                        if (j < lineLen) sb.append(String.format("%02X ", buf[i + j])) else sb.append("   ")
                        if (j == 7) sb.append(' ')
                    }
                    sb.append(" |")
                    for (j in 0 until lineLen) {
                        val c = buf[i + j].toInt() and 0xFF
                        sb.append(if (c in 32..126) c.toChar() else '.')
                    }
                    sb.append("|\n")
                    i += 16
                }
                sb.toString()
            }
        }
    } catch (e: Exception) {
        ""
    }
}

@Composable
fun HexViewerScreen(file: File, onClose: () -> Unit) {
    val total = remember(file) { file.length() }
    val chunk = 2048L
    val count = (((total + chunk - 1) / chunk).toInt()).coerceAtLeast(1)
    val hs = rememberScrollState()
    ViewerScaffold(file.name, "${formatSize(total)}  ·  hex", onClose) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .horizontalScroll(hs)
        ) {
            items(count) { i ->
                val text by produceState("", file, i) {
                    value = withContext(Dispatchers.IO) { hexChunk(file, i * chunk, chunk) }
                }
                Text(
                    text.ifEmpty { " \n".repeat(128) },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

// ---------------- large text viewer ----------------

private fun readChunk(file: File, offset: Long, len: Long): String {
    return try {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(len.toInt())
            val n = raf.read(buf)
            if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
        }
    } catch (e: Exception) {
        ""
    }
}

@Composable
fun LargeTextScreen(file: File, onClose: () -> Unit) {
    val total = remember(file) { file.length() }
    val chunk = 32L * 1024
    val count = (((total + chunk - 1) / chunk).toInt()).coerceAtLeast(1)
    ViewerScaffold(file.name, "${formatSize(total)}  ·  read only", onClose) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            items(count) { i ->
                val text by produceState("", file, i) {
                    value = withContext(Dispatchers.IO) { readChunk(file, i * chunk, chunk) }
                }
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .then(if (text.isEmpty()) Modifier.height(400.dp) else Modifier)
                )
            }
        }
    }
}

// ---------------- web viewer (html, svg) ----------------

@Composable
fun WebViewerScreen(file: File, onClose: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            try {
                web?.destroy()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
    ViewerScaffold(file.name, "web view", onClose) { padding ->
        AndroidView(
            factory = { ctx ->
                try {
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    loadUrl("file://" + file.absolutePath)
                    web = this
                }
                } catch (e: Throwable) {
                    android.widget.TextView(ctx).apply { text = "Web view is not available on this device" }
                }
            },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        )
    }
}

// ---------------- sqlite viewer ----------------

private fun dbTables(file: File): List<String> {
    val out = ArrayList<String>()
    try {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val c: Cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type IN ('table','view') ORDER BY name", null
            )
            c.use { while (it.moveToNext()) out.add(it.getString(0)) }
        } finally {
            db.close()
        }
    } catch (e: Exception) {
        // not a readable database
    }
    return out
}

private fun dbRows(file: File, table: String): String {
    val sb = StringBuilder()
    try {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val quoted = "\"" + table.replace("\"", "\"\"") + "\""
            val c: Cursor = db.rawQuery("SELECT * FROM $quoted LIMIT 300", null)
            c.use {
                sb.append(it.columnNames.joinToString(" | ")).append('\n')
                sb.append("-".repeat(40)).append('\n')
                var rows = 0
                while (it.moveToNext()) {
                    rows++
                    val cells = ArrayList<String>()
                    for (i in 0 until it.columnCount) {
                        val cell = when (it.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> "NULL"
                            Cursor.FIELD_TYPE_BLOB -> "<blob ${it.getBlob(i).size} bytes>"
                            else -> it.getString(i).replace('\n', ' ')
                        }
                        cells.add(if (cell.length > 80) cell.take(80) + "…" else cell)
                    }
                    sb.append(cells.joinToString(" | ")).append('\n')
                }
                if (rows >= 300) sb.append("\n… showing the first 300 rows")
            }
        } finally {
            db.close()
        }
    } catch (e: Exception) {
        sb.append("Can't read this table: ${e.message}")
    }
    return sb.toString()
}

@Composable
fun SqliteViewerScreen(file: File, onClose: () -> Unit) {
    val tables by produceState<List<String>?>(null, file) {
        value = withContext(Dispatchers.IO) { dbTables(file) }
    }
    var selected by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(tables) {
        if (selected == null) selected = tables?.firstOrNull()
    }
    val content by produceState("", file, selected) {
        val t = selected
        value = if (t == null) "" else withContext(Dispatchers.IO) { dbRows(file, t) }
    }
    ViewerScaffold(file.name, "database", onClose) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val list = tables
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tables found. This may not be a readable database.")
                }
            } else {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    list.forEach { t ->
                        FilterChip(
                            selected = t == selected,
                            onClick = { selected = t },
                            label = { Text(t) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Text(
                    content,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp)
                )
            }
        }
    }
}

// ---------------- document viewer (docx, xlsx, pptx, odt, epub ...) ----------------

object DocText {
    private fun read(zf: ZipFile, name: String): String? {
        val e = zf.getEntry(name) ?: return null
        return zf.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun strip(xml: String): String {
        var s = xml.replace(Regex("<w:tab\\s*/>"), "\t").replace(Regex("<w:br\\s*/>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
    }

    fun extract(file: File): String {
        val ext = file.extension.lowercase()
        ZipFile(file).use { zf ->
            val out = StringBuilder()
            when (ext) {
                "docx" -> out.append(strip((read(zf, "word/document.xml") ?: "").replace("</w:p>", "\n")))
                "odt", "ods", "odp" -> out.append(
                    strip(
                        (read(zf, "content.xml") ?: "")
                            .replace(Regex("</text:p>|</text:h>|</table:table-row>"), "\n")
                    )
                )
                "pptx" -> {
                    val slideRegex = Regex("ppt/slides/slide\\d+\\.xml")
                    val slides = zf.entries().asSequence().map { it.name }
                        .filter { slideRegex.matches(it) }
                        .sortedWith(Comparator<String> { a, b -> naturalCompare(a, b) })
                        .toList()
                    slides.forEachIndexed { i, n ->
                        out.append("— Slide ${i + 1} —\n")
                        out.append(strip((read(zf, n) ?: "").replace("</a:p>", "\n")))
                        out.append("\n")
                    }
                }
                "xlsx" -> {
                    out.append("(cell text only)\n")
                    out.append(strip((read(zf, "xl/sharedStrings.xml") ?: "").replace("</si>", "\n")))
                }
                "epub" -> {
                    val pages = zf.entries().asSequence().map { it.name }
                        .filter { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }
                        .sortedWith(Comparator<String> { a, b -> naturalCompare(a, b) })
                        .toList()
                    for (n in pages) {
                        out.append(
                            strip(
                                (read(zf, n) ?: "").replace(Regex("</p>|<br\\s*/?>|</h[1-6]>"), "\n")
                            )
                        )
                        out.append("\n")
                    }
                }
            }
            return out.toString().replace(Regex("\n{3,}"), "\n\n").take(3_000_000)
        }
    }
}

@Composable
fun DocViewerScreen(file: File, onClose: () -> Unit) {
    val text by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try {
                DocText.extract(file)
            } catch (e: Exception) {
                ""
            }
        }
    }
    ViewerScaffold(file.name, "text only", onClose) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val t = text
            if (t == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (t.isBlank()) {
                Text(
                    "No readable text found in this document",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                val lines = remember(t) { t.split("\n") }
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                ) {
                    items(lines) { line ->
                        Text(
                            line.ifEmpty { " " },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Read-only text screen (used for decoded binary XML such as AndroidManifest.xml). */
@Composable
fun TextViewerScreen(title: String, subtitle: String?, text: String, onClose: () -> Unit) {
    val lines = remember(text) { text.split("\n") }
    ViewerScaffold(title, subtitle, onClose) { padding ->
        SelectionContainer {
            LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
            ) {
                items(lines) { line ->
                    Text(
                        line.ifEmpty { " " },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}
