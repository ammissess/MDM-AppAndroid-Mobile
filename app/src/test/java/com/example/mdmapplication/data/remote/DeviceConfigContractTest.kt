package com.example.mdmapplication.data.remote

import com.example.mdmapplication.ui.launcher.LauncherViewModel
import com.example.mdmapplication.ui.launcher.DeviceLockState
import com.example.mdmapplication.ui.launcher.LauncherUiState
import com.example.mdmapplication.ui.launcher.LockOverlayReason
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceConfigContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun deviceConfigResponse_deserialization_shouldParseNewHardeningFlags() {
        val payload =
            """
            {
              "userCode": "TEST005",
              "allowedApps": ["com.example.alpha"],
              "disableWifi": true,
              "disableBluetooth": false,
              "disableCamera": true,
              "disableStatusBar": true,
              "kioskMode": true,
              "blockUninstall": true,
              "lockPrivateDnsConfig": true,
              "lockVpnConfig": true,
              "blockDebuggingFeatures": true,
              "disableUsbDataSignaling": false,
              "disallowSafeBoot": true,
              "disallowFactoryReset": true,
              "configVersionEpochMillis": 123456789
            }
            """.trimIndent()

        val parsed = json.decodeFromString<DeviceConfigResponse>(payload)

        assertEquals(true, parsed.lockPrivateDnsConfig)
        assertEquals(true, parsed.lockVpnConfig)
        assertEquals(true, parsed.blockDebuggingFeatures)
        assertEquals(false, parsed.disableUsbDataSignaling)
        assertEquals(true, parsed.disallowSafeBoot)
        assertEquals(true, parsed.disallowFactoryReset)
    }

    @Test
    fun appliedConfigHash_shouldChangeWhenHardeningFlagChanges() {
        val viewModel = LauncherViewModel()
        val method = LauncherViewModel::class.java.getDeclaredMethod(
            "buildAppliedConfigHashOrNull",
            DeviceConfigResponse::class.java
        ).apply { isAccessible = true }

        val base = sampleConfig(lockPrivateDnsConfig = false)
        val changed = sampleConfig(lockPrivateDnsConfig = true)

        val baseHash = method.invoke(viewModel, base) as String?
        val changedHash = method.invoke(viewModel, changed) as String?

        assertNotNull(baseHash)
        assertNotNull(changedHash)
        assertNotEquals(baseHash, changedHash)
    }

    @Test
    fun isProfileNotLinked_shouldRecognizeBackendCodeAndMessagePatterns() {
        val viewModel = LauncherViewModel()
        val method = LauncherViewModel::class.java.getDeclaredMethod(
            "isProfileNotLinked",
            MdmApi.ApiException::class.java
        ).apply { isAccessible = true }

        val byCode = MdmApi.ApiException(423, "DEVICE_PROFILE_NOT_LINKED", "Device profile not linked")
        val by404Message = MdmApi.ApiException(404, null, "Device profile not linked")

        assertTrue(method.invoke(viewModel, byCode) as Boolean)
        assertTrue(method.invoke(viewModel, by404Message) as Boolean)
    }

    @Test
    fun normalizeConfigErrorCode_shouldMapNoProfileToStableCode() {
        val viewModel = LauncherViewModel()
        val method = LauncherViewModel::class.java.getDeclaredMethod(
            "normalizeConfigErrorCode",
            MdmApi.ApiException::class.java
        ).apply { isAccessible = true }

        val mapped = method.invoke(
            viewModel,
            MdmApi.ApiException(404, null, "Device profile not linked")
        ) as String

        assertEquals("PROFILE_NOT_LINKED", mapped)
    }

    @Test
    fun noProfileApiException_shouldMapToLockedNoProfileUiState() {
        val viewModel = LauncherViewModel()

        viewModel.debugApplyApiExceptionForTest(
            e = MdmApi.ApiException(404, "DEVICE_PROFILE_NOT_LINKED", "Device profile not linked"),
            duringConfig = true
        )

        val state = viewModel.state.value
        assertEquals(DeviceLockState.LOCKED, state.lockState)
        assertTrue(state.noProfileLocked)
        assertEquals(LauncherViewModel.NO_PROFILE_LOCKED_MESSAGE, state.lockReason)
        assertTrue(state.apps.isEmpty())
    }

    @Test
    fun unlockLockedNoProfileResponse_shouldStayLockedAndShowNoProfileError() {
        val viewModel = LauncherViewModel()
        viewModel.debugSetStateForTest(
            LauncherUiState(lockState = DeviceLockState.LOCKED, noProfileLocked = false, lockReason = null)
        )

        val shouldLoadConfig = viewModel.debugApplyUnlockResponseForTest(
            status = "LOCKED",
            message = "Device profile not linked"
        )

        val state = viewModel.state.value
        assertEquals(false, shouldLoadConfig)
        assertEquals(DeviceLockState.LOCKED, state.lockState)
        assertTrue(state.noProfileLocked)
        assertEquals(LauncherViewModel.NO_PROFILE_UNLOCK_BLOCKED_MESSAGE, state.unlockError)
    }

    @Test
    fun unlockActiveResponse_shouldClearNoProfileAndRequestConfigLoad() {
        val viewModel = LauncherViewModel()
        viewModel.debugSetStateForTest(
            LauncherUiState(
                lockState = DeviceLockState.LOCKED,
                noProfileLocked = true,
                lockReason = LauncherViewModel.NO_PROFILE_LOCKED_MESSAGE,
                unlockError = LauncherViewModel.NO_PROFILE_UNLOCK_BLOCKED_MESSAGE
            )
        )

        val shouldLoadConfig = viewModel.debugApplyUnlockResponseForTest(
            status = "ACTIVE",
            message = "Unlocked"
        )

        val state = viewModel.state.value
        assertEquals(true, shouldLoadConfig)
        assertEquals(false, state.noProfileLocked)
        assertEquals(null, state.lockReason)
        assertEquals(null, state.unlockError)
    }

    @Test
    fun unlockAlreadyUnlockedWhileRemoteLocked_shouldStayLockedAndShowRemoteLockMessage() {
        val viewModel = LauncherViewModel()
        viewModel.debugSetStateForTest(
            LauncherUiState(
                lockState = DeviceLockState.LOCKED,
                noProfileLocked = false,
                lockReason = null,
                lockContainmentStatus = "FULL"
            )
        )

        val shouldLoadConfig = viewModel.debugApplyUnlockResponseForTest(
            status = "ACTIVE",
            message = "Already unlocked"
        )

        val state = viewModel.state.value
        assertEquals(false, shouldLoadConfig)
        assertEquals(DeviceLockState.LOCKED, state.lockState)
        assertEquals(false, state.noProfileLocked)
        assertEquals(LauncherViewModel.REMOTE_SCREEN_UNLOCK_UNSUPPORTED_MESSAGE, state.unlockError)
    }

    @Test
    fun unlockAlreadyUnlockedWhileCommandScreenLocked_shouldClearCommandLock() {
        val viewModel = LauncherViewModel()
        viewModel.debugSetStateForTest(
            LauncherUiState(
                lockState = DeviceLockState.LOCKED,
                noProfileLocked = false,
                commandScreenLocked = true,
                lockReason = LauncherViewModel.COMMAND_SCREEN_LOCKED_MESSAGE,
                lockContainmentStatus = "FULL"
            )
        )

        val shouldLoadConfig = viewModel.debugApplyUnlockResponseForTest(
            status = "ACTIVE",
            message = "Already unlocked"
        )

        val state = viewModel.state.value
        assertEquals(true, shouldLoadConfig)
        assertEquals(DeviceLockState.ACTIVE, state.lockState)
        assertEquals(false, state.commandScreenLocked)
        assertEquals(null, state.unlockError)
        assertEquals(false, state.lockOverlayActive)
        assertEquals(null, state.lockOverlayReason)
    }

    @Test
    fun launcherUiState_shouldPrioritizeAdminOverlayOverCommandLock() {
        val state = LauncherUiState(
            lockState = DeviceLockState.LOCKED,
            adminLocked = true,
            commandScreenLocked = true,
            noProfileLocked = true
        )

        assertEquals(true, state.lockOverlayActive)
        assertEquals(LockOverlayReason.ADMIN_LOCK, state.lockOverlayReason)
    }

    @Test
    fun launcherUiState_shouldExposeCommandOverlaySeparatelyFromAdminLock() {
        val state = LauncherUiState(
            lockState = DeviceLockState.LOCKED,
            adminLocked = false,
            commandScreenLocked = true
        )

        assertEquals(true, state.lockOverlayActive)
        assertEquals(LockOverlayReason.COMMAND_LOCK_SCREEN, state.lockOverlayReason)
    }

    @Test
    fun configSourceCommandWhileCommandScreenLocked_shouldStayLockedUntilPasswordUnlock() {
        val viewModel = LauncherViewModel()
        val method = LauncherViewModel::class.java.getDeclaredMethod(
            "shouldStayLockedOnConfigUpdate",
            DeviceLockState::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            String::class.java
        ).apply { isAccessible = true }

        val shouldStayLocked = method.invoke(
            viewModel,
            DeviceLockState.LOCKED,
            false,
            false,
            true,
            "command:refresh_config"
        ) as Boolean

        assertEquals(true, shouldStayLocked)
    }

    @Test
    fun configSourceRefreshWhileNoProfileLocked_shouldBecomeActiveAfterConfig() {
        val viewModel = LauncherViewModel()
        val method = LauncherViewModel::class.java.getDeclaredMethod(
            "shouldStayLockedOnConfigUpdate",
            DeviceLockState::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            String::class.java
        ).apply { isAccessible = true }

        val shouldStayLocked = method.invoke(
            viewModel,
            DeviceLockState.LOCKED,
            true,
            false,
            false,
            "refresh"
        ) as Boolean

        assertEquals(false, shouldStayLocked)
    }

    @Test
    fun configSourceRefreshWhileAdminLocked_shouldFollowServerAdminLockState() {
        val viewModel = LauncherViewModel()
        val method = LauncherViewModel::class.java.getDeclaredMethod(
            "shouldStayLockedOnConfigUpdate",
            DeviceLockState::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            String::class.java
        ).apply { isAccessible = true }

        val shouldStayLocked = method.invoke(
            viewModel,
            DeviceLockState.LOCKED,
            false,
            true,
            true,
            "refresh"
        ) as Boolean

        assertEquals(false, shouldStayLocked)
    }

    private fun sampleConfig(lockPrivateDnsConfig: Boolean): DeviceConfigResponse =
        DeviceConfigResponse(
            userCode = "TEST005",
            allowedApps = listOf("com.example.alpha", "com.example.beta"),
            disableWifi = true,
            disableBluetooth = false,
            disableCamera = true,
            disableStatusBar = true,
            kioskMode = true,
            blockUninstall = true,
            lockPrivateDnsConfig = lockPrivateDnsConfig,
            lockVpnConfig = false,
            blockDebuggingFeatures = false,
            disableUsbDataSignaling = false,
            disallowSafeBoot = false,
            disallowFactoryReset = false,
            configVersionEpochMillis = 123456789L
        )
}
