package com.versa.files

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArchiveScreen(vm: FileManagerViewModel) {
    val st = LocalSettings.current
    val file = vm.archiveFile
    BackHandler {
        if (vm.archiveSelected.isNotEmpty()) vm.clearArchiveSelection()
        else if (!vm.archiveUp()) vm.closeArchive()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file?.name ?: "Archive", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "/" + vm.archivePrefix,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (!vm.archiveUp()) vm.closeArchive() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (vm.archiveSelected.isNotEmpty()) {
                        TextButton(onClick = { vm.extractArchive(true) }) {
                            Text("Extract ${vm.archiveSelected.size}")
                        }
                    } else {
                        TextButton(onClick = { vm.extractArchive(false) }) { Text("Extract all") }
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
            if (vm.archiveLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (vm.archiveItems.isEmpty()) {
                Text(
                    "This folder is empty",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.archiveItems, key = { it.path }) { e ->
                        val isSel = e.path in vm.archiveSelected
                        val bg = if (isSel) MaterialTheme.colorScheme.secondaryContainer
                        else androidx.compose.ui.graphics.Color.Transparent
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .combinedClickable(
                                    onClick = {
                                        if (vm.archiveSelected.isNotEmpty()) vm.toggleArchiveSelect(e.path)
                                        else if (e.isDir) vm.archiveOpenDir(e.path + "/")
                                        else vm.openArchiveEntry(e)
                                    },
                                    onLongClick = { vm.toggleArchiveSelect(e.path) }
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FileIcon(
                                if (e.isDir) FileKind.FOLDER
                                else kindOfExt(e.name.substringAfterLast('.', "").lowercase()),
                                36.dp
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    e.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (!e.isDir) {
                                    Text(
                                        formatSize(e.size, st.binaryUnits),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (isSel) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanerScreen(vm: FileManagerViewModel) {
    val st = LocalSettings.current
    BackHandler { vm.closePage() }
    val selectedSize = remember(vm.cleanSelected, vm.cleanGroups) {
        vm.cleanGroups.sumOf { g ->
            g.files.filter { it.file.absolutePath in vm.cleanSelected }.sumOf { it.size }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cleaner") },
                navigationIcon = {
                    IconButton(onClick = { vm.closePage() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.startCleanScan() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                }
            )
        },
        bottomBar = {
            if (vm.cleanSelected.isNotEmpty()) {
                BottomAppBar {
                    Text(
                        "${vm.cleanSelected.size} selected  ·  ${formatSize(selectedSize, st.binaryUnits)}",
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .weight(1f)
                    )
                    Button(
                        onClick = { vm.cleanSelectedNow() },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) { Text("Clean") }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (vm.cleanScanning) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Scanning… ${vm.cleanProgress} files")
                }
            } else if (vm.cleanGroups.isEmpty()) {
                Text(
                    "Nothing to clean. Your storage looks tidy.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    vm.cleanGroups.forEach { g ->
                        item(key = "g:${g.title}") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        g.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "${g.files.size} item(s)  ·  ${formatSize(g.totalSize, st.binaryUnits)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { vm.toggleCleanGroup(g) }) { Text("Select all") }
                            }
                        }
                        items(g.files, key = { "${g.title}:${it.file.absolutePath}" }) { jf ->
                            val path = jf.file.absolutePath
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.toggleClean(path) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = path in vm.cleanSelected,
                                    onCheckedChange = { vm.toggleClean(path) }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        jf.file.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        jf.file.parent ?: "",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (jf.size > 0) {
                                    Text(
                                        formatSize(jf.size, st.binaryUnits),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(end = 8.dp)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(vm: FileManagerViewModel) {
    BackHandler { vm.closePage() }
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    var port by remember { mutableStateOf("8080") }
    var pass by remember { mutableStateOf((100000..999999).random().toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val running = ShareManager.running

    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi share") },
                navigationIcon = {
                    IconButton(onClick = { vm.closePage() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Share a folder over Wi-Fi", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Anyone on the same Wi-Fi who knows the address and password can open the folder in a browser. " +
                    "Keep VersaFiles open while sharing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Folder: ${(ShareManager.folder ?: vm.shareTarget)?.absolutePath ?: ""}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            if (!running) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    singleLine = true,
                    label = { Text("Port (1024 to 65535)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it.take(32) },
                    singleLine = true,
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Allow uploads", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Lets the browser add files to the folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = ShareManager.allowUploads,
                    onCheckedChange = { ShareManager.allowUploads = it }
                )
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    if (running) {
                        ShareManager.stop()
                        vm.showMessage("Sharing stopped")
                    } else {
                        val portNum = port.toIntOrNull() ?: 0
                        if (portNum !in 1024..65535) {
                            error = "Choose a port between 1024 and 65535"
                        } else {
                            val root = vm.shareTarget ?: Environment.getExternalStorageDirectory()
                            error = ShareManager.start(root, portNum, pass, ShareManager.allowUploads)
                            if (error == null) vm.showMessage("Sharing started")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (running) "Stop sharing" else "Start sharing") }

            if (running) {
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Open this address on your computer", style = MaterialTheme.typography.labelMedium)
                        Text(ShareManager.url, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Password (user name can be anything)", style = MaterialTheme.typography.labelMedium)
                        Text(ShareManager.password, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(ShareManager.url))
                                vm.showMessage("Address copied")
                            }) { Text("Copy address") }
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(ShareManager.password))
                                vm.showMessage("Password copied")
                            }) { Text("Copy password") }
                        }
                    }
                }
            }
        }
    }
}
