package com.lagradost.cloudstream3.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.home.LiveEventNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Exact alarm receiver for match start times. It wakes the device and posts the
 * notification immediately, without waiting for the app to be opened.
 */
class LiveEventNotificationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = LiveEventNotifier.notificationFromAlarmIntent(intent) ?: return
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                LiveEventNotifier.notifyScheduledEvent(
                    context.applicationContext,
                    event,
                    loadPoster = true
                )
                LiveEventNotifier.cancelScheduledFallback(
                    context.applicationContext,
                    event.key
                )
                LiveEventNotifier.clearScheduledEntry(
                    context.applicationContext,
                    event.key
                )
            } catch (t: Throwable) {
                logError(t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
