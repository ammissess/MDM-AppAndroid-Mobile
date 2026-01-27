package com.example.mdmapplication.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter


class DevicePolicyHelper( private val context: Context ) {

    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val admin =
        ComponentName(context, MyDeviceAdminReceiver::class.java)

    fun enableKiosk(packageName: String) {
        dpm.setLockTaskPackages(admin, arrayOf(packageName))
    }

    fun blockUninstall(pkg: String) {
        dpm.setUninstallBlocked(admin, pkg, true)
    }

    fun disableStatusBar() {
        dpm.setStatusBarDisabled(admin, true)
    }

    fun setPersistentHomeToLauncher() {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val launcher = ComponentName(context, com.example.mdmapplication.ui.launcher.LauncherActivity::class.java)
        dpm.addPersistentPreferredActivity(admin, filter, launcher)
    }

}
