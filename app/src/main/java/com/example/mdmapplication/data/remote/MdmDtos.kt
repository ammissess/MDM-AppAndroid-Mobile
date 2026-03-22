package com.example.mdmapplication.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val deviceCode: String? = null
)

@Serializable
data class LoginResponse(
    val token: String,
    val expiresAtEpochMillis: Long,
    val role: String
)

@Serializable
data class DeviceRegisterRequest(
    val deviceCode: String,
    val androidVersion: String = "",
    val sdkInt: Int = 0,
    val manufacturer: String = "",
    val model: String = "",
    val imei: String = "",
    val serial: String = "",
    val batteryLevel: Int = -1,
    val isCharging: Boolean = false,
    val wifiEnabled: Boolean = false
)

@Serializable
data class DeviceRegisterResponse(
    val deviceId: String,
    val deviceCode: String,
    val status: String,
    val message: String? = null
)

@Serializable
data class DeviceUnlockRequest(
    val deviceCode: String,
    val password: String
)

@Serializable
data class DeviceUnlockResponse(
    val status: String,
    val message: String
)

@Serializable
data class DeviceConfigResponse(
    val userCode: String,
    val allowedApps: List<String>,
    val disableWifi: Boolean,
    val disableBluetooth: Boolean,
    val disableCamera: Boolean,
    val disableStatusBar: Boolean,
    val kioskMode: Boolean,
    val blockUninstall: Boolean,
    val showWifi: Boolean,
    val showBluetooth: Boolean,
    val configVersionEpochMillis: Long
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
data class UsageBatchReportRequest(
    val deviceCode: String,
    val items: List<UsageItem>
) {
    @Serializable
    data class UsageItem(
        val packageName: String,
        val startedAtEpochMillis: Long,
        val endedAtEpochMillis: Long,
        val durationMs: Long
    )
}

@Serializable
data class UsageBatchReportResponse(
    val ok: Boolean,
    val inserted: Int
)

@Serializable
data class DeviceEventRequest(
    val type: String,
    val payload: String = "{}"
)

@Serializable
data class DevicePollCommandsRequest(
    val deviceCode: String,
    val limit: Int = 1
)

@Serializable
data class DevicePollCommandsResponse(
    val commands: List<DeviceLeasedCommand>,
    val serverTimeEpochMillis: Long
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
    val expiresAtEpochMillis: Long? = null
)

@Serializable
data class DeviceAckCommandRequest(
    val deviceCode: String,
    val commandId: String,
    val leaseToken: String,
    val result: String,
    val error: String? = null,
    val errorCode: String? = null,
    val output: String? = null
)

@Serializable
data class DeviceAckCommandResponse(
    val ok: Boolean,
    val status: String
)

@Serializable
data class ApiErrorResponse(
    val error: String? = null,
    val status: String? = null,
    val code: String? = null
)