package com.example.mdmapplication.device

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.example.mdmapplication.ui.launcher.LauncherActivity

class BootCompletedReceiver : BroadcastReceiver() {
    private val tag = "BootCompletedReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(tag, "boot receiver received action=$action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> handleBootSignal(context, action)

            ACTION_RUNTIME_BOOT_RECOVERY -> handleRuntimeRecovery(context, intent)
        }
    }

    private fun handleBootSignal(context: Context, action: String?) {
        val wakeReason = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> "broadcast:BOOT_COMPLETED"
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> "broadcast:LOCKED_BOOT_COMPLETED"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "broadcast:PACKAGE_REPLACED"
            else -> "broadcast:UNKNOWN"
        }

        val policy = DevicePolicyHelper(context)
        if (policy.isDeviceOwner()) {
            policy.ensureLauncherHomeApplied(reason = wakeReason)
        } else {
            Log.i(tag, "launcher home apply skip reason=not_device_owner source=$wakeReason")
            return
        }

        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.i(tag, "runtime wake skip reason=locked_boot_wait_for_boot_completed source=$wakeReason")
            return
        }

        val delaysMs = if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            longArrayOf(500L, 5_000L, 15_000L)
        } else {
            longArrayOf(1_500L, 5_000L, 15_000L)
        }
        delaysMs.forEachIndexed { index, delayMs ->
            scheduleRuntimeRecovery(context, wakeReason, attempt = index + 1, totalAttempts = delaysMs.size, delayMs)
        }
    }

    private fun handleRuntimeRecovery(context: Context, intent: Intent) {
        val reason = intent.getStringExtra(EXTRA_RECOVERY_REASON) ?: "broadcast:recovery"
        val attempt = intent.getIntExtra(EXTRA_RECOVERY_ATTEMPT, 1)
        val totalAttempts = intent.getIntExtra(EXTRA_RECOVERY_TOTAL, 1)
        val stageReason = "$reason:stage:$attempt"

        val policy = DevicePolicyHelper(context)
        if (!policy.isDeviceOwner()) {
            Log.i(tag, "runtime wake skip reason=not_device_owner source=$stageReason")
            return
        }

        policy.ensureLauncherHomeApplied(reason = stageReason)
        val mdmVisible = isMdmTaskVisible(context)
        Log.i(
            tag,
            "runtime wake check attempt=$attempt/$totalAttempts reason=$reason mdmTaskVisible=$mdmVisible defaultLauncher=${policy.isDefaultLauncher()}"
        )
        if (mdmVisible) {
            Log.i(tag, "runtime wake skip reason=already_visible source=$stageReason")
            return
        }

        startRuntimeWakeActivity(context, stageReason, attempt)
    }

    private fun scheduleRuntimeRecovery(
        context: Context,
        reason: String,
        attempt: Int,
        totalAttempts: Int,
        delayMs: Long
    ) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        val recoveryIntent = Intent(context, BootCompletedReceiver::class.java).apply {
            action = ACTION_RUNTIME_BOOT_RECOVERY
            putExtra(EXTRA_RECOVERY_REASON, reason)
            putExtra(EXTRA_RECOVERY_ATTEMPT, attempt)
            putExtra(EXTRA_RECOVERY_TOTAL, totalAttempts)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            RECOVERY_REQUEST_CODE_BASE + attempt,
            recoveryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        runCatching {
            alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
        }.onSuccess {
            Log.i(tag, "runtime wake scheduled reason=$reason attempt=$attempt/$totalAttempts delayMs=$delayMs")
        }.onFailure { err ->
            Log.w(tag, "runtime wake schedule failed reason=$reason attempt=$attempt", err)
        }
    }

    private fun startRuntimeWakeActivity(context: Context, reason: String, attempt: Int) {
        val launch = LauncherActivity.createRuntimeWakeIntent(context, reason, attempt)
        runCatching { context.startActivity(launch) }
            .onSuccess { Log.i(tag, "runtime wake start requested reason=$reason attempt=$attempt") }
            .onFailure { startError ->
                Log.w(tag, "runtime wake start failed reason=$reason attempt=$attempt", startError)
                val pending = PendingIntent.getActivity(
                    context,
                    ACTIVITY_REQUEST_CODE_BASE + attempt,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                runCatching { pending.send() }
                    .onSuccess { Log.i(tag, "runtime wake pendingIntent sent reason=$reason attempt=$attempt") }
                    .onFailure { sendError ->
                        Log.w(tag, "runtime wake pendingIntent failed reason=$reason attempt=$attempt", sendError)
                    }
            }
    }

    private fun isMdmTaskVisible(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return runCatching {
            activityManager.appTasks.any { task ->
                val info = task.taskInfo
                info.topActivity?.packageName == context.packageName && info.isVisible
            }
        }.getOrElse { err ->
            Log.w(tag, "runtime wake visibility check failed", err)
            false
        }
    }

    companion object {
        private const val ACTION_RUNTIME_BOOT_RECOVERY =
            "com.example.mdmapplication.action.RUNTIME_BOOT_RECOVERY"
        private const val EXTRA_RECOVERY_REASON = "extra_recovery_reason"
        private const val EXTRA_RECOVERY_ATTEMPT = "extra_recovery_attempt"
        private const val EXTRA_RECOVERY_TOTAL = "extra_recovery_total"
        private const val RECOVERY_REQUEST_CODE_BASE = 0x4D440000
        private const val ACTIVITY_REQUEST_CODE_BASE = 0x4D450000
    }
}
