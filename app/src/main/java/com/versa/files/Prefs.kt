package com.versa.files

import android.content.Context
import android.os.Build

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("versa_prefs", Context.MODE_PRIVATE)

    var sortBy: SortBy
        get() = try {
            SortBy.valueOf(sp.getString("sort", "NAME") ?: "NAME")
        } catch (e: Exception) {
            SortBy.NAME
        }
        set(value) {
            sp.edit().putString("sort", value.name).apply()
        }

    var ascending: Boolean
        get() = sp.getBoolean("asc", true)
        set(value) {
            sp.edit().putBoolean("asc", value).apply()
        }

    var showHidden: Boolean
        get() = sp.getBoolean("hidden", false)
        set(value) {
            sp.edit().putBoolean("hidden", value).apply()
        }

    var gridView: Boolean
        get() = sp.getBoolean("grid", false)
        set(value) {
            sp.edit().putBoolean("grid", value).apply()
        }

    var favorites: List<String>
        get() = (sp.getString("favs", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(value) {
            sp.edit().putString("favs", value.joinToString("\n")).apply()
        }

    /** "Always open with" choices: file extension -> target id. */
    var openDefaults: Map<String, String>
        get() = (sp.getString("open_defaults", "") ?: "").split("\n").mapNotNull { line ->
            val i = line.indexOf('=')
            if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
        }.toMap()
        set(value) {
            sp.edit().putString(
                "open_defaults",
                value.entries.joinToString("\n") { it.key + "=" + it.value }
            ).apply()
        }

    private fun themeModeOf(name: String?): ThemeMode = try {
        if (name == null) ThemeMode.SYSTEM else ThemeMode.valueOf(name)
    } catch (e: Exception) {
        ThemeMode.SYSTEM
    }

    private fun startOf(name: String?): StartLocation = try {
        if (name == null) StartLocation.HOME else StartLocation.valueOf(name)
    } catch (e: Exception) {
        StartLocation.HOME
    }

    private fun dateStyleOf(name: String?): DateStyle = try {
        if (name == null) DateStyle.DETAILED else DateStyle.valueOf(name)
    } catch (e: Exception) {
        DateStyle.DETAILED
    }

    fun defaultPalette(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) -1 else 0

    fun loadSettings(): Settings = Settings(
        themeMode = themeModeOf(sp.getString("s_theme", null)),
        palette = sp.getInt("s_palette", defaultPalette()),
        customHue = sp.getFloat("s_hue", 210f),
        amoled = sp.getBoolean("s_amoled", false),
        corners = sp.getInt("s_corners", 1),
        density = sp.getInt("s_density", 1),
        thumbSize = sp.getInt("s_thumbsize", 1),
        showThumbs = sp.getBoolean("s_thumbs", true),
        showDates = sp.getBoolean("s_dates", true),
        showSizes = sp.getBoolean("s_sizes", true),
        foldersFirst = sp.getBoolean("s_foldersfirst", true),
        dateStyle = dateStyleOf(sp.getString("s_datestyle", null)),
        binaryUnits = sp.getBoolean("s_binary", true),
        confirmDelete = sp.getBoolean("s_confirm", true),
        startLocation = startOf(sp.getString("s_start", null)),
        showCategories = sp.getBoolean("s_cats", true),
        showRecent = sp.getBoolean("s_recent", true),
        showQuickAccess = sp.getBoolean("s_quick", true),
        editorFontSize = sp.getInt("s_editorfont", 14),
        trashEnabled = sp.getBoolean("s_trash", true),
        trashAutoEmptyDays = sp.getInt("s_trashdays", 30),
        conflictMode = sp.getInt("s_conflict", 0),
        askOpenWith = sp.getBoolean("s_askopen", true),
        editorMaxKb = sp.getInt("s_editormax", 200),
        thumbMask = sp.getInt("s_thumbmask", 31)
    )

    fun saveSettings(s: Settings) {
        sp.edit()
            .putString("s_theme", s.themeMode.name)
            .putInt("s_palette", s.palette)
            .putFloat("s_hue", s.customHue)
            .putBoolean("s_amoled", s.amoled)
            .putInt("s_corners", s.corners)
            .putInt("s_density", s.density)
            .putInt("s_thumbsize", s.thumbSize)
            .putBoolean("s_thumbs", s.showThumbs)
            .putBoolean("s_dates", s.showDates)
            .putBoolean("s_sizes", s.showSizes)
            .putBoolean("s_foldersfirst", s.foldersFirst)
            .putString("s_datestyle", s.dateStyle.name)
            .putBoolean("s_binary", s.binaryUnits)
            .putBoolean("s_confirm", s.confirmDelete)
            .putString("s_start", s.startLocation.name)
            .putBoolean("s_cats", s.showCategories)
            .putBoolean("s_recent", s.showRecent)
            .putBoolean("s_quick", s.showQuickAccess)
            .putInt("s_editorfont", s.editorFontSize)
            .putBoolean("s_trash", s.trashEnabled)
            .putInt("s_trashdays", s.trashAutoEmptyDays)
            .putInt("s_conflict", s.conflictMode)
            .putBoolean("s_askopen", s.askOpenWith)
            .putInt("s_editormax", s.editorMaxKb)
            .putInt("s_thumbmask", s.thumbMask)
            .apply()
    }
}
