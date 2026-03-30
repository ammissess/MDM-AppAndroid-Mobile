package com.example.mdmapplication.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.mdmapplication.ui.launcher.LauncherActivity

class BootCompletedReceiver : BroadcastReceiver() {
    private val tag = "BootCompletedReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(tag, "onReceive action=${intent.action}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {

                val launch = Intent(context, LauncherActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                runCatching { context.startActivity(launch) }
                    .onSuccess { Log.i(tag, "onReceive launcher start requested") }
                    .onFailure { Log.w(tag, "onReceive launcher start failed", it) }
            }
        }
    }
}