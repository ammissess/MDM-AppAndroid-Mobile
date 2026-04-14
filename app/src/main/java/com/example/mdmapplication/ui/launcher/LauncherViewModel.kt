package com.example.mdmapplication.ui.launcher

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mdmapplication.BuildConfig
import com.example.mdmapplication.data.remote.DeviceAckCommandRequest
import com.example.mdmapplication.data.remote.DeviceAppInventoryItem
import com.example.mdmapplication.data.remote.DeviceAppInventoryReportRequest
import com.example.mdmapplication.data.remote.DeviceConfigResponse
import com.example.mdmapplication.data.remote.DeviceEventRequest
import com.example.mdmapplication.data.remote.DevicePolicyStateReportRequest
import com.example.mdmapplication.data.remote.DevicePollCommandsRequest
import com.example.mdmapplication.data.remote.DeviceRegisterRequest
import com.example.mdmapplication.data.remote.DeviceUnlockRequest
import com.example.mdmapplication.data.remote.LocationUpdateRequest
import com.example.mdmapplication.data.remote.MdmApi
import com.example.mdmapplication.data.remote.UsageBatchReportRequest
import com.example.mdmapplication.device.DevicePolicyHelper
import com.example.mdmapplication.device.MyDeviceAdminReceiver
import com.example.mdmapplication.model.LauncherApp
import com.example.mdmapplication.util.readBatteryInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

enum class DeviceLockState { UNKNOWN, LOCKED, ACTIVE }

data class LauncherUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val lockState: DeviceLockState = DeviceLockState.UNKNOWN,
    val unlockError: String? = null,
    val config: DeviceConfigResponse? = null,
    val apps: List<LauncherApp> = emptyList()
)

sealed class LauncherCommandAction {
    object TryLockScreen : LauncherCommandAction()
    object BringMdmToFrontAndLock : LauncherCommandAction()
    object AllowedAppsUpdated : LauncherCommandAction()
}

class LauncherViewModel : ViewModel() {

    private val _state = MutableStateFlow(LauncherUiState())
    val state: StateFlow<LauncherUiState> = _state

    private val _commandActions = MutableSharedFlow<LauncherCommandAction>(extraBufferCapacity = 16)
    val commandActions = _commandActions.asSharedFlow()

    private val api = MdmApi(baseUrl = "http://10.0.2.2:8080")
    private val deviceUser = "device"
    private val devicePass = "device123"
    private val tag = "LauncherViewModel"

    private var cachedToken: String? = null
    private var cachedTokenDeviceCode: String? = null
    private var cachedDeviceCode: String? = null

    private var locationJob: Job? = null
    private var usageBatchJob: Job? = null
    private var commandPollJob: Job? = null
    private var stateSnapshotJob: Job? = null
    private val configLoadMutex = Mutex()
    private var refreshAttemptSeq: Long = 0
    private val supportedCommandTypes = setOf("lock_screen", "refresh_config", "sync_config")

    fun refreshFromBackend(context: Context) {
        viewModelScope.launch {
            val deviceCode = resolveCurrentDeviceCode(context, reason = "refreshFromBackend")
            val refreshId = ++refreshAttemptSeq
            Log.i(tag, "refreshFromBackend start refreshId=$refreshId deviceCode=$deviceCode")
            _state.value = _state.value.copy(loading = true, error = null, unlockError = null)

            try {
                val token = getOrRefreshToken(deviceCode)

                val registerResp = api.registerDevice(
                    token = token,
                    req = buildRegisterRequest(context, deviceCode)
                )
                Log.i(tag, "register result refreshId=$refreshId status=${registerResp.status}")
                runCatching {
                    api.sendEvent(
                        token = token,
                        deviceCode = deviceCode,
                        req = DeviceEventRequest(type = "launcher_refresh", payload = "{\"refreshId\":$refreshId}")
                    )
                }.onSuccess {
                    Log.i(tag, "event upload success type=launcher_refresh refreshId=$refreshId deviceCode=$deviceCode")
                }.onFailure { eventErr ->
                    val apiErr = eventErr as? MdmApi.ApiException
                    if (apiErr != null && isDeviceCodeMismatch(apiErr)) {
                        clearIdentitySession()
                    }
                    Log.w(tag, "event upload failure type=launcher_refresh refreshId=$refreshId deviceCode=$deviceCode", eventErr)
                }
                runCatching { reportStateSnapshotNow(context, deviceCode, token) }
                val wasLocked = _state.value.lockState == DeviceLockState.LOCKED

                when (registerResp.status) {
                    "LOCKED" -> {
                        stopTelemetryLoops()
                        _state.value = _state.value.copy(
                            loading = false,
                            lockState = DeviceLockState.LOCKED,
                            config = null,
                            apps = emptyList()
                        )
                        startCommandPollLoop(context)
                        // Avoid repeated foreground relaunch while already locked.
                        if (!wasLocked) {
                            _commandActions.tryEmit(LauncherCommandAction.BringMdmToFrontAndLock)
                        }
                    }

                    "ACTIVE" -> {
                        Log.i(tag, "refreshFromBackend ACTIVE refreshId=$refreshId -> loadConfig")
                        loadConfig(context, source = "refresh")
                    }

                    else -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = "Trạng thái không xác định: ${registerResp.status}"
                        )
                    }
                }
            } catch (e: MdmApi.ApiException) {
                Log.e(
                    tag,
                    "refreshFromBackend api failure refreshId=$refreshId code=${e.httpCode} backendCode=${e.backendCode} message=${e.message}",
                    e
                )
                handleApiException(e, duringConfig = false, context = context)
            } catch (t: Throwable) {
                Log.e(tag, "refreshFromBackend failure refreshId=$refreshId", t)
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.message ?: "Lỗi kết nối"
                )
            }
        }
    }

    fun unlock(context: Context, password: String) {
        val deviceCode = resolveCurrentDeviceCode(context, reason = "unlock")
        Log.i(tag, "unlock requested deviceCode=$deviceCode")

        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, unlockError = null, error = null)
            try {
                val token = getOrRefreshToken(deviceCode)
                val resp = api.unlockDevice(
                    token = token,
                    req = DeviceUnlockRequest(deviceCode = deviceCode, password = password)
                )

                if (resp.status == "ACTIVE") {
                    Log.i(tag, "unlock response status=ACTIVE deviceCode=$deviceCode")
                    val loadResult = loadConfig(context, source = "unlock")
                    Log.i(
                        tag,
                        "unlock loadConfig result success=${loadResult.success} errorCode=${loadResult.errorCode} error=${loadResult.error}"
                    )
                    if (!loadResult.success && _state.value.lockState != DeviceLockState.LOCKED) {
                        _state.value = _state.value.copy(
                            loading = false,
                            unlockError = loadResult.error ?: "Đồng bộ cấu hình thất bại sau mở khóa"
                        )
                    }
                } else {
                    Log.w(tag, "unlock response status=${resp.status} deviceCode=$deviceCode")
                    _state.value = _state.value.copy(
                        loading = false,
                        lockState = DeviceLockState.LOCKED,
                        unlockError = "Mật khẩu không chính xác."
                    )
                }
            } catch (e: MdmApi.ApiException) {
                Log.w(
                    tag,
                    "unlock api failure deviceCode=$deviceCode code=${e.httpCode} backendCode=${e.backendCode} message=${e.message}",
                    e
                )
                when {
                    isDeviceLocked(e) -> {
                        stopTelemetryLoops()
                        _state.value = _state.value.copy(
                            loading = false,
                            lockState = DeviceLockState.LOCKED,
                            unlockError = e.message
                        )
                        startCommandPollLoop(context)
                        _commandActions.tryEmit(LauncherCommandAction.BringMdmToFrontAndLock)
                    }

                    isDeviceCodeMismatch(e) -> {
                        clearIdentitySession()
                        _state.value = _state.value.copy(
                            loading = false,
                            unlockError = "Device session mismatch, vui lòng thử lại."
                        )
                    }

                    else -> {
                        _state.value = _state.value.copy(loading = false, unlockError = e.message)
                    }
                }
            } catch (t: Throwable) {
                Log.e(tag, "unlock failure deviceCode=$deviceCode", t)
                _state.value = _state.value.copy(loading = false, unlockError = t.message ?: "Lỗi mở khóa")
            }
        }
    }

    fun sendEvent(type: String, payload: String = "{}") {
        val deviceCode = cachedDeviceCode ?: return
        viewModelScope.launch {
            try {
                val token = getOrRefreshToken(deviceCode)
                api.sendEvent(
                    token = token,
                    deviceCode = deviceCode,
                    req = DeviceEventRequest(type = type, payload = payload)
                )
            } catch (e: MdmApi.ApiException) {
                if (isDeviceCodeMismatch(e)) clearToken()
            } catch (_: Throwable) {
            }
        }
    }

    fun rebuildVisibleApps(context: Context) {
        val cfg = _state.value.config ?: return
        val rebuilt = loadAllowedApps(context, cfg.allowedApps)
        val oldPackages = _state.value.apps.map { it.packageName }
        val newPackages = rebuilt.map { it.packageName }

        _state.value = _state.value.copy(apps = rebuilt)

        if (oldPackages != newPackages) {
            _commandActions.tryEmit(LauncherCommandAction.AllowedAppsUpdated)
        }
    }

    private data class ConfigLoadResult(
        val success: Boolean,
        val error: String? = null,
        val errorCode: String? = null
    )

    private data class PolicyApplyResult(
        val success: Boolean,
        val error: String? = null,
        val errorCode: String? = null
    )

    private enum class AllowedAppReasonCode {
        LAUNCHABLE,
        INSTALLED_NON_LAUNCHABLE,
        NOT_INSTALLED,
        HIDDEN,
        SUSPENDED,
        PACKAGE_VISIBILITY_BLOCKED
    }

    private enum class PackageProbeStatus {
        PRESENT,
        NOT_FOUND,
        SECURITY_EXCEPTION,
        UNKNOWN_ERROR
    }

    private data class PackageProbeResult(
        val applicationInfo: ApplicationInfo? = null,
        val status: PackageProbeStatus
    )

    private data class AllowedAppResolution(
        val packageName: String,
        val launcherApp: LauncherApp? = null,
        val exists: Boolean?,
        val hasLaunchIntent: Boolean,
        val hidden: Boolean?,
        val suspended: Boolean?,
        val reasonCode: AllowedAppReasonCode
    )

    private suspend fun loadConfig(context: Context, source: String): ConfigLoadResult {
        return configLoadMutex.withLock {
            val deviceCode = resolveCurrentDeviceCode(context, reason = "loadConfig")
            Log.i(tag, "loadConfig enter source=$source deviceCode=$deviceCode")
            try {
                val token = getOrRefreshToken(deviceCode)
                val previousConfig = _state.value.config

                val config = api.fetchCurrentConfig(token = token, deviceCode = deviceCode)
                val appliedConfigHash = buildAppliedConfigHashOrNull(config)

                val policyResult = applyPolicyAndReport(
                    context = context,
                    deviceCode = deviceCode,
                    token = token,
                    config = config,
                    appliedConfigHash = appliedConfigHash
                )

                val apps = loadAllowedApps(context, config.allowedApps)

                val changed = previousConfig?.allowedApps != config.allowedApps ||
                        previousConfig?.configVersionEpochMillis != config.configVersionEpochMillis

                _state.value = _state.value.copy(
                    loading = false,
                    lockState = DeviceLockState.ACTIVE,
                    config = config,
                    apps = apps,
                    error = null,
                    unlockError = null
                )

                if (changed) {
                    _commandActions.tryEmit(LauncherCommandAction.AllowedAppsUpdated)
                }

                startLocationLoop(context)
                startUsageBatchLoop(context)
                startCommandPollLoop(context)
                startStateSnapshotLoop(context)
                runCatching { reportStateSnapshotNow(context, deviceCode, token) }
                runCatching { reportAppInventoryNow(context, deviceCode, token) }
                Log.i(
                    tag,
                    "loadConfig exit success source=$source deviceCode=$deviceCode configVersion=${config.configVersionEpochMillis} commandPollActive=${commandPollJob?.isActive == true}"
                )

                if (!policyResult.success && source.startsWith("command:")) {
                    Log.w(
                        tag,
                        "loadConfig policy apply failed for command source=$source deviceCode=$deviceCode errorCode=${policyResult.errorCode}"
                    )
                    ConfigLoadResult(
                        success = false,
                        error = policyResult.error ?: "Policy apply failed",
                        errorCode = policyResult.errorCode ?: "POLICY_APPLY_FAILED"
                    )
                } else {
                    if (!policyResult.success) {
                        Log.w(
                            tag,
                            "loadConfig policy apply failed but lifecycle continues source=$source deviceCode=$deviceCode errorCode=${policyResult.errorCode}"
                        )
                    }
                    ConfigLoadResult(success = true)
                }
            } catch (e: MdmApi.ApiException) {
                Log.e(
                    tag,
                    "loadConfig api failure source=$source deviceCode=$deviceCode code=${e.httpCode} backendCode=${e.backendCode} message=${e.message}",
                    e
                )
                handleApiException(e, duringConfig = true, context = context)
                ConfigLoadResult(
                    success = false,
                    error = e.message,
                    errorCode = e.backendCode ?: "HTTP_${e.httpCode}"
                )
            } catch (t: Throwable) {
                Log.e(tag, "loadConfig failure source=$source deviceCode=$deviceCode", t)
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.message ?: "Load config thất bại"
                )
                ConfigLoadResult(
                    success = false,
                    error = t.message ?: "Load config thất bại",
                    errorCode = "CONFIG_SYNC_FAILED"
                )
            } finally {
                Log.i(tag, "loadConfig exit source=$source deviceCode=$deviceCode")
            }
        }
    }

    private suspend fun applyPolicyAndReport(
        context: Context,
        deviceCode: String,
        token: String,
        config: DeviceConfigResponse,
        appliedConfigHash: String?
    ): PolicyApplyResult {
        val policy = DevicePolicyHelper(context)
        val policyReportedAt = System.currentTimeMillis()

        if (!policy.isDeviceOwner()) {
            reportPolicyStateNow(
                token = token,
                req = DevicePolicyStateReportRequest(
                    deviceCode = deviceCode,
                    policyApplyStatus = "FAILED",
                    policyApplyError = "Device is not owner, policy cannot be applied",
                    policyApplyErrorCode = "POLICY_NOT_DEVICE_OWNER",
                    policyAppliedAtEpochMillis = policyReportedAt
                )
            )
            return PolicyApplyResult(
                success = false,
                error = "Device is not owner, policy cannot be applied",
                errorCode = "POLICY_NOT_DEVICE_OWNER"
            )
        }

        try {
            Log.i(tag, "applyFromServerConfig enter deviceCode=$deviceCode configVersion=${config.configVersionEpochMillis}")
            policy.applyFromServerConfig(
                launcherPackage = context.packageName,
                allowedApps = config.allowedApps,
                kioskMode = config.kioskMode,
                disableStatusBar = config.disableStatusBar,
                blockUninstall = config.blockUninstall,
                disableWifi = config.disableWifi,
                disableBluetooth = config.disableBluetooth,
                disableCamera = config.disableCamera
            )
            Log.i(tag, "enforceAllowedPackages enter deviceCode=$deviceCode configVersion=${config.configVersionEpochMillis}")
            policy.enforceAllowedPackages(
                launcherPackage = context.packageName,
                allowedApps = config.allowedApps,
                kioskMode = config.kioskMode,
                allowSettingsIfExplicitlyWhitelisted = true
            )

            reportPolicyStateNow(
                token = token,
                req = DevicePolicyStateReportRequest(
                    deviceCode = deviceCode,
                    appliedConfigVersionEpochMillis = config.configVersionEpochMillis,
                    appliedConfigHash = appliedConfigHash,
                    policyApplyStatus = "SUCCESS",
                    policyAppliedAtEpochMillis = policyReportedAt
                )
            )
            return PolicyApplyResult(success = true)
        } catch (applyErr: Throwable) {
            runCatching {
                reportPolicyStateNow(
                    token = token,
                    req = DevicePolicyStateReportRequest(
                        deviceCode = deviceCode,
                        policyApplyStatus = "FAILED",
                        policyApplyError = applyErr.message ?: "Policy apply failed",
                        policyApplyErrorCode = "POLICY_APPLY_FAILED",
                        policyAppliedAtEpochMillis = policyReportedAt
                    )
                )
            }
            return PolicyApplyResult(
                success = false,
                error = applyErr.message ?: "Policy apply failed",
                errorCode = "POLICY_APPLY_FAILED"
            )
        }
    }

    private fun startLocationLoop(context: Context) {
        Log.i(tag, "startLocationLoop called active=${locationJob?.isActive == true}")
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            Log.i(tag, "location loop started")
            while (true) {
                Log.i(tag, "location loop tick cachedDeviceCode=$cachedDeviceCode")
                val deviceCode = cachedDeviceCode
                if (deviceCode != null) {
                    try {
                        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val hasFineLocation = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        val hasCoarseLocation = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                        val gps = if (hasFineLocation || hasCoarseLocation) {
                            try {
                                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            } catch (_: SecurityException) {
                                null
                            }
                        } else {
                            null
                        }
                         Log.i(tag, "location/system gps=$gps")

                         val req = LocationUpdateRequest(
                            deviceCode = deviceCode,
                            latitude = gps?.latitude ?: 0.0,
                            longitude = gps?.longitude ?: 0.0,
                            accuracyMeters = gps?.accuracy?.toDouble() ?: 0.0
                        )
                        Log.i(tag, "location/send lat=${req.latitude} lon=${req.longitude} acc=${req.accuracyMeters}")

                        val token = getOrRefreshToken(deviceCode)
                        api.updateLocation(token = token, req = req)
                    } catch (e: MdmApi.ApiException) {
                        if (isDeviceCodeMismatch(e)) clearToken()
                    } catch (t: Throwable) {
                        Log.e(tag, "location loop unexpected failure", t)
                    }
                }
                delay(60_000L)
            }
        }
    }

    private fun startUsageBatchLoop(context: Context) {
        if (usageBatchJob?.isActive == true) return
        usageBatchJob = viewModelScope.launch {
            while (true) {
                delay(5 * 60_000L)

                val deviceCode = cachedDeviceCode ?: continue
                try {
                    val endMs = System.currentTimeMillis()
                    val startMs = endMs - 5 * 60_000L
                    val usageList = collectAppUsage(context, startMs, endMs)
                    if (usageList.isEmpty()) continue

                    val token = getOrRefreshToken(deviceCode)
                    val req = UsageBatchReportRequest(
                        deviceCode = deviceCode,
                        items = usageList.map {
                            UsageBatchReportRequest.UsageItem(
                                packageName = it.packageName,
                                startedAtEpochMillis = it.startMs,
                                endedAtEpochMillis = it.endMs,
                                durationMs = it.durationMs
                            )
                        }
                    )
                    api.reportUsageBatch(token = token, req = req)
                } catch (e: MdmApi.ApiException) {
                    if (isDeviceCodeMismatch(e)) clearToken()
                } catch (t: Throwable) {
                    Log.e(tag, "usageBatch unexpected error", t)
                }
            }
        }
    }

    private fun startCommandPollLoop(context: Context) {
        if (commandPollJob?.isActive == true) {
            Log.i(tag, "startCommandPollLoop entered but already active")
            return
        }
        Log.i(tag, "startCommandPollLoop entered")
        commandPollJob = viewModelScope.launch {
            var pollAttempt = 0L
            while (true) {
                val deviceCode = cachedDeviceCode
                if (deviceCode != null) {
                    try {
                        pollAttempt += 1
                        Log.i(tag, "poll attempt=$pollAttempt deviceCode=$deviceCode")
                        val token = getOrRefreshToken(deviceCode)
                        runCatching { reportStateSnapshotNow(context, deviceCode, token) }
                        val pollResp = api.pollCommands(
                            token = token,
                            req = DevicePollCommandsRequest(deviceCode = deviceCode, limit = 5)
                        )
                        Log.i(tag, "poll success attempt=$pollAttempt commandCount=${pollResp.commands.size}")

                        for (cmd in pollResp.commands) {
                            Log.i(
                                tag,
                                "command polled commandId=${cmd.id} type=${cmd.type} leaseToken=${cmd.leaseToken}"
                            )
                            Log.i(
                                tag,
                                "command trace begin commandId=${cmd.id} type=${cmd.type} leaseToken=${cmd.leaseToken} deviceCode=$deviceCode"
                            )

                            val result = if (cmd.type.trim().lowercase() == "lock_screen") {
                                val policy = DevicePolicyHelper(context)
                                val isOwnerNow = isDeviceOwnerNow(context)
                                val isOwnerFromHelper = policy.isDeviceOwner()
                                Log.i(
                                    tag,
                                    "command trace lock_screen ownerCheck commandId=${cmd.id} dpm=$isOwnerNow helper=$isOwnerFromHelper"
                                )
                                if (!isOwnerNow || !isOwnerFromHelper) {
                                    CommandExecResult(
                                        success = false,
                                        error = "Device is not owner, cannot enforce lock containment",
                                        errorCode = "POLICY_NOT_DEVICE_OWNER"
                                    )
                                } else {
                                    Log.i(
                                        tag,
                                        "command trace before execute commandId=${cmd.id} type=${cmd.type} leaseToken=${cmd.leaseToken}"
                                    )
                                    executeCommand(context, cmd.type, commandId = cmd.id, leaseToken = cmd.leaseToken)
                                }
                            } else {
                                Log.i(
                                    tag,
                                    "command trace before execute commandId=${cmd.id} type=${cmd.type} leaseToken=${cmd.leaseToken}"
                                )
                                executeCommand(context, cmd.type, commandId = cmd.id, leaseToken = cmd.leaseToken)
                            }

                            Log.i(
                                tag,
                                "command trace result commandId=${cmd.id} type=${cmd.type} success=${result.success} errorCode=${result.errorCode} error=${result.error} output=${result.output}"
                            )

                            val ackResult = if (result.success) "SUCCESS" else "FAILED"
                            runCatching {
                                val ackToken = getOrRefreshToken(deviceCode)
                                Log.i(
                                    tag,
                                    "ack payload commandId=${cmd.id} leaseToken=${cmd.leaseToken} result=$ackResult errorCode=${result.errorCode} error=${result.error} output=${result.output}"
                                )
                                api.ackCommand(
                                    token = ackToken,
                                    req = DeviceAckCommandRequest(
                                        deviceCode = deviceCode,
                                        commandId = cmd.id,
                                        leaseToken = cmd.leaseToken,
                                        result = ackResult,
                                        error = result.error,
                                        errorCode = result.errorCode,
                                        output = result.output
                                    )
                                )
                            }.onSuccess {
                                Log.i(
                                    tag,
                                    "ack success commandId=${cmd.id} type=${cmd.type} result=${if (result.success) "SUCCESS" else "FAILED"}"
                                )
                            }.onFailure { ackErr ->
                                Log.e(
                                    tag,
                                    "ack failure commandId=${cmd.id} type=${cmd.type} leaseToken=${cmd.leaseToken}",
                                    ackErr
                                )
                            }
                        }
                    } catch (e: MdmApi.ApiException) {
                        Log.e(
                            tag,
                            "poll failure api attempt=$pollAttempt deviceCode=$deviceCode code=${e.httpCode} backendCode=${e.backendCode} message=${e.message}",
                            e
                        )
                        if (isDeviceCodeMismatch(e)) clearToken()
                    } catch (ce: CancellationException) {
                        Log.i(tag, "poll loop cancelled attempt=$pollAttempt reason=${ce.message}")
                        throw ce
                    } catch (t: Throwable) {
                        Log.e(tag, "poll failure unexpected attempt=$pollAttempt deviceCode=$deviceCode", t)
                    }
                } else {
                    Log.w(tag, "poll skipped because deviceCode is null")
                }
                delay(15_000L)
            }
        }
        commandPollJob?.invokeOnCompletion { cause ->
            if (cause == null) {
                Log.w(tag, "commandPollJob completed without exception")
            } else {
                Log.w(tag, "commandPollJob completed with cause=${cause.message}", cause)
            }
        }
        Log.i(tag, "commandPollJob started active=${commandPollJob?.isActive == true}")
    }

    private fun startStateSnapshotLoop(context: Context) {
        if (stateSnapshotJob?.isActive == true) return
        stateSnapshotJob = viewModelScope.launch {
            while (true) {
                val deviceCode = cachedDeviceCode
                if (deviceCode != null) {
                    runCatching {
                        val token = getOrRefreshToken(deviceCode)
                        reportStateSnapshotNow(context, deviceCode, token)
                    }
                }
                delay(30_000L)
            }
        }
    }

    private suspend fun reportStateSnapshotNow(context: Context, deviceCode: String, token: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePkg = runCatching {
            context.packageManager.resolveActivity(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        val isLauncherDefault = homePkg == context.packageName

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lockMode = runCatching { am.lockTaskModeState }.getOrDefault(ActivityManager.LOCK_TASK_MODE_NONE)
        val isKioskRunning = lockMode != ActivityManager.LOCK_TASK_MODE_NONE

        val reportedAtEpochMillis = System.currentTimeMillis()
        val battery = context.readBatteryInfo()
        val wifiEnabled = runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled
        }.getOrNull()
        val networkType = readNetworkType(context)
        val uptimeMs = android.os.SystemClock.elapsedRealtime().coerceAtLeast(0L)
        val lastBootAtEpochMillis = (reportedAtEpochMillis - uptimeMs).takeIf { it >= 0L }
        val (ramFreeMb, ramTotalMb) = readRamInfoMb(context)
        val (storageFreeBytes, storageTotalBytes) = readStorageInfoBytes(context)
        val agentVersion = BuildConfig.VERSION_NAME.takeIf { it.isNotBlank() }
        val agentBuildCode = BuildConfig.VERSION_CODE.takeIf { it > 0 }

        try {
            api.reportStateSnapshot(
                token = token,
                req = com.example.mdmapplication.data.remote.DeviceStateSnapshotRequest(
                    deviceCode = deviceCode,
                    reportedAtEpochMillis = reportedAtEpochMillis,
                    batteryLevel = battery.levelPercent,
                    isCharging = battery.isCharging,
                    wifiEnabled = wifiEnabled,
                    networkType = networkType,
                    foregroundPackage = context.packageName,
                    agentVersion = agentVersion,
                    agentBuildCode = agentBuildCode,
                    currentLauncherPackage = context.packageName,
                    uptimeMs = uptimeMs,
                    abi = Build.SUPPORTED_ABIS.firstOrNull(),
                    buildFingerprint = Build.FINGERPRINT,
                    isDeviceOwner = isDeviceOwner,
                    isLauncherDefault = isLauncherDefault,
                    isKioskRunning = isKioskRunning,
                    storageFreeBytes = storageFreeBytes,
                    storageTotalBytes = storageTotalBytes,
                    ramFreeMb = ramFreeMb,
                    ramTotalMb = ramTotalMb,
                    lastBootAtEpochMillis = lastBootAtEpochMillis
                )
            )
        } catch (e: MdmApi.ApiException) {
            if (isDeviceCodeMismatch(e)) clearIdentitySession()
            throw e
        }
        Log.i(
            tag,
            "state snapshot sent deviceCode=$deviceCode isDeviceOwner=$isDeviceOwner isLauncherDefault=$isLauncherDefault isKioskRunning=$isKioskRunning"
        )
    }

    private suspend fun reportPolicyStateNow(token: String, req: DevicePolicyStateReportRequest) {
        try {
            api.reportPolicyState(token = token, req = req)
            Log.i(tag, "policy state sent deviceCode=${req.deviceCode} status=${req.policyApplyStatus}")
        } catch (e: MdmApi.ApiException) {
            if (isDeviceCodeMismatch(e)) clearIdentitySession()
            throw e
        }
    }

    private suspend fun reportAppInventoryNow(context: Context, deviceCode: String, token: String) {
        val items = collectInstalledAppInventory(context)
        Log.i(tag, "inventory report start deviceCode=$deviceCode itemCount=${items.size}")

        try {
            val response = api.reportAppInventory(
                token = token,
                req = DeviceAppInventoryReportRequest(
                    deviceCode = deviceCode,
                    reportedAtEpochMillis = System.currentTimeMillis(),
                    items = items
                )
            )
            Log.i(
                tag,
                "inventory report success deviceCode=$deviceCode itemCount=${items.size} upserted=${response.upserted}"
            )
        } catch (e: MdmApi.ApiException) {
            if (isDeviceCodeMismatch(e)) clearIdentitySession()
            Log.w(
                tag,
                "inventory report fail deviceCode=$deviceCode itemCount=${items.size} code=${e.httpCode} backendCode=${e.backendCode} message=${e.message}"
            )
        } catch (t: Throwable) {
            Log.w(tag, "inventory report fail deviceCode=$deviceCode itemCount=${items.size}", t)
        }
    }

    private fun readNetworkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return "OFFLINE"
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return "UNKNOWN"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "UNKNOWN"
            else -> "OFFLINE"
        }
    }

    private fun readRamInfoMb(context: Context): Pair<Int?, Int?> {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val free = (info.availMem / (1024L * 1024L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val total = (info.totalMem / (1024L * 1024L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            Pair(free, total)
        }.getOrElse { Pair(null, null) }
    }

    private fun readStorageInfoBytes(context: Context): Pair<Long?, Long?> {
        return runCatching {
            val statFs = StatFs(context.filesDir.absolutePath)
            Pair(statFs.availableBytes, statFs.totalBytes)
        }.getOrElse { Pair(null, null) }
    }

    private fun buildAppliedConfigHashOrNull(config: DeviceConfigResponse): String? {
        // Mirror exactly backend ProfileRepository.buildDesiredConfigFingerprint().
        // If this mirrored computation cannot be completed, do not send appliedConfigHash.
        return runCatching {
            val canonicalJson = buildCanonicalConfigJson(config)
            sha256Hex(canonicalJson)
        }.getOrNull()
    }

    private fun collectInstalledAppInventory(context: Context): List<DeviceAppInventoryItem> {
        val pm = context.packageManager
        val launchablePackages = collectLaunchablePackages(pm)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
        val isOwner = dpm.isDeviceOwnerApp(context.packageName)

        return getInstalledApplicationsCompat(pm)
            .asSequence()
            .map { appInfo ->
                val packageInfo = getPackageInfoCompat(pm, appInfo.packageName)
                DeviceAppInventoryItem(
                    packageName = appInfo.packageName,
                    appName = readApplicationLabel(pm, appInfo),
                    versionName = packageInfo?.versionName?.takeIf { !it.isNullOrBlank() },
                    versionCode = packageInfo?.let(::readVersionCode),
                    isSystemApp = readSystemAppState(appInfo),
                    hasLauncherActivity = appInfo.packageName in launchablePackages,
                    installed = readInstalledState(appInfo),
                    disabled = readDisabledState(pm, appInfo),
                    hidden = readHiddenStateForInventory(dpm, admin, isOwner, appInfo.packageName),
                    suspended = readSuspendedState(appInfo)
                )
            }
            .sortedBy { it.packageName }
            .toList()
    }

    private fun collectLaunchablePackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrElse {
            Log.w(tag, "collectLaunchablePackages failed", it)
            emptySet()
        }
    }

    private fun getInstalledApplicationsCompat(pm: PackageManager): List<ApplicationInfo> {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(flags)
        }
    }

    private fun getPackageInfoCompat(pm: PackageManager, packageName: String): PackageInfo? {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS.toLong()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flags.toInt())
            }
        }.getOrNull()
    }

    private fun readApplicationLabel(pm: PackageManager, appInfo: ApplicationInfo): String? {
        return runCatching {
            pm.getApplicationLabel(appInfo).toString().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun readVersionCode(packageInfo: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    private fun readSystemAppState(appInfo: ApplicationInfo): Boolean {
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return (appInfo.flags and systemFlags) != 0
    }

    private fun readInstalledState(appInfo: ApplicationInfo): Boolean {
        return (appInfo.flags and ApplicationInfo.FLAG_INSTALLED) != 0
    }

    private fun readDisabledState(pm: PackageManager, appInfo: ApplicationInfo): Boolean? {
        return runCatching {
            when (pm.getApplicationEnabledSetting(appInfo.packageName)) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true

                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> false
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> !appInfo.enabled
                else -> !appInfo.enabled
            }
        }.getOrElse { !appInfo.enabled }
    }

    private fun readHiddenStateForInventory(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        isOwner: Boolean,
        packageName: String
    ): Boolean? {
        if (!isOwner) return null
        return runCatching { dpm.isApplicationHidden(admin, packageName) }.getOrNull()
    }

    private fun buildCanonicalConfigJson(config: DeviceConfigResponse): String {
        val normalizedAllowedApps = config.allowedApps
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()

        val appsJson = normalizedAllowedApps.joinToString(",") { jsonString(it) }
        return "{" +
                "\"userCode\":${jsonString(config.userCode.trim())}," +
                "\"allowedApps\":[${appsJson}]," +
                "\"disableWifi\":${config.disableWifi}," +
                "\"disableBluetooth\":${config.disableBluetooth}," +
                "\"disableCamera\":${config.disableCamera}," +
                "\"disableStatusBar\":${config.disableStatusBar}," +
                "\"kioskMode\":${config.kioskMode}," +
                "\"blockUninstall\":${config.blockUninstall}" +
                "}"
    }

    private fun jsonString(value: String): String = Json.encodeToString(String.serializer(), value)

    private fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun onCleared() {
        Log.w(
            tag,
            "onCleared called; jobs active location=${locationJob?.isActive == true}, usage=${usageBatchJob?.isActive == true}, poll=${commandPollJob?.isActive == true}"
        )
        super.onCleared()
    }

    private data class CommandExecResult(
        val success: Boolean,
        val error: String? = null,
        val errorCode: String? = null,
        val output: String? = null
    )

    private suspend fun executeCommand(
        context: Context,
        type: String,
        commandId: String? = null,
        leaseToken: String? = null
    ): CommandExecResult {
        val normalizedType = type.trim().lowercase()
        if (normalizedType !in supportedCommandTypes) {
            return CommandExecResult(
                success = false,
                error = "Unsupported command type: $type",
                errorCode = "UNSUPPORTED_COMMAND"
            )
        }

        return when (normalizedType) {
            "refresh_config", "sync_config" -> {
                val result = loadConfig(context, source = "command:$normalizedType")
                if (result.success) {
                    CommandExecResult(success = true, output = "Config refreshed and policy reported")
                } else {
                    CommandExecResult(
                        success = false,
                        error = result.error ?: "Refresh config failed",
                        errorCode = result.errorCode ?: "REFRESH_CONFIG_FAILED"
                    )
                }
            }

            "lock_screen" -> {
                val policy = DevicePolicyHelper(context)
                val isOwnerFromHelper = policy.isDeviceOwner()
                val isOwnerFromDpm = isDeviceOwnerNow(context)
                Log.i(
                    tag,
                    "executeCommand lock_screen ownerCheck commandId=$commandId leaseToken=$leaseToken helper=$isOwnerFromHelper dpm=$isOwnerFromDpm"
                )
                if (!isOwnerFromHelper || !isOwnerFromDpm) {
                    CommandExecResult(
                        success = false,
                        error = "Device is not owner, cannot enforce lock containment",
                        errorCode = "POLICY_NOT_DEVICE_OWNER"
                    )
                } else try {
                    policy.applyLockedContainment(context.packageName)
                    stopTelemetryLoops()
                    _state.value = _state.value.copy(
                        loading = false,
                        lockState = DeviceLockState.LOCKED,
                        config = null,
                        apps = emptyList()
                    )
                    startCommandPollLoop(context)
                    _commandActions.tryEmit(LauncherCommandAction.BringMdmToFrontAndLock)
                    CommandExecResult(success = true, output = "Lock containment applied")
                } catch (t: Throwable) {
                    CommandExecResult(
                        success = false,
                        error = t.message ?: "Lock containment failed",
                        errorCode = "LOCK_CONTAINMENT_FAILED"
                    )
                }
            }

            else -> CommandExecResult(success = false, error = "Unsupported command type: $type", errorCode = "UNSUPPORTED_COMMAND")
        }
    }

    private fun isDeviceOwnerNow(context: Context): Boolean {
        return runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName)
        }.getOrDefault(false)
    }

    private suspend fun getOrRefreshToken(deviceCode: String): String {
        val existing = cachedToken
        if (existing != null && cachedTokenDeviceCode == deviceCode) return existing

        if (existing != null && cachedTokenDeviceCode != deviceCode) {
            Log.w(
                tag,
                "token deviceCode mismatch: tokenBoundTo=$cachedTokenDeviceCode requested=$deviceCode -> re-login"
            )
            clearToken()
        }

        val newToken = api.login(
            username = deviceUser,
            password = devicePass,
            deviceCode = deviceCode
        ).token
        cachedToken = newToken
        cachedTokenDeviceCode = deviceCode
        return newToken
    }

    private fun clearToken() {
        cachedToken = null
        cachedTokenDeviceCode = null
    }

    private fun stopTelemetryLoops() {
        locationJob?.cancel()
        usageBatchJob?.cancel()
        stateSnapshotJob?.cancel()
        locationJob = null
        usageBatchJob = null
        stateSnapshotJob = null
    }

    private fun clearIdentitySession() {
        clearToken()
        cachedDeviceCode = null
    }

    private fun resolveCurrentDeviceCode(context: Context, reason: String): String {
        val current = getDeviceCode(context)
        val previous = cachedDeviceCode
        if (previous != null && previous != current) {
            Log.w(tag, "deviceCode changed reason=$reason old=$previous new=$current -> clear session")
            clearToken()
        }
        cachedDeviceCode = current
        return current
    }

    private fun getDeviceCode(context: Context): String {
        val fromDefault = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val fromDeviceProtected = runCatching {
            Settings.Secure.getString(
                context.createDeviceProtectedStorageContext().contentResolver,
                Settings.Secure.ANDROID_ID
            )
        }.getOrNull()

        val selected = when {
            !fromDeviceProtected.isNullOrBlank() -> fromDeviceProtected
            !fromDefault.isNullOrBlank() -> fromDefault
            else -> "UNKNOWN"
        }

        Log.i(
            tag,
            "getDeviceCode source default=$fromDefault deviceProtected=$fromDeviceProtected selected=$selected"
        )
        return selected
    }

    private fun buildRegisterRequest(context: Context, deviceCode: String): DeviceRegisterRequest {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiEnabled = wm.isWifiEnabled

        return DeviceRegisterRequest(
            deviceCode = deviceCode,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            imei = "",
            serial = "",
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            wifiEnabled = wifiEnabled
        )
    }

    private fun loadAllowedApps(context: Context, packages: List<String>): List<LauncherApp> {
        val resolutions = packages.map { resolveAllowedApp(context, it.trim()) }

        resolutions.forEach(::logAllowedAppResolution)

        val resolvedApps = resolutions.mapNotNull { it.launcherApp }
        val excluded = resolutions
            .filter { it.launcherApp == null }
            .map { "${it.packageName}:${it.reasonCode.name}" }

        if (resolvedApps.isEmpty() && packages.isNotEmpty()) {
            val firstExcluded = resolutions.firstOrNull { it.launcherApp == null }
            val msg = if (firstExcluded != null) {
                "Allowed package unavailable: ${firstExcluded.packageName} (${firstExcluded.reasonCode.name})"
            } else {
                "Allowed apps unavailable"
            }
            _state.value = _state.value.copy(error = msg)
            Log.w(tag, "All allowed packages excluded. allowed=$packages excluded=$excluded")
        }

        return resolvedApps
    }

    private fun resolveAllowedApp(context: Context, packageName: String): AllowedAppResolution {
        if (packageName.isBlank()) {
            return AllowedAppResolution(
                packageName = packageName,
                exists = false,
                hasLaunchIntent = false,
                hidden = false,
                suspended = false,
                reasonCode = AllowedAppReasonCode.NOT_INSTALLED
            )
        }

        val pm = context.packageManager
        val launchIntent = runCatching { pm.getLaunchIntentForPackage(packageName) }
            .onFailure { err ->
                Log.w(tag, "getLaunchIntentForPackage failed package=$packageName", err)
            }
            .getOrNull()
        val launcherResolve = queryLauncherActivity(pm, packageName)
        val launchIntentAppInfo = runCatching { launchIntent?.resolveActivityInfo(pm, 0)?.applicationInfo }
            .onFailure { err ->
                Log.w(tag, "resolveActivityInfo failed package=$packageName", err)
            }
            .getOrNull()
        val probe = probePackage(pm, packageName)
        val packageConfirmedAbsent =
            probe.status == PackageProbeStatus.NOT_FOUND && launcherResolve == null && launchIntent == null
        val hidden = if (packageConfirmedAbsent) {
            false
        } else {
            readHiddenState(context, packageName)
        }
        val appInfo = launcherResolve?.activityInfo?.applicationInfo ?: launchIntentAppInfo ?: probe.applicationInfo
        val suspended = if (packageConfirmedAbsent) {
            false
        } else {
            readSuspendedState(appInfo)
        }
        val exists = inferExists(
            hidden = hidden,
            launcherResolve = launcherResolve,
            launchIntent = launchIntent,
            probe = probe
        )

        val reasonCode = when {
            packageConfirmedAbsent -> AllowedAppReasonCode.NOT_INSTALLED
            hidden == true -> AllowedAppReasonCode.HIDDEN
            suspended == true -> AllowedAppReasonCode.SUSPENDED
            launcherResolve != null || launchIntent != null -> AllowedAppReasonCode.LAUNCHABLE
            probe.status == PackageProbeStatus.PRESENT -> AllowedAppReasonCode.INSTALLED_NON_LAUNCHABLE
            else -> AllowedAppReasonCode.PACKAGE_VISIBILITY_BLOCKED
        }

        val launcherApp = if (reasonCode == AllowedAppReasonCode.LAUNCHABLE) {
            buildLauncherApp(pm, packageName, launcherResolve, appInfo)
        } else {
            null
        }

        if (launcherApp == null && reasonCode == AllowedAppReasonCode.LAUNCHABLE) {
            return AllowedAppResolution(
                packageName = packageName,
                exists = exists,
                hasLaunchIntent = launchIntent != null,
                hidden = hidden,
                suspended = suspended,
                reasonCode = AllowedAppReasonCode.PACKAGE_VISIBILITY_BLOCKED
            )
        }

        return AllowedAppResolution(
            packageName = packageName,
            launcherApp = launcherApp,
            exists = exists,
            hasLaunchIntent = launchIntent != null,
            hidden = hidden,
            suspended = suspended,
            reasonCode = reasonCode
        )
    }

    private fun queryLauncherActivity(pm: PackageManager, packageName: String): ResolveInfo? {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)

        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .firstOrNull { it.activityInfo?.exported == true && it.activityInfo?.enabled == true }
        }.onFailure { err ->
            Log.w(tag, "queryIntentActivities failed package=$packageName", err)
        }.getOrNull()
    }

    private fun probePackage(pm: PackageManager, packageName: String): PackageProbeResult {
        return try {
            val info = pm.getApplicationInfo(
                packageName,
                PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
            )
            PackageProbeResult(applicationInfo = info, status = PackageProbeStatus.PRESENT)
        } catch (e: PackageManager.NameNotFoundException) {
            PackageProbeResult(status = PackageProbeStatus.NOT_FOUND)
        } catch (e: SecurityException) {
            Log.w(tag, "getApplicationInfo blocked package=$packageName", e)
            PackageProbeResult(status = PackageProbeStatus.SECURITY_EXCEPTION)
        } catch (t: Throwable) {
            Log.w(tag, "getApplicationInfo failed package=$packageName", t)
            PackageProbeResult(status = PackageProbeStatus.UNKNOWN_ERROR)
        }
    }

    private fun readHiddenState(context: Context, packageName: String): Boolean? {
        val policy = DevicePolicyHelper(context)
        if (!policy.isDeviceOwner()) return null

        return runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
            dpm.isApplicationHidden(admin, packageName)
        }.onFailure { err ->
            Log.w(tag, "isApplicationHidden failed package=$packageName", err)
        }.getOrNull()
    }

    private fun readSuspendedState(applicationInfo: ApplicationInfo?): Boolean? {
        if (applicationInfo == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return (applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
    }

    private fun inferExists(
        hidden: Boolean?,
        launcherResolve: ResolveInfo?,
        launchIntent: Intent?,
        probe: PackageProbeResult
    ): Boolean? {
        return when {
            probe.status == PackageProbeStatus.NOT_FOUND -> false
            launcherResolve != null || launchIntent != null -> true
            probe.status == PackageProbeStatus.PRESENT -> true
            hidden == true -> true
            else -> null
        }
    }

    private fun buildLauncherApp(
        pm: PackageManager,
        packageName: String,
        launcherResolve: ResolveInfo?,
        applicationInfo: ApplicationInfo?
    ): LauncherApp? {
        val label = when {
            launcherResolve != null -> launcherResolve.loadLabel(pm)?.toString()
            applicationInfo != null -> pm.getApplicationLabel(applicationInfo).toString()
            else -> null
        }?.takeIf { it.isNotBlank() } ?: packageName

        val icon = when {
            launcherResolve != null -> launcherResolve.loadIcon(pm)
            applicationInfo != null -> pm.getApplicationIcon(applicationInfo)
            else -> null
        } ?: return null

        return LauncherApp(
            packageName = packageName,
            label = label,
            icon = icon
        )
    }

    private fun logAllowedAppResolution(resolution: AllowedAppResolution) {
        val logLine = "allowedApps resolution " +
                "packageName=${resolution.packageName} " +
                "exists=${triStateLabel(resolution.exists)} " +
                "launchIntent=${resolution.hasLaunchIntent} " +
                "hidden=${triStateLabel(resolution.hidden)} " +
                "suspended=${triStateLabel(resolution.suspended)} " +
                "reasonCode=${resolution.reasonCode.name}"

        if (resolution.launcherApp != null) {
            Log.i(tag, logLine)
        } else {
            Log.w(tag, logLine)
        }
    }

    private fun triStateLabel(value: Boolean?): String = when (value) {
        true -> "true"
        false -> "false"
        null -> "unknown"
    }

    private data class AppUsageEntry(
        val packageName: String,
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long
    )

    private fun collectAppUsage(context: Context, startMs: Long, endMs: Long): List<AppUsageEntry> {
        return runCatching {
            val usm =
                context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_BEST,
                startMs,
                endMs
            ).filter { it.totalTimeInForeground > 0 }
                .map { s ->
                    AppUsageEntry(
                        packageName = s.packageName,
                        startMs = s.firstTimeStamp,
                        endMs = s.lastTimeStamp,
                        durationMs = s.totalTimeInForeground
                    )
                }
        }.getOrDefault(emptyList())
    }

    private fun isDeviceCodeMismatch(e: MdmApi.ApiException): Boolean {
        return e.httpCode == 409 && e.backendCode == "DEVICE_CODE_MISMATCH"
    }

    private fun isDeviceLocked(e: MdmApi.ApiException): Boolean {
        val msg = e.message.lowercase()
        return e.httpCode == 423 || e.backendCode == "DEVICE_LOCKED" || msg.contains("locked")
    }

    private fun handleApiException(e: MdmApi.ApiException, duringConfig: Boolean, context: Context? = null) {
        when {
            isDeviceLocked(e) -> {
                stopTelemetryLoops()
                _state.value = _state.value.copy(
                    loading = false,
                    lockState = DeviceLockState.LOCKED,
                    config = null,
                    apps = emptyList(),
                    error = if (duringConfig) "Thiết bị đang bị khóa." else null,
                    unlockError = if (duringConfig) null else e.message
                )
                context?.let { startCommandPollLoop(it) }
                _commandActions.tryEmit(LauncherCommandAction.BringMdmToFrontAndLock)
            }

            isDeviceCodeMismatch(e) -> {
                clearIdentitySession()
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Device session mismatch, đã reset token/session. Vui lòng thử lại."
                )
            }

            else -> {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message
                )
            }
        }
    }
}
