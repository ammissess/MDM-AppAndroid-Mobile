package com.example.mdmapplication.ui.launcher

import android.content.Context
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mdmapplication.data.remote.DeviceAckCommandRequest
import com.example.mdmapplication.data.remote.DeviceConfigResponse
import com.example.mdmapplication.data.remote.DeviceEventRequest
import com.example.mdmapplication.data.remote.DevicePollCommandsRequest
import com.example.mdmapplication.data.remote.DeviceRegisterRequest
import com.example.mdmapplication.data.remote.DeviceUnlockRequest
import com.example.mdmapplication.data.remote.LocationUpdateRequest
import com.example.mdmapplication.data.remote.MdmApi
import com.example.mdmapplication.data.remote.UsageBatchReportRequest
import com.example.mdmapplication.device.DevicePolicyHelper
import com.example.mdmapplication.model.LauncherApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

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
    private var cachedDeviceCode: String? = null

    private var locationJob: Job? = null
    private var usageBatchJob: Job? = null
    private var commandPollJob: Job? = null
    private var lockStateSyncJob: Job? = null

    fun refreshFromBackend(context: Context) {
        viewModelScope.launch {
            val deviceCode = getDeviceCode(context)
            cachedDeviceCode = deviceCode
            _state.value = _state.value.copy(loading = true, error = null, unlockError = null)

            try {
                val token = getOrRefreshToken(deviceCode)

                val registerResp = api.registerDevice(
                    token = token,
                    req = buildRegisterRequest(context, deviceCode)
                )

                when (registerResp.status) {
                    "LOCKED" -> {
                        stopLoops()
                        _state.value = _state.value.copy(
                            loading = false,
                            lockState = DeviceLockState.LOCKED,
                            config = null,
                            apps = emptyList()
                        )
                        _commandActions.tryEmit(LauncherCommandAction.BringMdmToFrontAndLock)
                    }

                    "ACTIVE" -> {
                        loadConfig(context)
                    }

                    else -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = "Trạng thái không xác định: ${registerResp.status}"
                        )
                    }
                }

                startLockStateSyncLoop(context)
            } catch (e: MdmApi.ApiException) {
                handleApiException(e, duringConfig = false)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.message ?: "Lỗi kết nối"
                )
            }
        }
    }

    fun unlock(context: Context, password: String) {
        val deviceCode = cachedDeviceCode ?: getDeviceCode(context).also { cachedDeviceCode = it }

        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, unlockError = null, error = null)
            try {
                val token = getOrRefreshToken(deviceCode)
                val resp = api.unlockDevice(
                    token = token,
                    req = DeviceUnlockRequest(deviceCode = deviceCode, password = password)
                )

                if (resp.status == "ACTIVE") {
                    loadConfig(context)
                } else {
                    _state.value = _state.value.copy(
                        loading = false,
                        lockState = DeviceLockState.LOCKED,
                        unlockError = "Mật khẩu không chính xác."
                    )
                }
            } catch (e: MdmApi.ApiException) {
                when {
                    e.httpCode == 423 || e.backendStatus == "LOCKED" -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            lockState = DeviceLockState.LOCKED,
                            unlockError = e.message
                        )
                        _commandActions.tryEmit(LauncherCommandAction.BringMdmToFrontAndLock)
                    }

                    isDeviceCodeMismatch(e) -> {
                        clearToken()
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

    private fun startLockStateSyncLoop(context: Context) {
        if (lockStateSyncJob?.isActive == true) return
        lockStateSyncJob = viewModelScope.launch {
            while (true) {
                val deviceCode = cachedDeviceCode
                if (deviceCode != null) {
                    runCatching {
                        val token = getOrRefreshToken(deviceCode)
                        api.registerDevice(token, buildRegisterRequest(context, deviceCode))
                    }.onSuccess { register ->
                        if (register.status == "LOCKED" && _state.value.lockState != DeviceLockState.LOCKED) {
                            stopLoops()
                            _state.value = _state.value.copy(
                                loading = false,
                                lockState = DeviceLockState.LOCKED,
                                config = null,
                                apps = emptyList()
                            )
                            _commandActions.tryEmit(LauncherCommandAction.BringMdmToFrontAndLock)
                        }
                    }.onFailure { err ->
                        val apiErr = err as? MdmApi.ApiException
                        if (apiErr != null && (apiErr.httpCode == 423 || apiErr.backendStatus == "LOCKED")) {
                            stopLoops()
                            _state.value = _state.value.copy(
                                loading = false,
                                lockState = DeviceLockState.LOCKED,
                                config = null,
                                apps = emptyList()
                            )
                            _commandActions.tryEmit(LauncherCommandAction.BringMdmToFrontAndLock)
                        }
                    }
                }
                delay(7_000L)
            }
        }
    }

    private suspend fun loadConfig(context: Context) {
        val deviceCode = cachedDeviceCode ?: return
        try {
            val token = getOrRefreshToken(deviceCode)
            val previousConfig = _state.value.config

            val config = api.fetchCurrentConfig(token = token, deviceCode = deviceCode)

            val policy = DevicePolicyHelper(context)
            if (policy.isDeviceOwner()) {
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
                policy.enforceAllowedPackages(
                    launcherPackage = context.packageName,
                    allowedApps = config.allowedApps,
                    kioskMode = config.kioskMode,
                    allowSettingsIfExplicitlyWhitelisted = true
                )
            }

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

            startLocationLoop()
            startUsageBatchLoop(context)
            startCommandPollLoop(context)
            startLockStateSyncLoop(context)
        } catch (e: MdmApi.ApiException) {
            handleApiException(e, duringConfig = true)
        } catch (t: Throwable) {
            _state.value = _state.value.copy(
                loading = false,
                error = t.message ?: "Load config thất bại"
            )
        }
    }

    private fun startLocationLoop() {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            while (true) {
                val deviceCode = cachedDeviceCode
                if (deviceCode != null) {
                    try {
                        val token = getOrRefreshToken(deviceCode)
                        api.updateLocation(
                            token = token,
                            req = LocationUpdateRequest(
                                deviceCode = deviceCode,
                                latitude = 0.0,
                                longitude = 0.0,
                                accuracyMeters = 0.0
                            )
                        )
                    } catch (e: MdmApi.ApiException) {
                        if (isDeviceCodeMismatch(e)) clearToken()
                    } catch (_: Throwable) {
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
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun startCommandPollLoop(context: Context) {
        if (commandPollJob?.isActive == true) return
        commandPollJob = viewModelScope.launch {
            while (true) {
                val deviceCode = cachedDeviceCode
                if (deviceCode != null) {
                    try {
                        val token = getOrRefreshToken(deviceCode)
                        val pollResp = api.pollCommands(
                            token = token,
                            req = DevicePollCommandsRequest(deviceCode = deviceCode, limit = 5)
                        )

                        for (cmd in pollResp.commands) {
                            val result = executeCommand(context, cmd.type)
                            runCatching {
                                api.ackCommand(
                                    token = token,
                                    req = DeviceAckCommandRequest(
                                        deviceCode = deviceCode,
                                        commandId = cmd.id,
                                        leaseToken = cmd.leaseToken,
                                        result = if (result.success) "SUCCESS" else "FAILED",
                                        error = result.error,
                                        errorCode = result.errorCode,
                                        output = result.output
                                    )
                                )
                            }
                        }
                    } catch (e: MdmApi.ApiException) {
                        if (isDeviceCodeMismatch(e)) clearToken()
                    } catch (_: Throwable) {
                    }
                }
                delay(15_000L)
            }
        }
    }

    private data class CommandExecResult(
        val success: Boolean,
        val error: String? = null,
        val errorCode: String? = null,
        val output: String? = null
    )

    private suspend fun executeCommand(context: Context, type: String): CommandExecResult {
        return when (type.lowercase()) {
            "refresh_config", "sync_config" -> {
                runCatching { loadConfig(context) }
                    .fold(
                        onSuccess = { CommandExecResult(success = true, output = "Config refreshed") },
                        onFailure = {
                            CommandExecResult(
                                success = false,
                                error = it.message ?: "Refresh config failed",
                                errorCode = "REFRESH_CONFIG_FAILED"
                            )
                        }
                    )
            }

            "lock_screen" -> {
                _state.value = _state.value.copy(
                    lockState = DeviceLockState.LOCKED,
                    config = null,
                    apps = emptyList()
                )
                _commandActions.tryEmit(LauncherCommandAction.BringMdmToFrontAndLock)
                CommandExecResult(success = true, output = "Lock screen requested")
            }

            else -> {
                CommandExecResult(
                    success = false,
                    error = "Unsupported command type: $type",
                    errorCode = "UNSUPPORTED_COMMAND"
                )
            }
        }
    }

    private suspend fun getOrRefreshToken(deviceCode: String): String {
        val existing = cachedToken
        if (existing != null) return existing

        val newToken = api.login(
            username = deviceUser,
            password = devicePass,
            deviceCode = deviceCode
        ).token
        cachedToken = newToken
        return newToken
    }

    private fun clearToken() {
        cachedToken = null
    }

    private fun stopLoops() {
        locationJob?.cancel()
        usageBatchJob?.cancel()
        commandPollJob?.cancel()
        locationJob = null
        usageBatchJob = null
        commandPollJob = null
    }

    private fun getDeviceCode(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"

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
        val pm = context.packageManager
        val resolvedApps = mutableListOf<LauncherApp>()
        val unresolved = mutableListOf<String>()

        packages.forEach { pkg ->
            val app = runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                LauncherApp(
                    packageName = pkg,
                    label = pm.getApplicationLabel(info).toString(),
                    icon = pm.getApplicationIcon(info)
                )
            }.onFailure { err ->
                Log.w(tag, "Allowed package cannot be resolved: $pkg", err)
            }.getOrNull()

            if (app != null) {
                resolvedApps += app
            } else {
                unresolved += pkg
            }
        }

        if (resolvedApps.isEmpty() && packages.isNotEmpty()) {
            val first = unresolved.firstOrNull() ?: packages.first()
            val msg = "Allowed package not installed or still hidden: $first"
            _state.value = _state.value.copy(error = msg)
            Log.w(tag, "All allowed packages unresolved. allowed=$packages unresolved=$unresolved")
        }

        return resolvedApps
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

    private fun handleApiException(e: MdmApi.ApiException, duringConfig: Boolean) {
        when {
            e.httpCode == 423 || e.backendStatus == "LOCKED" -> {
                stopLoops()
                _state.value = _state.value.copy(
                    loading = false,
                    lockState = DeviceLockState.LOCKED,
                    config = null,
                    apps = emptyList(),
                    error = if (duringConfig) "Thiết bị đang bị khóa." else null,
                    unlockError = if (duringConfig) null else e.message
                )
                _commandActions.tryEmit(LauncherCommandAction.BringMdmToFrontAndLock)
            }

            isDeviceCodeMismatch(e) -> {
                clearToken()
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Device session mismatch, đã reset token. Vui lòng thử lại."
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