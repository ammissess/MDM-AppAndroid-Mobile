package com.example.mdmapplication.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.mdmapplication.ui.launcher.LauncherActivity

class BootCompletedReceiver : BroadcastReceiver() {
    private val tag = "BootCompletedReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(tag, "wake-up receiver action=$action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val wakeReason = when (action) {
                    Intent.ACTION_BOOT_COMPLETED -> "broadcast:BOOT_COMPLETED"
                    Intent.ACTION_LOCKED_BOOT_COMPLETED -> "broadcast:LOCKED_BOOT_COMPLETED"
                    Intent.ACTION_MY_PACKAGE_REPLACED -> "broadcast:PACKAGE_REPLACED"
                    else -> "broadcast:UNKNOWN"
                }

                val launch = LauncherActivity.createRuntimeWakeIntent(context, wakeReason)
                runCatching { context.startActivity(launch) }
                    .onSuccess { Log.i(tag, "wake-up launcher start requested reason=$wakeReason") }
                    .onFailure { Log.w(tag, "wake-up launcher start failed reason=$wakeReason", it) }
            }
        }
    }
}
