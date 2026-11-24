package com.versa.files

import java.util.concurrent.CancellationException

/** Shared between a background file task and the UI so progress and cancel work. */
class TaskProgress {
    @Volatile var total: Long = 0L
    @Volatile var done: Long = 0L
    @Volatile var current: String = ""
    @Volatile var cancelled: Boolean = false

    fun add(n: Long) {
        done += n
    }

    fun check() {
        if (cancelled) throw CancellationException("Cancelled")
    }
}
