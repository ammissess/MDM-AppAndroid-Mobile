package com.example.mdmapplication.device

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import android.view.inputmethod.InputMethodManager
import com.example.mdmapplication.ui.launcher.LauncherActivity

class DevicePolicyHelper(private val context: Context) {

    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val admin =
        ComponentName(context, MyDeviceAdminReceiver::class.java)

    private val prefs = context.getSharedPreferences("mdm_policy_prefs", Context.MODE_PRIVATE)
    private val KEY_LAST_MANAGED = "last_managed_packages"

    private val tag = "DevicePolicyHelper"
    private val selfPackage: String = context.packageName

    private fun isSelfPackage(packageName: String): Boolean = packageName == selfPackage

    private fun packageExists(pm: PackageManager, pkg: String): Boolean =
        runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    private fun restorePackage(packageName: String) {
        if (packageName.isBlank()) return
        runCatching { dpm.setApplicationHidden(admin, packageName, false) }
            .onFailure { Log.w(tag, "restorePackage unhide failed package=$packageName", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { dpm.setPackagesSuspended(admin, arrayOf(packageName), false) }
                .onFailure { Log.w(tag, "restorePackage unsuspend failed package=$packageName", it) }
        }
    }

    private fun restorePackages(packages: Collection<String>) {
        packages.forEach { restorePackage(it) }
    }

    private fun restoreSelfBeforePolicy() {
        restorePackage(selfPackage)
        Log.i(tag, "restoreSelfBeforePolicy done selfPackage=$selfPackage")
    }

    private fun removeSelfFromSet(source: Set<String>): Set<String> = source.filterNot(::isSelfPackage).toSet()

    private inline fun runPolicyOrThrow(step: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            throw IllegalStateException("$step failed: ${t.message}", t)
        }
    }

    private fun restorePackageStrict(packageName: String) {
        if (packageName.isBlank()) return
        runPolicyOrThrow("setApplicationHidden(false)[$packageName]") {
            dpm.setApplicationHidden(admin, packageName, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runPolicyOrThrow("setPackagesSuspended(false)[$packageName]") {
                dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
            }
        }
    }

    private fun restorePackagesStrict(packages: Collection<String>) {
        packages.forEach { restorePackageStrict(it) }
    }

    private fun restoreSelfBeforePolicyStrict() {
        restorePackageStrict(selfPackage)
        Log.i(tag, "restoreSelfBeforePolicyStrict done selfPackage=$selfPackage")
    }

    private fun setLockTaskPackagesStrict(packages: Array<String>) {
        restoreSelfBeforePolicyStrict()
        val distinct = packages.distinct().toTypedArray()
        runPolicyOrThrow("setLockTaskPackages") {
            dpm.setLockTaskPackages(admin, distinct)
        }
    }

    private fun setUserRestrictionStrict(key: String, disabled: Boolean) {
        runPolicyOrThrow("setUserRestriction[$key]=$disabled") {
            if (disabled) dpm.addUserRestriction(admin, key)
            else dpm.clearUserRestriction(admin, key)
        }
    }

    private fun setPersistentHomeToLauncherStrict() {
        restoreSelfBeforePolicyStrict()
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val launcher = ComponentName(context, LauncherActivity::class.java)
        runPolicyOrThrow("addPersistentPreferredActivity") {
            dpm.addPersistentPreferredActivity(admin, filter, launcher)
        }
    }

    private fun applyLockTaskFeaturesStrict(kioskMode: Boolean, lockedMode: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val flags = when {
            !kioskMode -> DevicePolicyManager.LOCK_TASK_FEATURE_HOME
            lockedMode -> DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
            else -> DevicePolicyManager.LOCK_TASK_FEATURE_NONE
        }
        runPolicyOrThrow("setLockTaskFeatures") {
            dpm.setLockTaskFeatures(admin, flags)
        }
    }

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(selfPackage)

    fun setLockTaskPackages(packages: Array<String>) {
        restoreSelfBeforePolicy()
        runCatching { dpm.setLockTaskPackages(admin, packages.distinct().toTypedArray()) }
            .onFailure { Log.w(tag, "setLockTaskPackages failed", it) }
    }

    fun blockUninstall(pkg: String) {
        runCatching { dpm.setUninstallBlocked(admin, pkg, true) }
            .onFailure { Log.w(tag, "blockUninstall failed for $pkg", it) }
    }

    fun disableStatusBar(disabled: Boolean) {
        runCatching { dpm.setStatusBarDisabled(admin, disabled) }
            .onFailure { Log.w(tag, "disableStatusBar failed disabled=$disabled", it) }
    }

    private fun setUserRestriction(key: String, disabled: Boolean) {
        runCatching {
            if (disabled) dpm.addUserRestriction(admin, key)
            else dpm.clearUserRestriction(admin, key)
        }.onFailure { Log.w(tag, "setUserRestriction failed key=$key disabled=$disabled", it) }
    }

    fun setWifiDisabled(disabled: Boolean) = setUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, disabled)
    fun setBluetoothDisabled(disabled: Boolean) = setUserRestriction(UserManager.DISALLOW_BLUETOOTH, disabled)

    fun setCameraDisabled(disabled: Boolean) {
        runCatching { dpm.setCameraDisabled(admin, disabled) }
            .onFailure { Log.w(tag, "setCameraDisabled failed disabled=$disabled", it) }
    }

    fun setDebuggingDisabled(disabled: Boolean) = setUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, disabled)
    fun setSafeBootDisabled(disabled: Boolean) = setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, disabled)

    fun setPersistentHomeToLauncher() {
        restoreSelfBeforePolicy()
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val launcher = ComponentName(context, LauncherActivity::class.java)
        runCatching { dpm.addPersistentPreferredActivity(admin, filter, launcher) }
            .onFailure { Log.w(tag, "setPersistentHomeToLauncher failed", it) }
    }

    fun clearPersistentPreferredActivities() {
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, selfPackage) }
            .onFailure { Log.w(tag, "clearPersistentPreferredActivities failed", it) }
    }

    fun applyMinimumKioskPolicy(
        launcherPackage: String,
        allowedApps: List<String> = emptyList(),
        disableDebugging: Boolean = false,
        disableSafeBoot: Boolean = false
    ) {
        val isOwner = isDeviceOwner()
        Log.i(
            tag,
            "applyMinimumKioskPolicy enter launcherPackage=$launcherPackage allowedApps=${allowedApps.size} isDeviceOwner=$isOwner"
        )
        if (!isOwner) return
        restoreSelfBeforePolicy()
        setPersistentHomeToLauncher()
        setLockTaskPackages((listOf(launcherPackage, selfPackage) + allowedApps).distinct().toTypedArray())
        applyLockTaskFeatures(kioskMode = true, lockedMode = false)
        setDebuggingDisabled(disableDebugging)
        setSafeBootDisabled(disableSafeBoot)
        Log.i(tag, "applyMinimumKioskPolicy done")
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
        val isOwner = isDeviceOwner()
        Log.i(
            tag,
            "applyFromServerConfig enter launcherPackage=$launcherPackage allowedApps=${allowedApps.size} kioskMode=$kioskMode isDeviceOwner=$isOwner"
        )
        if (!isOwner) return
        restoreSelfBeforePolicyStrict()
        setPersistentHomeToLauncherStrict()
        setLockTaskPackagesStrict((listOf(launcherPackage, selfPackage) + allowedApps).distinct().toTypedArray())
        applyLockTaskFeaturesStrict(kioskMode = kioskMode, lockedMode = false)

        if (blockUninstall) {
            runPolicyOrThrow("setUninstallBlocked[$selfPackage]=true") {
                dpm.setUninstallBlocked(admin, selfPackage, true)
            }
        }
        runPolicyOrThrow("setStatusBarDisabled[$disableStatusBar]") {
            dpm.setStatusBarDisabled(admin, disableStatusBar)
        }
        setUserRestrictionStrict(UserManager.DISALLOW_CONFIG_WIFI, disableWifi)
        setUserRestrictionStrict(UserManager.DISALLOW_BLUETOOTH, disableBluetooth)
        runPolicyOrThrow("setCameraDisabled[$disableCamera]") {
            dpm.setCameraDisabled(admin, disableCamera)
        }

        setUserRestrictionStrict(UserManager.DISALLOW_DEBUGGING_FEATURES, false)
        setUserRestrictionStrict(UserManager.DISALLOW_SAFE_BOOT, false)
        Log.i(tag, "applyFromServerConfig done")
    }

    private fun applyLockTaskFeatures(kioskMode: Boolean, lockedMode: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            val flags = when {
                !kioskMode -> DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                lockedMode -> DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                        DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                        DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                else -> DevicePolicyManager.LOCK_TASK_FEATURE_NONE
            }
            dpm.setLockTaskFeatures(admin, flags)
        }.onFailure {
            Log.w(tag, "applyLockTaskFeatures failed kioskMode=$kioskMode lockedMode=$lockedMode", it)
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
        }.getOrElse {
            Log.w(tag, "enabledImePackages failed", it)
            emptySet()
        }
    }

    private fun minimumSystemSafelist(pm: PackageManager): Set<String> {
        val candidates = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.documentsui",
            "com.android.providers.downloads",
            "com.android.providers.downloads.ui",
            "com.android.providers.media",
            "com.android.externalstorage",
            "com.android.webview",
            "com.google.android.webview",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.settings",
            "com.android.providers.settings",
            "com.android.launcher3"
        )
        return candidates.filterTo(mutableSetOf()) { pkg -> packageExists(pm, pkg) }
    }

    private fun readLastManagedPackages(): Set<String> =
        prefs.getStringSet(KEY_LAST_MANAGED, emptySet())?.toSet() ?: emptySet()

    private fun writeLastManagedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_LAST_MANAGED, packages).apply()
    }

    private fun manageableCandidates(
        currentLaunchables: Set<String>,
        previousManaged: Set<String>,
        keep: Set<String>
    ): Set<String> {
        return (currentLaunchables + previousManaged + keep)
            .filterNot(::isSelfPackage)
            .toSet()
    }

    private fun nonCoreSystemPackages(pm: PackageManager, packages: Set<String>): Set<String> {
        return packages.filterTo(mutableSetOf()) { pkg ->
            if (isSelfPackage(pkg)) return@filterTo false
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@filterTo false
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !isSystem
        }
    }

    fun preparePackageForLaunch(packageName: String) {
        if (!isDeviceOwner()) return
        restoreSelfBeforePolicy()
        restorePackage(packageName)
    }

    fun enforceAllowedPackages(
        launcherPackage: String,
        allowedApps: List<String>,
        kioskMode: Boolean,
        allowSettingsIfExplicitlyWhitelisted: Boolean
    ) {
        val isOwner = isDeviceOwner()
        Log.i(
            tag,
            "enforceAllowedPackages enter launcherPackage=$launcherPackage allowedApps=${allowedApps.size} kioskMode=$kioskMode isDeviceOwner=$isOwner"
        )
        if (!isOwner) return

        val pm = context.packageManager
        val currentLaunchables = launchablePackages(pm)
        val imePkgs = enabledImePackages()
        val previousManaged = readLastManagedPackages()

        val explicitAllowed = allowedApps.toMutableSet()
        if (!allowSettingsIfExplicitlyWhitelisted) {
            explicitAllowed.remove("com.android.settings")
        }

        val keep = mutableSetOf<String>().apply {
            add(selfPackage)
            add(launcherPackage)
            addAll(explicitAllowed)
            addAll(minimumSystemSafelist(pm))
            addAll(imePkgs)
        }.filterTo(mutableSetOf()) { pkg -> packageExists(pm, pkg) }

        restoreSelfBeforePolicyStrict()
        restorePackagesStrict(keep)

        val managedCandidates = manageableCandidates(
            currentLaunchables = currentLaunchables,
            previousManaged = previousManaged,
            keep = keep
        )

        val restrictCandidatesBase = nonCoreSystemPackages(pm, managedCandidates)
            .filterNot { it in keep || it in imePkgs }
            .toSet()

        val restrictCandidates = removeSelfFromSet(restrictCandidatesBase)

        val restored = mutableListOf<String>()
        val hidden = mutableListOf<String>()
        val suspended = mutableListOf<String>()

        keep.forEach { pkg ->
            if (isSelfPackage(pkg)) return@forEach

            runPolicyOrThrow("setApplicationHidden(false)[$pkg]") {
                dpm.setApplicationHidden(admin, pkg, false)
            }
            restored += pkg

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runPolicyOrThrow("setPackagesSuspended(false)[$pkg]") {
                    dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
                }
            }
        }

        // Active mode only: do not infer containment from empty allowlist.
        // Backend lock status is handled separately via applyLockedContainment().
        val lockedContainment = false
        Log.i(tag, "ACTIVE mode: skip aggressive hide/suspend containment")

        setLockTaskPackagesStrict(keep.toTypedArray())
        applyLockTaskFeaturesStrict(kioskMode = kioskMode, lockedMode = lockedContainment)

        writeLastManagedPackages(restrictCandidates)

        Log.i(
            tag,
            "enforceAllowedPackages done | self=$selfPackage lockedContainment=$lockedContainment " +
                    "keep=${keep.size} ime=${imePkgs.size} managedCandidates=${managedCandidates.size} " +
                    "restrictCandidates=${restrictCandidates.size} restored=${restored.size} " +
                    "hidden=${hidden.size} suspended=${suspended.size} selfInRestrict=${restrictCandidates.any(::isSelfPackage)}"
        )
    }

    fun applyLockedContainment(launcherPackage: String) {
        val isOwner = isDeviceOwner()
        Log.i(tag, "applyLockedContainment enter launcherPackage=$launcherPackage isDeviceOwner=$isOwner")
        if (!isOwner) {
            throw IllegalStateException("Device is not owner, cannot enforce lock containment")
        }

        val pm = context.packageManager
        val imePkgs = enabledImePackages()
        val keep = mutableSetOf<String>().apply {
            add(selfPackage)
            add(launcherPackage)
            addAll(minimumSystemSafelist(pm))
            addAll(imePkgs)
        }.filterTo(mutableSetOf()) { pkg -> packageExists(pm, pkg) }

        restoreSelfBeforePolicyStrict()
        restorePackagesStrict(keep)

        // Locked containment is limited to lock-task + persistent home to avoid launcher self-break.
        setPersistentHomeToLauncherStrict()
        setLockTaskPackagesStrict(keep.toTypedArray())
        applyLockTaskFeaturesStrict(kioskMode = true, lockedMode = true)
        writeLastManagedPackages(emptySet())

        Log.i(
            tag,
            "applyLockedContainment done | self=$selfPackage keep=${keep.size} ime=${imePkgs.size}"
        )
    }

    fun startLockTaskIfPermitted(activity: Activity) {
        restoreSelfBeforePolicy()
        runCatching {
            if (dpm.isLockTaskPermitted(selfPackage)) {
                activity.startLockTask()
            }
        }.onFailure { Log.w(tag, "startLockTaskIfPermitted failed", it) }
    }
}