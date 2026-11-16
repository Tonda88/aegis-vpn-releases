package com.aegis.vpn.core

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Access to the "usage access" special permission. It is required to observe
 * which application is currently on the screen, which is the only supported
 * way to implement per-application automatic connect/disconnect on stock
 * Android (getRunningTasks() returns nothing useful since API 21).
 */
object UsageAccess {

    private const val OPSTR_GET_USAGE_STATS = "android:get_usage_stats"

    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Packages that only appear on top of the current application for a moment
     * (notification shade, permission dialogs, share sheets, the keyboard).
     * They must never be treated as "the user left the application".
     */
    private val TRANSIENT_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.providers.settings",
        "com.android.sharingdialog",
        "com.android.intentresolver",
        "com.google.android.intentresolver",
        "com.android.samsung.systemui",
        "com.android.vending"
    )

    fun isTransient(packageName: String, ownPackage: String): Boolean {
        if (packageName == ownPackage) return true
        if (packageName in TRANSIENT_PACKAGES) return true
        if (packageName.contains(".inputmethod") || packageName.contains(".ime")) return true
        if (packageName.endsWith(".latin")) return true
        return false
    }
}
