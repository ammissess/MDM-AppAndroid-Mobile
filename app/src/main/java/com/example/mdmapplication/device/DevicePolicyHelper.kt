package com.example.mdmapplication.device

import android.app.Activity
import android.app.ActivityManager
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.example.mdmapplication.ui.launcher.LauncherActivity

class DevicePolicyHelper(private val context: Context) {

    data class PolicyApplyOutcome(
        val status: String,
        val error: String? = null,
        val errorCode: String? = null
    )

    data class LockContainmentOutcome(
        val status: String,
        val error: String? = null,
        val errorCode: String? = null
    )

    private data class UsbDataSignalingOutcome(
        val applied: Boolean,
        val enabled: Boolean? = null,
        val reason: String
    )

    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val admin =
        ComponentName(context, MyDeviceAdminReceiver::class.java)

    private val prefs = context.getSharedPreferences("mdm_policy_prefs", Context.MODE_PRIVATE)
    private val KEY_LAST_MANAGED = "last_managed_packages"

    private val tag = "DevicePolicyHelper"
    private val lockContainmentTag = "MDM_LOCK_CONTAINMENT"
    private val selfPackage: String = context.packageName

    private fun isSelfPackage(packageName: String): Boolean = packageName == selfPackage

    private data class ManagedPackageState(
        val present: Boolean,
        val hidden: Boolean?,
        val suspended: Boolean?
    )

    private fun packageInfoFlags(): Int =
        PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES

    private fun packageExists(pm: PackageManager, pkg: String): Boolean =
        runCatching { pm.getApplicationInfo(pkg, packageInfoFlags()); true }.getOrDefault(false)

    private fun readManagedPackageState(pm: PackageManager, packageName: String): ManagedPackageState {
        val info = runCatching { pm.getApplicationInfo(packageName, packageInfoFlags()) }.getOrNull()
        val hidden = if (info != null) {
            runCatching { dpm.isApplicationHidden(admin, packageName) }.getOrNull()
        } else {
            null
        }
        val suspended = if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
        } else {
            null
        }
        return ManagedPackageState(
            present = info != null,
            hidden = hidden,
            suspended = suspended
        )
    }

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
        disableCamera: Boolean,
        lockPrivateDnsConfig: Boolean,
        lockVpnConfig: Boolean,
        blockDebuggingFeatures: Boolean,
        disableUsbDataSignaling: Boolean,
        disallowSafeBoot: Boolean,
        disallowFactoryReset: Boolean
    ): PolicyApplyOutcome {
        val isOwner = isDeviceOwner()
        Log.i(
            tag,
            "applyFromServerConfig enter launcherPackage=$launcherPackage allowedApps=${allowedApps.size} kioskMode=$kioskMode isDeviceOwner=$isOwner"
        )
        if (!isOwner) {
            return PolicyApplyOutcome(
                status = "FAILED",
                error = "Device is not owner, policy cannot be applied",
                errorCode = "POLICY_NOT_DEVICE_OWNER"
            )
        }
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
        setUserRestrictionStrict(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, lockPrivateDnsConfig)
        setUserRestrictionStrict(UserManager.DISALLOW_CONFIG_VPN, lockVpnConfig)
        setUserRestrictionStrict(UserManager.DISALLOW_DEBUGGING_FEATURES, blockDebuggingFeatures)
        val usbOutcome = applyUsbDataSignalingStrict(disableUsbDataSignaling)
        setUserRestrictionStrict(UserManager.DISALLOW_SAFE_BOOT, disallowSafeBoot)
        setUserRestrictionStrict(UserManager.DISALLOW_FACTORY_RESET, disallowFactoryReset)

        Log.i(
            tag,
            "applyHardening network lockPrivateDnsConfig=$lockPrivateDnsConfig lockVpnConfig=$lockVpnConfig"
        )
        Log.i(
            tag,
            "applyHardening security blockDebuggingFeatures=$blockDebuggingFeatures disallowSafeBoot=$disallowSafeBoot disallowFactoryReset=$disallowFactoryReset"
        )
        Log.i(
            tag,
            "applyHardening usb disableUsbDataSignaling=$disableUsbDataSignaling applied=${usbOutcome.applied} enabled=${usbOutcome.enabled} reason=${usbOutcome.reason}"
        )
        val unsupported = disableUsbDataSignaling && !usbOutcome.applied &&
                (usbOutcome.reason.startsWith("unsupported_"))

        val outcome = if (unsupported) {
            PolicyApplyOutcome(
                status = "PARTIAL",
                error = "Policy partially applied: ${usbOutcome.reason}",
                errorCode = "UNSUPPORTED_API"
            )
        } else {
            PolicyApplyOutcome(status = "SUCCESS")
        }
        Log.i(tag, "applyFromServerConfig done status=${outcome.status} errorCode=${outcome.errorCode}")
        return outcome
    }

    private fun applyUsbDataSignalingStrict(disabled: Boolean): UsbDataSignalingOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return UsbDataSignalingOutcome(
                applied = false,
                reason = "unsupported_sdk_${Build.VERSION.SDK_INT}"
            )
        }

        val supported = runCatching { dpm.canUsbDataSignalingBeDisabled() }
            .getOrElse { err ->
                throw IllegalStateException("canUsbDataSignalingBeDisabled failed: ${err.message}", err)
            }

        if (!supported) {
            return UsbDataSignalingOutcome(
                applied = false,
                reason = "unsupported_capability"
            )
        }

        val enabled = !disabled
        runPolicyOrThrow("setUsbDataSignalingEnabled[$enabled]") {
            dpm.setUsbDataSignalingEnabled(enabled)
        }
        return UsbDataSignalingOutcome(
            applied = true,
            enabled = enabled,
            reason = if (disabled) "disabled" else "enabled"
        )
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

        val requestedKeep = mutableSetOf<String>().apply {
            add(selfPackage)
            add(launcherPackage)
            addAll(explicitAllowed)
            addAll(minimumSystemSafelist(pm))
            addAll(imePkgs)
        }
        val keep = requestedKeep.filterTo(mutableSetOf()) { pkg -> packageExists(pm, pkg) }
        val missingAllowed = explicitAllowed.filterNot { it in keep }
        missingAllowed.forEach { pkg ->
            Log.w(tag, "enforceAllowedPackages allowlisted package not present in PackageManager package=$pkg")
        }

        restoreSelfBeforePolicyStrict()

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
        val restoredFromHidden = mutableListOf<String>()
        val restoredFromSuspended = mutableListOf<String>()

        keep.forEach { pkg ->
            if (isSelfPackage(pkg)) return@forEach

            val beforeState = readManagedPackageState(pm, pkg)
            restorePackageStrict(pkg)
            val afterState = readManagedPackageState(pm, pkg)
            restored += pkg
            if (beforeState.hidden == true && afterState.hidden == false) {
                restoredFromHidden += pkg
            }
            if (beforeState.suspended == true && afterState.suspended == false) {
                restoredFromSuspended += pkg
            }
            Log.i(
                tag,
                "enforceAllowedPackages restore package=$pkg presentBefore=${beforeState.present} " +
                        "hiddenBefore=${beforeState.hidden} suspendedBefore=${beforeState.suspended} " +
                        "presentAfter=${afterState.present} hiddenAfter=${afterState.hidden} suspendedAfter=${afterState.suspended}"
            )
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
                    "restoredFromHidden=${restoredFromHidden.size} restoredFromSuspended=${restoredFromSuspended.size} " +
                    "missingAllowed=${missingAllowed.size} selfInRestrict=${restrictCandidates.any(::isSelfPackage)}"
        )
    }

    fun applyLockedContainment(launcherPackage: String) {
        val isOwner = isDeviceOwner()
        Log.i(tag, "applyLockedContainment enter launcherPackage=$launcherPackage isDeviceOwner=$isOwner")
        if (!isOwner) {
            throw IllegalStateException("Device is not owner, cannot enforce lock containment")
        }

        restoreSelfBeforePolicyStrict()

        // Locked containment is limited to lock-task + persistent home to avoid launcher self-break.
        setPersistentHomeToLauncherStrict()
        setLockTaskPackagesStrict(arrayOf(launcherPackage))
        applyLockTaskFeaturesStrict(kioskMode = true, lockedMode = false)
        writeLastManagedPackages(emptySet())

        Log.i(
            tag,
            "applyLockedContainment done | self=$selfPackage launcherPackage=$launcherPackage"
        )
    }

    fun isDefaultLauncherApp(): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackage = runCatching {
            context.packageManager
                .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
        }.getOrNull()
        return homePackage == selfPackage
    }

    fun enforceLockedContainment(
        activity: Activity,
        launcherPackage: String,
        disableStatusBar: Boolean
    ): LockContainmentOutcome {
        runCatching { dpm.setStatusBarDisabled(admin, disableStatusBar) }
            .onFailure { Log.w(tag, "setStatusBarDisabled failed in lock containment", it) }
        return ensureStrictLockedContainment(activity)
    }

    fun ensureStrictLockedContainment(activity: Activity): LockContainmentOutcome {
        val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)
        val ownerCheck = dpm.isDeviceOwnerApp(selfPackage)
        val isDefaultLauncher = isDefaultLauncherApp()
        val lifecycleState = (activity as? LifecycleOwner)?.lifecycle?.currentState
        val isResumed = lifecycleState?.isAtLeast(Lifecycle.State.RESUMED) == true
        val hasFocus = activity.hasWindowFocus()
        val isActivityAlive = !activity.isFinishing && !activity.isDestroyed
        val canStartLockTask = isActivityAlive && isResumed && hasFocus
        Log.i(
            lockContainmentTag,
            "activityState resumed=$isResumed hasFocus=$hasFocus alive=$isActivityAlive ownerCheck=$ownerCheck defaultLauncher=$isDefaultLauncher"
        )
        if (!ownerCheck) {
            val outcome = LockContainmentOutcome(
                status = "FAILED",
                error = "Device is not owner, cannot enforce lock containment",
                errorCode = "NOT_DEVICE_OWNER"
            )
            Log.i(
                lockContainmentTag,
                "modeStateBefore=-1 modeStateAfterStart=-1 modeStateAfterDelay=-1 outcome=${outcome.status} errorCode=${outcome.errorCode}"
            )
            return outcome
        }

        runCatching {
            dpm.setLockTaskPackages(adminComponent, arrayOf(selfPackage))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
        }.onFailure { err ->
            val outcome = LockContainmentOutcome(
                status = "FAILED",
                error = err.message ?: "Unable to configure lock task allowlist",
                errorCode = "LOCK_TASK_NOT_ALLOWED"
            )
            Log.i(lockContainmentTag, "allowlist failed package=$selfPackage")
            Log.i(
                lockContainmentTag,
                "modeStateBefore=-1 modeStateAfterStart=-1 modeStateAfterDelay=-1 outcome=${outcome.status} errorCode=${outcome.errorCode}"
            )
            return outcome
        }
        Log.i(lockContainmentTag, "allowlist package=$selfPackage")

        val isPermitted = runCatching { dpm.isLockTaskPermitted(selfPackage) }.getOrDefault(false)
        if (!isPermitted) {
            val outcome = LockContainmentOutcome(
                status = "PARTIAL",
                error = "Lock task is not permitted for launcher package",
                errorCode = "LOCK_TASK_NOT_ALLOWED"
            )
            Log.i(lockContainmentTag, "start result=not_permitted")
            Log.i(
                lockContainmentTag,
                "modeStateBefore=-1 modeStateAfterStart=-1 modeStateAfterDelay=-1 outcome=${outcome.status} errorCode=${outcome.errorCode}"
            )
            return outcome
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val modeStateBefore = runCatching { activityManager.lockTaskModeState }
            .getOrDefault(ActivityManager.LOCK_TASK_MODE_NONE)

        val startError = if (canStartLockTask) {
            runCatching { activity.startLockTask() }.exceptionOrNull()
        } else {
            IllegalStateException("Activity not foreground enough for startLockTask")
        }

        val modeStateAfterStart = runCatching { activityManager.lockTaskModeState }
            .getOrDefault(ActivityManager.LOCK_TASK_MODE_NONE)
        // Avoid blocking UI thread during containment checks.
        val modeStateAfterDelay = runCatching { activityManager.lockTaskModeState }
            .getOrDefault(ActivityManager.LOCK_TASK_MODE_NONE)
        val expectedLocked = ActivityManager.LOCK_TASK_MODE_LOCKED
        val isLocked = modeStateAfterDelay == expectedLocked

        if (startError != null && !isLocked) {
            val outcome = LockContainmentOutcome(
                status = "FAILED",
                error = if (!canStartLockTask) {
                    "Activity not in resumed/focused foreground state"
                } else {
                    startError.message ?: "Unable to enter lock task"
                },
                errorCode = if (!canStartLockTask) "LOCK_TASK_NOT_ACTIVE" else "LOCK_TASK_NOT_ALLOWED"
            )
            Log.i(lockContainmentTag, "start result=failed")
            Log.i(
                lockContainmentTag,
                "modeStateBefore=$modeStateBefore modeStateAfterStart=$modeStateAfterStart modeStateAfterDelay=$modeStateAfterDelay expectedLocked=$expectedLocked isLocked=$isLocked outcome=${outcome.status} errorCode=${outcome.errorCode}"
            )
            return outcome
        }
        Log.i(lockContainmentTag, "start result=${if (startError == null) "ok" else "already_locked_or_recovered"}")

        val outcome = when {
            modeStateAfterDelay != expectedLocked -> {
                LockContainmentOutcome(
                    status = "FAILED",
                    error = "Lock task is not active",
                    errorCode = "LOCK_TASK_NOT_ACTIVE"
                )
            }

            !isDefaultLauncher -> {
                LockContainmentOutcome(
                    status = "PARTIAL",
                    error = "Launcher is not default HOME app",
                    errorCode = "NOT_DEFAULT_LAUNCHER"
                )
            }

            else -> LockContainmentOutcome(status = "FULL")
        }

        Log.i(
            lockContainmentTag,
            "modeStateBefore=$modeStateBefore modeStateAfterStart=$modeStateAfterStart modeStateAfterDelay=$modeStateAfterDelay expectedLocked=$expectedLocked isLocked=$isLocked outcome=${outcome.status} errorCode=${outcome.errorCode}"
        )
        return outcome
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
