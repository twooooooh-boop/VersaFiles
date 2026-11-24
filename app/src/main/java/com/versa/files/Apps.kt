package com.versa.files

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Immutable
import java.io.File

@Immutable
data class AppInfo(
    val pkg: String,
    val label: String,
    val version: String,
    val apkPath: String,
    val splits: List<String>,
    val size: Long,
    val system: Boolean
)

object AppsRepo {
    @Suppress("DEPRECATION")
    fun list(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val out = ArrayList<AppInfo>()
        for (pi in pm.getInstalledPackages(0)) {
            val ai = pi.applicationInfo ?: continue
            val src = ai.sourceDir ?: continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val label = try {
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pi.packageName
            }
            val splits = ai.splitSourceDirs?.toList() ?: emptyList()
            val size = File(src).length() + splits.sumOf { File(it).length() }
            out.add(AppInfo(pi.packageName, label, pi.versionName ?: "", src, splits, size, isSystem))
        }
        return out.sortedBy { it.label.lowercase() }
    }
}
