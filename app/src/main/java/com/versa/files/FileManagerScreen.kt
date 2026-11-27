package com.versa.files

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface UiDialog {
    data class Delete(val files: List<File>, val trash: Boolean) : UiDialog
    data class BatchRename(val files: List<File>) : UiDialog
    data class Encrypt(val files: List<File>) : UiDialog
    data class Decrypt(val file: File) : UiDialog
    data class Rename(val file: File) : UiDialog
    data object NewFolder : UiDialog
    data object NewFile : UiDialog
    data object GoTo : UiDialog
    data class Compress(val files: List<File>) : UiDialog
    data class Info(val file: File) : UiDialog
}

private data class Quick(val name: String, val dir: File, val icon: ImageVector)

private data class StorageInfo(val root: StorageRoot, val total: Long, val free: Long)

private fun categoryIcon(c: Category): ImageVector = when (c) {
    Category.IMAGES -> Icons.Default.Image
    Category.VIDEOS -> Icons.Default.Movie
    Category.AUDIO -> Icons.Default.MusicNote
    Category.DOCUMENTS -> Icons.Default.Description
    Category.APKS -> Icons.Default.Android
    Category.ARCHIVES -> Icons.Default.FolderZip
}

private fun categoryColor(c: Category): Color = when (c) {
    Category.IMAGES -> Color(0xFF43A047)
    Category.VIDEOS -> Color(0xFFE53935)
    Category.AUDIO -> Color(0xFF8E24AA)
    Category.DOCUMENTS -> Color(0xFF1E88E5)
    Category.APKS -> Color(0xFF3DDC84)
    Category.ARCHIVES -> Color(0xFFF9A825)
}

@Composable
fun FileManagerScreen(vm0: FileManagerViewModel, tabs: TabsViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val vms = tabs.ids.map { id ->
        if (id == 0) vm0 else viewModel<FileManagerViewModel>(key = "tab$id")
    }
    tabs.ids.forEachIndexed { i, id ->
        val v = vms[i]
        remember(v) {
            v.attach(context, applyStart = false)
            tabs.consumeStart(id)?.let { v.open(it) }
            true
        }
    }
    val active = vms.getOrNull(tabs.ids.indexOf(tabs.active)) ?: vm0
    val multi = tabs.ids.size > 1
    val shown = remember { HashSet<Long>() }

    LaunchedEffect(active, active.message) {
        val m = active.message ?: return@LaunchedEffect
        if (!shown.add(m.id)) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = m.text,
            actionLabel = m.actionLabel,
            duration = if (m.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) m.action?.invoke()
    }
    BackHandler(enabled = multi) { tabs.closeTab(tabs.active) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (multi) TabStrip(tabs, vms)
            Box(
                Modifier
                    .weight(1f)
                    .then(if (multi) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)
            ) {
                key(tabs.active) { FilesContent(active, tabs) }
            }
        }
        when (active.page) {
            Page.TRASH -> TrashScreen(active)
            Page.ANALYZER -> AnalyzerScreen(active)
            Page.DUPLICATES -> DuplicatesScreen(active)
            Page.CLEANER -> CleanerScreen(active)
            Page.APPS -> AppsScreen(active)
            Page.SHARE -> ShareScreen(active)
            Page.ARCHIVE -> ArchiveScreen(active)
            Page.ASSOCIATIONS -> AssociationsScreen(active)
            Page.SETTINGS -> SettingsScreen(active)
            Page.FILES -> {}
        }
        active.editing?.let { f ->
            TextEditor(
                file = f,
                fontSize = active.settings.editorFontSize,
                onClose = { active.closeEditor() },
                onNotify = { active.showMessage(it) }
            )
        }
        active.pdfFile?.let { f ->
            PdfViewerScreen(f) { active.closePdf() }
        }
        active.hexFile?.let { f -> HexViewerScreen(f) { active.closeHex() } }
        active.bigText?.let { f -> LargeTextScreen(f) { active.closeBigText() } }
        active.webFile?.let { f -> WebViewerScreen(f) { active.closeWeb() } }
        active.dbFile?.let { f -> SqliteViewerScreen(f) { active.closeDb() } }
        active.docFile?.let { f -> DocViewerScreen(f) { active.closeDoc() } }
        active.axmlDoc?.let { d ->
            TextViewerScreen(d.title, "decoded binary XML (read only)", d.text) { active.closeAxml() }
        }
        active.assocEditor?.let { e ->
            AssociationDialog(
                editor = e,
                onSet = { id -> active.setAssociation(e.ext, id) },
                onAsk = {
                    active.clearDefault(e.ext)
                    active.closeAssocEditor()
                },
                onDismiss = { active.closeAssocEditor() }
            )
        }
        active.openRequest?.let { r ->
            OpenWithDialog(
                request = r,
                onDismiss = { active.dismissOpenRequest() },
                onChoose = { t, always -> active.chooseTarget(t, always) },
                onClearDefault = { active.clearDefault(r.key) }
            )
        }
        active.playing?.let { f ->
            MediaPlayerScreen(
                file = f,
                isAudio = kindOfExt(f.extension.lowercase()) == FileKind.AUDIO,
                onClose = { active.closePlayer() },
                onFallback = { active.playerFallback(it) }
            )
        }
        active.viewer?.let { s ->
            ImageViewer(
                state = s,
                trashEnabled = active.settings.trashEnabled,
                onClose = { active.closeViewer() },
                onShare = { shareFiles(context, listOf(it)) },
                onDelete = { active.deleteFromViewer(it) }
            )
        }
        active.busy?.let {
            BusyOverlay(it, active.taskProgress, active.taskDetail, active.taskCancellable) {
                active.cancelTask()
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}

private fun tabTitle(v: FileManagerViewModel): String {
    val d = v.currentDir
    val c = v.category
    return when {
        c != null -> c.label
        d == null -> "Home"
        d.absolutePath == Environment.getExternalStorageDirectory().absolutePath -> "Internal"
        else -> d.name.ifEmpty { "/" }
    }
}

@Composable
private fun TabStrip(tabs: TabsViewModel, vms: List<FileManagerViewModel>) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .statusBarsPadding()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.ids.forEachIndexed { i, id ->
            val v = vms.getOrNull(i)
            FilterChip(
                selected = id == tabs.active,
                onClick = { tabs.selectTab(id) },
                label = {
                    Text(
                        if (v != null) tabTitle(v) else "Tab",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close tab",
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { tabs.closeTab(id) }
                    )
                }
            )
        }
        IconButton(onClick = {
            tabs.addTab(vms.getOrNull(tabs.ids.indexOf(tabs.active))?.currentDir)
        }) {
            Icon(Icons.Default.Add, contentDescription = "New tab")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesContent(vm: FileManagerViewModel, tabs: TabsViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val roots = remember { storageRoots(context) }
    var dialog by remember { mutableStateOf<UiDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val selectedFiles = remember(vm.selected, vm.items) { vm.selectedFiles() }
    val selectedSize = remember(vm.selected, vm.items) {
        vm.items.filter { it.path in vm.selected }.sumOf { it.size }
    }
    val cat = vm.category
    val inFolder = vm.currentDir != null
    val atHome = !inFolder && cat == null
    val showPath = cat != null || vm.searchActive
    val settings = vm.settings

    LaunchedEffect(Unit) { vm.setRoots(roots.map { it.dir.absolutePath }) }
    BackHandler(
        enabled = vm.selected.isNotEmpty() || vm.searching || vm.currentDir != null || cat != null
    ) {
        vm.back()
    }

    val dirNow = vm.currentDir
    val title = when {
        cat != null -> cat.label
        dirNow == null -> "VersaFiles"
        else -> roots.firstOrNull { it.dir.absolutePath == dirNow.absolutePath }?.name
            ?: dirNow.name.ifEmpty { "/" }
    }

    val onItemClick: (FileItem) -> Unit = { item ->
        when {
            vm.selected.isNotEmpty() -> vm.toggleSelect(item.path)
            item.isDir -> vm.open(item.file)
            item.nameKey.endsWith(".vfenc") -> dialog = UiDialog.Decrypt(item.file)
            else -> vm.openFileFlow(item)
        }
    }

    val requestDelete: () -> Unit = {
        if (!settings.trashEnabled) dialog = UiDialog.Delete(selectedFiles, false)
        else if (settings.confirmDelete) dialog = UiDialog.Delete(selectedFiles, true)
        else vm.delete(selectedFiles, false)
    }

    Scaffold(
        topBar = {
            if (vm.selected.isNotEmpty()) {
                TopAppBar(
                    title = {
                        Column {
                            Text("${vm.selected.size} selected")
                            if (selectedSize > 0) {
                                Text(
                                    formatSize(selectedSize, settings.binaryUnits),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.selectAll() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = { vm.copySelected(false) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(onClick = { vm.copySelected(true) }) {
                            Icon(Icons.Default.ContentCut, contentDescription = "Cut")
                        }
                        IconButton(onClick = requestDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                        Box {
                            IconButton(onClick = { moreOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                val single = selectedFiles.singleOrNull()
                                DropdownMenuItem(
                                    text = { Text("Compress…") },
                                    leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                                    onClick = {
                                        moreOpen = false
                                        dialog = UiDialog.Compress(selectedFiles)
                                    }
                                )
                                if (single != null && isExtractable(single)) {
                                    DropdownMenuItem(
                                        text = { Text("Extract here") },
                                        leadingIcon = { Icon(Icons.Default.Unarchive, contentDescription = null) },
                                        onClick = {
                                            moreOpen = false
                                            vm.extract(single)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    onClick = {
                                        moreOpen = false
                                        shareFiles(context, selectedFiles)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Invert selection") },
                                    leadingIcon = { Icon(Icons.Default.FlipToBack, contentDescription = null) },
                                    onClick = {
                                        moreOpen = false
                                        vm.invertSelection()
                                    }
                                )
                                if (selectedFiles.isNotEmpty() && selectedFiles.all { it.isFile }) {
                                    DropdownMenuItem(
                                        text = { Text("Encrypt…") },
                                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                        onClick = {
                                            moreOpen = false
                                            dialog = UiDialog.Encrypt(selectedFiles)
                                        }
                                    )
                                }
                                if (single != null && single.isFile && single.name.endsWith(".vfenc")) {
                                    DropdownMenuItem(
                                        text = { Text("Decrypt…") },
                                        leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
                                        onClick = {
                                            moreOpen = false
                                            dialog = UiDialog.Decrypt(single)
                                        }
                                    )
                                }
                                if (single != null && single.isFile &&
                                    (isExtractable(single) || single.extension.equals("apk", ignoreCase = true))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Browse archive") },
                                        leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null) },
                                        onClick = {
                                            moreOpen = false
                                            vm.openArchive(single)
                                        }
                                    )
                                }
                                if (selectedFiles.size > 1) {
                                    DropdownMenuItem(
                                        text = { Text("Batch rename…") },
                                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                                        onClick = {
                                            moreOpen = false
                                            dialog = UiDialog.BatchRename(selectedFiles)
                                        }
                                    )
                                }
                                if (single != null && single.isFile) {
                                    DropdownMenuItem(
                                        text = { Text("Open with…") },
                                        leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                                        onClick = {
                                            moreOpen = false
                                            vm.openFileFlow(toItem(single), forceAsk = true)
                                        }
                                    )
                                }
                                if (single != null && single.isDirectory) {
                                    val isFav = single.absolutePath in vm.favorites
                                    DropdownMenuItem(
                                        text = { Text(if (isFav) "Remove from favorites" else "Add to favorites") },
                                        leadingIcon = {
                                            Icon(
                                                if (isFav) Icons.Default.StarBorder else Icons.Default.Star,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            moreOpen = false
                                            vm.toggleFavorite(single)
                                            vm.clearSelection()
                                        }
                                    )
                                }
                                if (single != null && showPath) {
                                    DropdownMenuItem(
                                        text = { Text("Show in folder") },
                                        leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                                        onClick = {
                                            moreOpen = false
                                            vm.showInFolder(single)
                                        }
                                    )
                                }
                                if (single != null) {
                                    DropdownMenuItem(
                                        text = { Text("Copy path") },
                                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                        onClick = {
                                            moreOpen = false
                                            clipboard.setText(AnnotatedString(single.absolutePath))
                                            vm.showMessage("Path copied")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                                        onClick = {
                                            moreOpen = false
                                            dialog = UiDialog.Rename(single)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Properties") },
                                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                        onClick = {
                                            moreOpen = false
                                            dialog = UiDialog.Info(single)
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        if (vm.searching) {
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            TextField(
                                value = vm.query,
                                onValueChange = { vm.updateQuery(it) },
                                placeholder = { Text("Search in this folder") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focus)
                            )
                        } else {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        if (vm.searching || inFolder || cat != null) {
                            IconButton(onClick = { vm.back() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        if (atHome) {
                            IconButton(onClick = { tabs.addTab(null) }) {
                                Icon(Icons.Default.Tab, contentDescription = "New tab")
                            }
                            IconButton(onClick = { vm.openTrash() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Trash")
                            }
                            IconButton(onClick = { vm.openSettings() }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                        if (inFolder && !vm.searching) {
                            IconButton(onClick = { vm.startSearch() }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                        if (inFolder || cat != null) {
                            IconButton(onClick = { vm.toggleView() }) {
                                Icon(
                                    if (vm.viewMode == ViewMode.LIST) Icons.Default.GridView
                                    else Icons.AutoMirrored.Filled.ViewList,
                                    contentDescription = "Switch view"
                                )
                            }
                        }
                        if (inFolder) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("New folder") },
                                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            dialog = UiDialog.NewFolder
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("New file") },
                                        leadingIcon = { Icon(Icons.Default.NoteAdd, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            dialog = UiDialog.NewFile
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Go to path…") },
                                        leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            dialog = UiDialog.GoTo
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("New tab here") },
                                        leadingIcon = { Icon(Icons.Default.Tab, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            tabs.addTab(vm.currentDir)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share folder over Wi-Fi") },
                                        leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            vm.openShare(vm.currentDir)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Refresh") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            vm.refresh()
                                        }
                                    )
                                    HorizontalDivider()
                                    SortBy.entries.forEach { s ->
                                        val arrow = if (vm.sortBy == s) (if (vm.ascending) "  ↑" else "  ↓") else ""
                                        DropdownMenuItem(
                                            text = { Text("Sort by ${s.label}$arrow") },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                vm.setSort(s)
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(if (vm.showHidden) "Hide hidden files" else "Show hidden files") },
                                        onClick = {
                                            menuOpen = false
                                            vm.toggleHidden()
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            val cb = vm.clipboard
            if (cb != null && vm.currentDir != null) {
                BottomAppBar {
                    Text(
                        "${cb.files.size} item(s) to ${if (cb.move) "move" else "copy"}",
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .weight(1f)
                    )
                    TextButton(onClick = { vm.cancelClipboard() }) { Text("Cancel") }
                    Button(
                        onClick = { vm.paste() },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) { Text("Paste here") }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val dir = vm.currentDir
            if (dir == null && cat == null) {
                HomeContent(vm, roots) { item ->
                    vm.openFileFlow(item, vm.recent)
                }
            } else {
                var pulling by remember { mutableStateOf(false) }
                LaunchedEffect(vm.loading) { if (!vm.loading) pulling = false }
                PullToRefreshBox(
                    isRefreshing = pulling,
                    onRefresh = {
                        pulling = true
                        vm.refresh()
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                Column(Modifier.fillMaxSize()) {
                    if (dir != null) {
                        Breadcrumbs(dir, roots) { vm.open(it) }
                        HorizontalDivider()
                    }
                    if (vm.searching) {
                        SearchFilters(vm)
                    }
                    if (vm.items.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (vm.loading) {
                                CircularProgressIndicator()
                            } else {
                                Text(
                                    when {
                                        vm.searchActive -> "No results"
                                        cat != null -> "Nothing found"
                                        else -> "This folder is empty"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (vm.viewMode == ViewMode.LIST) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(vm.items, key = { it.path }) { item ->
                                FileRow(
                                    item = item,
                                    selected = item.path in vm.selected,
                                    showPath = showPath,
                                    onClick = { onItemClick(item) },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        vm.toggleSelect(item.path)
                                    }
                                )
                            }
                        }
                    } else {
                        val cell = when (settings.thumbSize) {
                            0 -> 88.dp
                            2 -> 136.dp
                            else -> 108.dp
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(cell),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            gridItems(vm.items, key = { it.path }) { item ->
                                FileCell(
                                    item = item,
                                    selected = item.path in vm.selected,
                                    onClick = { onItemClick(item) },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        vm.toggleSelect(item.path)
                                    }
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> {}
        is UiDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (d.trash) "Move to trash" else "Delete permanently") },
            text = {
                Text(
                    if (d.trash) "Move ${d.files.size} item(s) to trash? You can restore them later."
                    else "Permanently delete ${d.files.size} item(s)? Trash is turned off, so this can't be undone."
                )
            },
            confirmButton = {
                Row {
                    if (d.trash) {
                        TextButton(onClick = {
                            vm.delete(d.files, true)
                            dialog = null
                        }) { Text("Delete forever") }
                        TextButton(onClick = {
                            vm.delete(d.files, false)
                            dialog = null
                        }) { Text("Move to trash") }
                    } else {
                        TextButton(onClick = {
                            vm.delete(d.files, true)
                            dialog = null
                        }) { Text("Delete") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )
        is UiDialog.BatchRename -> TextInputDialog(
            title = "Batch rename ${d.files.size} items",
            initial = "File",
            confirm = "Rename",
            onConfirm = {
                vm.batchRename(d.files, it)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        is UiDialog.Rename -> TextInputDialog(
            title = "Rename",
            initial = d.file.name,
            confirm = "Rename",
            onConfirm = {
                vm.rename(d.file, it)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        UiDialog.NewFolder -> TextInputDialog(
            title = "New folder",
            initial = "",
            confirm = "Create",
            onConfirm = {
                vm.newFolder(it)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        UiDialog.NewFile -> TextInputDialog(
            title = "New file",
            initial = "",
            confirm = "Create",
            onConfirm = {
                vm.newFile(it)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        UiDialog.GoTo -> TextInputDialog(
            title = "Go to path",
            initial = vm.currentDir?.absolutePath ?: "",
            confirm = "Go",
            onConfirm = {
                vm.goTo(it)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        is UiDialog.Encrypt -> PasswordDialog(
            title = "Encrypt ${d.files.size} file(s)",
            confirmPassword = true,
            extraLabel = "Move the originals to trash",
            confirm = "Encrypt",
            onConfirm = { pass, trashOriginals ->
                vm.encryptFiles(d.files, pass, trashOriginals)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        is UiDialog.Decrypt -> PasswordDialog(
            title = "Decrypt ${d.file.name}",
            confirmPassword = false,
            extraLabel = null,
            confirm = "Decrypt",
            onConfirm = { pass, _ ->
                vm.decryptFile(d.file, pass)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        is UiDialog.Compress -> CompressDialog(
            files = d.files,
            onConfirm = { name, format ->
                vm.compress(d.files, name, format)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        is UiDialog.Info -> InfoDialog(d.file, settings, onCopied = { vm.showMessage("Copied") }) { dialog = null }
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    confirmPassword: Boolean,
    extraLabel: String?,
    confirm: String,
    onConfirm: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf(false) }
    val mismatch = confirmPassword && pass2.isNotEmpty() && pass != pass2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation()
                )
                if (confirmPassword) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pass2,
                        onValueChange = { pass2 = it },
                        singleLine = true,
                        isError = mismatch,
                        label = { Text("Repeat password") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                if (extraLabel != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { extra = !extra },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = extra, onCheckedChange = { extra = it })
                        Text(extraLabel)
                    }
                }
                if (confirmPassword) {
                    Text(
                        "If you forget the password the files can't be recovered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pass.isNotEmpty() && (!confirmPassword || pass == pass2)) onConfirm(pass, extra)
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CompressDialog(
    files: List<File>,
    onConfirm: (String, ArchiveFormat) -> Unit,
    onDismiss: () -> Unit
) {
    val first = files.firstOrNull()
    val defaultName = when {
        files.size != 1 || first == null -> "Archive"
        first.isDirectory -> first.name
        else -> first.nameWithoutExtension
    }
    var name by remember { mutableStateOf(defaultName) }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress ${files.size} item(s)") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Archive name") }
                )
                Spacer(Modifier.height(8.dp))
                ArchiveFormat.entries.forEach { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { format = f },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = format == f, onClick = { format = f })
                        Text("${f.label}  (.${f.ext})")
                    }
                }
                if (format == ArchiveFormat.LOVE) {
                    Text(
                        "A single folder's contents go to the archive root, so main.lua ends up at the top.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), format) }) {
                Text("Compress")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun InfoDialog(file: File, settings: Settings, onCopied: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val size by produceState("Calculating…", file) {
        value = withContext(Dispatchers.IO) {
            formatSize(FileOps.totalSize(file), settings.binaryUnits)
        }
    }
    var md5 by remember(file) { mutableStateOf<String?>(null) }
    var sha by remember(file) { mutableStateOf<String?>(null) }
    var hashing by remember(file) { mutableStateOf(false) }
    LaunchedEffect(hashing) {
        if (hashing) {
            val r = withContext(Dispatchers.IO) {
                try {
                    Hashes.hash(file, "MD5") to Hashes.hash(file, "SHA-256")
                } catch (e: Exception) {
                    null
                }
            }
            md5 = r?.first ?: "error"
            sha = r?.second ?: "error"
            hashing = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScrollCompat()) {
                InfoLine("Path", file.absolutePath)
                InfoLine("Size", size)
                InfoLine("Modified", formatDate(file.lastModified(), settings.dateStyle))
                InfoLine("Type", if (file.isDirectory) "Folder" else mimeOf(file))
                if (file.isFile) {
                    if (md5 == null && !hashing) {
                        TextButton(onClick = { hashing = true }) { Text("Calculate checksums") }
                    } else if (hashing) {
                        Text("Calculating…", style = MaterialTheme.typography.bodySmall)
                    } else {
                        val m = md5 ?: ""
                        val s = sha ?: ""
                        InfoLine("MD5 (tap to copy)", m, onClick = {
                            clipboard.setText(AnnotatedString(m))
                            onCopied()
                        })
                        InfoLine("SHA-256 (tap to copy)", s, onClick = {
                            clipboard.setText(AnnotatedString(s))
                            onCopied()
                        })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier =
    this.then(Modifier.verticalScroll(rememberScrollState()))

@Composable
private fun InfoLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BusyOverlay(
    text: String,
    progress: Float?,
    detail: String,
    cancellable: Boolean,
    onCancel: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        contentAlignment = Alignment.Center
    ) {
        Card(Modifier.padding(32.dp)) {
            Column(
                Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                } else {
                    CircularProgressIndicator()
                }
                if (detail.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        detail,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (cancellable) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun SearchFilters(vm: FileManagerViewModel) {
    val groups = listOf(
        "Images" to setOf(FileKind.IMAGE),
        "Videos" to setOf(FileKind.VIDEO),
        "Audio" to setOf(FileKind.AUDIO),
        "Docs" to setOf(FileKind.TEXT, FileKind.PDF),
        "Archives" to setOf(FileKind.ARCHIVE),
        "APKs" to setOf(FileKind.APK),
        "Folders" to setOf(FileKind.FOLDER)
    )
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = vm.searchEverywhere,
            onClick = { vm.toggleSearchEverywhere() },
            label = { Text("Everywhere") }
        )
        groups.forEach { (label, kinds) ->
            FilterChip(
                selected = kinds.all { it in vm.searchKinds },
                onClick = { vm.toggleSearchKinds(kinds) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun Breadcrumbs(dir: File, roots: List<StorageRoot>, onNavigate: (File) -> Unit) {
    val crumbs = remember(dir, roots) {
        val root = roots
            .filter {
                dir.absolutePath == it.dir.absolutePath ||
                    dir.absolutePath.startsWith(it.dir.absolutePath + "/")
            }
            .maxByOrNull { it.dir.absolutePath.length }
        val list = ArrayList<Pair<String, File>>()
        var cur: File? = dir
        while (cur != null) {
            if (root != null && cur.absolutePath == root.dir.absolutePath) {
                list.add(0, root.name to cur)
                break
            }
            list.add(0, cur.name.ifEmpty { "/" } to cur)
            cur = cur.parentFile
        }
        list
    }
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll, reverseScrolling = true)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        crumbs.forEachIndexed { i, (name, f) ->
            if (i > 0) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                name,
                maxLines = 1,
                color = if (i == crumbs.lastIndex) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onNavigate(f) }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun CategoryGrid(onOpen: (Category) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Category.entries.chunked(3).forEach { rowItems ->
            Row(Modifier.fillMaxWidth()) {
                rowItems.forEach { c ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpen(c) }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(categoryColor(c).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(categoryIcon(c), contentDescription = null, tint = categoryColor(c))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(c.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    vm: FileManagerViewModel,
    roots: List<StorageRoot>,
    onOpenRecent: (FileItem) -> Unit
) {
    val st = vm.settings
    val quick = remember {
        listOf(
            Quick("Downloads", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Icons.Default.Download),
            Quick("Camera (DCIM)", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), Icons.Default.CameraAlt),
            Quick("Pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), Icons.Default.Image),
            Quick("Movies", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), Icons.Default.Movie),
            Quick("Music", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), Icons.Default.MusicNote),
            Quick("Documents", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), Icons.Default.Description)
        ).filter { it.dir.exists() }
    }
    val primaryPath = remember { Environment.getExternalStorageDirectory().absolutePath }
    val spaces = remember(roots) { roots.map { StorageInfo(it, it.dir.totalSpace, it.dir.freeSpace) } }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("Storage") }
        items(spaces) { info ->
            val used = if (info.total > 0) (info.total - info.free).toFloat() / info.total.toFloat() else 0f
            Card(
                onClick = { vm.open(info.root.dir) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (info.root.dir.absolutePath == primaryPath) Icons.Default.PhoneAndroid else Icons.Default.SdCard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(info.root.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${formatSize(info.free, st.binaryUnits)} free of ${formatSize(info.total, st.binaryUnits)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (st.showRecent && vm.recent.isNotEmpty()) {
            item { SectionTitle("Recent") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(vm.recent, key = { it.path }) { item ->
                        Column(
                            Modifier
                                .width(88.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenRecent(item) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            FileThumb(item, false, 88.dp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                item.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
        if (st.showCategories) {
            item { SectionTitle("Categories") }
            item { CategoryGrid(onOpen = { vm.openCategory(it) }) }
        }
        if (vm.favorites.isNotEmpty()) {
            item { SectionTitle("Favorites") }
            items(vm.favorites, key = { "fav:$it" }) { path ->
                val f = File(path)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.open(f) }
                        .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name.ifEmpty { "/" }, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            path,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { vm.toggleFavorite(f) }) {
                        Icon(Icons.Default.Star, contentDescription = "Remove favorite", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (st.showQuickAccess) {
            item { SectionTitle("Quick access") }
            items(quick) { q ->
                ToolRow(q.icon, q.name) { vm.open(q.dir) }
            }
        }
        item { SectionTitle("Tools") }
        item {
            ToolRow(
                Icons.Default.Delete,
                if (vm.trashCount > 0) "Trash (${vm.trashCount})" else "Trash"
            ) { vm.openTrash() }
        }
        item { ToolRow(Icons.Default.Android, "Apps") { vm.openApps() } }
        item { ToolRow(Icons.Default.PieChart, "Storage analyzer") { vm.openAnalyzer() } }
        item { ToolRow(Icons.Default.ContentCopy, "Duplicate finder") { vm.openDuplicates() } }
        item { ToolRow(Icons.Default.CleaningServices, "Cleaner") { vm.openCleaner() } }
        item {
            ToolRow(
                Icons.Default.Wifi,
                if (ShareManager.running) "Wi-Fi share (on)" else "Wi-Fi share"
            ) { vm.openShare(null) }
        }
        item { ToolRow(Icons.Default.Settings, "Settings") { vm.openSettings() } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem,
    selected: Boolean,
    showPath: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val st = LocalSettings.current
    val vpad = when (st.density) {
        0 -> 4.dp
        2 -> 12.dp
        else -> 8.dp
    }
    val thumb = when (st.thumbSize) {
        0 -> 36.dp
        2 -> 56.dp
        else -> 44.dp
    }
    val count by produceState<Int?>(
        if (item.isDir) Counts.peek(item) else null,
        item.path, item.modified
    ) {
        if (item.isDir && value == null) value = Counts.count(item)
    }
    val sub = remember(item, count, st.showSizes, st.showDates, st.binaryUnits, st.dateStyle) {
        val parts = ArrayList<String>(2)
        if (st.showSizes) {
            parts.add(
                if (item.isDir) (count?.let { "$it items" } ?: "Folder")
                else formatSize(item.size, st.binaryUnits)
            )
        }
        if (st.showDates) parts.add(formatDate(item.modified, st.dateStyle))
        parts.joinToString("  ·  ")
    }
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = vpad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileThumb(item, selected, thumb)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showPath) {
                Text(
                    item.file.parent ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCell(
    item: FileItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val st = LocalSettings.current
    val thumb = when (st.thumbSize) {
        0 -> 72.dp
        2 -> 112.dp
        else -> 88.dp
    }
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FileThumb(item, selected, thumb)
        Spacer(Modifier.height(4.dp))
        Text(
            item.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
