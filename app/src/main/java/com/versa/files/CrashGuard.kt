package com.versa.files

import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Last line of defence: if anything ever throws unexpectedly the app restarts itself
 * and offers an error report instead of just disappearing.
 */
object CrashGuard {
    private const val FILE = "last_crash.txt"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            var restarted = false
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE).writeText("Thread: ${thread.name}\n$sw")
                val sp = app.getSharedPreferences("versa_crash", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                if (now - sp.getLong("last", 0L) > 10_000L) {
                    sp.edit().putLong("last", now).commit()
                    val intent = Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    app.startActivity(intent)
                    restarted = true
                }
            } catch (e: Throwable) {
                // fall through to the default handler
            }
            if (restarted) {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } else {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    fun consumeReport(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        val text = try {
            f.readText()
        } catch (e: Exception) {
            null
        }
        f.delete()
        return text
    }
}
