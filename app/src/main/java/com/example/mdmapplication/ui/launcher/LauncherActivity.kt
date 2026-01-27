package com.example.mdmapplication.ui.launcher

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.mdmapplication.device.DevicePolicyHelper

class LauncherActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val policy = DevicePolicyHelper(this)

        if (dpm.isDeviceOwnerApp(packageName)) {
            policy.setPersistentHomeToLauncher()
            policy.enableKiosk(packageName)
            policy.blockUninstall(packageName)
            startLockTask()
        }

        setContent {
            LauncherScreen(apps = viewModel.allowedApps)
        }
    }
}
