package com.versa.files

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class StartLocation(val label: String) {
    HOME("Home"), INTERNAL("Internal storage"), DOWNLOADS("Downloads")
}

enum class DateStyle(val label: String) {
    DETAILED("Detailed"), ISO("ISO"), RELATIVE("Relative")
}

@Immutable
data class Palette(val name: String, val seed: Color)

val Palettes = listOf(
    Palette("Ocean", Color(0xFF1E88E5)),
    Palette("Indigo", Color(0xFF3F51B5)),
    Palette("Violet", Color(0xFF7E57C2)),
    Palette("Rose", Color(0xFFE91E63)),
    Palette("Crimson", Color(0xFFD32F2F)),
    Palette("Sunset", Color(0xFFFF7043)),
    Palette("Amber", Color(0xFFFFB300)),
    Palette("Lime", Color(0xFF9E9D24)),
    Palette("Emerald", Color(0xFF2E7D32)),
    Palette("Teal", Color(0xFF00897B)),
    Palette("Cyan", Color(0xFF00ACC1)),
    Palette("Slate", Color(0xFF607D8B))
)

/** palette: -1 = Material You (dynamic), -2 = custom hue, 0.. = preset index */
@Immutable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val palette: Int = 0,
    val customHue: Float = 210f,
    val amoled: Boolean = false,
    val corners: Int = 1,
    val density: Int = 1,
    val thumbSize: Int = 1,
    val showThumbs: Boolean = true,
    val showDates: Boolean = true,
    val showSizes: Boolean = true,
    val foldersFirst: Boolean = true,
    val dateStyle: DateStyle = DateStyle.DETAILED,
    val binaryUnits: Boolean = true,
    val confirmDelete: Boolean = true,
    val startLocation: StartLocation = StartLocation.HOME,
    val showCategories: Boolean = true,
    val showRecent: Boolean = true,
    val showQuickAccess: Boolean = true,
    val editorFontSize: Int = 14,
    val trashEnabled: Boolean = true,
    val trashAutoEmptyDays: Int = 30,
    val conflictMode: Int = 0,
    val askOpenWith: Boolean = true
)
