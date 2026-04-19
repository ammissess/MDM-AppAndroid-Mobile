package com.example.mdmapplication.data.remote

import com.example.mdmapplication.ui.launcher.LauncherViewModel
import com.example.mdmapplication.ui.launcher.DeviceLockState
import com.example.mdmapplication.ui.launcher.LauncherUiState
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
    fun configSourceCommandWhileLocked_shouldStayLocked() {
        val viewModel = LauncherViewModel()
        val method = LauncherViewModel::class.java.getDeclaredMethod(
            "shouldStayLockedOnConfigUpdate",
            DeviceLockState::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val shouldStayLocked = method.invoke(
            viewModel,
            DeviceLockState.LOCKED,
            "command:refresh_config"
        ) as Boolean

        assertTrue(shouldStayLocked)
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
