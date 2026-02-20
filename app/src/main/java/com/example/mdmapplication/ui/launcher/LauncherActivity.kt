package com.example.mdmapplication.ui.launcher

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.mdmapplication.BuildConfig
import com.example.mdmapplication.device.DevicePolicyHelper
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class LauncherActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val policy = DevicePolicyHelper(this)
        val isDo = dpm.isDeviceOwnerApp(packageName)

        if (isDo && BuildConfig.DEBUG) {
            policy.clearPersistentPreferredActivities()
        }

        // Fetch từ backend khi khởi động
        viewModel.refreshFromBackend(this)

        // Apply policy khi config thay đổi
        lifecycleScope.launch {
            viewModel.state.collectLatest { st ->
                val cfg = st.config ?: return@collectLatest
                if (!isDo) return@collectLatest

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

                if (cfg.kioskMode && !BuildConfig.DEBUG) {
                    try { startLockTask() } catch (_: Throwable) {}
                }
            }
        }

        setContent {
            val st by viewModel.state.collectAsState()

            when (st.lockState) {
                DeviceLockState.LOCKED -> {
                    // Hiện màn hình nhập mật khẩu unlock
                    UnlockScreen(
                        error = st.unlockError,
                        loading = st.loading,
                        onUnlock = { password ->
                            viewModel.unlock(this@LauncherActivity, password)
                        }
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
                                }
                            }
                        },
                        onExitLockTask = { try { stopLockTask() } catch (_: Throwable) {} }
                    )
                }
                DeviceLockState.UNKNOWN -> {
                    // Màn hình loading / lỗi
                    LoadingOrErrorScreen(
                        loading = st.loading,
                        error = st.error,
                        onRetry = { viewModel.refreshFromBackend(this@LauncherActivity) }
                    )
                }
            }
        }
    }
}