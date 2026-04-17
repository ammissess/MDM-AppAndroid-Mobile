package com.example.mdmapplication.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceRuntimeIdentityInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        DeviceRuntimeIdentity.clearPendingFcmToken(context)
    }

    @Test
    fun stageGetAndClearPendingFcmToken_roundTripsExpectedValues() {
        DeviceRuntimeIdentity.clearPendingFcmToken(context)

        DeviceRuntimeIdentity.stagePendingFcmToken(
            context = context,
            token = "ticket9-token",
            updatedAtEpochMillis = 1_710_000_000_123,
        )

        val pending = DeviceRuntimeIdentity.getPendingFcmToken(context)
        assertNotNull(pending)
        assertEquals("ticket9-token", pending?.token)
        assertEquals(1_710_000_000_123, pending?.updatedAtEpochMillis)

        DeviceRuntimeIdentity.clearPendingFcmToken(context)
        assertNull(DeviceRuntimeIdentity.getPendingFcmToken(context))
    }
}
