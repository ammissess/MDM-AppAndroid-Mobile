package com.example.mdmapplication.ui.launcher

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.mdmapplication.R
import com.example.mdmapplication.device.DevicePolicyHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LauncherActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()
    private val tag = "LauncherActivity"
    private val lockContainmentTag = "MDM_LOCK_CONTAINMENT"
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastKnownLockState: DeviceLockState = DeviceLockState.UNKNOWN
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recoveryGeneration = 0
    private var lastBringToFrontAtMs = 0L
    private var lastLockTaskStartAtMs = 0L
    private var lockContainmentInFlight = false
    private var languageSelected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        val initialLanguage = readAppLanguage()
        languageSelected = initialLanguage != null
        val wakeReason = parseWakeReason(intent) ?: "app_launch:onCreate"
        Log.i(
            tag,
            "onCreate savedInstanceState=${savedInstanceState != null} taskId=$taskId pid=${Process.myPid()} " +
                    "action=${intent?.action} wakeReason=$wakeReason wakeAttempt=${intent?.getIntExtra(EXTRA_WAKE_ATTEMPT, 0)} " +
                    "languageSelected=$languageSelected setupState=${viewModel.state.value.setupState} lockState=${viewModel.state.value.lockState}"
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.state.value.lockState == DeviceLockState.LOCKED) {
                    Log.i(lockContainmentTag, "enforce reason=back_dispatcher_blocked")
                    enforceLockedContainment("back_dispatcher")
                    return
                }
                isEnabled = false
                try {
                    onBackPressedDispatcher.onBackPressed()
                } finally {
                    isEnabled = true
                }
            }
        })

        val policy = DevicePolicyHelper(this)

        if (languageSelected) {
            triggerRuntimeWake(reason = wakeReason, force = true)
        }

        lifecycleScope.launch {
            viewModel.commandActions.collectLatest { action ->
                when (action) {
                    LauncherCommandAction.TryLockScreen -> {
                        enforceLockedContainment("command:try_lock")
                    }

                    LauncherCommandAction.BringMdmToFrontAndLock -> {
                        enforceLockedContainment("command:bring_front_and_lock")
                    }

                    LauncherCommandAction.AllowedAppsUpdated -> {
                        mainHandler.postDelayed({
                            val current = viewModel.state.value
                            if (
                                current.setupState == SetupState.ENFORCEMENT_ACTIVE &&
                                !current.applyingConfiguration
                            ) {
                                // Recovery foreground before lock-task so profile changes do not leave an old app on top.
                                bringSelfToFrontOnce()
                            }
                            if (
                                current.setupState == SetupState.ENFORCEMENT_ACTIVE &&
                                !current.applyingConfiguration &&
                                policy.isDeviceOwner() &&
                                (current.lockState == DeviceLockState.LOCKED || current.config?.kioskMode == true)
                            ) {
                                policy.startLockTaskIfPermitted(this@LauncherActivity)
                            }
                        }, 750L)
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
            var selectedLanguage by remember { mutableStateOf(initialLanguage) }
            var showMdmInfo by remember { mutableStateOf(false) }

            if (selectedLanguage == null) {
                LanguageSelectionScreen { language ->
                    saveAppLanguage(language)
                    selectedLanguage = language
                    languageSelected = true
                    triggerRuntimeWake(reason = "language:selected", force = true)
                }
                return@setContent
            }

            val language = selectedLanguage ?: AppLanguage.VI

            LaunchedEffect(st.unlockError) {
                val err = st.unlockError
                if (st.lockState == DeviceLockState.LOCKED && !err.isNullOrBlank()) {
                    Toast.makeText(this@LauncherActivity, err, Toast.LENGTH_SHORT).show()
                    maybeNotifyUnlockFailure(err)
                }
            }

            LaunchedEffect(
                st.lockState,
                st.config?.kioskMode,
                st.config?.disableStatusBar,
                st.adminLocked,
                st.commandScreenLocked,
                st.noProfileLocked
            ) {
                if (lastKnownLockState == DeviceLockState.LOCKED && st.lockState == DeviceLockState.ACTIVE) {
                    releaseUnlockedNonKioskContainment(st, policy, "state:unlock_transition")
                }
                lastKnownLockState = st.lockState
            }

            LaunchedEffect(
                st.setupState,
                st.applyingConfiguration,
                st.lockState,
                st.config?.kioskMode,
                st.config?.disableStatusBar,
                st.adminLocked,
                st.commandScreenLocked,
                st.noProfileLocked
            ) {
                releaseUnlockedNonKioskContainment(st, policy, "state:non_kiosk")
            }

            LaunchedEffect(st.lockState, st.setupState, st.applyingConfiguration, st.adminLocked, st.commandScreenLocked, st.noProfileLocked) {
                if (
                    st.lockState == DeviceLockState.LOCKED &&
                    st.setupState == SetupState.ENFORCEMENT_ACTIVE &&
                    !st.applyingConfiguration
                ) {
                    delay(750L)
                    val current = viewModel.state.value
                    if (
                        current.lockState == DeviceLockState.LOCKED &&
                        current.setupState == SetupState.ENFORCEMENT_ACTIVE &&
                        !current.applyingConfiguration
                    ) {
                        enforceLockedContainment("state:locked:delayed")
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    st.setupState != SetupState.ENFORCEMENT_ACTIVE -> {
                        ProvisioningScreen(
                            setupState = st.setupState,
                            steps = st.setupSteps,
                            loading = st.loading,
                            error = st.error,
                            rebootError = st.rebootError,
                            rebootRequested = st.rebootRequested,
                            deviceCode = st.deviceCode,
                            deviceDisplayName = st.deviceDisplayName,
                            language = language,
                            onLanguageChange = { nextLanguage ->
                                saveAppLanguage(nextLanguage)
                                selectedLanguage = nextLanguage
                                languageSelected = true
                            },
                            onRetry = { triggerRuntimeWake("provisioning:retry", force = true) },
                            onReboot = { viewModel.rebootAfterProvisioning(this@LauncherActivity) }
                        )
                    }

                    st.lockState == DeviceLockState.LOCKED -> {
                        BackHandler(enabled = true) { }
                        UnlockScreen(
                            language,
                            st.unlockError,
                            st.lockReason,
                            st.noProfileLocked,
                            st.adminLocked,
                            st.commandScreenLocked,
                            st.lockState,
                            st.lockContainmentStatus,
                            st.lockContainmentErrorCode,
                            st.loading,
                            st.unlockSubmitting,
                            { password ->
                                Log.i(
                                    "MDM_UNLOCK_UI",
                                    "activity onUnlock received passwordLength=${password.length} lockedState=${st.lockState.name} noProfileLocked=${st.noProfileLocked}"
                                )
                                viewModel.unlock(this@LauncherActivity, password)
                            }
                        )
                    }

                    st.lockState == DeviceLockState.ACTIVE -> {
                        if (showMdmInfo) {
                            MdmDeviceInfoScreen(
                                language = language,
                                deviceCode = st.deviceCode,
                                deviceDisplayName = st.deviceDisplayName,
                                ownerLabel = st.config?.userCode,
                                lastConfigSyncAtEpochMillis = st.lastConfigSyncAtEpochMillis,
                                isDeviceOwner = st.isDeviceOwner,
                                profileLinked = st.config != null,
                                adminLocked = st.adminLocked,
                                onLanguageChange = { nextLanguage ->
                                    saveAppLanguage(nextLanguage)
                                    selectedLanguage = nextLanguage
                                    languageSelected = true
                                },
                                onBack = { showMdmInfo = false }
                            )
                        } else {
                            LauncherScreen(
                                language = language,
                                apps = st.apps,
                                isDeviceOwner = st.isDeviceOwner,
                                deviceCode = st.deviceCode,
                                deviceDisplayName = st.deviceDisplayName,
                                onAppClick = { pkg ->
                                    Log.i(tag, "launcher app click package=$pkg")
                                    if (pkg == packageName) {
                                        showMdmInfo = true
                                        Log.i(tag, "self app click opened mdm info screen")
                                        return@LauncherScreen
                                    }

                                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                                    if (launchIntent == null) {
                                        Log.w(tag, "launcher app click blocked reason=not_launchable package=$pkg")
                                        Toast.makeText(
                                            this@LauncherActivity,
                                            if (language == AppLanguage.VI) "Ứng dụng chưa sẵn sàng để mở." else "App is not ready to open.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        if (st.isDeviceOwner) {
                                            policy.preparePackageForLaunch(pkg)
                                        }

                                        runCatching { startActivity(launchIntent) }
                                            .onSuccess { Log.i(tag, "launcher app launch requested package=$pkg") }
                                            .onFailure { err ->
                                                Log.w(tag, "launcher app launch failed package=$pkg", err)
                                                Toast.makeText(
                                                    this@LauncherActivity,
                                                    if (language == AppLanguage.VI) "Không thể mở ứng dụng này." else "Unable to open this app.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                    }
                                },
                                onClearPersistentHome = {
                                    if (st.isDeviceOwner) policy.clearPersistentPreferredActivities()
                                },
                                onApplyKioskHome = {
                                    triggerRuntimeWake("manualApplyKioskHome", force = true)
                                },
                                onExitLockTask = { runCatching { stopLockTask() } }
                            )
                        }
                    }

                    else -> {
                        LoadingOrErrorScreen(
                            loading = st.loading,
                            error = st.error,
                            deviceCode = st.deviceCode,
                            deviceDisplayName = st.deviceDisplayName,
                            onRetry = { triggerRuntimeWake("manualRetry", force = true) }
                        )
                    }
                }

                if (st.applyingConfiguration) {
                    ApplyingConfigurationOverlay()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i(
            tag,
            "onStart taskId=$taskId pid=${Process.myPid()} languageSelected=$languageSelected " +
                    "setupState=${viewModel.state.value.setupState} lockState=${viewModel.state.value.lockState}"
        )
        registerNetworkCallback()
    }

    override fun onResume() {
        super.onResume()
        recoveryGeneration += 1
        Log.i(
            tag,
            "onResume taskId=$taskId pid=${Process.myPid()} languageSelected=$languageSelected " +
                    "setupState=${viewModel.state.value.setupState} lockState=${viewModel.state.value.lockState}"
        )
        scheduleDelayedLockContainment("lifecycle:onResume")
        if (viewModel.state.value.lockState != DeviceLockState.LOCKED) {
            triggerRuntimeWake("ui:onResume")
        } else {
            Log.i(lockContainmentTag, "skip runtime wake reason=ui:onResume state=LOCKED")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(
            tag,
            "onWindowFocusChanged hasFocus=$hasFocus taskId=$taskId languageSelected=$languageSelected " +
                    "setupState=${viewModel.state.value.setupState} lockState=${viewModel.state.value.lockState}"
        )
        if (hasFocus) {
            scheduleDelayedLockContainment("lifecycle:onWindowFocusChanged")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(tag, "onNewIntent action=${intent.action} taskId=$taskId")
        if (viewModel.state.value.lockState == DeviceLockState.LOCKED) {
            enforceLockedContainment("lifecycle:onNewIntent")
        } else {
            val wakeReason = parseWakeReason(intent) ?: "ui:onNewIntent"
            triggerRuntimeWake(wakeReason)
        }
    }

    override fun onPause() {
        Log.i(
            tag,
            "onPause taskId=$taskId isFinishing=$isFinishing languageSelected=$languageSelected " +
                    "setupState=${viewModel.state.value.setupState} lockState=${viewModel.state.value.lockState}"
        )
        if (viewModel.state.value.lockState == DeviceLockState.LOCKED) {
            scheduleDelayedForegroundRecovery("lifecycle:onPause")
        }
        super.onPause()
    }

    override fun onStop() {
        Log.i(
            tag,
            "onStop taskId=$taskId isFinishing=$isFinishing languageSelected=$languageSelected " +
                    "setupState=${viewModel.state.value.setupState} lockState=${viewModel.state.value.lockState}"
        )
        if (viewModel.state.value.lockState == DeviceLockState.LOCKED) {
            scheduleDelayedForegroundRecovery("lifecycle:onStop")
        }
        unregisterNetworkCallback()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (viewModel.state.value.lockState == DeviceLockState.LOCKED) {
            scheduleDelayedForegroundRecovery("lifecycle:onUserLeaveHint")
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (viewModel.state.value.lockState == DeviceLockState.LOCKED) {
            Log.i(lockContainmentTag, "enforce reason=back_pressed_blocked")
            enforceLockedContainment("back_pressed")
            return
        }
        super.onBackPressed()
    }

    override fun finish() {
        Log.i(
            tag,
            "finish requested taskId=$taskId isFinishing=$isFinishing languageSelected=$languageSelected " +
                    "setupState=${viewModel.state.value.setupState} lockState=${viewModel.state.value.lockState}"
        )
        if (viewModel.state.value.lockState == DeviceLockState.LOCKED) {
            Log.i(lockContainmentTag, "enforce reason=finish_blocked")
            enforceLockedContainment("finish_blocked")
            return
        }
        super.finish()
    }


    private fun bringSelfToFrontOnce() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBringToFrontAtMs < 4_000L) {
            Log.i(lockContainmentTag, "bringSelfToFront skipped reason=cooldown")
            return
        }
        lastBringToFrontAtMs = now
        val launch = Intent(this, LauncherActivity::class.java).apply {
            action = ACTION_RUNTIME_WAKE
            putExtra(EXTRA_WAKE_REASON, "activity:bringSelfToFront")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching { startActivity(launch) }
            .onSuccess { Log.i(lockContainmentTag, "bringSelfToFront requested") }
            .onFailure { err -> Log.w(lockContainmentTag, "bringSelfToFront failed", err) }
    }

    private fun triggerRuntimeWake(reason: String, force: Boolean = false) {
        if (!languageSelected) {
            Log.i(tag, "runtime retrigger skipped reason=$reason languageSelected=false")
            return
        }
        Log.i(
            tag,
            "runtime retrigger reason=$reason force=$force setupState=${viewModel.state.value.setupState} " +
                    "lockState=${viewModel.state.value.lockState}"
        )
        viewModel.requestRuntimeWake(context = this, reason = reason, force = force)
    }

    private fun releaseUnlockedNonKioskContainment(
        st: LauncherUiState,
        policy: DevicePolicyHelper,
        reason: String
    ) {
        if (st.setupState != SetupState.ENFORCEMENT_ACTIVE) return
        if (st.applyingConfiguration) return
        if (st.lockState == DeviceLockState.LOCKED) return
        val config = st.config ?: return
        val effectiveKioskMode = config.kioskMode || st.lockOverlayActive
        val effectiveDisableStatusBar = config.disableStatusBar || st.lockOverlayActive

        Log.i(
            lockContainmentTag,
            "restore reason=$reason lockOverlayActive=${st.lockOverlayActive} lockOverlayReason=${st.lockOverlayReason} " +
                    "adminLocked=${st.adminLocked} commandScreenLocked=${st.commandScreenLocked} " +
                    "desiredKioskMode=${config.kioskMode} desiredDisableStatusBar=${config.disableStatusBar} " +
                    "effectiveKioskMode=$effectiveKioskMode effectiveDisableStatusBar=$effectiveDisableStatusBar"
        )

        if (effectiveDisableStatusBar) {
            policy.disableStatusBar(true)
            Log.i(tag, "setStatusBarDisabled(true) requested reason=$reason")
        } else {
            policy.disableStatusBar(false)
            Log.i(tag, "setStatusBarDisabled(false) requested reason=$reason")
            restoreUnlockedSystemBars(reason)
        }

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lockTaskModeState = runCatching {
            activityManager?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE
        }.getOrDefault(ActivityManager.LOCK_TASK_MODE_NONE)
        Log.i(
            tag,
            "release containment check reason=$reason desiredKioskMode=${config.kioskMode} desiredDisableStatusBar=${config.disableStatusBar} effectiveKioskMode=$effectiveKioskMode effectiveDisableStatusBar=$effectiveDisableStatusBar lockTaskModeState=$lockTaskModeState"
        )

        if (effectiveKioskMode) {
            policy.startLockTaskIfPermitted(this)
            return
        }

        if (lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { stopLockTask() }
                .onSuccess { Log.i(tag, "stopLockTask enforced reason=$reason modeBefore=$lockTaskModeState") }
                .onFailure { Log.w(tag, "stopLockTask enforced failed reason=$reason modeBefore=$lockTaskModeState", it) }
        }
    }

    private fun restoreUnlockedSystemBars(reason: String) {
        val decorView = window.decorView
        val flagsBefore = decorView.systemUiVisibility
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, decorView).show(WindowInsetsCompat.Type.statusBars())
        @Suppress("DEPRECATION")
        decorView.systemUiVisibility =
            flagsBefore and View.SYSTEM_UI_FLAG_FULLSCREEN.inv() and
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv() and
                    View.SYSTEM_UI_FLAG_IMMERSIVE.inv() and
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv() and
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN.inv() and
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION.inv()
        Log.i(
            lockContainmentTag,
            "systemUi restore reason=$reason flagsBefore=$flagsBefore flagsAfter=${decorView.systemUiVisibility}"
        )
    }

    private fun readAppLanguage(): AppLanguage? =
        AppLanguage.fromStorage(
            getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_APP_LANGUAGE, null)
        )

    private fun saveAppLanguage(language: AppLanguage) {
        getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LANGUAGE, language.storageValue)
            .apply()
    }

    private fun enforceLockedContainment(reason: String) {
        val st = viewModel.state.value
        if (st.lockState != DeviceLockState.LOCKED) return
        if (st.setupState != SetupState.ENFORCEMENT_ACTIVE) return
        val desiredKioskMode = st.config?.kioskMode == true
        val desiredDisableStatusBar = st.config?.disableStatusBar == true
        val effectiveKioskMode = desiredKioskMode || st.lockOverlayActive
        val effectiveDisableStatusBar = desiredDisableStatusBar || st.lockOverlayActive
        val policy = DevicePolicyHelper(this)
        Log.i(
            lockContainmentTag,
            "lockOverlayActive=${st.lockOverlayActive} lockOverlayReason=${st.lockOverlayReason} " +
                    "adminLocked=${st.adminLocked} commandScreenLocked=${st.commandScreenLocked} noProfileLocked=${st.noProfileLocked} " +
                    "desiredKioskMode=$desiredKioskMode desiredDisableStatusBar=$desiredDisableStatusBar " +
                    "effectiveKioskMode=$effectiveKioskMode effectiveDisableStatusBar=$effectiveDisableStatusBar reason=$reason"
        )
        if (effectiveDisableStatusBar) {
            policy.disableStatusBar(true)
            Log.i(tag, "setStatusBarDisabled(true) requested reason=$reason")
        }
        if (st.applyingConfiguration) {
            Log.i(lockContainmentTag, "enforce skip reason=policyInFlight source=$reason")
            return
        }
        if (lockContainmentInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLockTaskStartAtMs < 4_000L) {
            Log.i(lockContainmentTag, "enforce skip reason=lockTaskCooldown source=$reason")
            return
        }
        val resumedAndFocused =
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && hasWindowFocus()
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val systemLocked = runCatching {
            activityManager?.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_LOCKED
        }.getOrDefault(false)
        Log.i(
            lockContainmentTag,
            "activityState reason=$reason resumed=${lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)} hasFocus=${hasWindowFocus()}"
        )
        Log.i(lockContainmentTag, "enforce reason=$reason")

        // Avoid re-launch churn when already foregrounded; this can create onPause/onResume loops.
        if (!resumedAndFocused) {
            bringSelfToFrontOnce()
            return
        }

        if (systemLocked) {
            Log.i(lockContainmentTag, "skip reason=alreadyForegroundLocked")
            return
        }

        logTopActivityState("before:$reason")

        lockContainmentInFlight = true
        val outcome = try {
            lastLockTaskStartAtMs = SystemClock.elapsedRealtime()
            runCatching {
                policy.ensureStrictLockedContainment(activity = this)
            }.getOrElse { err ->
                Log.e(tag, "enforceLockedContainment failed reason=$reason", err)
                DevicePolicyHelper.LockContainmentOutcome(
                    status = "FAILED",
                    error = err.message ?: "Lock containment failed",
                    errorCode = "LOCK_TASK_NOT_ALLOWED"
                )
            }
        } finally {
            lockContainmentInFlight = false
        }

        viewModel.updateLockContainment(outcome.status, outcome.errorCode)
        Log.i(tag, "locked containment reason=$reason status=${outcome.status} errorCode=${outcome.errorCode}")
        logTopActivityState("after:$reason")
    }

    private fun scheduleDelayedLockContainment(reason: String) {
        mainHandler.postDelayed({
            val current = viewModel.state.value
            if (
                current.lockState == DeviceLockState.LOCKED &&
                current.setupState == SetupState.ENFORCEMENT_ACTIVE &&
                !current.applyingConfiguration
            ) {
                enforceLockedContainment("$reason:delayed")
            }
        }, 750L)
    }

    private fun scheduleDelayedForegroundRecovery(reason: String) {
        recoveryGeneration += 1
        val scheduledGeneration = recoveryGeneration
        val delaysMs = longArrayOf(300L, 800L)
        delaysMs.forEachIndexed { index, delayMs ->
            val attempt = index + 1
            mainHandler.postDelayed({
                if (scheduledGeneration != recoveryGeneration) return@postDelayed
                if (viewModel.state.value.lockState == DeviceLockState.LOCKED) {
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && hasWindowFocus()) {
                        return@postDelayed
                    }
                    Log.i(lockContainmentTag, "recovery reason=$reason attempt=$attempt")
                    bringSelfToFrontOnce()
                    enforceLockedContainment("$reason:recovery:$attempt")
                }
            }, delayMs)
        }
    }

    private fun logTopActivityState(stage: String) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
        val top = runCatching {
            am.appTasks.firstOrNull()?.taskInfo?.topActivity?.flattenToShortString()
        }.getOrNull()
        Log.i(lockContainmentTag, "topCheck stage=$stage top=$top")
    }

    override fun onDestroy() {
        recoveryGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        Log.w(
            tag,
            "onDestroy taskId=$taskId isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations " +
                    "languageSelected=$languageSelected setupState=${viewModel.state.value.setupState} lockState=${viewModel.state.value.lockState}"
        )
        super.onDestroy()
    }

    private fun maybeNotifyUnlockFailure(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                UNLOCK_ERROR_CHANNEL_ID,
                "Unlock Errors",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, UNLOCK_ERROR_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Unlock failed")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(UNLOCK_ERROR_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(tag, "unlock error notification failed", it) }
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
        const val EXTRA_WAKE_ATTEMPT = "extra_wake_attempt"
        private const val LANGUAGE_PREFS_NAME = "mdm_app_preferences"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val UNLOCK_ERROR_CHANNEL_ID = "unlock_error_channel"
        private const val UNLOCK_ERROR_NOTIFICATION_ID = 4001

        fun createRuntimeWakeIntent(context: Context, reason: String, attempt: Int? = null): Intent =
            Intent(context, LauncherActivity::class.java).apply {
                action = ACTION_RUNTIME_WAKE
                putExtra(EXTRA_WAKE_REASON, reason)
                if (attempt != null) {
                    putExtra(EXTRA_WAKE_ATTEMPT, attempt)
                }
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
    }
}

@androidx.compose.runtime.Composable
private fun ApplyingConfigurationOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = Color(0xEE101827),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .padding(20.dp)
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
        ) {
            Text(
                text = "Đang áp dụng hồ sơ cấu hình...",
                color = Color(0xFFF2F7FF),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}
