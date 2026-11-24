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
import java.util.concurrent.CancellationException

const val MAX_EDIT_BYTES = 200L * 1024

enum class Page { FILES, TRASH, ANALYZER, DUPLICATES, APPS, SETTINGS }

data class PasteBuffer(val files: List<File>, val move: Boolean)

data class UiMessage(
    val id: Long,
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

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
    var taskProgress by mutableStateOf<Float?>(null)
        private set
    var taskDetail by mutableStateOf("")
        private set
    var taskCancellable by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var searching by mutableStateOf(false)
        private set
    var searchKinds by mutableStateOf<Set<FileKind>>(emptySet())
        private set
    var searchEverywhere by mutableStateOf(false)
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
    var trashCount by mutableStateOf(0)
        private set
    var trashSize by mutableStateOf(0L)
        private set
    var analysis by mutableStateOf<AnalyzerResult?>(null)
        private set
    var analyzing by mutableStateOf(false)
        private set
    var scannedCount by mutableStateOf(0L)
        private set
    var settings by mutableStateOf(Settings())
        private set
    var recent by mutableStateOf<List<FileItem>>(emptyList())
        private set
    var dupGroups by mutableStateOf<List<DupGroup>>(emptyList())
        private set
    var dupScanning by mutableStateOf(false)
        private set
    var dupProgress by mutableStateOf(0L)
        private set
    var dupSelected by mutableStateOf<Set<String>>(emptySet())
        private set
    var apps by mutableStateOf<List<AppInfo>>(emptyList())
        private set
    var appsLoading by mutableStateOf(false)
        private set
    var showSystemApps by mutableStateOf(false)
        private set
    var appQuery by mutableStateOf("")
        private set

    val searchActive: Boolean
        get() = query.isNotBlank() || searchKinds.isNotEmpty()

    private var rootPaths: Set<String> = emptySet()
    private var loadJob: Job? = null
    private var analyzerJob: Job? = null
    private var dupJob: Job? = null
    private var appContext: Context? = null
    private var prefs: Prefs? = null
    private var trash: TrashManager? = null
    private var lastTrashed: List<TrashEntry> = emptyList()
    private var currentProgress: TaskProgress? = null

    fun attach(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        Thumbs.init(app)
        val p = Prefs(app)
        prefs = p
        sortBy = p.sortBy
        ascending = p.ascending
        showHidden = p.showHidden
        viewMode = if (p.gridView) ViewMode.GRID else ViewMode.LIST
        favorites = p.favorites
        settings = p.loadSettings()
        val tm = TrashManager(app.getExternalFilesDir("trash") ?: File(app.filesDir, "trash"))
        trash = tm
        val days = settings.trashAutoEmptyDays
        if (days > 0) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    tm.purgeOlderThan(days)
                } catch (e: Throwable) {
                    // ignore
                }
            }
        }
        when (settings.startLocation) {
            StartLocation.INTERNAL -> open(Environment.getExternalStorageDirectory())
            StartLocation.DOWNLOADS ->
                open(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            StartLocation.HOME -> loadRecent()
        }
    }

    fun onPermissionGranted() {
        refresh()
    }

    fun setRoots(paths: List<String>) {
        rootPaths = paths.toSet()
    }

    fun selectedFiles(): List<File> = items.filter { it.path in selected }.map { it.file }

    fun showMessage(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        message = UiMessage(System.nanoTime(), text, actionLabel, action)
    }

    // ---------- settings ----------

    fun updateSettings(refreshList: Boolean = false, transform: (Settings) -> Settings) {
        val s = transform(settings)
        settings = s
        prefs?.saveSettings(s)
        if (refreshList) refresh()
    }

    fun resetSettings() {
        val p = prefs
        val s = Settings(palette = p?.defaultPalette() ?: 0)
        settings = s
        p?.saveSettings(s)
        refresh()
    }

    fun clearThumbCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Thumbs.clear() }
            showMessage("Thumbnail cache cleared")
        }
    }

    // ---------- navigation ----------

    fun open(dir: File?) {
        loadJob?.cancel()
        currentDir = dir
        category = null
        selected = emptySet()
        query = ""
        searching = false
        searchKinds = emptySet()
        searchEverywhere = false
        items = emptyList()
        if (dir != null) {
            refresh()
        } else {
            loading = false
            loadRecent()
        }
    }

    fun goTo(path: String) {
        val f = File(path.trim())
        if (f.isDirectory) open(f) else showMessage("Folder not found: $path")
    }

    fun openCategory(c: Category) {
        loadJob?.cancel()
        currentDir = null
        category = c
        selected = emptySet()
        query = ""
        searching = false
        searchKinds = emptySet()
        searchEverywhere = false
        items = emptyList()
        refresh()
    }

    fun loadRecent() {
        val ctx = appContext ?: return
        val hidden = showHidden
        val t = trash
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val list: List<FileItem> = try {
                    Categories.recent(ctx, 15, hidden)
                } catch (e: Throwable) {
                    emptyList()
                }
                list to (t?.count() ?: 0)
            }
            recent = result.first
            trashCount = result.second
        }
    }

    fun refresh() {
        val dir = currentDir
        val cat = category
        if (dir == null && cat == null) {
            loadRecent()
            return
        }
        loadJob?.cancel()
        val q = query
        val kinds = searchKinds
        val everywhere = searchEverywhere
        val sort = sortBy
        val asc = ascending
        val hidden = showHidden
        val first = settings.foldersFirst
        val ctx = appContext
        loadJob = viewModelScope.launch {
            loading = true
            if (q.isNotBlank()) delay(250)
            val searchOn = q.isNotBlank() || kinds.isNotEmpty()
            val result: List<FileItem> = withContext(Dispatchers.IO) {
                if (cat != null) {
                    if (ctx != null) Categories.query(ctx, cat, hidden) else emptyList()
                } else if (dir == null) {
                    emptyList()
                } else if (!searchOn) {
                    FileOps.list(dir, hidden, sort, asc, first)
                } else {
                    val root = if (everywhere) Environment.getExternalStorageDirectory() else dir
                    FileOps.search(root, q, hidden, kinds) { isActive }
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
        searchKinds = emptySet()
        searchEverywhere = false
        refresh()
    }

    fun updateQuery(q: String) {
        query = q
        refresh()
    }

    fun toggleSearchKinds(kinds: Set<FileKind>) {
        searchKinds = if (kinds.all { it in searchKinds }) searchKinds - kinds else searchKinds + kinds
        refresh()
    }

    fun toggleSearchEverywhere() {
        searchEverywhere = !searchEverywhere
        refresh()
    }

    fun toggleSelect(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    fun selectAll() {
        selected = items.map { it.path }.toSet()
    }

    fun invertSelection() {
        selected = items.map { it.path }.filter { it !in selected }.toSet()
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

    fun setSortOrder(s: SortBy, asc: Boolean) {
        sortBy = s
        ascending = asc
        prefs?.sortBy = s
        prefs?.ascending = asc
        refresh()
    }

    fun toggleHidden() {
        showHidden = !showHidden
        prefs?.showHidden = showHidden
        refresh()
    }

    fun toggleView() {
        changeViewMode(if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST)
    }

    fun changeViewMode(mode: ViewMode) {
        viewMode = mode
        prefs?.gridView = mode == ViewMode.GRID
    }

    // ---------- built-in viewer / editor ----------

    fun openInternally(item: FileItem, pool: List<FileItem> = items): Boolean {
        when (item.kind) {
            FileKind.IMAGE -> {
                val images = pool.filter { it.kind == FileKind.IMAGE }.map { it.file }
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

    fun deleteFromViewer(file: File) {
        val v = viewer ?: return
        val remaining = v.files.filter { it != file }
        viewer = if (remaining.isEmpty()) null
        else ViewerState(remaining, v.index.coerceAtMost(remaining.size - 1))
        delete(listOf(file), permanent = false)
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

    fun cancelTask() {
        currentProgress?.cancelled = true
    }

    private fun runTask(
        label: String,
        undoable: Boolean = false,
        progress: TaskProgress? = null,
        work: () -> String
    ) {
        viewModelScope.launch {
            busy = label
            currentProgress = progress
            taskProgress = null
            taskDetail = ""
            taskCancellable = progress != null
            val poller = if (progress != null) {
                launch {
                    while (isActive) {
                        val total = progress.total
                        taskProgress = if (total > 0) {
                            (progress.done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        } else {
                            null
                        }
                        taskDetail = progress.current
                        delay(100)
                    }
                }
            } else {
                null
            }
            val result = withContext(Dispatchers.IO) { runCatching { work() } }
            poller?.cancel()
            currentProgress = null
            taskCancellable = false
            busy = null
            val err = result.exceptionOrNull()
            val text = when {
                err == null -> result.getOrDefault("")
                err is CancellationException -> "Cancelled"
                else -> "Error: ${err.message ?: err.javaClass.simpleName}"
            }
            if (undoable && err == null && lastTrashed.isNotEmpty()) {
                showMessage(text, "Undo") { undoTrash() }
            } else {
                showMessage(text)
            }
            refresh()
            if (page == Page.TRASH) loadTrash()
        }
    }

    private fun undoTrash() {
        val t = trash ?: return
        val entries = lastTrashed
        lastTrashed = emptyList()
        if (entries.isEmpty()) return
        runTask("Restoring…") {
            entries.forEach { t.restore(it) }
            "Restored ${entries.size} item(s)"
        }
    }

    fun paste() {
        val cb = clipboard ?: return
        val dir = currentDir ?: return
        if (cb.move) clipboard = null
        val mode = settings.conflictMode
        val p = TaskProgress()
        runTask(if (cb.move) "Moving…" else "Copying…", progress = p) {
            p.total = cb.files.sumOf { FileOps.totalSize(it) }
            var done = 0
            var skipped = 0
            cb.files.forEach {
                val r = if (cb.move) FileOps.move(it, dir, p, mode) else FileOps.copy(it, dir, p, mode)
                if (r == null) skipped++ else done++
            }
            val verb = if (cb.move) "Moved" else "Copied"
            if (skipped > 0) "$verb $done item(s), skipped $skipped" else "$verb $done item(s)"
        }
    }

    fun delete(files: List<File>, permanent: Boolean) {
        selected = emptySet()
        val t = trash
        if (permanent || t == null || !settings.trashEnabled) {
            runTask("Deleting…") {
                var failed = 0
                files.forEach { if (!it.deleteRecursively()) failed++ }
                if (failed == 0) "Deleted ${files.size} item(s)" else "Could not delete $failed item(s)"
            }
        } else {
            runTask("Moving to trash…", undoable = true) {
                lastTrashed = t.trash(files)
                "Moved ${files.size} item(s) to trash"
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

    fun batchRename(files: List<File>, base: String) {
        selected = emptySet()
        runTask("Renaming…") {
            val b = FileOps.cleanName(base)
            if (b.isEmpty()) throw IllegalArgumentException("Invalid name")
            val sorted = files.sortedWith(
                Comparator<File> { x, y -> naturalCompare(x.name.lowercase(), y.name.lowercase()) }
            )
            val width = sorted.size.toString().length
            val temps = ArrayList<Triple<File, File, Int>>()
            sorted.forEachIndexed { i, f ->
                val tmp = File(f.parentFile, ".versa_tmp_${System.nanoTime()}_$i")
                if (!f.renameTo(tmp)) throw IllegalStateException("Rename failed: ${f.name}")
                temps.add(Triple(f, tmp, i))
            }
            temps.forEach { (orig, tmp, i) ->
                val dot = orig.name.lastIndexOf('.')
                val ext = if (orig.isFile && dot > 0) orig.name.substring(dot) else ""
                val num = (i + 1).toString().padStart(width, '0')
                val parent = orig.parentFile ?: throw IllegalStateException("No parent folder")
                val target = FileOps.uniqueFile(parent, "$b $num$ext")
                if (!tmp.renameTo(target)) {
                    tmp.renameTo(orig)
                    throw IllegalStateException("Rename failed: ${orig.name}")
                }
            }
            "Renamed ${files.size} items"
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
        val p = TaskProgress()
        runTask("Compressing…", progress = p) {
            val out = FileOps.uniqueFile(dir, "${FileOps.cleanName(name)}.${format.ext}")
            try {
                Archiver.compress(files, out, format, p)
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
        val p = TaskProgress()
        runTask("Extracting…", progress = p) {
            val dest = FileOps.uniqueFile(dir, file.nameWithoutExtension)
            try {
                Archiver.extract(file, dest, p)
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
        dupJob?.cancel()
        analyzing = false
        dupScanning = false
        loadRecent()
    }

    fun openSettings() {
        page = Page.SETTINGS
    }

    fun openTrash() {
        page = Page.TRASH
        loadTrash()
    }

    fun loadTrash() {
        val t = trash ?: return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                val list = t.list()
                list to t.totalSize(list)
            }
            trashEntries = r.first
            trashCount = r.first.size
            trashSize = r.second
        }
    }

    fun restoreTrash(e: TrashEntry) {
        val t = trash ?: return
        runTask("Restoring…") {
            t.restore(e)
            "Restored ${e.item.name}"
        }
    }

    fun restoreAllTrash() {
        val t = trash ?: return
        val all = trashEntries
        runTask("Restoring…") {
            all.forEach { t.restore(it) }
            "Restored ${all.size} item(s)"
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
        runTask("Emptying trash…") {
            t.emptyAll()
            "Trash emptied"
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

    fun openDuplicates() {
        page = Page.DUPLICATES
        startDuplicateScan()
    }

    fun startDuplicateScan() {
        dupJob?.cancel()
        dupScanning = true
        dupProgress = 0L
        dupSelected = emptySet()
        dupGroups = emptyList()
        dupJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                Duplicates.scan(
                    Environment.getExternalStorageDirectory(),
                    { n -> dupProgress = n },
                    { isActive }
                )
            }
            dupGroups = result
            dupScanning = false
        }
    }

    fun toggleDup(path: String) {
        dupSelected = if (path in dupSelected) dupSelected - path else dupSelected + path
    }

    fun autoSelectDuplicates() {
        dupSelected = dupGroups.flatMap { g -> g.files.drop(1).map { it.absolutePath } }.toSet()
    }

    fun deleteSelectedDuplicates() {
        val victims = dupSelected.map { File(it) }
        if (victims.isEmpty()) return
        dupSelected = emptySet()
        val t = trash
        val useTrash = settings.trashEnabled && t != null
        runTask(if (useTrash) "Moving to trash…" else "Deleting…", undoable = useTrash) {
            if (useTrash && t != null) {
                lastTrashed = t.trash(victims)
            } else {
                victims.forEach { it.deleteRecursively() }
            }
            val gone = victims.map { it.absolutePath }.toSet()
            dupGroups = dupGroups
                .map { g -> g.copy(files = g.files.filter { it.absolutePath !in gone }) }
                .filter { it.files.size > 1 }
            if (useTrash) "Moved ${victims.size} duplicate(s) to trash"
            else "Deleted ${victims.size} duplicate(s)"
        }
    }

    // ---------- apps ----------

    fun openApps() {
        page = Page.APPS
        if (apps.isEmpty()) loadApps()
    }

    fun loadApps() {
        val ctx = appContext ?: return
        appsLoading = true
        viewModelScope.launch {
            apps = withContext(Dispatchers.IO) {
                try {
                    AppsRepo.list(ctx)
                } catch (e: Throwable) {
                    emptyList()
                }
            }
            appsLoading = false
        }
    }

    fun toggleSystemApps() {
        showSystemApps = !showSystemApps
    }

    fun updateAppQuery(q: String) {
        appQuery = q
    }

    fun backupApp(app: AppInfo) {
        val p = TaskProgress()
        runTask("Extracting APK…", progress = p) {
            val outDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VersaFiles Apps"
            )
            outDir.mkdirs()
            val safe = (app.label + "_" + app.version).replace(Regex("[^A-Za-z0-9._-]"), "_")
            val sources = listOf(File(app.apkPath)) + app.splits.map { File(it) }
            p.total = sources.sumOf { it.length() }
            if (sources.size == 1) {
                val target = FileOps.uniqueFile(outDir, "$safe.apk")
                FileOps.copyFileTo(sources[0], target, p)
                "Saved ${target.name} to Downloads/VersaFiles Apps"
            } else {
                val dir = FileOps.uniqueFile(outDir, safe)
                dir.mkdirs()
                sources.forEach { FileOps.copyFileTo(it, File(dir, it.name), p) }
                "Saved ${sources.size} APK files to Downloads/VersaFiles Apps/${dir.name}"
            }
        }
    }
}
