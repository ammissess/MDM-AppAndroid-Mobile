package com.example.mdmapplication.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val ProvisioningBgTop = Color(0xFF09111D)
private val ProvisioningBgMid = Color(0xFF101827)
private val ProvisioningBgBottom = Color(0xFF05070C)
private val ProvisioningPanel = Color(0xCC111A27)
private val ProvisioningRow = Color(0x66192535)
private val ProvisioningBorder = Color.White.copy(alpha = 0.12f)
private val ProvisioningText = Color(0xFFF3F7FF)
private val ProvisioningMuted = Color(0xFFAEB9C8)
private val ProvisioningAccent = Color(0xFF7CCBFF)
private val ProvisioningPass = Color(0xFF7EE0A1)
private val ProvisioningFail = Color(0xFFFF8A96)
private val ProvisioningManual = Color(0xFFFFC66D)

@Composable
fun ProvisioningScreen(
    setupState: SetupState,
    steps: List<ProvisioningStep>,
    loading: Boolean,
    error: String?,
    rebootError: String?,
    rebootRequested: Boolean,
    onRetry: () -> Unit,
    onReboot: () -> Unit
) {
    val scrollState = rememberScrollState()
    val rebootEnabled = setupState == SetupState.REBOOT_RECOMMENDED && !rebootRequested
    val retryEnabled = !loading && !rebootRequested && setupState != SetupState.REBOOT_RECOMMENDED

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(ProvisioningBgTop, ProvisioningBgMid, ProvisioningBgBottom)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = ProvisioningPanel,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .border(1.dp, ProvisioningBorder, RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Thiết lập thiết bị mới",
                            color = ProvisioningText,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = setupStateLabel(setupState),
                            color = ProvisioningMuted,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    if (steps.any { it.key == ProvisioningStepKey.DEVICE_OWNER_ACTIVE && it.status == ProvisioningStepStatus.MANUAL_REQUIRED }) {
                        ManualDeviceOwnerHint()
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        steps.forEach { step ->
                            ProvisioningStepRow(step = step)
                        }
                    }

                    if (!error.isNullOrBlank()) {
                        Text(
                            text = error,
                            color = ProvisioningFail,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (!rebootError.isNullOrBlank()) {
                        Text(
                            text = rebootError,
                            color = ProvisioningFail,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onRetry,
                            enabled = retryEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (loading) "Đang kiểm tra" else "Thử lại")
                        }

                        Button(
                            onClick = onReboot,
                            enabled = rebootEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (rebootRequested) "Đã yêu cầu khởi động lại" else "Khởi động lại")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualDeviceOwnerHint() {
    Surface(
        color = ProvisioningManual.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.border(
            width = 1.dp,
            color = ProvisioningManual.copy(alpha = 0.28f),
            shape = RoundedCornerShape(14.dp)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Device Owner phải được cấp bên ngoài ứng dụng.",
                color = ProvisioningText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = ".\\scripts\\provision-emulator-device-owner.ps1 -Serial emulator-5554",
                color = ProvisioningManual,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Với thiết bị thật, dùng QR Android hoặc manual provisioning trên thiết bị sạch/factory reset.",
                color = ProvisioningMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ProvisioningStepRow(step: ProvisioningStep) {
    val statusColor = when (step.status) {
        ProvisioningStepStatus.PENDING -> ProvisioningMuted
        ProvisioningStepStatus.RUNNING -> ProvisioningAccent
        ProvisioningStepStatus.PASSED -> ProvisioningPass
        ProvisioningStepStatus.FAILED -> ProvisioningFail
        ProvisioningStepStatus.MANUAL_REQUIRED -> ProvisioningManual
    }

    Surface(
        color = ProvisioningRow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                color = statusColor.copy(alpha = 0.16f),
                shape = CircleShape,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (step.status == ProvisioningStepStatus.RUNNING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = statusColor
                        )
                    } else {
                        Text(
                            text = statusGlyph(step.status),
                            color = statusColor,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = step.label,
                    color = ProvisioningText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!step.detail.isNullOrBlank()) {
                    Text(
                        text = step.detail,
                        color = ProvisioningMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = statusLabel(step.status),
                color = statusColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }

    Spacer(modifier = Modifier.height(0.dp))
}

private fun statusGlyph(status: ProvisioningStepStatus): String =
    when (status) {
        ProvisioningStepStatus.PENDING -> "-"
        ProvisioningStepStatus.RUNNING -> ""
        ProvisioningStepStatus.PASSED -> "OK"
        ProvisioningStepStatus.FAILED -> "!"
        ProvisioningStepStatus.MANUAL_REQUIRED -> "!"
    }

private fun setupStateLabel(state: SetupState): String =
    when (state) {
        SetupState.NOT_PROVISIONED -> "Chưa provision"
        SetupState.DEVICE_OWNER_READY -> "Device Owner sẵn sàng"
        SetupState.BACKEND_CONNECTED -> "Đã kết nối backend"
        SetupState.DEVICE_REGISTERED -> "Thiết bị đã đăng ký"
        SetupState.PROFILE_WAITING -> "Đang chờ gán hồ sơ"
        SetupState.CONFIG_FETCHED -> "Đã tải cấu hình"
        SetupState.POLICY_REPORTED -> "Đã báo cáo chính sách"
        SetupState.ENFORCEMENT_READY -> "Sẵn sàng enforcement"
        SetupState.ENFORCEMENT_ACTIVE -> "Enforcement đang hoạt động"
        SetupState.REBOOT_RECOMMENDED -> "Nên khởi động lại"
    }

private fun statusLabel(status: ProvisioningStepStatus): String =
    when (status) {
        ProvisioningStepStatus.PENDING -> "Chờ"
        ProvisioningStepStatus.RUNNING -> "Đang chạy"
        ProvisioningStepStatus.PASSED -> "Đạt"
        ProvisioningStepStatus.FAILED -> "Lỗi"
        ProvisioningStepStatus.MANUAL_REQUIRED -> "Cần thao tác"
    }
