package com.versa.files

import android.content.Context

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
}
