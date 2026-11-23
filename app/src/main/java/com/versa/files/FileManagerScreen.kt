package com.versa.files

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface UiDialog {
    data class Delete(val files: List<File>) : UiDialog
    data class Rename(val file: File) : UiDialog
    data object NewFolder : UiDialog
    data object NewFile : UiDialog
    data class Compress(val files: List<File>) : UiDialog
    data class Info(val file: File) : UiDialog
}

private data class Quick(val name: String, val dir: File, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(vm: FileManagerViewModel = viewModel()) {
    val context = LocalContext.current
    val roots = remember { storageRoots(context) }
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<UiDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val selectedFiles = vm.selectedFiles()

    LaunchedEffect(Unit) { vm.setRoots(roots.map { it.dir.absolutePath }) }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it.text) } }
    BackHandler(enabled = vm.selected.isNotEmpty() || vm.searching || vm.currentDir != null) {
        vm.back()
    }

    val dirNow = vm.currentDir
    val title = if (dirNow == null) "VersaFiles"
    else roots.firstOrNull { it.dir.absolutePath == dirNow.absolutePath }?.name
        ?: dirNow.name.ifEmpty { "/" }

    val onItemClick: (FileItem) -> Unit = { item ->
        when {
            vm.selected.isNotEmpty() -> vm.toggleSelect(item.path)
            item.isDir -> vm.open(item.file)
            else -> openFile(context, item.file)
        }
    }

    Scaffold(
        topBar = {
            if (vm.selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${vm.selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::selectAll) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = { vm.copySelected(false) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(onClick = { vm.copySelected(true) }) {
                            Icon(Icons.Default.ContentCut, contentDescription = "Cut")
                        }
                        IconButton(onClick = { dialog = UiDialog.Delete(selectedFiles) }) {
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
                                if (single != null) {
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
                        if (vm.searching || vm.currentDir != null) {
                            IconButton(onClick = vm::back) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        if (vm.currentDir != null) {
                            if (!vm.searching) {
                                IconButton(onClick = vm::startSearch) {
                                    Icon(Icons.Default.Search, contentDescription = "Search")
                                }
                            }
                            IconButton(onClick = vm::toggleView) {
                                Icon(
                                    if (vm.viewMode == ViewMode.LIST) Icons.Default.GridView
                                    else Icons.AutoMirrored.Filled.ViewList,
                                    contentDescription = "Switch view"
                                )
                            }
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
                    TextButton(onClick = vm::cancelClipboard) { Text("Cancel") }
                    Button(
                        onClick = vm::paste,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) { Text("Paste here") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val dir = vm.currentDir
            if (dir == null) {
                HomeContent(roots) { vm.open(it) }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Breadcrumbs(dir, roots) { vm.open(it) }
                    HorizontalDivider()
                    if (vm.items.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (vm.loading) {
                                CircularProgressIndicator()
                            } else {
                                Text(
                                    if (vm.query.isNotBlank()) "No results" else "This folder is empty",
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
                                    onClick = { onItemClick(item) },
                                    onLongClick = { vm.toggleSelect(item.path) }
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(104.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            gridItems(vm.items, key = { it.path }) { item ->
                                FileCell(
                                    item = item,
                                    selected = item.path in vm.selected,
                                    onClick = { onItemClick(item) },
                                    onLongClick = { vm.toggleSelect(item.path) }
                                )
                            }
                        }
                    }
                }
            }
            vm.busy?.let { BusyOverlay(it) }
        }
    }

    when (val d = dialog) {
        null -> {}
        is UiDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete") },
            text = { Text("Delete ${d.files.size} item(s)? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(d.files)
                    dialog = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
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
        is UiDialog.Compress -> CompressDialog(
            files = d.files,
            onConfirm = { name, format ->
                vm.compress(d.files, name, format)
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        is UiDialog.Info -> InfoDialog(d.file) { dialog = null }
    }
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
private fun InfoDialog(file: File, onDismiss: () -> Unit) {
    val size by produceState("Calculating…", file) {
        value = withContext(Dispatchers.IO) { formatSize(FileOps.totalSize(file)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                InfoLine("Path", file.absolutePath)
                InfoLine("Size", size)
                InfoLine("Modified", formatDate(file.lastModified()))
                InfoLine("Type", if (file.isDirectory) "Folder" else mimeOf(file))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BusyOverlay(text: String) {
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
        Card {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(text)
            }
        }
    }
}

@Composable
private fun Breadcrumbs(dir: File, roots: List<StorageRoot>, onNavigate: (File) -> Unit) {
    val root = roots
        .filter {
            dir.absolutePath == it.dir.absolutePath ||
                dir.absolutePath.startsWith(it.dir.absolutePath + "/")
        }
        .maxByOrNull { it.dir.absolutePath.length }
    val crumbs = ArrayList<Pair<String, File>>()
    var cur: File? = dir
    while (cur != null) {
        if (root != null && cur.absolutePath == root.dir.absolutePath) {
            crumbs.add(0, root.name to cur)
            break
        }
        crumbs.add(0, cur.name.ifEmpty { "/" } to cur)
        cur = cur.parentFile
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
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun HomeContent(roots: List<StorageRoot>, onOpen: (File) -> Unit) {
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
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("Storage") }
        items(roots) { r ->
            val total = r.dir.totalSpace
            val free = r.dir.freeSpace
            val used = if (total > 0) (total - free).toFloat() / total.toFloat() else 0f
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(r.dir) }
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (r.dir.absolutePath == primaryPath) Icons.Default.PhoneAndroid else Icons.Default.SdCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(r.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${formatSize(free)} free of ${formatSize(total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth())
            }
        }
        item { SectionTitle("Quick access") }
        items(quick) { q ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(q.dir) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(q.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(q.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun FileIcon(kind: FileKind, selected: Boolean, size: Dp = 40.dp) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = if (selected) {
        Icons.Default.CheckCircle to scheme.primary
    } else when (kind) {
        FileKind.FOLDER -> Icons.Default.Folder to scheme.primary
        FileKind.IMAGE -> Icons.Default.Image to Color(0xFF43A047)
        FileKind.VIDEO -> Icons.Default.VideoFile to Color(0xFFE53935)
        FileKind.AUDIO -> Icons.Default.AudioFile to Color(0xFF8E24AA)
        FileKind.ARCHIVE -> Icons.Default.FolderZip to Color(0xFFF9A825)
        FileKind.TEXT -> Icons.Default.Description to scheme.onSurfaceVariant
        FileKind.APK -> Icons.Default.Android to Color(0xFF3DDC84)
        FileKind.PDF -> Icons.Default.PictureAsPdf to Color(0xFFD32F2F)
        FileKind.OTHER -> Icons.Default.InsertDriveFile to scheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileIcon(item.kind, selected)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
            val sub = if (item.isDir) "${item.childCount} items" else formatSize(item.size)
            Text(
                "$sub  ·  ${formatDate(item.modified)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FileIcon(item.kind, selected, 48.dp)
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
