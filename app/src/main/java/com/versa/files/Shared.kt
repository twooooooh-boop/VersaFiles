package com.versa.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.io.File

/** State shared by every tab: settings, favorites and the copy/cut clipboard. */
object SharedState {
    var settings by mutableStateOf(Settings())
    var favorites by mutableStateOf<List<String>>(emptyList())
    var clipboard by mutableStateOf<PasteBuffer?>(null)
    var defaults by mutableStateOf<Map<String, String>>(emptyMap())
    var loaded = false
    var started = false
}

class TabsViewModel : ViewModel() {
    var ids by mutableStateOf(listOf(0))
        private set
    var active by mutableStateOf(0)
        private set

    private var nextId = 1
    private val starts = HashMap<Int, File?>()

    fun addTab(startDir: File?) {
        val id = nextId++
        starts[id] = startDir
        ids = ids + id
        active = id
    }

    fun closeTab(id: Int) {
        if (ids.size <= 1) return
        val idx = ids.indexOf(id)
        ids = ids - id
        if (active == id) active = ids[(idx - 1).coerceAtLeast(0)]
    }

    fun selectTab(id: Int) {
        active = id
    }

    fun consumeStart(id: Int): File? = starts.remove(id)
}
