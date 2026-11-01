package com.lagradost.cloudstream3.services

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.home.LiveEventNotifier
import com.lagradost.cloudstream3.ui.home.LiveEventSchedule
import java.util.concurrent.TimeUnit

/**
 * Keeps the live-event schedule fresh in the background. The one-time notification
 * workers still fire at each match start; this worker only refreshes the schedule
 * so the app does not need to be opened before every match.
 */
class LiveEventScheduleWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    companion object {
        private const val WORK_NAME = "live_event_schedule_work"
        private const val IMMEDIATE_WORK_NAME = "live_event_schedule_now"
        private const val WORK_TAG = "live_event_schedule_work"
        private const val REFRESH_INTERVAL_HOURS = 6L

        private fun networkConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueuePeriodicWork(context: Context?) {
            if (context == null) return

            val workManager = WorkManager.getInstance(context)
            if (!LiveEventNotifier.isEnabled(context)) {
                workManager.cancelUniqueWork(WORK_NAME)
                workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
                return
            }

            val request = PeriodicWorkRequestBuilder<LiveEventScheduleWorker>(
                REFRESH_INTERVAL_HOURS,
                TimeUnit.HOURS
            )
                .addTag(WORK_TAG)
                .setConstraints(networkConstraints())
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Fetches once immediately after the user enables notifications. */
        fun enqueueImmediateRefresh(context: Context?) {
            if (context == null || !LiveEventNotifier.isEnabled(context)) return

            val request = OneTimeWorkRequestBuilder<LiveEventScheduleWorker>()
                .addTag(WORK_TAG)
                .setConstraints(networkConstraints())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancelPeriodicWork(context: Context?) {
            if (context == null) return
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(WORK_NAME)
                cancelUniqueWork(IMMEDIATE_WORK_NAME)
            }
        }
    }

    override suspend fun doWork(): Result {
        if (!LiveEventNotifier.isEnabled(applicationContext)) {
            return Result.success()
        }

        return try {
            // Background workers run in a fresh process. Load only the live-event
            // providers first; the rest of the plugins are not needed here.
            if (LiveEventSchedule.findApis().isEmpty()) {
                PluginManager.loadLiveEventPlugins(applicationContext)
            }
            if (LiveEventSchedule.findApis().isEmpty()) {
                PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(
                    applicationContext
                )
                PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(
                    applicationContext,
                    false
                )
            }

            val schedule = LiveEventSchedule.fetchFirstAvailable()
                ?: return Result.retry()

            LiveEventNotifier.scheduleUpcomingEvents(
                applicationContext,
                schedule.cards
            )
            Result.success()
        } catch (t: Throwable) {
            logError(t)
            Result.retry()
        }
    }
}
