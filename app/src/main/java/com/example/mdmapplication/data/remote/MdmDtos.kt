package com.example.mdmapplication.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
     val password: String,
     val deviceCode: String? = null,
    )

@Serializable
data class LoginResponse(
    val token: String,
    val expiresAtEpochMillis: Long,
    val role: String
)
// giữ LoginResponse như cũ

@Serializable
data class UsageBatchReportRequest(
    val deviceCode: String,
    val items: List<UsageItem>,
) {
    @Serializable
    data class UsageItem(
        val packageName: String,
        val startedAtEpochMillis: Long,
        val endedAtEpochMillis: Long,
        val durationMs: Long,
    )
}

@Serializable
data class UsageBatchReportResponse(
    val ok: Boolean,
    val inserted: Int,
)


// ✅ Commands (match backend CommandDtos)
@Serializable
data class DevicePollCommandsRequest(
    val deviceCode: String,
    val limit: Int = 1,
)

@Serializable
data class DevicePollCommandsResponse(
    val commands: List<DeviceLeasedCommand>,
    val serverTimeEpochMillis: Long,
)

@Serializable
data class DeviceLeasedCommand(
    val id: String,
    val type: String,
    val payload: String,
    val status: String,
    val leaseToken: String,
    val leaseExpiresAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
)

@Serializable
data class DeviceAckCommandRequest(
    val deviceCode: String,
    val commandId: String,
    val leaseToken: String,
    val result: String, // SUCCESS|FAILED
    val error: String? = null,
    val output: String? = null,
)

@Serializable
data class DeviceAckCommandResponse(
    val ok: Boolean,
    val status: String,
)
/**
 * Đồng bộ theo backend DeviceRegisterRequest
 */
@Serializable
data class DeviceRegisterRequest(
    val deviceCode: String,

    // thiết bị
    val androidVersion: String = "",
    val sdkInt: Int = 0,
    val manufacturer: String = "",
    val model: String = "",
    val imei: String = "",
    val serial: String = "",

    // trạng thái tạm để test
    val batteryLevel: Int = -1,
    val isCharging: Boolean = false,
    val wifiEnabled: Boolean = false
)

/**
 * Đồng bộ theo backend DeviceRegisterResponse
 */
@Serializable
data class DeviceRegisterResponse(
    val deviceId: String,
    val deviceCode: String,
    val status: String,           // ACTIVE / LOCKED
    val message: String? = null
)

/**
 * Đồng bộ theo backend DeviceConfigResponse
 */
@Serializable
data class DeviceConfigResponse(
    val userCode: String,
    val allowedApps: List<String> = emptyList(),

    val disableWifi: Boolean = false,
    val disableBluetooth: Boolean = false,
    val disableCamera: Boolean = false,
    val disableStatusBar: Boolean = false,
    val kioskMode: Boolean = false,
    val blockUninstall: Boolean = false,

    val showWifi: Boolean = true,
    val showBluetooth: Boolean = true,

    val configVersionEpochMillis: Long = 0
)

// ===== Missing DTOs (đang làm bạn lỗi compile) =====

@Serializable
data class DeviceUnlockRequest(
    val deviceCode: String,
    val password: String
)

@Serializable
data class DeviceUnlockResponse(
    val status: String,   // ACTIVE / LOCKED
    val message: String
)

@Serializable
data class LocationUpdateRequest(
    val deviceCode: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double = 0.0
)

@Serializable
data class UsageReportRequest(
    val deviceCode: String,
    val packageName: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val durationMs: Long
)

@Serializable
data class DeviceEventRequest(
    val type: String,
    val payload: String = "{}"
)

@Serializable
data class ApiErrorResponse(
    val error: String? = null,
    val status: String? = null
)