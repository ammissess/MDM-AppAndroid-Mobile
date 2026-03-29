package com.example.mdmapplication.ui.launcher

import android.app.admin.DevicePolicyManager
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
    private var lastAppliedConfigVersion: Long? = null
    private var lastHandledLockState: DeviceLockState? = null
    private var lastRefreshTriggerAtMs: Long = 0L
    private val tag = "LauncherActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(tag, "onCreate savedInstanceState=${savedInstanceState != null} taskId=$taskId")

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val policy = DevicePolicyHelper(this)
        val isDo = dpm.isDeviceOwnerApp(packageName)

        if (isDo && BuildConfig.DEBUG) {
            policy.clearPersistentPreferredActivities()
        }

        triggerRefreshFromBackend("onCreate")

        lifecycleScope.launch {
            viewModel.state.collectLatest { st ->
                if (!isDo) return@collectLatest

                if (lastHandledLockState != st.lockState) {
                    lastHandledLockState = st.lockState
                    if (st.lockState == DeviceLockState.LOCKED) {
                        policy.applyLockedContainment(packageName)
                        policy.startLockTaskIfPermitted(this@LauncherActivity)
                    }
                }

                if (st.lockState == DeviceLockState.ACTIVE) {
                    val cfg = st.config ?: return@collectLatest
                    if (lastAppliedConfigVersion != cfg.configVersionEpochMillis) {
                        lastAppliedConfigVersion = cfg.configVersionEpochMillis

                        policy.applyFromServerConfig(
                            launcherPackage = packageName,
                            allowedApps = cfg.allowedApps,
                            kioskMode = cfg.kioskMode,
                            disableStatusBar = cfg.disableStatusBar,
                            blockUninstall = cfg.blockUninstall,
                            disableWifi = cfg.disableWifi,
                            disableBluetooth = cfg.disableBluetooth,
                            disableCamera = cfg.disableCamera
                        )
                        policy.enforceAllowedPackages(
                            launcherPackage = packageName,
                            allowedApps = cfg.allowedApps,
                            kioskMode = cfg.kioskMode,
                            allowSettingsIfExplicitlyWhitelisted = true
                        )

                        if (cfg.kioskMode && !BuildConfig.DEBUG) {
                            runCatching { startLockTask() }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.commandActions.collectLatest { action ->
                when (action) {
                    LauncherCommandAction.TryLockScreen -> {
                        if (isDo) runCatching { startLockTask() }
                    }

                    LauncherCommandAction.BringMdmToFrontAndLock -> {
                        bringSelfToFrontOnce()
                        if (isDo) {
                            policy.applyLockedContainment(packageName)
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
                            if (isDo) {
                                st.config?.let { cfg ->
                                    policy.applyFromServerConfig(
                                        launcherPackage = packageName,
                                        allowedApps = cfg.allowedApps,
                                        kioskMode = cfg.kioskMode,
                                        disableStatusBar = cfg.disableStatusBar,
                                        blockUninstall = cfg.blockUninstall,
                                        disableWifi = cfg.disableWifi,
                                        disableBluetooth = cfg.disableBluetooth,
                                        disableCamera = cfg.disableCamera
                                    )
                                    policy.enforceAllowedPackages(
                                        launcherPackage = packageName,
                                        allowedApps = cfg.allowedApps,
                                        kioskMode = cfg.kioskMode,
                                        allowSettingsIfExplicitlyWhitelisted = true
                                    )
                                }
                            }
                        },
                        onExitLockTask = { runCatching { stopLockTask() } }
                    )
                }

                DeviceLockState.UNKNOWN -> {
                    LoadingOrErrorScreen(
                        loading = st.loading,
                        error = st.error,
                        onRetry = { viewModel.refreshFromBackend(this@LauncherActivity) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(tag, "onResume taskId=$taskId")
        triggerRefreshFromBackend("onResume")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(tag, "onNewIntent action=${intent.action} taskId=$taskId")
        triggerRefreshFromBackend("onNewIntent")
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

    private fun triggerRefreshFromBackend(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRefreshTriggerAtMs < 2_000L) {
            Log.i(tag, "skip refreshFromBackend reason=$reason (debounced)")
            return
        }
        lastRefreshTriggerAtMs = now
        Log.i(tag, "trigger refreshFromBackend reason=$reason")
        viewModel.refreshFromBackend(this)
    }
}