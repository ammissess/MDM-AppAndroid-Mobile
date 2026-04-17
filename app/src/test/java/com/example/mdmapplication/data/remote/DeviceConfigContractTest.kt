package com.example.mdmapplication.data.remote

import com.example.mdmapplication.ui.launcher.LauncherViewModel
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
