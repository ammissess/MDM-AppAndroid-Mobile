package com.example.mdmapplication.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatteryInfo(
    val levelPercent: Int,
    val isCharging: Boolean
)

fun Context.readBatteryInfo(): BatteryInfo {
    val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return BatteryInfo(levelPercent = -1, isCharging = false)

    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val percent = ((level * 100f) / scale).toInt().coerceIn(0, 100)

    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

    return BatteryInfo(levelPercent = percent, isCharging = charging)
}