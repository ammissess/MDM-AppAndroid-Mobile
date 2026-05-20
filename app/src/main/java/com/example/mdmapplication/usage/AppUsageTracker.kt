package com.example.mdmapplication.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlin.math.max

data class AppUsageDelta(
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long
)

class AppUsageTracker(private val selfPackageName: String) {
    private var activePackageName: String? = null
    private var activeStartMs: Long = 0L

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun collect(context: Context, startMs: Long, endMs: Long): List<AppUsageDelta> {
        if (endMs <= startMs) return emptyList()
        val permissionGranted = hasUsageStatsPermission(context)
        if (!permissionGranted) {
            Log.i("MDM_USAGE", "tracker permission=false foreground=$activePackageName deltaMs=0")
            return emptyList()
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val rawDeltas = mutableListOf<AppUsageDelta>()
        val event = UsageEvents.Event()
        val events = usageStatsManager.queryEvents(startMs, endMs)

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName?.toString()?.trim().orEmpty()
            if (packageName.isBlank()) continue
            val eventTime = event.timeStamp.coerceIn(startMs, endMs)

            when {
                isForegroundEvent(event.eventType) -> {
                    appendActiveDelta(rawDeltas, startMs, eventTime)
                    activePackageName = packageName
                    activeStartMs = eventTime
                }

                isBackgroundEvent(event.eventType) -> {
                    if (activePackageName == packageName) {
                        appendActiveDelta(rawDeltas, startMs, eventTime)
                        activePackageName = null
                        activeStartMs = 0L
                    }
                }
            }
        }

        appendActiveDelta(rawDeltas, startMs, endMs)
        if (activePackageName != null) activeStartMs = endMs

        val deltas = rawDeltas
            .filter { it.packageName != selfPackageName && it.durationMs > 0 }
            .groupBy { it.packageName }
            .map { (packageName, rows) ->
                AppUsageDelta(
                    packageName = packageName,
                    startMs = rows.minOf { it.startMs },
                    endMs = rows.maxOf { it.endMs },
                    durationMs = rows.sumOf { it.durationMs }
                )
            }

        val foreground = activePackageName ?: "none"
        val deltaMs = deltas.sumOf { it.durationMs }
        Log.i("MDM_USAGE", "tracker permission=true foreground=$foreground deltaMs=$deltaMs items=${deltas.size}")
        return deltas
    }

    private fun appendActiveDelta(out: MutableList<AppUsageDelta>, windowStartMs: Long, endMs: Long) {
        val packageName = activePackageName ?: return
        val startedAt = max(activeStartMs, windowStartMs)
        if (endMs <= startedAt) return
        out += AppUsageDelta(
            packageName = packageName,
            startMs = startedAt,
            endMs = endMs,
            durationMs = endMs - startedAt
        )
    }

    private fun isForegroundEvent(eventType: Int): Boolean =
        eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && eventType == UsageEvents.Event.ACTIVITY_RESUMED)

    private fun isBackgroundEvent(eventType: Int): Boolean =
        eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && eventType == UsageEvents.Event.ACTIVITY_PAUSED) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && eventType == UsageEvents.Event.ACTIVITY_STOPPED)
}
