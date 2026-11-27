package com.versa.files

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

const val MAX_EDIT_BYTES = 200L * 1024

enum class Page { FILES, TRASH, ANALYZER, DUPLICATES, CLEANER, APPS, SHARE, ARCHIVE, ASSOCIATIONS, SETTINGS }

data class PasteBuffer(val files: List<File>, val move: Boolean)

data class UiMessage(
    val id: Long,
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

data class ViewerState(val files: List<File>, val index: Int)

data class DecodedDoc(val title: String, val text: String)

private class PendingDelete(val restore: () -> Unit, val commit: () -> Unit)

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
    var viewer by mutableStateOf<ViewerState?>(null)
        private set
    var editing by mutableStateOf<File?>(null)
        private set
    var playing by mutableStateOf<File?>(null)
        private set
    var pdfFile by mutableStateOf<File?>(null)
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
    var cleanGroups by mutableStateOf<List<JunkGroup>>(emptyList())
        private set
    var cleanScanning by mutableStateOf(false)
        private set
    var cleanProgress by mutableStateOf(0L)
        private set
    var cleanSelected by mutableStateOf<Set<String>>(emptySet())
        private set
    var apps by mutableStateOf<List<AppInfo>>(emptyList())
        private set
    var appsLoading by mutableStateOf(false)
        private set
    var showSystemApps by mutableStateOf(false)
        private set
    var appQuery by mutableStateOf("")
        private set
    var archiveFile by mutableStateOf<File?>(null)
        private set
    var archivePrefix by mutableStateOf("")
        private set
    var archiveItems by mutableStateOf<List<ArchiveEntryItem>>(emptyList())
        private set
    var archiveSelected by mutableStateOf<Set<String>>(emptySet())
        private set
    var archiveLoading by mutableStateOf(false)
        private set
    var shareTarget by mutableStateOf<File?>(null)
        private set
    var openRequest by mutableStateOf<OpenRequest?>(null)
        private set
    var hexFile by mutableStateOf<File?>(null)
        private set
    var bigText by mutableStateOf<File?>(null)
        private set
    var webFile by mutableStateOf<File?>(null)
        private set
    var dbFile by mutableStateOf<File?>(null)
        private set
    var docFile by mutableStateOf<File?>(null)
        private set
    var axmlDoc by mutableStateOf<DecodedDoc?>(null)
        private set
    var assocEditor by mutableStateOf<AssocEditor?>(null)
        private set

    // shared between all tabs
    var settings: Settings
        get() = SharedState.settings
        private set(value) {
            SharedState.settings = value
        }
    var favorites: List<String>
        get() = SharedState.favorites
        private set(value) {
            SharedState.favorites = value
        }
    var clipboard: PasteBuffer?
        get() = SharedState.clipboard
        private set(value) {
            SharedState.clipboard = value
        }

    val searchActive: Boolean
        get() = query.isNotBlank() || searchKinds.isNotEmpty()

    private var rootPaths: Set<String> = emptySet()
    private var loadJob: Job? = null
    private var analyzerJob: Job? = null
    private var dupJob: Job? = null
    private var cleanJob: Job? = null
    private var appContext: Context? = null
    private var prefs: Prefs? = null
    private var trash: TrashManager? = null
    private var staging: TrashManager? = null
    private var stagingDir: File? = null
    private var currentProgress: TaskProgress? = null
    private var undoAction: (() -> Unit)? = null
    private var pending: PendingDelete? = null
    private var pendingJob: Job? = null
    private var archiveSession: ArchiveSession? = null

    /** Any unexpected error inside a background job becomes a message instead of a crash. */
    private val safeHandler = CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) {
            showMessage("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(safeHandler, block = block)

    fun attach(context: Context, applyStart: Boolean = true) {
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
        if (!SharedState.loaded) {
            SharedState.loaded = true
            settings = p.loadSettings()
            favorites = p.favorites
            SharedState.defaults = p.openDefaults
        }
        val tm = TrashManager(app.getExternalFilesDir("trash") ?: File(app.filesDir, "trash"))
        trash = tm
        val sd = app.getExternalFilesDir("staging") ?: File(app.filesDir, "staging")
        sd.mkdirs()
        stagingDir = sd
        staging = TrashManager(sd)
        if (!SharedState.started) {
            SharedState.started = true
            val days = settings.trashAutoEmptyDays
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    sd.listFiles()?.forEach { it.deleteRecursively() }
                    File(app.cacheDir, "archive_tmp").deleteRecursively()
                    if (days > 0) tm.purgeOlderThan(days)
                } catch (e: Throwable) {
                    // ignore
                }
            }
        }
        if (applyStart) {
            when (settings.startLocation) {
                StartLocation.INTERNAL -> open(Environment.getExternalStorageDirectory())
                StartLocation.DOWNLOADS ->
                    open(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                StartLocation.HOME -> loadRecent()
            }
        }
    }

    override fun onCleared() {
        val p = pending
        pending = null
        if (p != null) {
            thread(isDaemon = false) {
                try {
                    p.commit()
                } catch (e: Throwable) {
                    // ignore
                }
            }
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
        showMessage("Settings reset to defaults")
    }

    fun clearThumbCache() {
        launchSafe {
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
        launchSafe {
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
        loadJob = launchSafe {
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

    // ---------- built-in viewers ----------

    /** Starts "open a file": uses the saved ALWAYS choice, or shows the Open with list. */
    fun openFileFlow(item: FileItem, pool: List<FileItem> = items, forceAsk: Boolean = false) {
        val ctx = appContext ?: return
        val raw = item.nameKey.substringAfterLast('.', "")
        val key = if (raw.isEmpty()) "(none)" else raw
        launchSafe {
            val maxEdit = settings.editorMaxKb * 1024L
            val targets = withContext(Dispatchers.IO) { OpenWith.targets(ctx, item, maxEdit) }
            if (!forceAsk) {
                val saved = SharedState.defaults[key]
                val savedTarget = if (saved != null) targets.firstOrNull { it.id == saved } else null
                if (savedTarget != null) {
                    launchTarget(savedTarget, item, pool)
                    return@launchSafe
                }
                if (!settings.askOpenWith) {
                    targets.firstOrNull()?.let { launchTarget(it, item, pool) }
                    return@launchSafe
                }
            }
            openRequest = OpenRequest(item, pool, targets, key, SharedState.defaults[key])
        }
    }

    fun chooseTarget(t: OpenTarget, always: Boolean) {
        val r = openRequest ?: return
        openRequest = null
        if (always) {
            val map = SharedState.defaults + (r.key to t.id)
            SharedState.defaults = map
            prefs?.openDefaults = map
            val what = if (r.key == "(none)") "these files" else ".${r.key} files"
            showMessage("Always open $what with ${t.label}")
        }
        launchTarget(t, r.item, r.pool)
    }

    fun dismissOpenRequest() {
        openRequest = null
    }

    fun clearDefault(key: String) {
        val map = SharedState.defaults - key
        SharedState.defaults = map
        prefs?.openDefaults = map
        showMessage("Default cleared. You will be asked next time")
    }

    fun clearAllDefaults() {
        SharedState.defaults = emptyMap()
        prefs?.openDefaults = emptyMap()
        showMessage("All default apps cleared")
    }

    private fun launchTarget(t: OpenTarget, item: FileItem, pool: List<FileItem>) {
        if (!t.builtin) {
            appContext?.let { OpenWith.launchExternal(it, item.file, t) }
            return
        }
        launchBuiltin(t.id.removePrefix("b:"), item, pool)
    }

    private fun launchBuiltin(id: String, item: FileItem, pool: List<FileItem>) {
        when (id) {
            "image" -> {
                val images = pool.filter { it.kind == FileKind.IMAGE }.map { it.file }
                val list = if (item.file in images) images else listOf(item.file)
                viewer = ViewerState(list, list.indexOf(item.file).coerceAtLeast(0))
            }
            "media" -> playing = item.file
            "pdf" -> pdfFile = item.file
            "text" -> openText(item)
            "axml" -> openAxml(item)
            "archive" -> openArchive(item.file)
            "hex" -> hexFile = item.file
            "web" -> webFile = item.file
            "sqlite" -> dbFile = item.file
            "doc" -> docFile = item.file
        }
    }

    /** Never opens binary or monster-line files in the editor: they go to a safe viewer instead. */
    private fun openText(item: FileItem) {
        val maxEdit = settings.editorMaxKb * 1024L
        launchSafe {
            val mode = withContext(Dispatchers.IO) {
                try {
                    when {
                        Sniffer.isAxml(item.file) -> 1
                        Sniffer.isBinary(item.file) -> 2
                        item.size > maxEdit || Sniffer.hasHugeLine(item.file, 20_000) -> 3
                        else -> 0
                    }
                } catch (e: Throwable) {
                    2
                }
            }
            when (mode) {
                1 -> openAxml(item)
                2 -> {
                    showMessage("This is a binary file, so it opened in the hex viewer")
                    hexFile = item.file
                }
                3 -> bigText = item.file
                else -> editing = item.file
            }
        }
    }

    private fun openAxml(item: FileItem) {
        launchSafe {
            val text = withContext(Dispatchers.IO) { Axml.decodeFile(item.file) }
            if (text != null) {
                axmlDoc = DecodedDoc(item.name, text)
            } else {
                showMessage("Can't decode this file, so it opened in the hex viewer")
                hexFile = item.file
            }
        }
    }

    fun closeAxml() {
        axmlDoc = null
    }

    // ---------- file associations ----------

    fun openAssociations() {
        page = Page.ASSOCIATIONS
    }

    fun editAssociation(ext: String) {
        val ctx = appContext ?: return
        launchSafe {
            val targets = withContext(Dispatchers.IO) { OpenWith.targetsForExt(ctx, ext) }
            assocEditor = AssocEditor(ext, targets, SharedState.defaults[ext])
        }
    }

    fun setAssociation(ext: String, id: String) {
        val map = SharedState.defaults + (ext to id)
        SharedState.defaults = map
        prefs?.openDefaults = map
        assocEditor = null
        showMessage("Always open .$ext files with ${OpenWith.describe(id)}")
    }

    fun closeAssocEditor() {
        assocEditor = null
    }

    fun closeHex() {
        hexFile = null
    }

    fun closeBigText() {
        bigText = null
    }

    fun closeWeb() {
        webFile = null
    }

    fun closeDb() {
        dbFile = null
    }

    fun closeDoc() {
        docFile = null
    }

    fun closeViewer() {
        viewer = null
    }

    fun closePlayer() {
        playing = null
    }

    fun closePdf() {
        pdfFile = null
    }

    fun playerFallback(file: File) {
        playing = null
        appContext?.let { openFile(it, file) }
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
        val files = selectedFiles()
        clipboard = PasteBuffer(files, move)
        selected = emptySet()
        showMessage(
            (if (move) "Cut " else "Copied ") + "${files.size} item(s). Open the destination and tap Paste"
        )
    }

    fun cancelClipboard() {
        clipboard = null
        showMessage("Cancelled")
    }

    fun cancelTask() {
        currentProgress?.cancelled = true
    }

    private fun runTask(
        label: String,
        progress: TaskProgress? = null,
        work: () -> String
    ) {
        launchSafe {
            busy = label
            currentProgress = progress
            undoAction = null
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
            val undo = undoAction
            undoAction = null
            if (err == null && undo != null) {
                showMessage(text, "Undo") { undo() }
            } else {
                showMessage(text)
            }
            refresh()
            if (page == Page.TRASH) loadTrash()
        }
    }

    // ----- undo support for deletes -----

    private fun commitPending() {
        val p = pending ?: return
        pending = null
        pendingJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                p.commit()
            } catch (e: Throwable) {
                // ignore
            }
        }
    }

    private fun registerPending(p: PendingDelete) {
        commitPending()
        pending = p
        pendingJob = launchSafe {
            delay(15_000)
            if (pending === p) {
                pending = null
                withContext(Dispatchers.IO) {
                    try {
                        p.commit()
                    } catch (e: Throwable) {
                        // ignore
                    }
                }
            }
        }
    }

    private fun undoPending(p: PendingDelete) {
        if (pending !== p) {
            showMessage("Too late to undo")
            return
        }
        pending = null
        pendingJob?.cancel()
        runTask("Restoring…") {
            p.restore()
            "Restored"
        }
    }

    private fun restoreEntries(t: TrashManager, entries: List<TrashEntry>) {
        runTask("Restoring…") {
            entries.forEach { t.restore(it) }
            "Restored ${entries.size} item(s)"
        }
    }

    /** Runs on a background thread. Sets undoAction when the delete can be undone. */
    private fun deleteWork(files: List<File>, permanent: Boolean): String {
        val t = trash
        val sm = staging
        if (!permanent && settings.trashEnabled && t != null) {
            val entries = t.trash(files)
            undoAction = { restoreEntries(t, entries) }
            return "Moved ${files.size} item(s) to trash"
        }
        val primary = Environment.getExternalStorageDirectory().absolutePath
        val stageable = if (sm != null) files.filter { it.absolutePath.startsWith(primary) } else emptyList()
        val direct = files.filter { it !in stageable }
        val entries = if (sm != null && stageable.isNotEmpty()) sm.trash(stageable) else emptyList()
        direct.forEach { it.deleteRecursively() }
        if (sm != null && entries.isNotEmpty()) {
            val p = PendingDelete(
                restore = { entries.forEach { sm.restore(it) } },
                commit = { entries.forEach { sm.purge(it) } }
            )
            registerPending(p)
            undoAction = { undoPending(p) }
            return "Deleted ${files.size} item(s)"
        }
        return "Deleted ${files.size} item(s) permanently"
    }

    /** Removes trash entries but keeps them aside for a few seconds so Undo works. */
    private fun stashTrash(entries: List<TrashEntry>): String {
        val dir = stagingDir
        if (dir == null) {
            entries.forEach { trash?.purge(it) }
            return "Deleted ${entries.size} item(s) from trash"
        }
        val moved = ArrayList<Pair<File, File>>()
        for (e in entries) {
            val target = File(dir, "p_" + e.folder.name)
            if (e.folder.renameTo(target)) moved.add(e.folder to target) else e.folder.deleteRecursively()
        }
        if (moved.isNotEmpty()) {
            val p = PendingDelete(
                restore = { moved.forEach { (orig, staged) -> staged.renameTo(orig) } },
                commit = { moved.forEach { it.second.deleteRecursively() } }
            )
            registerPending(p)
            undoAction = { undoPending(p) }
        }
        return "Deleted ${entries.size} item(s) from trash"
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
        val toTrash = !permanent && settings.trashEnabled
        runTask(if (toTrash) "Moving to trash…" else "Deleting…") { deleteWork(files, permanent) }
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

    // ---------- encryption ----------

    fun encryptFiles(files: List<File>, password: String, trashOriginals: Boolean) {
        selected = emptySet()
        val p = TaskProgress()
        runTask("Encrypting…", progress = p) {
            val list = files.filter { it.isFile }
            if (list.isEmpty()) throw IllegalArgumentException("Select files (compress folders first)")
            p.total = list.sumOf { it.length() }
            for (f in list) {
                val parent = f.parentFile ?: throw IllegalStateException("No parent folder")
                val out = FileOps.uniqueFile(parent, f.name + ".vfenc")
                try {
                    Crypto.encrypt(f, out, password, p)
                } catch (e: Throwable) {
                    out.delete()
                    throw e
                }
            }
            if (trashOriginals) {
                deleteWork(list, false)
                "Encrypted ${list.size} file(s). Originals moved to trash"
            } else {
                "Encrypted ${list.size} file(s). The originals are still there"
            }
        }
    }

    fun decryptFile(file: File, password: String) {
        val p = TaskProgress()
        runTask("Decrypting…", progress = p) {
            val parent = file.parentFile ?: throw IllegalStateException("No parent folder")
            val base = if (file.name.endsWith(".vfenc")) file.name.removeSuffix(".vfenc") else file.name + ".decrypted"
            val out = FileOps.uniqueFile(parent, base)
            try {
                Crypto.decrypt(file, out, password, p)
            } catch (e: Throwable) {
                out.delete()
                throw e
            }
            "Decrypted to ${out.name}"
        }
    }

    // ---------- archive browser ----------

    fun openArchive(file: File) {
        page = Page.ARCHIVE
        archiveFile = file
        archivePrefix = ""
        archiveSelected = emptySet()
        archiveItems = emptyList()
        archiveLoading = true
        archiveSession = null
        launchSafe {
            val s = ArchiveSession(file)
            val ok = withContext(Dispatchers.IO) {
                try {
                    s.load()
                    true
                } catch (e: Throwable) {
                    false
                }
            }
            if (ok) {
                archiveSession = s
                archiveItems = s.list("")
            } else {
                showMessage("Can't read this archive")
                closeArchive()
            }
            archiveLoading = false
        }
    }

    fun archiveOpenDir(path: String) {
        archivePrefix = path
        archiveSelected = emptySet()
        archiveItems = archiveSession?.list(path) ?: emptyList()
    }

    /** Returns false when already at the archive root. */
    fun archiveUp(): Boolean {
        if (archivePrefix.isEmpty()) return false
        val parent = archivePrefix.trimEnd('/').substringBeforeLast('/', "")
        archiveOpenDir(if (parent.isEmpty()) "" else "$parent/")
        return true
    }

    fun toggleArchiveSelect(path: String) {
        archiveSelected = if (path in archiveSelected) archiveSelected - path else archiveSelected + path
    }

    fun clearArchiveSelection() {
        archiveSelected = emptySet()
    }

    fun closeArchive() {
        page = Page.FILES
        archiveFile = null
        archiveSession = null
        archiveItems = emptyList()
        archiveSelected = emptySet()
        archivePrefix = ""
        refresh()
    }

    fun extractArchive(selectedOnly: Boolean) {
        val file = archiveFile ?: return
        val s = archiveSession ?: return
        val sel = if (selectedOnly) archiveSelected else null
        val base = if (selectedOnly) archivePrefix else ""
        archiveSelected = emptySet()
        val p = TaskProgress()
        runTask("Extracting…", progress = p) {
            val parent = file.parentFile ?: throw IllegalStateException("No parent folder")
            val dest = FileOps.uniqueFile(parent, file.nameWithoutExtension)
            try {
                s.extract(sel, base, dest, p)
            } catch (e: Throwable) {
                dest.deleteRecursively()
                throw e
            }
            "Extracted to ${dest.name}"
        }
    }

    fun openArchiveEntry(entry: ArchiveEntryItem) {
        val s = archiveSession ?: return
        val ctx = appContext ?: return
        launchSafe {
            busy = "Opening ${entry.name}…"
            val result = withContext(Dispatchers.IO) {
                runCatching { s.extractToCache(File(ctx.cacheDir, "archive_tmp"), entry.path) }
            }
            busy = null
            val f = result.getOrNull()
            if (f == null) {
                showMessage("Can't open this file")
            } else {
                val item = toItem(f)
                openFileFlow(item, listOf(item))
            }
        }
    }

    // ---------- tool pages ----------

    fun closePage() {
        if (page == Page.ARCHIVE) {
            closeArchive()
            return
        }
        page = Page.FILES
        analyzerJob?.cancel()
        dupJob?.cancel()
        cleanJob?.cancel()
        analyzing = false
        dupScanning = false
        cleanScanning = false
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
        launchSafe {
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
        runTask("Deleting…") { stashTrash(listOf(e)) }
    }

    fun emptyTrash() {
        val t = trash ?: return
        runTask("Emptying trash…") { stashTrash(t.list()) }
    }

    fun openAnalyzer() {
        page = Page.ANALYZER
        startAnalysis()
    }

    fun startAnalysis() {
        analyzerJob?.cancel()
        analyzing = true
        scannedCount = 0L
        analyzerJob = launchSafe {
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
        dupJob = launchSafe {
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
        runTask("Deleting duplicates…") {
            val msg = deleteWork(victims, false)
            val gone = victims.map { it.absolutePath }.toSet()
            dupGroups = dupGroups
                .map { g -> g.copy(files = g.files.filter { it.absolutePath !in gone }) }
                .filter { it.files.size > 1 }
            msg
        }
    }

    // ---------- cleaner ----------

    fun openCleaner() {
        page = Page.CLEANER
        startCleanScan()
    }

    fun startCleanScan() {
        cleanJob?.cancel()
        cleanScanning = true
        cleanProgress = 0L
        cleanSelected = emptySet()
        cleanGroups = emptyList()
        cleanJob = launchSafe {
            val result = withContext(Dispatchers.IO) {
                Cleaner.scan(
                    Environment.getExternalStorageDirectory(),
                    { n -> cleanProgress = n },
                    { isActive }
                )
            }
            cleanGroups = result
            cleanScanning = false
        }
    }

    fun toggleClean(path: String) {
        cleanSelected = if (path in cleanSelected) cleanSelected - path else cleanSelected + path
    }

    fun toggleCleanGroup(group: JunkGroup) {
        val paths = group.files.map { it.file.absolutePath }
        cleanSelected = if (paths.all { it in cleanSelected }) cleanSelected - paths.toSet()
        else cleanSelected + paths
    }

    fun cleanSelectedNow() {
        val victims = cleanSelected.map { File(it) }
        if (victims.isEmpty()) return
        cleanSelected = emptySet()
        runTask("Cleaning…") {
            val msg = deleteWork(victims, false)
            val gone = victims.map { it.absolutePath }.toSet()
            cleanGroups = cleanGroups
                .map { g -> g.copy(files = g.files.filter { it.file.absolutePath !in gone }) }
                .filter { it.files.isNotEmpty() }
            msg
        }
    }

    // ---------- Wi-Fi share ----------

    fun openShare(folder: File?) {
        shareTarget = folder ?: Environment.getExternalStorageDirectory()
        page = Page.SHARE
    }

    // ---------- apps ----------

    fun openApps() {
        page = Page.APPS
        if (apps.isEmpty()) loadApps()
    }

    fun loadApps() {
        val ctx = appContext ?: return
        appsLoading = true
        launchSafe {
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
