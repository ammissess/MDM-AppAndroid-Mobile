package com.example.mdmapplication.device

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.view.inputmethod.InputMethodManager
import com.example.mdmapplication.ui.launcher.LauncherActivity

class DevicePolicyHelper(private val context: Context) {

    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val admin =
        ComponentName(context, MyDeviceAdminReceiver::class.java)

    private val prefs = context.getSharedPreferences("mdm_policy_prefs", Context.MODE_PRIVATE)
    private val KEY_LAST_MANAGED = "last_managed_packages"

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    fun setLockTaskPackages(packages: Array<String>) {
        runCatching { dpm.setLockTaskPackages(admin, packages) }
    }

    fun blockUninstall(pkg: String) {
        runCatching { dpm.setUninstallBlocked(admin, pkg, true) }
    }

    fun disableStatusBar(disabled: Boolean) {
        runCatching { dpm.setStatusBarDisabled(admin, disabled) }
    }

    private fun setUserRestriction(key: String, disabled: Boolean) {
        runCatching {
            if (disabled) dpm.addUserRestriction(admin, key)
            else dpm.clearUserRestriction(admin, key)
        }
    }

    fun setWifiDisabled(disabled: Boolean) = setUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, disabled)
    fun setBluetoothDisabled(disabled: Boolean) = setUserRestriction(UserManager.DISALLOW_BLUETOOTH, disabled)
    fun setCameraDisabled(disabled: Boolean) = runCatching { dpm.setCameraDisabled(admin, disabled) }
    fun setDebuggingDisabled(disabled: Boolean) = setUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, disabled)
    fun setSafeBootDisabled(disabled: Boolean) = setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, disabled)

    fun setPersistentHomeToLauncher() {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val launcher = ComponentName(context, LauncherActivity::class.java)
        runCatching { dpm.addPersistentPreferredActivity(admin, filter, launcher) }
    }

    fun clearPersistentPreferredActivities() {
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, context.packageName) }
    }

    fun applyMinimumKioskPolicy(
        launcherPackage: String,
        allowedApps: List<String> = emptyList(),
        disableDebugging: Boolean = false,
        disableSafeBoot: Boolean = false
    ) {
        if (!isDeviceOwner()) return
        setPersistentHomeToLauncher()
        setLockTaskPackages((listOf(launcherPackage) + allowedApps).distinct().toTypedArray())
        applyLockTaskFeatures(kioskMode = true)
        setDebuggingDisabled(disableDebugging)
        setSafeBootDisabled(disableSafeBoot)
    }

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
        if (!isDeviceOwner()) return
        setPersistentHomeToLauncher()
        setLockTaskPackages((listOf(launcherPackage) + allowedApps).distinct().toTypedArray())
        applyLockTaskFeatures(kioskMode = kioskMode)

        if (blockUninstall) blockUninstall(launcherPackage)
        disableStatusBar(disableStatusBar)
        setWifiDisabled(disableWifi)
        setBluetoothDisabled(disableBluetooth)
        setCameraDisabled(disableCamera)

        setDebuggingDisabled(false)
        setSafeBootDisabled(false)
    }

    private fun applyLockTaskFeatures(kioskMode: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            val flags = if (kioskMode) DevicePolicyManager.LOCK_TASK_FEATURE_NONE
            else DevicePolicyManager.LOCK_TASK_FEATURE_HOME
            dpm.setLockTaskFeatures(admin, flags)
        }
    }

    private fun launchablePackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private fun enabledImePackages(): Set<String> {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return emptySet()
        return runCatching {
            imm.enabledInputMethodList.mapNotNull { it.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun minimumSystemSafelist(): Set<String> = setOf(
        "com.android.systemui"
    )

    private fun readLastManagedPackages(): Set<String> =
        prefs.getStringSet(KEY_LAST_MANAGED, emptySet())?.toSet() ?: emptySet()

    private fun writeLastManagedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_LAST_MANAGED, packages).apply()
    }

    fun enforceAllowedPackages(
        launcherPackage: String,
        allowedApps: List<String>,
        kioskMode: Boolean,
        allowSettingsIfExplicitlyWhitelisted: Boolean
    ) {
        if (!isDeviceOwner()) return

        val pm = context.packageManager
        val currentLaunchables = launchablePackages(pm)
        val imePkgs = enabledImePackages()
        val previousManaged = readLastManagedPackages()

        val explicitAllowed = allowedApps.toMutableSet()
        if (!allowSettingsIfExplicitlyWhitelisted) {
            explicitAllowed.remove("com.android.settings")
        }

        val keep = mutableSetOf<String>().apply {
            add(launcherPackage)
            addAll(explicitAllowed)
            addAll(minimumSystemSafelist())
            addAll(imePkgs)
        }

        // FIX #1: explicit restore toàn bộ keep trước, không phụ thuộc universe/currentLaunchables/previousManaged
        keep.forEach { pkg ->
            if (pkg == launcherPackage) return@forEach
            runCatching { dpm.setApplicationHidden(admin, pkg, false) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), false) }
            }
        }

        val universe = (currentLaunchables + previousManaged).toMutableSet()
        universe.remove(launcherPackage)

        universe.forEach { pkg ->
            if (pkg in keep) {
                runCatching { dpm.setApplicationHidden(admin, pkg, false) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), false) }
                }
            }
        }

        universe.forEach { pkg ->
            if (pkg !in keep) {
                runCatching { dpm.setApplicationHidden(admin, pkg, true) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), true) }
                }
            }
        }

        setLockTaskPackages(keep.toTypedArray())
        applyLockTaskFeatures(kioskMode = kioskMode)
        writeLastManagedPackages(universe)
    }

    fun applyLockedContainment(launcherPackage: String) {
        enforceAllowedPackages(
            launcherPackage = launcherPackage,
            allowedApps = emptyList(),
            kioskMode = true,
            allowSettingsIfExplicitlyWhitelisted = false
        )
    }

    fun startLockTaskIfPermitted(activity: Activity) {
        runCatching {
            if (dpm.isLockTaskPermitted(context.packageName)) {
                activity.startLockTask()
            }
        }
    }
}