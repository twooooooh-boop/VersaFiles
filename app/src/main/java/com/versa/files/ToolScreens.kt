package com.versa.files

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(vm: FileManagerViewModel) {
    val st = LocalSettings.current
    var confirmEmpty by remember { mutableStateOf(false) }
    BackHandler { vm.closePage() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = {
                    IconButton(onClick = vm::closePage) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (vm.trashEntries.isNotEmpty()) {
                        IconButton(onClick = { vm.restoreAllTrash() }) {
                            Icon(Icons.Default.RestoreFromTrash, contentDescription = "Restore all")
                        }
                        IconButton(onClick = { confirmEmpty = true }) {
                            Icon(Icons.Default.DeleteForever, contentDescription = "Empty trash")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (!st.trashEnabled) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Trash is turned off, so new deletions are permanent. Items already here can still be restored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.updateSettings { it.copy(trashEnabled = true) } }) {
                        Text("Turn on")
                    }
                }
            }
            if (vm.trashEntries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Trash is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    "${vm.trashEntries.size} item(s)  ·  ${formatSize(vm.trashSize, st.binaryUnits)}" +
                        if (st.trashAutoEmptyDays > 0) "  ·  auto-empties after ${st.trashAutoEmptyDays} days" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.trashEntries, key = { it.folder.absolutePath }) { e ->
                        val item = remember(e.item) { toItem(e.item) }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FileThumb(item, false, 44.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    e.original,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Deleted ${formatDate(e.deletedAt, st.dateStyle)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { vm.restoreTrash(e) }) {
                                Icon(Icons.Default.Restore, contentDescription = "Restore")
                            }
                            IconButton(onClick = { vm.purgeTrash(e) }) {
                                Icon(Icons.Default.DeleteForever, contentDescription = "Delete forever")
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty trash") },
            text = { Text("Permanently delete everything in the trash? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    vm.emptyTrash()
                }) { Text("Empty") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } }
        )
    }
}

private fun kindLabel(kind: FileKind): String = when (kind) {
    FileKind.IMAGE -> "Images"
    FileKind.VIDEO -> "Videos"
    FileKind.AUDIO -> "Audio"
    FileKind.ARCHIVE -> "Archives"
    FileKind.TEXT -> "Text & code"
    FileKind.APK -> "APKs"
    FileKind.PDF -> "PDF"
    FileKind.OTHER -> "Other"
    FileKind.FOLDER -> "Folders"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzerScreen(vm: FileManagerViewModel) {
    val binary = LocalSettings.current.binaryUnits
    BackHandler { vm.closePage() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage analyzer") },
                navigationIcon = {
                    IconButton(onClick = vm::closePage) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::startAnalysis) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                }
            )
        }
    ) { padding ->
        val result = vm.analysis
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (vm.analyzing || result == null) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Scanning… ${vm.scannedCount} files")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                formatSize(result.total, binary),
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Text(
                                "in ${result.files} files on internal storage",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(result.byKind) { pair ->
                        val kind = pair.first
                        val size = pair.second
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Row {
                                Text(kindLabel(kind), modifier = Modifier.weight(1f))
                                Text(formatSize(size, binary), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            val fraction = if (result.total > 0) size.toFloat() / result.total.toFloat() else 0f
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    item { SectionTitle("Largest files (tap to show in folder)") }
                    items(result.largest, key = { it.path }) { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.showInFolder(f.file) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FileThumb(f, false, 44.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    f.file.parent ?: "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(formatSize(f.size, binary))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(vm: FileManagerViewModel) {
    val binary = LocalSettings.current.binaryUnits
    BackHandler { vm.closePage() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duplicate finder") },
                navigationIcon = {
                    IconButton(onClick = vm::closePage) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!vm.dupScanning && vm.dupGroups.isNotEmpty()) {
                        TextButton(onClick = vm::autoSelectDuplicates) { Text("Auto-select") }
                        IconButton(
                            enabled = vm.dupSelected.isNotEmpty(),
                            onClick = vm::deleteSelectedDuplicates
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Move selected to trash")
                        }
                    }
                    IconButton(onClick = vm::startDuplicateScan) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (vm.dupScanning) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Scanning… ${vm.dupProgress} files")
                    Text(
                        "Looks for identical files of 64 KB or more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (vm.dupGroups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No duplicates found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    vm.dupGroups.forEach { g ->
                        item(key = "h:${g.key}") {
                            Text(
                                "${g.files.size} copies  ·  ${formatSize(g.size, binary)} each",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                            )
                        }
                        items(g.files, key = { it.absolutePath }) { f ->
                            val item = remember(f) { toItem(f) }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.toggleDup(f.absolutePath) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = f.absolutePath in vm.dupSelected,
                                    onCheckedChange = { vm.toggleDup(f.absolutePath) }
                                )
                                FileThumb(item, false, 44.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        f.parent ?: "",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private object AppIcons {
    val cache = LruCache<String, Bitmap>(300)
}

@Composable
private fun AppIcon(pkg: String, size: Dp = 44.dp) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(AppIcons.cache.get(pkg), pkg) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                try {
                    val d = context.packageManager.getApplicationIcon(pkg)
                    val b = d.toBitmap(96, 96)
                    AppIcons.cache.put(pkg, b)
                    b
                } catch (e: Throwable) {
                    null
                }
            }
        }
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            val image = remember(b) { b.asImageBitmap() }
            Image(bitmap = image, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AppActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vm: FileManagerViewModel) {
    val context = LocalContext.current
    val binary = LocalSettings.current.binaryUnits
    var chosen by remember { mutableStateOf<AppInfo?>(null) }
    BackHandler { vm.closePage() }

    val filtered = remember(vm.apps, vm.appQuery, vm.showSystemApps) {
        val q = vm.appQuery.trim().lowercase()
        vm.apps.filter {
            (vm.showSystemApps || !it.system) &&
                (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps (${filtered.size})") },
                navigationIcon = {
                    IconButton(onClick = vm::closePage) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.toggleSystemApps() }) {
                        Icon(
                            if (vm.showSystemApps) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Show system apps"
                        )
                    }
                    IconButton(onClick = { vm.loadApps() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = vm.appQuery,
                onValueChange = { vm.updateAppQuery(it) },
                singleLine = true,
                placeholder = { Text("Search apps") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (vm.appsLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.pkg }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { chosen = app }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(app.pkg)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    app.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    "${app.pkg}  ·  ${formatSize(app.size, binary)}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    chosen?.let { app ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(
                        "Version ${app.version.ifEmpty { "?" }}" +
                            if (app.splits.isNotEmpty()) "  ·  ${app.splits.size + 1} APK files" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AppActionRow(Icons.Default.Android, "Extract APK to Downloads") {
                        chosen = null
                        vm.backupApp(app)
                    }
                    AppActionRow(Icons.Default.Share, "Share APK") {
                        chosen = null
                        shareFiles(context, listOf(File(app.apkPath)))
                    }
                    AppActionRow(Icons.Default.OpenInNew, "Open app") {
                        chosen = null
                        val launch = context.packageManager.getLaunchIntentForPackage(app.pkg)
                        if (launch != null) {
                            try {
                                context.startActivity(launch)
                            } catch (e: Exception) {
                                vm.showMessage("Can't open this app")
                            }
                        } else {
                            vm.showMessage("This app has no launcher")
                        }
                    }
                    AppActionRow(Icons.Default.Info, "App info") {
                        chosen = null
                        try {
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${app.pkg}")
                                )
                            )
                        } catch (e: Exception) {
                            vm.showMessage("Can't open app info")
                        }
                    }
                    if (!app.system) {
                        AppActionRow(Icons.Default.Delete, "Uninstall") {
                            chosen = null
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))
                                )
                            } catch (e: Exception) {
                                vm.showMessage("Can't uninstall this app")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chosen = null }) { Text("Close") } }
        )
    }
}
