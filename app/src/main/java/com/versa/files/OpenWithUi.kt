package com.versa.files

import android.content.ComponentName
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object TargetIcons {
    val cache = LruCache<String, Bitmap>(200)
}

private fun builtinIcon(id: String): ImageVector = when (id.removePrefix("b:")) {
    "image" -> Icons.Default.Image
    "media" -> Icons.Default.Movie
    "pdf" -> Icons.Default.PictureAsPdf
    "text" -> Icons.Default.Edit
    "archive" -> Icons.Default.FolderZip
    "hex" -> Icons.Default.Memory
    "web" -> Icons.Default.Language
    "sqlite" -> Icons.Default.Storage
    else -> Icons.Default.Description
}

@Composable
private fun TargetIcon(t: OpenTarget) {
    val context = LocalContext.current
    if (t.builtin) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                builtinIcon(t.id),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    } else {
        val bmp by produceState<Bitmap?>(TargetIcons.cache.get(t.id), t.id) {
            if (value == null) {
                value = withContext(Dispatchers.IO) {
                    try {
                        val d = context.packageManager.getActivityIcon(ComponentName(t.pkg, t.cls))
                        val b = d.toBitmap(96, 96)
                        TargetIcons.cache.put(t.id, b)
                        b
                    } catch (e: Throwable) {
                        null
                    }
                }
            }
        }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) {
                val image = remember(b) { b.asImageBitmap() }
                Image(bitmap = image, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The "Open with" list with JUST ONCE / ALWAYS, styled after the Android system chooser. */
@Composable
fun OpenWithDialog(
    request: OpenRequest,
    onDismiss: () -> Unit,
    onChoose: (OpenTarget, Boolean) -> Unit
) {
    var selectedId by remember(request) { mutableStateOf<String?>(null) }
    val selected = request.targets.firstOrNull { it.id == selectedId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open with") },
        text = {
            Column {
                Text(
                    request.item.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(request.targets, key = { it.id }) { t ->
                        val isSel = t.id == selectedId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSel) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                                )
                                .clickable { selectedId = t.id }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TargetIcon(t)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                t.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = selected != null,
                onClick = { if (selected != null) onChoose(selected, false) }
            ) { Text("JUST ONCE") }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { if (selected != null) onChoose(selected, true) }
            ) { Text("ALWAYS") }
        }
    )
}
