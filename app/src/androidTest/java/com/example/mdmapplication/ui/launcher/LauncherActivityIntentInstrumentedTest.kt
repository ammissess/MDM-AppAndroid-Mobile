package com.example.mdmapplication.ui.launcher

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherActivityIntentInstrumentedTest {

    @Test
    fun createRuntimeWakeIntent_setsWakeActionReasonAndLaunchFlags() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val intent = LauncherActivity.createRuntimeWakeIntent(context, "ticket9-harness")

        assertEquals(LauncherActivity.ACTION_RUNTIME_WAKE, intent.action)
        assertEquals("ticket9-harness", intent.getStringExtra(LauncherActivity.EXTRA_WAKE_REASON))

        val requiredFlags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP

        assertTrue((intent.flags and requiredFlags) == requiredFlags)
    }
}
