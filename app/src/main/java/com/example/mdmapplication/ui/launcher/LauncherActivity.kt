package com.example.mdmapplication.ui.launcher

import android.app.admin.DevicePolicyManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.example.mdmapplication.BuildConfig
import com.example.mdmapplication.device.DevicePolicyHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LauncherActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()
    private val tag = "LauncherActivity"
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(tag, "onCreate savedInstanceState=${savedInstanceState != null} taskId=$taskId")

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val policy = DevicePolicyHelper(this)
        val isDo = dpm.isDeviceOwnerApp(packageName)

        if (isDo && BuildConfig.DEBUG) {
            policy.clearPersistentPreferredActivities()
        }

        val wakeReason = parseWakeReason(intent) ?: "app_launch:onCreate"
        triggerRuntimeWake(reason = wakeReason, force = true)

        lifecycleScope.launch {
            viewModel.commandActions.collectLatest { action ->
                when (action) {
                    LauncherCommandAction.TryLockScreen -> {
                        if (isDo) runCatching { startLockTask() }
                    }

                    LauncherCommandAction.BringMdmToFrontAndLock -> {
                        bringSelfToFrontOnce()
                        if (isDo) {
                            runCatching { policy.applyLockedContainment(packageName) }
                                .onFailure { Log.e(tag, "applyLockedContainment failed", it) }
                            policy.startLockTaskIfPermitted(this@LauncherActivity)
                        }
                    }

                    LauncherCommandAction.AllowedAppsUpdated -> {
                        // Recovery foreground trước để trnh kẹt app cũ (mn xm) khi profile vừa đổi.
                        bringSelfToFrontOnce()
                        if (isDo) {
                            policy.startLockTaskIfPermitted(this@LauncherActivity)
                        }
                        Toast.makeText(
                            this@LauncherActivity,
                            "Allowed apps updated",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        setContent {
            val st by viewModel.state.collectAsState()

            when (st.lockState) {
                DeviceLockState.LOCKED -> {
                    UnlockScreen(
                        error = st.unlockError,
                        loading = st.loading,
                        onUnlock = { password -> viewModel.unlock(this@LauncherActivity, password) }
                    )
                }

                DeviceLockState.ACTIVE -> {
                    LauncherScreen(
                        apps = st.apps,
                        isDeviceOwner = isDo,
                        onAppClick = { pkg ->
                            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                        },
                        onClearPersistentHome = {
                            if (isDo) policy.clearPersistentPreferredActivities()
                        },
                        onApplyKioskHome = {
                            triggerRuntimeWake("manualApplyKioskHome", force = true)
                        },
                        onExitLockTask = { runCatching { stopLockTask() } }
                    )
                }

                DeviceLockState.UNKNOWN -> {
                    LoadingOrErrorScreen(
                        loading = st.loading,
                        error = st.error,
                        onRetry = { triggerRuntimeWake("manualRetry", force = true) }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
    }

    override fun onResume() {
        super.onResume()
        Log.i(tag, "onResume taskId=$taskId")
        triggerRuntimeWake("ui:onResume")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(tag, "onNewIntent action=${intent.action} taskId=$taskId")
        val wakeReason = parseWakeReason(intent) ?: "ui:onNewIntent"
        triggerRuntimeWake(wakeReason)
    }

    override fun onStop() {
        unregisterNetworkCallback()
        super.onStop()
    }

    override fun onDestroy() {
        Log.w(tag, "onDestroy isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations")
        super.onDestroy()
    }


    private fun bringSelfToFrontOnce() {
        startActivity(
            Intent(this, LauncherActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        )
    }

    private fun triggerRuntimeWake(reason: String, force: Boolean = false) {
        Log.i(tag, "runtime retrigger reason=$reason force=$force")
        viewModel.requestRuntimeWake(context = this, reason = reason, force = force)
    }

    private fun parseWakeReason(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == ACTION_RUNTIME_WAKE) {
            return intent.getStringExtra(EXTRA_WAKE_REASON) ?: "broadcast:unknown"
        }
        return null
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                if (hasInternet) {
                    triggerRuntimeWake("network:return")
                }
            }
        }

        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess {
                networkCallback = callback
                Log.i(tag, "network callback registered")
            }
            .onFailure { Log.w(tag, "network callback register failed", it) }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { cm.unregisterNetworkCallback(callback) }
            .onFailure { Log.w(tag, "network callback unregister failed", it) }
        networkCallback = null
    }

    companion object {
        const val ACTION_RUNTIME_WAKE = "com.example.mdmapplication.action.RUNTIME_WAKE"
        const val EXTRA_WAKE_REASON = "extra_wake_reason"

        fun createRuntimeWakeIntent(context: Context, reason: String): Intent =
            Intent(context, LauncherActivity::class.java).apply {
                action = ACTION_RUNTIME_WAKE
                putExtra(EXTRA_WAKE_REASON, reason)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
    }
}
