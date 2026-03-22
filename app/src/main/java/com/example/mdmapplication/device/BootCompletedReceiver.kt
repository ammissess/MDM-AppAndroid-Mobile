package com.example.mdmapplication.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val policy = DevicePolicyHelper(context)
                policy.applyMinimumKioskPolicy(
                    launcherPackage = context.packageName,
                    allowedApps = emptyList()
                )
                policy.applyLockedContainment(context.packageName)

                val launch = Intent(context, com.example.mdmapplication.ui.launcher.LauncherActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                runCatching { context.startActivity(launch) }
            }
        }
    }
}