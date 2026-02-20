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

    // ===== BƯỚC 1: Login + Register + Kiểm tra trạng thái =====
    fun refreshFromBackend(context: Context) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(loading = true, error = null)

                val token = getOrRefreshToken()
                val deviceCode = getDeviceCode(context)
                cachedDeviceCode = deviceCode

                val registerResp = api.registerDevice(
                    token = token,
                    req = buildRegisterRequest(context, deviceCode)
                )

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
                            error = "Unknown status: ${registerResp.status}"
                        )
                    }
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.message ?: "Lỗi kết nối backend"
                )
            }
        }
    }

    // ===== BƯỚC 2: Unlock (khi user nhập mật khẩu) =====
    fun unlock(context: Context, password: String) {
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
                        unlockError = "Sai mật khẩu, thử lại."
                    )
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    unlockError = t.message ?: "Lỗi unlock"
                )
            }
        }
    }

    // ===== BƯỚC 3: Load config sau khi ACTIVE =====
    private suspend fun loadConfig(token: String, deviceCode: String, context: Context) {
        val config = api.fetchConfig(token, userCode, deviceCode)
        val apps = loadAllowedApps(context, config.allowedApps)

        _state.value = LauncherUiState(
            loading = false,
            lockState = DeviceLockState.ACTIVE,
            config = config,
            apps = apps
        )

        // Bắt đầu background jobs
        startLocationLoop(token, deviceCode)
        startUsageReportLoop(token, deviceCode, context)
    }

    // ===== LOCATION LOOP: mỗi 60 giây =====
    private fun startLocationLoop(token: String, deviceCode: String) {
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
    private fun startUsageReportLoop(token: String, deviceCode: String, context: Context) {
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
                } catch (_: Throwable) { /* silent fail */ }
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
}