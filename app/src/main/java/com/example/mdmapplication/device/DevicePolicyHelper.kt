/*
//package com.example.mdmapplication.device
//
//import android.app.admin.DevicePolicyManager
//import android.content.ComponentName
//import android.content.Context
//import android.content.Intent
//import android.content.IntentFilter
//import com.example.mdmapplication.ui.launcher.LauncherActivity
//
//class DevicePolicyHelper(private val context: Context) {
//
//    private val dpm =
//        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
//
//    private val admin =
//        ComponentName(context, MyDeviceAdminReceiver::class.java)
//
//    fun enableKiosk(packageName: String) {
//        dpm.setLockTaskPackages(admin, arrayOf(packageName))
//    }
//
//    fun blockUninstall(pkg: String) {
//        dpm.setUninstallBlocked(admin, pkg, true)
//    }
//
//    fun disableStatusBar() {
//        dpm.setStatusBarDisabled(admin, true)
//    }
//
//    /** Đóng đinh HOME về LauncherActivity của app (persistent) */
//    fun setPersistentHomeToLauncher() {
//        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
//            addCategory(Intent.CATEGORY_HOME)
//            addCategory(Intent.CATEGORY_DEFAULT)
//        }
//        val launcher = ComponentName(context, LauncherActivity::class.java)
//        dpm.addPersistentPreferredActivity(admin, filter, launcher)
//    }
//
//    /** Gỡ tất cả persistent preferred activities mà app này đã set */
//    fun clearPersistentPreferredActivities() {
//        // Gỡ persistent preferred activities cho chính package của app
//        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
//    }
//
//    /** Gói thao tác "apply kiosk + set home + block uninstall" */
//    fun applyKioskAsHome(packageName: String) {
//        setPersistentHomeToLauncher()
//        enableKiosk(packageName)
//        blockUninstall(packageName)
//    }
//}
*/

//code cu phia tren chay mdm cu, de ket noi toi backend can nang cap
//DevicePolicyHelper để apply policy từ backend

package com.example.mdmapplication.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import com.example.mdmapplication.ui.launcher.LauncherActivity

class DevicePolicyHelper(private val context: Context) {

    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val userManager =
        context.getSystemService(Context.USER_SERVICE) as UserManager

    private val admin =
        ComponentName(context, MyDeviceAdminReceiver::class.java)

    /** Allow lock-task for packages (launcher + allowed apps) */
    fun setLockTaskPackages(packages: Array<String>) {
        dpm.setLockTaskPackages(admin, packages)
    }

    fun blockUninstall(pkg: String) {
        dpm.setUninstallBlocked(admin, pkg, true)
    }

    fun disableStatusBar(disabled: Boolean) {
        dpm.setStatusBarDisabled(admin, disabled)
    }

    private fun setUserRestriction(key: String, disabled: Boolean) {
        if (disabled) dpm.addUserRestriction(admin, key)
        else dpm.clearUserRestriction(admin, key)
    }

    fun setWifiDisabled(disabled: Boolean) {
        setUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, disabled)
    }

    fun setBluetoothDisabled(disabled: Boolean) {
        setUserRestriction(UserManager.DISALLOW_BLUETOOTH, disabled)
    }

    fun setCameraDisabled(disabled: Boolean) {
        dpm.setCameraDisabled(admin, disabled)
    }

    /** Đóng đinh HOME về LauncherActivity của app (persistent) */
    fun setPersistentHomeToLauncher() {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val launcher = ComponentName(context, LauncherActivity::class.java)
        dpm.addPersistentPreferredActivity(admin, filter, launcher)
    }

    fun clearPersistentPreferredActivities() {
        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
    }

    /** Apply policy từ server */
    fun applyFromServerConfig(
        launcherPackage: String,
        allowedApps: List<String>,
        kioskMode: Boolean,
        disableStatusBar: Boolean,
        blockUninstall: Boolean,
        disableWifi: Boolean,
        disableBluetooth: Boolean,
        disableCamera: Boolean
    ) {
        // HOME
        setPersistentHomeToLauncher()

        // Lock task allowlist = launcher + allowed packages
        val lockPackages = (listOf(launcherPackage) + allowedApps).distinct().toTypedArray()
        setLockTaskPackages(lockPackages)

        // Uninstall + statusbar
        if (blockUninstall) blockUninstall(launcherPackage)
        disableStatusBar(disableStatusBar)

        // restrictions
        setWifiDisabled(disableWifi)
        setBluetoothDisabled(disableBluetooth)
        setCameraDisabled(disableCamera)

        // kioskMode => activity sẽ gọi startLockTask() (vì cần Activity context)
    }
}
