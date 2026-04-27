package com.example.mdmapplication.ui.launcher

enum class AppLanguage(val storageValue: String) {
    VI("vi"),
    EN("en");

    companion object {
        fun fromStorage(value: String?): AppLanguage? =
            values().firstOrNull { it.storageValue == value }
    }
}

data class ProvisioningStrings(
    val title: String,
    val changeLanguage: String,
    val checklistTitle: String,
    val nextStepsTitle: String,
    val technicalDetails: String,
    val retry: String,
    val checking: String,
    val reboot: String,
    val rebootRequested: String,
    val selectLanguageTitle: String,
    val selectLanguageSubtitle: String,
    val vietnamese: String,
    val english: String,
    val noActionNeeded: String,
    val genericError: String
)

fun provisioningStrings(language: AppLanguage): ProvisioningStrings =
    when (language) {
        AppLanguage.VI -> ProvisioningStrings(
            title = "Thiết lập thiết bị mới",
            changeLanguage = "EN",
            checklistTitle = "Checklist",
            nextStepsTitle = "Hướng dẫn tiếp theo",
            technicalDetails = "Chi tiết kỹ thuật",
            retry = "Thử lại",
            checking = "Đang kiểm tra",
            reboot = "Khởi động lại",
            rebootRequested = "Đã yêu cầu khởi động lại",
            selectLanguageTitle = "Chọn ngôn ngữ",
            selectLanguageSubtitle = "Bạn có thể đổi lại ngôn ngữ trên màn hình thiết lập.",
            vietnamese = "Tiếng Việt",
            english = "English",
            noActionNeeded = "Không cần thao tác thêm. Tiếp tục theo trạng thái trên màn hình.",
            genericError = "Thiết lập chưa hoàn tất. Hãy kiểm tra kết nối và thử lại."
        )

        AppLanguage.EN -> ProvisioningStrings(
            title = "New device setup",
            changeLanguage = "VI",
            checklistTitle = "Checklist",
            nextStepsTitle = "Next steps",
            technicalDetails = "Technical details",
            retry = "Retry",
            checking = "Checking",
            reboot = "Reboot",
            rebootRequested = "Reboot requested",
            selectLanguageTitle = "Choose language",
            selectLanguageSubtitle = "You can change the language later on the setup screen.",
            vietnamese = "Tiếng Việt",
            english = "English",
            noActionNeeded = "No extra action is needed right now. Follow the current setup status.",
            genericError = "Setup is not complete yet. Check connectivity and retry."
        )
    }

fun setupStateLabel(state: SetupState, language: AppLanguage): String =
    when (language) {
        AppLanguage.VI -> when (state) {
            SetupState.NOT_PROVISIONED -> "Chưa provision"
            SetupState.DEVICE_OWNER_READY -> "Device Owner sẵn sàng"
            SetupState.BACKEND_CONNECTED -> "Đã kết nối máy chủ"
            SetupState.DEVICE_REGISTERED -> "Thiết bị đã đăng ký"
            SetupState.PROFILE_WAITING -> "Đang chờ gán hồ sơ"
            SetupState.CONFIG_FETCHED -> "Đã nhận cấu hình"
            SetupState.POLICY_REPORTED -> "Đã báo cáo chính sách"
            SetupState.ENFORCEMENT_READY -> "Sẵn sàng enforcement"
            SetupState.ENFORCEMENT_ACTIVE -> "Enforcement đang hoạt động"
            SetupState.REBOOT_RECOMMENDED -> "Nên khởi động lại"
        }

        AppLanguage.EN -> when (state) {
            SetupState.NOT_PROVISIONED -> "Not provisioned"
            SetupState.DEVICE_OWNER_READY -> "Device Owner ready"
            SetupState.BACKEND_CONNECTED -> "Backend connected"
            SetupState.DEVICE_REGISTERED -> "Device registered"
            SetupState.PROFILE_WAITING -> "Waiting for profile"
            SetupState.CONFIG_FETCHED -> "Config received"
            SetupState.POLICY_REPORTED -> "Policy state reported"
            SetupState.ENFORCEMENT_READY -> "Enforcement ready"
            SetupState.ENFORCEMENT_ACTIVE -> "Enforcement active"
            SetupState.REBOOT_RECOMMENDED -> "Reboot recommended"
        }
    }

fun stepTitle(key: ProvisioningStepKey, language: AppLanguage): String =
    when (language) {
        AppLanguage.VI -> when (key) {
            ProvisioningStepKey.APP_INSTALLED -> "Ứng dụng đã cài đặt"
            ProvisioningStepKey.DEVICE_OWNER_ACTIVE -> "Quyền quản trị thiết bị"
            ProvisioningStepKey.BACKEND_REACHABLE -> "Kết nối máy chủ"
            ProvisioningStepKey.DEVICE_REGISTERED -> "Đăng ký thiết bị"
            ProvisioningStepKey.PROFILE_LINKED -> "Hồ sơ cấu hình"
            ProvisioningStepKey.CONFIG_FETCHED -> "Nhận cấu hình"
            ProvisioningStepKey.POLICY_STATE_REPORTED -> "Báo cáo trạng thái chính sách"
            ProvisioningStepKey.LAUNCHER_READY -> "Launcher sẵn sàng"
            ProvisioningStepKey.KIOSK_READY -> "Chế độ kiosk sẵn sàng"
            ProvisioningStepKey.READY_TO_REBOOT -> "Sẵn sàng khởi động lại"
        }

        AppLanguage.EN -> when (key) {
            ProvisioningStepKey.APP_INSTALLED -> "App installed"
            ProvisioningStepKey.DEVICE_OWNER_ACTIVE -> "Device administration"
            ProvisioningStepKey.BACKEND_REACHABLE -> "Server connection"
            ProvisioningStepKey.DEVICE_REGISTERED -> "Device registration"
            ProvisioningStepKey.PROFILE_LINKED -> "Configuration profile"
            ProvisioningStepKey.CONFIG_FETCHED -> "Receive configuration"
            ProvisioningStepKey.POLICY_STATE_REPORTED -> "Report policy state"
            ProvisioningStepKey.LAUNCHER_READY -> "Launcher ready"
            ProvisioningStepKey.KIOSK_READY -> "Kiosk mode ready"
            ProvisioningStepKey.READY_TO_REBOOT -> "Ready to reboot"
        }
    }

fun statusLabel(status: ProvisioningStepStatus, language: AppLanguage): String =
    when (language) {
        AppLanguage.VI -> when (status) {
            ProvisioningStepStatus.PASSED -> "Hoàn tất"
            ProvisioningStepStatus.PENDING -> "Đang chờ"
            ProvisioningStepStatus.RUNNING -> "Đang kiểm tra"
            ProvisioningStepStatus.FAILED -> "Lỗi"
            ProvisioningStepStatus.MANUAL_REQUIRED -> "Cần thao tác"
        }

        AppLanguage.EN -> when (status) {
            ProvisioningStepStatus.PASSED -> "Passed"
            ProvisioningStepStatus.PENDING -> "Pending"
            ProvisioningStepStatus.RUNNING -> "Checking"
            ProvisioningStepStatus.FAILED -> "Failed"
            ProvisioningStepStatus.MANUAL_REQUIRED -> "Action needed"
        }
    }

fun stepSubtitle(step: ProvisioningStep, language: AppLanguage): String =
    when (language) {
        AppLanguage.VI -> when (step.status) {
            ProvisioningStepStatus.PASSED -> "Đã xác nhận"
            ProvisioningStepStatus.PENDING -> "Chờ bước trước hoàn tất"
            ProvisioningStepStatus.RUNNING -> "Đang kiểm tra trạng thái"
            ProvisioningStepStatus.FAILED -> "Cần kiểm tra lại"
            ProvisioningStepStatus.MANUAL_REQUIRED -> "Cần thao tác từ quản trị viên"
        }

        AppLanguage.EN -> when (step.status) {
            ProvisioningStepStatus.PASSED -> "Verified"
            ProvisioningStepStatus.PENDING -> "Waiting for previous step"
            ProvisioningStepStatus.RUNNING -> "Checking status"
            ProvisioningStepStatus.FAILED -> "Needs attention"
            ProvisioningStepStatus.MANUAL_REQUIRED -> "Admin action needed"
        }
    }

fun userFacingProvisioningMessage(
    step: ProvisioningStepKey?,
    status: ProvisioningStepStatus?,
    rawDetail: String?,
    language: AppLanguage
): String? {
    if (step == null || status == null) return null
    return when (language) {
        AppLanguage.VI -> when {
            step == ProvisioningStepKey.PROFILE_LINKED &&
                    status == ProvisioningStepStatus.MANUAL_REQUIRED ->
                "Vào trang quản trị, chọn thiết bị này và nhập mã mở khóa hoặc yêu cầu Admin kích hoạt. Sau đó bấm “Thử lại”."

            step == ProvisioningStepKey.CONFIG_FETCHED &&
                    status == ProvisioningStepStatus.FAILED ->
                "Thiết bị chưa nhận được cấu hình. Hãy kiểm tra kết nối backend và gửi lệnh refresh_config từ dashboard."

            step == ProvisioningStepKey.KIOSK_READY &&
                    status == ProvisioningStepStatus.FAILED ->
                "Chế độ kiosk chưa sẵn sàng. Hãy kiểm tra quyền Device Owner và danh sách ứng dụng được phép."

            step == ProvisioningStepKey.DEVICE_OWNER_ACTIVE &&
                    status == ProvisioningStepStatus.MANUAL_REQUIRED ->
                "Chạy host-side provisioning script trên máy phát triển, hoặc dùng QR/manual provisioning trên thiết bị sạch."

            status == ProvisioningStepStatus.FAILED ->
                "Bước này chưa hoàn tất. Hãy kiểm tra điều kiện thiết lập rồi bấm “Thử lại”."

            status == ProvisioningStepStatus.MANUAL_REQUIRED ->
                "Bước này cần thao tác thủ công trước khi tiếp tục."

            else -> null
        }

        AppLanguage.EN -> when {
            step == ProvisioningStepKey.PROFILE_LINKED &&
                    status == ProvisioningStepStatus.MANUAL_REQUIRED ->
                "Open the admin dashboard, select this device, and enter the unlock code or ask an admin to activate it. Then tap “Retry”."

            step == ProvisioningStepKey.CONFIG_FETCHED &&
                    status == ProvisioningStepStatus.FAILED ->
                "The device has not received configuration yet. Check backend connectivity and send refresh_config from the dashboard."

            step == ProvisioningStepKey.KIOSK_READY &&
                    status == ProvisioningStepStatus.FAILED ->
                "Kiosk mode is not ready. Check Device Owner permissions and the allowed app list."

            step == ProvisioningStepKey.DEVICE_OWNER_ACTIVE &&
                    status == ProvisioningStepStatus.MANUAL_REQUIRED ->
                "Run the host-side provisioning script on the development machine, or use QR/manual provisioning on a clean device."

            status == ProvisioningStepStatus.FAILED ->
                "This step is not complete. Check the setup requirements and tap “Retry”."

            status == ProvisioningStepStatus.MANUAL_REQUIRED ->
                "This step requires manual action before setup can continue."

            else -> null
        }
    }
}

fun userFacingProvisioningError(rawError: String?, language: AppLanguage): String? {
    if (rawError.isNullOrBlank()) return null
    return provisioningStrings(language).genericError
}
