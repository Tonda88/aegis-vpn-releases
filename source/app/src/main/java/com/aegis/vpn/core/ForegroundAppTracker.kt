package com.aegis.vpn.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * Tracks the package that is currently in the foreground by reading usage
 * events. It requires the "usage access" special permission.
 *
 * Both the query bounds and the timestamps of the returned events use the
 * wall clock timebase, so the cursor can be advanced directly.
 */
class ForegroundAppTracker(context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private var lastEventTime = System.currentTimeMillis() - SEED_WINDOW_MS

    var current: String? = null
        private set

    /** Polls for new usage events and returns the package in the foreground. */
    fun refresh(): String? {
        val manager = usageStatsManager ?: return current
        val endTime = System.currentTimeMillis() + 1000
        if (endTime <= lastEventTime) return current

        val events = runCatching { manager.queryEvents(lastEventTime, endTime) }.getOrNull()
            ?: return current

        val event = UsageEvents.Event()
        var previous = current
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> current = event.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (current == event.packageName) current = null
                }
            }
            if (event.timeStamp > lastEventTime) lastEventTime = event.timeStamp
        }
        if (current != previous) {
            Log.d(TAG, "foreground=$current")
        }
        return current
    }

    private companion object {
        const val TAG = "ForegroundAppTracker"
        /** How far back the first query looks, so the state is known at once. */
        const val SEED_WINDOW_MS = 120_000L
    }
}
