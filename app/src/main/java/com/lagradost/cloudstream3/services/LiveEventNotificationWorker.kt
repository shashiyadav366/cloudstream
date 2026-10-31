package com.lagradost.cloudstream3.services

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.home.LiveEventNotifier

/**
 * Fallback one-time worker for an event's advertised start time. The exact alarm is
 * the primary path; this still runs if the alarm was unavailable or lost.
 */
class LiveEventNotificationWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val event = LiveEventNotifier.notificationFromWorkData(inputData)
            ?: return Result.success()

        return try {
            LiveEventNotifier.notifyScheduledEvent(context, event)
            LiveEventNotifier.clearScheduledEntry(context, event.key)
            Result.success()
        } catch (t: Throwable) {
            logError(t)
            Result.retry()
        }
    }
}
