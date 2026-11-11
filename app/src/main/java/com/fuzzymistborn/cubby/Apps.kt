package com.fuzzymistborn.cubby

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/** [icon] is rendered once here so tiles don't rasterize it again on every composition. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
)

private const val ICON_PX = 192

/**
 * Rendered icons by package, tagged with the install's last update time. Package broadcasts are
 * frequent (component changes, store housekeeping) and re-rendering every icon on each one is
 * wasted work; an update changes the time and so invalidates the entry.
 */
private val iconCache = ConcurrentHashMap<String, Pair<Long, ImageBitmap>>()

/** Every launchable app except this launcher itself, sorted by name. Does blocking work: call off the main thread. */
fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(query, 0)
        .asSequence()
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map {
            val pkg = it.activityInfo.packageName
            val stamp = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(-1L)
            val cached = iconCache[pkg]?.takeIf { c -> stamp >= 0 && c.first == stamp }?.second
            val icon = cached ?: it.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
            if (stamp >= 0) iconCache[pkg] = stamp to icon
            AppEntry(pkg, it.loadLabel(pm).toString(), icon)
        }
        .sortedBy { it.label.lowercase() }
        .toList()
        .also { apps -> iconCache.keys.retainAll(apps.map { it.packageName }.toSet()) }
}

fun launchApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    try {
        if (intent == null) throw ActivityNotFoundException(packageName)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Disabled, suspended or removed since the list was loaded.
        Toast.makeText(context, "That app isn't available right now", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, "That app isn't available right now", Toast.LENGTH_SHORT).show()
    }
}
