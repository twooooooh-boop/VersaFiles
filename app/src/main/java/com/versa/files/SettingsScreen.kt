package com.versa.files

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
private fun LabelText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selected,
                onClick = { onSelect(i) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ColorSwatch(
    selected: Boolean,
    onClick: () -> Unit,
    background: Modifier,
    icon: ImageVector? = null
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(background)
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: FileManagerViewModel) {
    BackHandler { vm.closePage() }
    val s = vm.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = vm::closePage) {
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
        ) {
            SectionTitle("Appearance")
            LabelText("Theme")
            ChipRow(ThemeMode.entries.map { it.label }, s.themeMode.ordinal) { i ->
                vm.updateSettings { it.copy(themeMode = ThemeMode.entries[i]) }
            }
            SwitchRow("AMOLED black", "Pure black background in dark theme", s.amoled) { v ->
                vm.updateSettings { it.copy(amoled = v) }
            }
            LabelText("Color")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ColorSwatch(
                        selected = s.palette == -1,
                        onClick = { vm.updateSettings { it.copy(palette = -1) } },
                        background = Modifier.background(MaterialTheme.colorScheme.primary),
                        icon = Icons.Default.AutoAwesome
                    )
                }
                Palettes.forEachIndexed { i, p ->
                    ColorSwatch(
                        selected = s.palette == i,
                        onClick = { vm.updateSettings { it.copy(palette = i) } },
                        background = Modifier.background(p.seed)
                    )
                }
                ColorSwatch(
                    selected = s.palette == -2,
                    onClick = { vm.updateSettings { it.copy(palette = -2) } },
                    background = Modifier.background(
                        Brush.sweepGradient(
                            listOf(
                                Color.Red, Color.Yellow, Color.Green,
                                Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                            )
                        )
                    ),
                    icon = Icons.Default.Palette
                )
            }
            Text(
                when {
                    s.palette == -1 -> "Material You (follows your wallpaper)"
                    s.palette == -2 -> "Custom color"
                    s.palette in Palettes.indices -> Palettes[s.palette].name
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            if (s.palette == -2) {
                LabelText("Custom hue")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                (0..6).map { hslColor(it * 60f, 0.8f, 0.5f) }
                            )
                        )
                )
                Slider(
                    value = s.customHue,
                    onValueChange = { h -> vm.updateSettings { it.copy(customHue = h, palette = -2) } },
                    valueRange = 0f..360f,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            LabelText("Corner style")
            ChipRow(listOf("Sharp", "Rounded", "Extra round"), s.corners) { i ->
                vm.updateSettings { it.copy(corners = i) }
            }

            SectionTitle("File list")
            LabelText("Default view")
            ChipRow(listOf("List", "Grid"), vm.viewMode.ordinal) { i ->
                vm.changeViewMode(ViewMode.entries[i])
            }
            LabelText("Sort by")
            ChipRow(SortBy.entries.map { it.label.replaceFirstChar { c -> c.uppercase() } }, vm.sortBy.ordinal) { i ->
                vm.setSortOrder(SortBy.entries[i], vm.ascending)
            }
            SwitchRow("Ascending order", null, vm.ascending) { v ->
                vm.setSortOrder(vm.sortBy, v)
            }
            SwitchRow("Folders first", null, s.foldersFirst) { v ->
                vm.updateSettings(refreshList = true) { it.copy(foldersFirst = v) }
            }
            SwitchRow("Show hidden files", "Files and folders starting with a dot", vm.showHidden) {
                vm.toggleHidden()
            }
            LabelText("Row density")
            ChipRow(listOf("Compact", "Comfortable", "Spacious"), s.density) { i ->
                vm.updateSettings { it.copy(density = i) }
            }
            LabelText("Thumbnail size")
            ChipRow(listOf("Small", "Medium", "Large"), s.thumbSize) { i ->
                vm.updateSettings { it.copy(thumbSize = i) }
            }
            SwitchRow("Show thumbnails", "Turn off for the fastest scrolling", s.showThumbs) { v ->
                vm.updateSettings { it.copy(showThumbs = v) }
            }
            SwitchRow("Show file sizes", null, s.showSizes) { v ->
                vm.updateSettings { it.copy(showSizes = v) }
            }
            SwitchRow("Show dates", null, s.showDates) { v ->
                vm.updateSettings { it.copy(showDates = v) }
            }
            LabelText("Date format")
            ChipRow(DateStyle.entries.map { it.label }, s.dateStyle.ordinal) { i ->
                vm.updateSettings { it.copy(dateStyle = DateStyle.entries[i]) }
            }
            LabelText("Size units")
            ChipRow(listOf("Binary (1024)", "Decimal (1000)"), if (s.binaryUnits) 0 else 1) { i ->
                vm.updateSettings { it.copy(binaryUnits = i == 0) }
            }

            SectionTitle("Home screen")
            LabelText("Start in")
            ChipRow(StartLocation.entries.map { it.label }, s.startLocation.ordinal) { i ->
                vm.updateSettings { it.copy(startLocation = StartLocation.entries[i]) }
            }
            SwitchRow("Recent files", null, s.showRecent) { v ->
                vm.updateSettings { it.copy(showRecent = v) }
            }
            SwitchRow("Categories", null, s.showCategories) { v ->
                vm.updateSettings { it.copy(showCategories = v) }
            }
            SwitchRow("Quick access", null, s.showQuickAccess) { v ->
                vm.updateSettings { it.copy(showQuickAccess = v) }
            }

            SectionTitle("Behavior")
            SwitchRow(
                "Ask before deleting",
                "When off, deleted items go straight to the recycle bin",
                s.confirmDelete
            ) { v -> vm.updateSettings { it.copy(confirmDelete = v) } }
            LabelText("Text editor font size: ${s.editorFontSize} sp")
            Slider(
                value = s.editorFontSize.toFloat(),
                onValueChange = { v -> vm.updateSettings { it.copy(editorFontSize = v.roundToInt()) } },
                valueRange = 11f..22f,
                steps = 10,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SectionTitle("Maintenance")
            OutlinedButton(
                onClick = vm::clearThumbCache,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) { Text("Clear thumbnail cache") }
            OutlinedButton(
                onClick = vm::resetSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) { Text("Reset all settings") }

            SectionTitle("About")
            Text(
                "VersaFiles 1.2",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 32.dp)
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
