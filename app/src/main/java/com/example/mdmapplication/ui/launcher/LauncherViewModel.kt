package com.example.mdmapplication.ui.launcher

import android.content.Context
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mdmapplication.data.remote.*
import com.example.mdmapplication.model.LauncherApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

class LauncherViewModel : ViewModel() {

    private var locationLoopStarted = false
    private var usageLoopStarted = false
    private var commandLoopStarted = false

    //cờ để loop load config sau khi unlock thành công, để áp dụng policy mới ngay lập tức
    private var configLoopStarted = false

    //luu appcontext de goi loadConfig tu command loop
    private var appContext: Context? = null

    private val _state = MutableStateFlow(LauncherUiState())
    val state: StateFlow<LauncherUiState> = _state

    // ĐỔI IP NÀY theo môi trường:
    // Emulator: http://10.0.2.2:8080
    // Thiết bị thật: http://<IP_LAN_PC>:8080
    private val api = MdmApi(baseUrl = "http://10.0.2.2:8080")

    private val deviceUser = "device"
    private val devicePass = "device123"
    private val userCode = "TEST123"  // phải khớp seed backend

    private var cachedToken: String? = null
    private var cachedDeviceCode: String? = null

    //Hàm syncConfig để gọi lại loadConfig sau khi unlock thành công, đảm bảo policy mới được áp dụng ngay lập tức mà không phải đợi đến lần refresh tiếp theo
    private fun startConfigSyncLoop(token: String, deviceCode: String, context: Context) {
        if (configLoopStarted) return
        configLoopStarted = true

        viewModelScope.launch {
            while (true) {
                delay(10_000L)
                try {
                    val config = api.fetchConfig(token, userCode, deviceCode)
                    val apps = loadAllowedApps(context, config.allowedApps)

                    _state.value = _state.value.copy(
                        loading = false,
                        lockState = DeviceLockState.ACTIVE,
                        config = config,
                        apps = apps,
                        error = null
                    )
                } catch (_: Throwable) {
                    // giữ launcher chạy ổn định
                }
            }
        }
    }

    // ===== BƯỚC 1: Login + Register + Kiểm tra trạng thái =====
    fun refreshFromBackend(context: Context) {
        appContext = context.applicationContext
        viewModelScope.launch {
            try {
/*                _state.value = _state.value.copy(loading = true, error = null)

                val token = getOrRefreshToken()
                val deviceCode = getDeviceCode(context)
                cachedDeviceCode = deviceCode

                val registerResp = api.registerDevice(
                    token = token,
                    req = buildRegisterRequest(context, deviceCode)
                )*/

                //dao thu tu lay deviceCode len truoc
                _state.value = _state.value.copy(loading = true, error = null)

                val deviceCode = getDeviceCode(context)
                cachedDeviceCode = deviceCode

                val token = getOrRefreshToken(deviceCode)

                val registerResp = api.registerDevice(
                    token = token,
                    req = buildRegisterRequest(context, deviceCode)
                )

                // Map theo status trả về từ DTO mới
                when (registerResp.status) {
                    "LOCKED" -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            lockState = DeviceLockState.LOCKED
                        )
                    }
                    "ACTIVE" -> {
                        loadConfig(token, deviceCode, context)
                    }
                    else -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = "Trạng thái không xác định: ${registerResp.status}"
                        )
                    }
                }
            } catch (e: MdmApi.ApiException) {
                handleApiException(e)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(loading = false, error = t.message ?: "Lỗi kết nối")
            }
        }
    }

    // ===== BƯỚC 2: Unlock (khi user nhập mật khẩu) =====
    fun unlock(context: Context, password: String) {
        appContext = context.applicationContext
        val deviceCode = cachedDeviceCode ?: return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(loading = true, unlockError = null)
                val token = getOrRefreshToken()

                val resp = api.unlockDevice(
                    token = token,
                    req = DeviceUnlockRequest(deviceCode = deviceCode, password = password)
                )

                if (resp.status == "ACTIVE") {
                    loadConfig(token, deviceCode, context)
                } else {
                    _state.value = _state.value.copy(
                        loading = false,
                        unlockError = "Mật khẩu không chính xác."
                    )
                }
            } catch (e: MdmApi.ApiException) {
                if (e.httpCode == 423) {
                    _state.value = _state.value.copy(
                        loading = false,
                        lockState = DeviceLockState.LOCKED,
                        unlockError = e.message // Hiển thị thông báo khóa từ server
                    )
                } else {
                    _state.value = _state.value.copy(loading = false, unlockError = e.message)
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(loading = false, unlockError = t.message)
            }
        }
    }

    // ===== BƯỚC 3: Load config sau khi ACTIVE =====

    //lấy deviceCode
    private suspend fun getOrRefreshToken(deviceCode: String): String {
        return cachedToken ?: api.login(deviceUser, devicePass, deviceCode).token.also { cachedToken = it }
    }
    private suspend fun loadConfig(token: String, deviceCode: String, context: Context) {
        try {
            val config = api.fetchConfig(token, userCode, deviceCode)
            val apps = loadAllowedApps(context, config.allowedApps)

            _state.value = LauncherUiState(
                loading = false,
                lockState = DeviceLockState.ACTIVE,
                config = config,
                apps = apps
            )

            startLocationLoop(token, deviceCode)

            startUsageReportLoop(token, deviceCode, context)
            //goi command loop sau, de dam bao chi active moi co quyen nhan lenh
            startCommandLoop(context,token, deviceCode)
            startConfigSyncLoop(token, deviceCode, context)
        } catch (e: MdmApi.ApiException) {
            if (e.httpCode == 423) {
                _state.value = _state.value.copy(
                    loading = false,
                    lockState = DeviceLockState.LOCKED,
                    error = "Thiết bị đã bị khóa từ hệ thống."
                )
            } else {
                throw e // Để catch bên ngoài xử lý
            }
        }
    }

    // ===== LOCATION LOOP: mỗi 60 giây =====
    private fun startLocationLoop(token: String, deviceCode: String) {
        if(locationLoopStarted) return
        locationLoopStarted = true
        viewModelScope.launch {
            while (true) {
                try {
                    // Dùng last known location - cần permission ACCESS_COARSE_LOCATION
                    // Nếu muốn GPS thật, thêm LocationManager/FusedLocationProvider
                    // Đây là placeholder - bạn thay bằng location thật
                    api.updateLocation(
                        token = token,
                        req = LocationUpdateRequest(
                            deviceCode = deviceCode,
                            latitude = 0.0,
                            longitude = 0.0,
                            accuracyMeters = 0.0
                        )
                    )
                } catch (_: Throwable) { /* silent fail */ }
                delay(60_000L)
            }
        }
    }

    // ===== USAGE LOOP: mỗi 5 phút gửi batch =====
/*    private fun startUsageReportLoop(token: String, deviceCode: String, context: Context) {
        viewModelScope.launch {
            while (true) {
                delay(5 * 60_000L)
                try {
                    val endMs = System.currentTimeMillis()
                    val startMs = endMs - 5 * 60_000L

                    val usageList = collectAppUsage(context, startMs, endMs)
                    for (usage in usageList) {
                        api.reportUsage(
                            token = token,
                            req = UsageReportRequest(
                                deviceCode = deviceCode,
                                packageName = usage.packageName,
                                startedAtEpochMillis = usage.startMs,
                                endedAtEpochMillis = usage.endMs,
                                durationMs = usage.durationMs
                            )
                        )
                    }
                } catch (_: Throwable) { *//* silent fail *//* }
            }
        }
    }*/

    private fun startUsageReportLoop(token: String, deviceCode: String, context: Context) {
        if (usageLoopStarted) return
        usageLoopStarted= true
        viewModelScope.launch {
            while (true) {
                delay(5 * 60_000L)
                try {
                    val endMs = System.currentTimeMillis()
                    val startMs = endMs - 5 * 60_000L

                    val usageList = collectAppUsage(context, startMs, endMs)
                    if (usageList.isEmpty()) continue

                    val items = usageList.map {
                        UsageBatchReportRequest.UsageItem(
                            packageName = it.packageName,
                            startedAtEpochMillis = it.startMs,
                            endedAtEpochMillis = it.endMs,
                            durationMs = it.durationMs
                        )
                    }

                    api.reportUsageBatch(
                        token = token,
                        req = UsageBatchReportRequest(deviceCode = deviceCode, items = items)
                    )
                } catch (_: Throwable) { /* silent fail */ }
            }
        }
    }



    //====== POLL COMMANDS LOOP: mỗi 30 giây =====

    private data class CommandExecResult(
        val result: String,           // "SUCCESS" | "FAILED"
        val error: String? = null,
        val output: String? = null,
    )

    private fun startCommandLoop(context: Context, token : String, deviceCode: String) {
        if(commandLoopStarted) return
        commandLoopStarted = true
        viewModelScope.launch {
            while (true) {
                delay(10_000L)
                try {
                    val resp = api.pollCommands(token, DevicePollCommandsRequest(deviceCode, limit = 3))
                    for (cmd in resp.commands) {
                        val exec = executeCommand(cmd)
                        api.ackCommand(
                            token,
                            DeviceAckCommandRequest(
                                deviceCode = deviceCode,
                                commandId = cmd.id,
                                leaseToken = cmd.leaseToken,
                                result = exec.result,
                                error = exec.error,
                                output = exec.output
                            )
                        )
                        refreshConfigOnly(context)
                    }
                } catch (_: Throwable) { /* silent fail */ }
                delay(5_000L)
            }
        }
    }

/*
    private fun executeCommand(cmd: DeviceLeasedCommand): CommandExecResult {
        // MVP: chỉ ACK để thông pipeline. Về sau map type->DevicePolicyHelper
        return when (cmd.type.uppercase()) {
            "PING" -> CommandExecResult(result = "SUCCESS", output = "pong")
            else -> CommandExecResult(result = "FAILED", error = "Unsupported command: ${cmd.type}")
        }
    }
*/

    // Có command thì sẽ loop lại refesh config, để áp dụng policy mới ngay lập tức
    private fun executeCommand(cmd: DeviceLeasedCommand): CommandExecResult {
        return when (cmd.type.uppercase()) {
            "PING" -> CommandExecResult(result = "SUCCESS", output = "pong")

            "REFRESH_CONFIG" -> {
                val token = cachedToken
                    ?: return CommandExecResult(result = "FAILED", error = "No token")
                val deviceCode = cachedDeviceCode
                    ?: return CommandExecResult(result = "FAILED", error = "No deviceCode")
                val context = appContext
                    ?: return CommandExecResult(result = "FAILED", error = "No appContext")

                viewModelScope.launch {
                    try {
                        loadConfig(token, deviceCode, context)
                    } catch (_: Throwable) {
                    }
                }

                CommandExecResult(result = "SUCCESS", output = "config refresh triggered")
            }

            else -> CommandExecResult(
                result = "FAILED",
                error = "Unsupported command: ${cmd.type}"
            )
        }
    }

    // reload config từ server mà không cần đợi đến lần refresh định kỳ tiếp theo, thường dùng sau khi nhận được command REFRESH_CONFIG


    private fun refreshConfigOnly(context: Context) {
        val token = cachedToken ?: return
        val deviceCode = cachedDeviceCode ?: return

        viewModelScope.launch {
            try {
                val config = api.fetchConfig(token, userCode, deviceCode)
                val apps = loadAllowedApps(context, config.allowedApps)

                _state.value = _state.value.copy(
                    config = config,
                    apps = apps,
                    error = null
                )
            } catch (_: Throwable) {
                // silent hoặc log nhẹ
            }
        }
    }

    // ===== GỬI EVENT =====
    fun sendEvent(type: String, payload: String = "{}") {
        val deviceCode = cachedDeviceCode ?: return
        viewModelScope.launch {
            try {
                val token = getOrRefreshToken()
                api.sendEvent(token, deviceCode, DeviceEventRequest(type = type, payload = payload))
            } catch (_: Throwable) {}
        }
    }

    // ===== HELPERS =====
    private suspend fun getOrRefreshToken(): String {
        return cachedToken ?: api.login(deviceUser, devicePass).token.also { cachedToken = it }
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
            imei = "",          // cần READ_PHONE_STATE permission, để trống nếu chưa có
            serial = "",        // Android 8+ cần permission
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            wifiEnabled = wifiEnabled
        )
    }

    private fun loadAllowedApps(context: Context, packages: List<String>): List<LauncherApp> {
        val pm = context.packageManager
        return packages.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                LauncherApp(
                    packageName = pkg,
                    label = pm.getApplicationLabel(info).toString(),
                    icon = pm.getApplicationIcon(info)
                )
            } catch (_: Exception) { null }
        }
    }

    // Collect usage stats (cần PACKAGE_USAGE_STATS permission)
    private data class AppUsageEntry(
        val packageName: String,
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long
    )

    private fun collectAppUsage(context: Context, startMs: Long, endMs: Long): List<AppUsageEntry> {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_BEST,
                startMs, endMs
            )
            stats
                .filter { it.totalTimeInForeground > 0 }
                .map { s ->
                    AppUsageEntry(
                        packageName = s.packageName,
                        startMs = s.firstTimeStamp,
                        endMs = s.lastTimeStamp,
                        durationMs = s.totalTimeInForeground
                    )
                }
        } catch (_: Exception) { emptyList() }
    }

    // Helper xử lý lỗi API chung
    private fun handleApiException(e: MdmApi.ApiException) {
        if (e.httpCode == 423) {
            _state.value = _state.value.copy(
                loading = false,
                lockState = DeviceLockState.LOCKED,
                unlockError = e.message
            )
        } else {
            _state.value = _state.value.copy(loading = false, error = e.message)
        }
    }
}