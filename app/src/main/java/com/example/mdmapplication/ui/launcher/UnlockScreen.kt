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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val UnlockBgTop = Color(0xCC300A13)
private val UnlockBgBottom = Color(0xDD070B12)
private val UnlockGlass = Color(0xB3151C28)
private val UnlockBorder = Color.White.copy(alpha = 0.12f)
private val UnlockText = Color(0xFFF2F7FF)
private val UnlockMuted = Color(0xFFB6C2D2)
private val UnlockError = Color(0xFFFF9AA6)

@Composable
fun UnlockScreen(
    error: String?,
    lockReason: String?,
    noProfileLocked: Boolean,
    lockedState: DeviceLockState,
    lockContainmentStatus: String?,
    lockContainmentErrorCode: String?,
    loading: Boolean,
    unlockSubmitting: Boolean,
    onUnlock: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var previousUnlockSubmitting by remember { mutableStateOf(unlockSubmitting) }
    var previousNoProfileLocked by remember { mutableStateOf(noProfileLocked) }
    var previousLockReason by remember { mutableStateOf(lockReason) }
    var previousLockState by remember { mutableStateOf(lockedState) }
    var previousError by remember { mutableStateOf(error) }

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

    val noProfileMessageVisible = noProfileLocked || isNoProfileLockReason(lockReason)

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(UnlockBgTop, Color(0xAA101827), UnlockBgBottom)
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
                        text = "Thiết bị đang bị khóa",
                        color = UnlockText,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Chính sách MDM đã chặn sử dụng launcher. Chỉ quản trị viên mới có thể cung cấp mã mở khóa.",
                        color = UnlockMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    if (noProfileMessageVisible) {
                        Text(
                            text = LauncherViewModel.NO_PROFILE_LOCKED_MESSAGE,
                            color = UnlockError,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }

                    if (
                        noProfileLocked &&
                        !lockReason.isNullOrBlank() &&
                        lockReason != LauncherViewModel.NO_PROFILE_LOCKED_MESSAGE
                    ) {
                        Text(
                            text = lockReason,
                            color = UnlockError,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }

                    val containmentText = buildString {
                        append("Containment: ")
                        append(lockContainmentStatus ?: "PENDING")
                        if (!lockContainmentErrorCode.isNullOrBlank()) {
                            append(" (")
                            append(lockContainmentErrorCode)
                            append(")")
                        }
                    }
                    Text(
                        text = containmentText,
                        color = UnlockMuted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Mật khẩu mở khóa") },
                        singleLine = true,
                        isError = error != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
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

                    if (error != null) {
                        Text(
                            text = error,
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
                        Text(if (unlockSubmitting) "Đang mở khóa..." else "Mở khóa thiết bị")
                    }
                }
            }
        }
    }
}

private fun isNoProfileLockReason(lockReason: String?): Boolean {
    val reason = lockReason?.trim()?.lowercase() ?: return false
    if (reason == LauncherViewModel.NO_PROFILE_LOCKED_MESSAGE.lowercase()) return true
    return reason.contains("profile not linked") ||
        reason.contains("profile_not_linked") ||
        reason.contains("device_profile_not_linked") ||
        reason.contains("chua duoc gan profile")
}
