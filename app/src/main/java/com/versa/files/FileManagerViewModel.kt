package com.versa.files

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

data class PasteBuffer(val files: List<File>, val move: Boolean)

data class UiMessage(val id: Long, val text: String)

class FileManagerViewModel : ViewModel() {

    var currentDir by mutableStateOf<File?>(null)
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

    private var rootPaths: Set<String> = emptySet()
    private var loadJob: Job? = null

    fun setRoots(paths: List<String>) {
        rootPaths = paths.toSet()
    }

    fun selectedFiles(): List<File> = items.filter { it.path in selected }.map { it.file }

    private fun toast(text: String) {
        message = UiMessage(System.nanoTime(), text)
    }

    fun open(dir: File?) {
        loadJob?.cancel()
        currentDir = dir
        selected = emptySet()
        query = ""
        searching = false
        items = emptyList()
        if (dir != null) refresh() else loading = false
    }

    fun refresh() {
        val dir = currentDir ?: return
        loadJob?.cancel()
        val q = query
        val sort = sortBy
        val asc = ascending
        val hidden = showHidden
        loadJob = viewModelScope.launch {
            loading = true
            if (q.isNotBlank()) delay(250)
            val result = withContext(Dispatchers.IO) {
                if (q.isBlank()) FileOps.list(dir, hidden, sort, asc)
                else FileOps.search(dir, q, hidden) { isActive }
            }
            items = result
            loading = false
        }
    }

    fun back() {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            searching -> closeSearch()
            else -> {
                val dir = currentDir ?: return
                if (dir.absolutePath in rootPaths) open(null) else open(dir.parentFile)
            }
        }
    }

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
        if (sortBy == s) ascending = !ascending else {
            sortBy = s
            ascending = true
        }
        refresh()
    }

    fun toggleHidden() {
        showHidden = !showHidden
        refresh()
    }

    fun toggleView() {
        viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
    }

    fun copySelected(move: Boolean) {
        clipboard = PasteBuffer(selectedFiles(), move)
        selected = emptySet()
        toast(if (move) "Cut. Open the destination and tap Paste" else "Copied. Open the destination and tap Paste")
    }

    fun cancelClipboard() {
        clipboard = null
    }

    private fun runTask(label: String, work: () -> String) {
        viewModelScope.launch {
            busy = label
            val result = withContext(Dispatchers.IO) { runCatching { work() } }
            busy = null
            toast(result.getOrElse { "Error: ${it.message ?: it.javaClass.simpleName}" })
            refresh()
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

    fun delete(files: List<File>) {
        selected = emptySet()
        runTask("Deleting…") {
            var failed = 0
            files.forEach { if (!it.deleteRecursively()) failed++ }
            if (failed == 0) "Deleted ${files.size} item(s)" else "Could not delete $failed item(s)"
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
        val dir = currentDir ?: return
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
        val dir = currentDir ?: return
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
}
