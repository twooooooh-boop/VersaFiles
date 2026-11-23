package com.versa.files

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

const val MAX_EDIT_BYTES = 200L * 1024

enum class Page { FILES, TRASH, ANALYZER }

data class PasteBuffer(val files: List<File>, val move: Boolean)

data class UiMessage(val id: Long, val text: String)

data class ViewerState(val files: List<File>, val index: Int)

class FileManagerViewModel : ViewModel() {

    var currentDir by mutableStateOf<File?>(null)
        private set
    var category by mutableStateOf<Category?>(null)
        private set
    var items by mutableStateOf<List<FileItem>>(emptyList())
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var sortBy by mutableStateOf(SortBy.NAME)
        private set
    var ascending by mutableStateOf(true)
        private set
    var showHidden by mutableStateOf(false)
        private set
    var viewMode by mutableStateOf(ViewMode.LIST)
        private set
    var clipboard by mutableStateOf<PasteBuffer?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var searching by mutableStateOf(false)
        private set
    var message by mutableStateOf<UiMessage?>(null)
        private set
    var page by mutableStateOf(Page.FILES)
        private set
    var favorites by mutableStateOf<List<String>>(emptyList())
        private set
    var viewer by mutableStateOf<ViewerState?>(null)
        private set
    var editing by mutableStateOf<File?>(null)
        private set
    var trashEntries by mutableStateOf<List<TrashEntry>>(emptyList())
        private set
    var analysis by mutableStateOf<AnalyzerResult?>(null)
        private set
    var analyzing by mutableStateOf(false)
        private set
    var scannedCount by mutableStateOf(0L)
        private set

    private var rootPaths: Set<String> = emptySet()
    private var loadJob: Job? = null
    private var analyzerJob: Job? = null
    private var appContext: Context? = null
    private var prefs: Prefs? = null
    private var trash: TrashManager? = null

    fun attach(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val p = Prefs(app)
        prefs = p
        sortBy = p.sortBy
        ascending = p.ascending
        showHidden = p.showHidden
        viewMode = if (p.gridView) ViewMode.GRID else ViewMode.LIST
        favorites = p.favorites
        trash = TrashManager(app.getExternalFilesDir("trash") ?: File(app.filesDir, "trash"))
    }

    fun setRoots(paths: List<String>) {
        rootPaths = paths.toSet()
    }

    fun selectedFiles(): List<File> = items.filter { it.path in selected }.map { it.file }

    fun showMessage(text: String) {
        message = UiMessage(System.nanoTime(), text)
    }

    // ---------- navigation ----------

    fun open(dir: File?) {
        loadJob?.cancel()
        currentDir = dir
        category = null
        selected = emptySet()
        query = ""
        searching = false
        items = emptyList()
        if (dir != null) refresh() else loading = false
    }

    fun openCategory(c: Category) {
        loadJob?.cancel()
        currentDir = null
        category = c
        selected = emptySet()
        query = ""
        searching = false
        items = emptyList()
        refresh()
    }

    fun refresh() {
        val dir = currentDir
        val cat = category
        if (dir == null && cat == null) return
        loadJob?.cancel()
        val q = query
        val sort = sortBy
        val asc = ascending
        val hidden = showHidden
        val ctx = appContext
        loadJob = viewModelScope.launch {
            loading = true
            if (q.isNotBlank()) delay(250)
            val result: List<FileItem> = withContext(Dispatchers.IO) {
                if (cat != null) {
                    if (ctx != null) Categories.query(ctx, cat, hidden) else emptyList()
                } else if (dir == null) {
                    emptyList()
                } else if (q.isBlank()) {
                    FileOps.list(dir, hidden, sort, asc)
                } else {
                    FileOps.search(dir, q, hidden) { isActive }
                }
            }
            items = result
            loading = false
        }
    }

    fun back() {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            searching -> closeSearch()
            category != null -> open(null)
            else -> {
                val dir = currentDir ?: return
                if (dir.absolutePath in rootPaths) open(null) else open(dir.parentFile)
            }
        }
    }

    fun showInFolder(file: File) {
        val parent = file.parentFile ?: return
        closePage()
        open(parent)
    }

    // ---------- search / selection / view ----------

    fun startSearch() {
        searching = true
    }

    fun closeSearch() {
        searching = false
        query = ""
        refresh()
    }

    fun updateQuery(q: String) {
        query = q
        refresh()
    }

    fun toggleSelect(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    fun selectAll() {
        selected = items.map { it.path }.toSet()
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun setSort(s: SortBy) {
        if (sortBy == s) {
            ascending = !ascending
        } else {
            sortBy = s
            ascending = true
        }
        prefs?.sortBy = sortBy
        prefs?.ascending = ascending
        refresh()
    }

    fun toggleHidden() {
        showHidden = !showHidden
        prefs?.showHidden = showHidden
        refresh()
    }

    fun toggleView() {
        viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
        prefs?.gridView = viewMode == ViewMode.GRID
    }

    // ---------- built-in viewer / editor ----------

    fun openInternally(item: FileItem): Boolean {
        when (item.kind) {
            FileKind.IMAGE -> {
                val images = items.filter { it.kind == FileKind.IMAGE }.map { it.file }
                viewer = ViewerState(images, images.indexOf(item.file).coerceAtLeast(0))
                return true
            }
            FileKind.TEXT -> {
                if (item.size > MAX_EDIT_BYTES) return false
                editing = item.file
                return true
            }
            else -> return false
        }
    }

    fun closeViewer() {
        viewer = null
    }

    fun closeEditor() {
        editing = null
        refresh()
    }

    // ---------- favorites ----------

    fun toggleFavorite(dir: File) {
        val p = dir.absolutePath
        val adding = p !in favorites
        favorites = if (adding) favorites + p else favorites - p
        prefs?.favorites = favorites
        showMessage(if (adding) "Added to favorites" else "Removed from favorites")
    }

    // ---------- clipboard & file tasks ----------

    fun copySelected(move: Boolean) {
        clipboard = PasteBuffer(selectedFiles(), move)
        selected = emptySet()
        showMessage(if (move) "Cut. Open the destination and tap Paste" else "Copied. Open the destination and tap Paste")
    }

    fun cancelClipboard() {
        clipboard = null
    }

    private fun runTask(label: String, work: () -> String) {
        viewModelScope.launch {
            busy = label
            val result = withContext(Dispatchers.IO) { runCatching { work() } }
            busy = null
            showMessage(result.getOrElse { "Error: ${it.message ?: it.javaClass.simpleName}" })
            refresh()
            if (page == Page.TRASH) loadTrash()
        }
    }

    fun paste() {
        val cb = clipboard ?: return
        val dir = currentDir ?: return
        if (cb.move) clipboard = null
        runTask(if (cb.move) "Moving…" else "Copying…") {
            cb.files.forEach { if (cb.move) FileOps.move(it, dir) else FileOps.copy(it, dir) }
            "${if (cb.move) "Moved" else "Copied"} ${cb.files.size} item(s)"
        }
    }

    fun delete(files: List<File>, permanent: Boolean) {
        selected = emptySet()
        val t = trash
        if (permanent || t == null) {
            runTask("Deleting…") {
                var failed = 0
                files.forEach { if (!it.deleteRecursively()) failed++ }
                if (failed == 0) "Deleted ${files.size} item(s)" else "Could not delete $failed item(s)"
            }
        } else {
            runTask("Moving to recycle bin…") {
                t.trash(files)
                "Moved ${files.size} item(s) to the recycle bin"
            }
        }
    }

    fun rename(file: File, newName: String) {
        selected = emptySet()
        runTask("Renaming…") {
            FileOps.rename(file, newName)
            "Renamed to ${FileOps.cleanName(newName)}"
        }
    }

    fun newFolder(name: String) {
        val dir = currentDir ?: return
        runTask("Creating…") {
            val target = File(dir, FileOps.cleanName(name))
            if (target.exists()) throw IllegalArgumentException("Name already exists")
            if (!target.mkdirs()) throw IllegalStateException("Could not create folder")
            "Folder created"
        }
    }

    fun newFile(name: String) {
        val dir = currentDir ?: return
        runTask("Creating…") {
            val target = File(dir, FileOps.cleanName(name))
            if (target.exists()) throw IllegalArgumentException("Name already exists")
            if (!target.createNewFile()) throw IllegalStateException("Could not create file")
            "File created"
        }
    }

    fun compress(files: List<File>, name: String, format: ArchiveFormat) {
        val dir = currentDir ?: files.firstOrNull()?.parentFile ?: return
        selected = emptySet()
        runTask("Compressing…") {
            val out = FileOps.uniqueFile(dir, "${FileOps.cleanName(name)}.${format.ext}")
            try {
                Archiver.compress(files, out, format)
            } catch (e: Throwable) {
                out.delete()
                throw e
            }
            "Created ${out.name}"
        }
    }

    fun extract(file: File) {
        val dir = currentDir ?: file.parentFile ?: return
        selected = emptySet()
        runTask("Extracting…") {
            val dest = FileOps.uniqueFile(dir, file.nameWithoutExtension)
            try {
                Archiver.extract(file, dest)
            } catch (e: Throwable) {
                dest.deleteRecursively()
                throw e
            }
            "Extracted to ${dest.name}"
        }
    }

    // ---------- tool pages ----------

    fun closePage() {
        page = Page.FILES
        analyzerJob?.cancel()
        analyzing = false
    }

    fun openTrash() {
        page = Page.TRASH
        loadTrash()
    }

    fun loadTrash() {
        val t = trash ?: return
        viewModelScope.launch {
            trashEntries = withContext(Dispatchers.IO) { t.list() }
        }
    }

    fun restoreTrash(e: TrashEntry) {
        val t = trash ?: return
        runTask("Restoring…") {
            t.restore(e)
            "Restored ${e.item.name}"
        }
    }

    fun purgeTrash(e: TrashEntry) {
        val t = trash ?: return
        runTask("Deleting…") {
            t.purge(e)
            "Deleted ${e.item.name} permanently"
        }
    }

    fun emptyTrash() {
        val t = trash ?: return
        runTask("Emptying…") {
            t.emptyAll()
            "Recycle bin emptied"
        }
    }

    fun openAnalyzer() {
        page = Page.ANALYZER
        startAnalysis()
    }

    fun startAnalysis() {
        analyzerJob?.cancel()
        analyzing = true
        scannedCount = 0L
        analyzerJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                Analyzer.scan(
                    Environment.getExternalStorageDirectory(),
                    { n -> scannedCount = n },
                    { isActive }
                )
            }
            analysis = result
            analyzing = false
        }
    }
}
