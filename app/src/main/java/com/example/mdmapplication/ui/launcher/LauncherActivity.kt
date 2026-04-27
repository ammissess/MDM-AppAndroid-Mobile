package com.example.mdmapplication.ui.launcher

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
        Log.i(tag, "onCreate savedInstanceState=${savedInstanceState != null} taskId=$taskId")
        val initialLanguage = readAppLanguage()
        languageSelected = initialLanguage != null

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

        val wakeReason = parseWakeReason(intent) ?: "app_launch:onCreate"
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
                                policy.isDeviceOwner()
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

            LaunchedEffect(st.lockState) {
                if (lastKnownLockState == DeviceLockState.LOCKED && st.lockState == DeviceLockState.ACTIVE) {
                    runCatching { stopLockTask() }
                        .onFailure { Log.w(tag, "stopLockTask on unlock transition failed", it) }
                }
                lastKnownLockState = st.lockState
            }

            LaunchedEffect(st.lockState, st.setupState, st.applyingConfiguration) {
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
                            st.unlockError,
                            st.lockReason,
                            st.noProfileLocked,
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
                        LauncherScreen(
                            apps = st.apps,
                            isDeviceOwner = st.isDeviceOwner,
                            onAppClick = { pkg ->
                                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
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

                    else -> {
                        LoadingOrErrorScreen(
                            loading = st.loading,
                            error = st.error,
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
        registerNetworkCallback()
    }

    override fun onResume() {
        super.onResume()
        recoveryGeneration += 1
        Log.i(tag, "onResume taskId=$taskId")
        scheduleDelayedLockContainment("lifecycle:onResume")
        if (viewModel.state.value.lockState != DeviceLockState.LOCKED) {
            triggerRuntimeWake("ui:onResume")
        } else {
            Log.i(lockContainmentTag, "skip runtime wake reason=ui:onResume state=LOCKED")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
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
        if (viewModel.state.value.lockState == DeviceLockState.LOCKED) {
            scheduleDelayedForegroundRecovery("lifecycle:onPause")
        }
        super.onPause()
    }

    override fun onStop() {
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
        if (!languageSelected) {
            Log.i(tag, "runtime retrigger skipped reason=$reason languageSelected=false")
            return
        }
        Log.i(tag, "runtime retrigger reason=$reason force=$force")
        viewModel.requestRuntimeWake(context = this, reason = reason, force = force)
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

        val policy = DevicePolicyHelper(this)
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
        Log.w(tag, "onDestroy isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations")
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
        private const val LANGUAGE_PREFS_NAME = "mdm_app_preferences"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val UNLOCK_ERROR_CHANNEL_ID = "unlock_error_channel"
        private const val UNLOCK_ERROR_NOTIFICATION_ID = 4001

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
