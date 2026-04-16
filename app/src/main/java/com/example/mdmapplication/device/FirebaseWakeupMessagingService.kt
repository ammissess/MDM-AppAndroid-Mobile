package com.example.mdmapplication.device

import android.util.Log
import com.example.mdmapplication.ui.launcher.LauncherActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FirebaseWakeupMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tag = "FirebaseWakeupMsgSvc"

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val updatedAt = System.currentTimeMillis()
        DeviceRuntimeIdentity.stagePendingFcmToken(applicationContext, token, updatedAt)
        Log.i(tag, "onNewToken received updatedAtEpochMillis=$updatedAt")

        serviceScope.launch {
            runCatching { syncPendingFcmToken(applicationContext) }
                .onSuccess { synced ->
                    Log.i(tag, "fcm token sync result synced=$synced")
                }
                .onFailure { error ->
                    Log.w(tag, "fcm token sync deferred", error)
                }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data["type"] != "wake_up") {
            Log.i(tag, "ignore non wake-up data message keys=${data.keys}")
            return
        }

        val triggerSource = data["triggerSource"] ?: "unknown"
        val reason = data["reason"] ?: "pending_command"
        val wakeReason = "fcm:$triggerSource:$reason"

        runCatching {
            startActivity(LauncherActivity.createRuntimeWakeIntent(applicationContext, wakeReason))
        }.onSuccess {
            Log.i(tag, "wake-up activity requested reason=$wakeReason")
        }.onFailure { error ->
            Log.w(tag, "wake-up activity request failed reason=$wakeReason", error)
        }
    }
}
