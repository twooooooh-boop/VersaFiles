package com.versa.files

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import java.io.File

/** id is "b:<builtin>" for VersaFiles' own viewers or "e:<package>/<activity>" for other apps. */
@Immutable
data class OpenTarget(
    val id: String,
    val label: String,
    val builtin: Boolean,
    val pkg: String = "",
    val cls: String = ""
)

@Immutable
data class OpenRequest(
    val item: FileItem,
    val pool: List<FileItem>,
    val targets: List<OpenTarget>,
    val key: String
)

object Sniffer {
    private fun head(file: File, n: Int): ByteArray? = try {
        val b = ByteArray(n)
        val read = file.inputStream().use { it.read(b) }
        if (read <= 0) null else b.copyOf(read)
    } catch (e: Exception) {
        null
    }

    /** Guesses the type from the first bytes, for files without a known extension. */
    fun kind(file: File): FileKind? {
        val b = head(file, 16) ?: return null
        val n = b.size
        if (n < 4) return null
        fun starts(vararg v: Int): Boolean = v.indices.all { it < n && (b[it].toInt() and 0xFF) == v[it] }
        fun ascii(from: Int, len: Int): String =
            if (from + len <= n) String(b, from, len, Charsets.ISO_8859_1) else ""
        return when {
            starts(0x89, 0x50, 0x4E, 0x47) || starts(0xFF, 0xD8, 0xFF) || starts(0x47, 0x49, 0x46, 0x38) -> FileKind.IMAGE
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> FileKind.IMAGE
            starts(0x25, 0x50, 0x44, 0x46) -> FileKind.PDF
            starts(0x50, 0x4B, 0x03, 0x04) || starts(0x37, 0x7A, 0xBC, 0xAF) || starts(0x52, 0x61, 0x72, 0x21) ||
                starts(0x1F, 0x8B) || starts(0x42, 0x5A, 0x68) || starts(0xFD, 0x37, 0x7A, 0x58) -> FileKind.ARCHIVE
            ascii(4, 4) == "ftyp" -> FileKind.VIDEO
            starts(0x49, 0x44, 0x33) || ascii(0, 4) == "OggS" || ascii(0, 4) == "fLaC" -> FileKind.AUDIO
            else -> null
        }
    }

    fun isSqlite(file: File): Boolean {
        val b = head(file, 15) ?: return false
        return String(b, Charsets.ISO_8859_1) == "SQLite format 3"
    }
}

object OpenWith {
    val builtinLabels = mapOf(
        "image" to "VersaFiles Image viewer",
        "media" to "VersaFiles Media player",
        "pdf" to "VersaFiles PDF viewer",
        "text" to "VersaFiles Text editor",
        "archive" to "VersaFiles Archive viewer",
        "hex" to "VersaFiles Hex viewer",
        "web" to "VersaFiles Web viewer",
        "sqlite" to "VersaFiles Database viewer",
        "doc" to "VersaFiles Document viewer"
    )

    private val docExts = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "epub")
    private val webExts = setOf("html", "htm", "xhtml", "svg", "mht", "mhtml")
    private val dbExts = setOf("db", "sqlite", "sqlite3", "db3", "sqlitedb")

    fun describe(id: String): String =
        if (id.startsWith("b:")) builtinLabels[id.removePrefix("b:")] ?: id
        else id.removePrefix("e:").substringBefore('/')

    fun targets(context: Context, item: FileItem): List<OpenTarget> {
        val ext = item.nameKey.substringAfterLast('.', "")
        var kind = item.kind
        if (kind == FileKind.OTHER) {
            Sniffer.kind(item.file)?.let { kind = it }
        }
        val ids = LinkedHashSet<String>()
        if (ext in docExts) ids.add("doc")
        if (ext in webExts) ids.add("web")
        if (ext in dbExts || Sniffer.isSqlite(item.file)) ids.add("sqlite")
        when (kind) {
            FileKind.IMAGE -> ids.add("image")
            FileKind.VIDEO, FileKind.AUDIO -> ids.add("media")
            FileKind.PDF -> ids.add("pdf")
            FileKind.ARCHIVE, FileKind.APK -> ids.add("archive")
            else -> {}
        }
        ids.add("text")
        ids.add("archive")
        ids.add("hex")

        val list = ArrayList<OpenTarget>()
        for (id in ids) {
            var label = builtinLabels[id] ?: id
            if (id == "text" && item.size > MAX_EDIT_BYTES) label = "VersaFiles Text viewer"
            list.add(OpenTarget("b:$id", label, true))
        }
        list.addAll(external(context, item.file))
        return list
    }

    @Suppress("DEPRECATION")
    private fun external(context: Context, file: File): List<OpenTarget> {
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val pm = context.packageManager
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .filter { it.activityInfo.packageName != context.packageName }
                .map {
                    OpenTarget(
                        id = "e:${it.activityInfo.packageName}/${it.activityInfo.name}",
                        label = it.loadLabel(pm).toString(),
                        builtin = false,
                        pkg = it.activityInfo.packageName,
                        cls = it.activityInfo.name
                    )
                }
                .distinctBy { it.id }
                .sortedBy { it.label.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun launchExternal(context: Context, file: File, t: OpenTarget) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(file))
                .setClassName(t.pkg, t.cls)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Can't open with ${t.label}", Toast.LENGTH_SHORT).show()
        }
    }
}
