package com.example.mdmapplication.ui.launcher

enum class ProvisioningStepStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
    MANUAL_REQUIRED
}

enum class SetupState {
    NOT_PROVISIONED,
    DEVICE_OWNER_READY,
    BACKEND_CONNECTED,
    DEVICE_REGISTERED,
    PROFILE_WAITING,
    CONFIG_FETCHED,
    POLICY_REPORTED,
    ENFORCEMENT_READY,
    ENFORCEMENT_ACTIVE,
    REBOOT_RECOMMENDED
}

enum class ProvisioningStepKey(val label: String) {
    APP_INSTALLED("Ứng dụng đã cài"),
    DEVICE_OWNER_ACTIVE("Device Owner đã kích hoạt"),
    BACKEND_REACHABLE("Kết nối backend"),
    DEVICE_REGISTERED("Thiết bị đã đăng ký"),
    PROFILE_LINKED("Hồ sơ đã gán"),
    CONFIG_FETCHED("Cấu hình đã tải"),
    POLICY_STATE_REPORTED("Đã báo cáo trạng thái chính sách"),
    LAUNCHER_READY("Launcher sẵn sàng"),
    KIOSK_READY("Kiosk sẵn sàng"),
    READY_TO_REBOOT("Sẵn sàng khởi động lại")
}

data class ProvisioningStep(
    val key: ProvisioningStepKey,
    val label: String = key.label,
    val status: ProvisioningStepStatus = ProvisioningStepStatus.PENDING,
    val detail: String? = null
)

fun defaultProvisioningSteps(): List<ProvisioningStep> =
    ProvisioningStepKey.values().map { key ->
        ProvisioningStep(
            key = key,
            status = if (key == ProvisioningStepKey.APP_INSTALLED) {
                ProvisioningStepStatus.PASSED
            } else {
                ProvisioningStepStatus.PENDING
            }
        )
    }
