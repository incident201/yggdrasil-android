package eu.neilalexander.yggdrasil

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

class ExcludedAppsRepository(private val packageManager: PackageManager) {
    data class LauncherApp(
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    fun loadLauncherApps(filterSystemApps: Boolean = true): List<LauncherApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherActivities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }

        val uniqueApps = LinkedHashMap<String, LauncherApp>()
        for (resolveInfo in launcherActivities) {
            val activityInfo = resolveInfo.activityInfo ?: continue
            val packageName = activityInfo.packageName?.trim().orEmpty()
            if (packageName.isEmpty() || uniqueApps.containsKey(packageName)) {
                continue
            }

            val appInfo = activityInfo.applicationInfo
            val isSystemApp =
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (filterSystemApps && isSystemApp) {
                continue
            }

            val label = resolveInfo.loadLabel(packageManager).toString().trim()
            if (label.isEmpty()) {
                continue
            }
            uniqueApps[packageName] = LauncherApp(
                label = label,
                packageName = packageName,
                icon = resolveInfo.loadIcon(packageManager)
            )
        }

        return uniqueApps.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
}
