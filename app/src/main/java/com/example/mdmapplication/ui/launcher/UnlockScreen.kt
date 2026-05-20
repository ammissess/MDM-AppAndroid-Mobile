package com.example.mdmapplication.ui.launcher

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.mdmapplication.BuildConfig
import kotlinx.coroutines.delay

private val UnlockBgTop = Color(0xFF300A13)
private val UnlockBgBottom = Color(0xFF070B12)
private val UnlockGlass = Color(0xB3151C28)
private val UnlockBorder = Color.White.copy(alpha = 0.12f)
private val UnlockText = Color(0xFFF2F7FF)
private val UnlockMuted = Color(0xFFB6C2D2)
private val UnlockError = Color(0xFFFF9AA6)

@Composable
fun UnlockScreen(
    language: AppLanguage,
    error: String?,
    lockReason: String?,
    noProfileLocked: Boolean,
    adminLocked: Boolean,
    commandScreenLocked: Boolean,
    lockedState: DeviceLockState,
    lockContainmentStatus: String?,
    lockContainmentErrorCode: String?,
    loading: Boolean,
    unlockSubmitting: Boolean,
    onUnlock: (String) -> Unit
) {
    val strings = unlockStrings(language)
    var password by remember { mutableStateOf("") }
    var previousUnlockSubmitting by remember { mutableStateOf(unlockSubmitting) }
    var previousNoProfileLocked by remember { mutableStateOf(noProfileLocked) }
    var previousLockReason by remember { mutableStateOf(lockReason) }
    var previousLockState by remember { mutableStateOf(lockedState) }
    var previousError by remember { mutableStateOf(error) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun clearPassword(reason: String) {
        if (password.isEmpty()) return
        password = ""
        Log.i("MDM_UNLOCK_UI", "password cleared reason=$reason")
    }

    LaunchedEffect(unlockSubmitting, error, noProfileLocked, lockReason, lockedState) {
        if (previousError != error && !error.isNullOrBlank()) {
            clearPassword("unlock_error_changed")
        }
        if (!previousNoProfileLocked && noProfileLocked) {
            clearPassword("no_profile_locked")
        }
        if (previousLockReason != lockReason) {
            clearPassword("lock_reason_changed")
        }
        if (previousUnlockSubmitting && !unlockSubmitting) {
            clearPassword("submit_finished")
        }
        if (previousLockState == DeviceLockState.LOCKED && lockedState != DeviceLockState.LOCKED) {
            clearPassword("unlock_success_or_exit")
        }

        previousUnlockSubmitting = unlockSubmitting
        previousNoProfileLocked = noProfileLocked
        previousLockReason = lockReason
        previousLockState = lockedState
        previousError = error
    }

    LaunchedEffect(lockedState, adminLocked) {
        if (lockedState == DeviceLockState.LOCKED && !adminLocked) {
            delay(250L)
            runCatching { focusRequester.requestFocus() }
                .onFailure { Log.w("MDM_UNLOCK_UI", "focus request failed", it) }
            delay(120L)
            keyboardController?.show()
            Log.i("MDM_UNLOCK_UI", "keyboard focus requested")
        }
    }

    val noProfileMessageVisible = noProfileLocked || isNoProfileLockReason(lockReason)
    val adminLockedVisible = adminLocked || isAdminLockedReason(lockReason) || isAdminLockedReason(error)
    val displayError = unlockErrorMessage(error, noProfileLocked, language)
    val commandLockedVisible = commandScreenLocked && !adminLockedVisible
    val titleText = when {
        adminLockedVisible -> strings.adminLockedTitle
        commandLockedVisible -> strings.commandLockedTitle
        else -> strings.title
    }
    val descriptionText = when {
        adminLockedVisible -> strings.adminLockedDescription
        commandLockedVisible -> strings.commandLockedDescription
        else -> strings.description
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(UnlockBgTop, Color(0xFF101827), UnlockBgBottom)
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = UnlockGlass,
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .border(1.dp, UnlockBorder, RoundedCornerShape(30.dp))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = titleText,
                        color = UnlockText,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = descriptionText,
                        color = UnlockMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    if (noProfileMessageVisible) {
                        Text(
                            text = strings.noProfileLocked,
                            color = UnlockError,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (adminLockedVisible) {
                        Text(
                            text = strings.contactAdmin,
                            color = UnlockError,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (BuildConfig.DEBUG) {
                        Text(
                            text = containmentStatusText(lockContainmentStatus, lockContainmentErrorCode, strings),
                            color = UnlockMuted,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (!adminLockedVisible) {
                        Spacer(modifier = Modifier.height(4.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(strings.passwordLabel) },
                            singleLine = true,
                            isError = displayError != null,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                                disabledContainerColor = Color.White.copy(alpha = 0.05f),
                                errorContainerColor = Color.White.copy(alpha = 0.05f),
                                focusedTextColor = UnlockText,
                                unfocusedTextColor = UnlockText,
                                focusedLabelColor = UnlockMuted,
                                unfocusedLabelColor = UnlockMuted,
                                cursorColor = Color(0xFF7CCBFF),
                                focusedIndicatorColor = Color(0xFF7CCBFF),
                                unfocusedIndicatorColor = Color.White.copy(alpha = 0.18f),
                                errorIndicatorColor = UnlockError,
                                errorLabelColor = UnlockError
                            )
                        )

                        if (displayError != null) {
                            Text(
                                text = displayError,
                                color = UnlockError,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }

                        Button(
                            onClick = {
                                Log.i(
                                    "MDM_UNLOCK_UI",
                                    "button clicked passwordLength=${password.length} lockedState=${lockedState.name} noProfileLocked=$noProfileLocked loading=$loading unlockSubmitting=$unlockSubmitting"
                                )
                                onUnlock(password)
                            },
                            enabled = password.isNotBlank() && !unlockSubmitting,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF7CCBFF),
                                contentColor = Color(0xFF07111E)
                            )
                        ) {
                            Text(if (unlockSubmitting) strings.submitting else strings.unlockButton)
                        }
                    }
                }
            }
        }
    }
}

private data class UnlockStrings(
    val title: String,
    val description: String,
    val adminLockedTitle: String,
    val adminLockedDescription: String,
    val commandLockedTitle: String,
    val commandLockedDescription: String,
    val contactAdmin: String,
    val noProfileLocked: String,
    val passwordLabel: String,
    val unlockButton: String,
    val submitting: String,
    val wrongPassword: String,
    val blockedNoProfile: String,
    val remoteScreenUnlockUnsupported: String,
    val genericError: String,
    val containmentActive: String,
    val containmentPreparing: String,
    val containmentLimited: String
)

private fun unlockStrings(language: AppLanguage): UnlockStrings =
    when (language) {
        AppLanguage.VI -> UnlockStrings(
            title = "Thiết bị đang bị khóa",
            description = "Nhập mã mở khóa do quản trị viên cung cấp để tiếp tục sử dụng thiết bị.",
            adminLockedTitle = "Thiết bị đang bị khóa bởi quản trị viên",
            adminLockedDescription = "Chỉ quản trị viên có thể mở khóa thiết bị này từ dashboard.",
            commandLockedTitle = "Màn hình đang bị khóa",
            commandLockedDescription = "Nhập mật khẩu mở khóa do quản trị viên cung cấp để tiếp tục sử dụng thiết bị.",
            contactAdmin = "Vui lòng liên hệ quản trị viên.",
            noProfileLocked = "Thiết bị chưa được gán hồ sơ cấu hình. Vui lòng liên hệ quản trị viên.",
            passwordLabel = "Mã mở khóa",
            unlockButton = "Mở khóa thiết bị",
            submitting = "Đang mở khóa...",
            wrongPassword = "Mã mở khóa không đúng.",
            blockedNoProfile = "Thiết bị chưa được gán hồ sơ cấu hình nên chưa thể mở khóa.",
            remoteScreenUnlockUnsupported =
                "Thiết bị đang bị khóa từ xa. Mã kích hoạt chỉ dùng để kích hoạt thiết bị, không dùng để mở khóa màn hình. Chức năng mở khóa màn hình từ xa cần được hỗ trợ bằng lệnh riêng từ hệ thống quản trị.",
            genericError = "Không thể mở khóa. Vui lòng kiểm tra mã và thử lại.",
            containmentActive = "Chế độ khóa đang hoạt động.",
            containmentPreparing = "Đang kích hoạt chế độ khóa.",
            containmentLimited = "Chế độ khóa chưa hoàn tất."
        )

        AppLanguage.EN -> UnlockStrings(
            title = "Device is locked",
            description = "Enter the unlock code from your administrator to continue using this device.",
            adminLockedTitle = "Device is locked by an administrator",
            adminLockedDescription = "Only an administrator can unlock this device from the dashboard.",
            commandLockedTitle = "Screen is locked",
            commandLockedDescription = "Enter the unlock password provided by your administrator to continue using this device.",
            contactAdmin = "Contact your administrator.",
            noProfileLocked = "This device has not been assigned a configuration profile. Contact your administrator.",
            passwordLabel = "Unlock code",
            unlockButton = "Unlock device",
            submitting = "Unlocking...",
            wrongPassword = "The unlock code is incorrect.",
            blockedNoProfile = "This device has no configuration profile yet, so it cannot be unlocked.",
            remoteScreenUnlockUnsupported =
                "This device is remotely screen-locked. The activation code cannot unlock this screen. Remote screen unlock requires a dedicated management command.",
            genericError = "Unable to unlock. Check the code and try again.",
            containmentActive = "Lock mode is active.",
            containmentPreparing = "Lock mode is being activated.",
            containmentLimited = "Lock mode is not fully active."
        )
    }

private fun containmentStatusText(
    status: String?,
    errorCode: String?,
    strings: UnlockStrings
): String {
    return when (status?.uppercase()) {
        "FULL" -> strings.containmentActive
        "FAILED", "PARTIAL" -> strings.containmentLimited
        else -> strings.containmentPreparing
    }.let { text ->
        if (BuildConfig.DEBUG && !errorCode.isNullOrBlank()) "$text ($errorCode)" else text
    }
}

private fun isAdminLockedReason(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase() ?: return false
    return normalized.contains("khóa bởi quản trị viên") ||
        normalized.contains("khoa boi quan tri vien") ||
        normalized.contains("locked by administrator") ||
        normalized.contains("device_admin_locked")
}

private fun unlockErrorMessage(error: String?, noProfileLocked: Boolean, language: AppLanguage): String? {
    if (error.isNullOrBlank()) return null
    val strings = unlockStrings(language)
    val normalized = error.trim().lowercase()
    return when {
        noProfileLocked || normalized.contains("profile not linked") ||
            normalized.contains("profile_not_linked") ||
            normalized.contains("device_profile_not_linked") -> strings.blockedNoProfile

        normalized.contains("invalid password") ||
            normalized.contains("không chính xác") ||
            normalized.contains("khong chinh xac") -> strings.wrongPassword

        normalized.contains("khóa từ xa") ||
            normalized.contains("khoa tu xa") ||
            normalized.contains("remotely screen-locked") ||
            normalized.contains("remote screen lock") -> strings.remoteScreenUnlockUnsupported

        else -> strings.genericError
    }
}

private fun isNoProfileLockReason(lockReason: String?): Boolean {
    val reason = lockReason?.trim()?.lowercase() ?: return false
    if (reason == LauncherViewModel.NO_PROFILE_LOCKED_MESSAGE.lowercase()) return true
    return reason.contains("profile not linked") ||
        reason.contains("profile_not_linked") ||
        reason.contains("device_profile_not_linked") ||
        reason.contains("chưa được gán") ||
        reason.contains("chua duoc gan profile")
}
