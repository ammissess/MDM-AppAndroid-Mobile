package com.example.mdmapplication.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.test.uiautomator.UiDevice

internal const val TARGET_PACKAGE = "com.example.mdmapplication"
internal const val DEFAULT_ITERATIONS = 10
internal const val MAIN_ACTIVITY_COMPONENT = "$TARGET_PACKAGE/.MainActivity"
internal const val LAUNCHER_ACTIVITY_COMPONENT = "$TARGET_PACKAGE/.ui.launcher.LauncherActivity"
internal const val ACTION_RUNTIME_WAKE = "com.example.mdmapplication.action.RUNTIME_WAKE"
internal const val EXTRA_WAKE_REASON = "extra_wake_reason"

internal fun mainActivityIntent(): Intent =
    Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(TARGET_PACKAGE, "$TARGET_PACKAGE.MainActivity")
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

internal fun launcherActivityIntent(): Intent =
    Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(TARGET_PACKAGE, "$TARGET_PACKAGE.ui.launcher.LauncherActivity")
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

internal fun UiDevice.startComponentWithAmStart(componentName: String) {
    executeShellCommand("am start -W -n $componentName")
    waitForIdle(2_000)
}

internal fun UiDevice.startRuntimeWakeWithAmStart() {
    executeShellCommand(
        "am start -W -n $LAUNCHER_ACTIVITY_COMPONENT " +
            "-a $ACTION_RUNTIME_WAKE " +
            "--es $EXTRA_WAKE_REASON macrobenchmark:runtime_wake " +
            "-f 0x34000000"
    )
    waitForIdle(2_000)
}
