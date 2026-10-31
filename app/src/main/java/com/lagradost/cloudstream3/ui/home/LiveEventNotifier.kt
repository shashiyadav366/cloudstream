package com.lagradost.cloudstream3.ui.home

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.getSourcePlugin
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.services.LiveEventNotificationAlarmReceiver
import com.lagradost.cloudstream3.services.LiveEventNotificationWorker
import com.lagradost.cloudstream3.ui.result.START_ACTION_RESUME_LATEST
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getImageBitmapFromUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

const val LIVE_CRICKET_EVENTS_PREF_KEY = "live_cricket_events_key"

internal data class LiveEventNotification(
    val key: String,
    val name: String,
    val url: String,
    val apiName: String,
    val posterUrl: String?,
    val posterHeaders: Map<String, String>? = null,
)

/**
 * Detects live cricket cards, schedules their start-time notifications, and shows
 * them. Exact alarms wake the device at the match time even when the app is closed;
 * WorkManager is kept as a fallback if an exact alarm cannot be scheduled.
 */
object LiveEventNotifier {
    private const val WORK_TAG = "live_cricket_event_work"
    private const val WORK_KEY_EVENT_KEY = "live_event_key"
    private const val WORK_KEY_EVENT_NAME = "live_event_name"
    private const val WORK_KEY_EVENT_URL = "live_event_url"
    private const val WORK_KEY_EVENT_API_NAME = "live_event_api_name"
    private const val WORK_KEY_EVENT_POSTER = "live_event_poster"
    private const val WORK_KEY_EVENT_POSTER_HEADERS = "live_event_poster_headers"
    private const val TAG = "LiveEventNotifier"

    private const val CHANNEL_ID = "cloudstream3.live_events"
    private const val CHANNEL_NAME = "Live Cricket Events"
    private const val CHANNEL_DESCRIPTION =
        "Notifications when a live cricket event starts"
    private const val SHOWN_KEYS = "live_cricket_shown_keys"
    private const val SCHEDULED_KEYS = "live_cricket_scheduled_keys"
    private const val FALLBACK_DELAY_MS = 5 * 60 * 1000L

    // The alarm receiver and the worker both have a short execution window, so the
    // poster is an optional bonus: once this timeout passes the notification is
    // posted text-only instead of waiting for a slow or dead image host.
    private const val POSTER_LOAD_TIMEOUT_MS = 6_000L

    // Notification bitmaps are copied into System UI, so they are decoded small.
    // The aspect ratio is preserved; these are only upper bounds.
    private const val LARGE_ICON_MAX_PX = 512
    private const val BIG_PICTURE_MAX_PX = 1024

    // WorkManager input data is limited to 10 KB in total. Headers are tiny in
    // practice, but never let an unusual map break scheduling entirely.
    private const val MAX_POSTER_HEADERS_JSON_LENGTH = 4_000

    private val eventTimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private val timeRegex = Regex("[?&]time=([^&]+)")
    private val datePatterns = listOf(
        "MMM dd, yyyy hh:mm a",
        "MMM d, yyyy hh:mm a",
        "MMM dd, yyyy HH:mm",
        "MMM d, yyyy HH:mm",
    )

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(LIVE_CRICKET_EVENTS_PREF_KEY, false)

    fun isCurrentlyLive(event: SearchResponse): Boolean {
        val poster = event.posterUrl ?: return false
        return poster.contains("isLive=true") && !poster.contains("isEnded=true")
    }

    fun isLiveOrToday(event: SearchResponse): Boolean {
        val poster = event.posterUrl ?: return false
        if (poster.contains("isEnded=true")) return false
        if (poster.contains("isLive=true")) return true
        val startTime = parseStartTime(event) ?: return false
        return isSameDay(startTime, System.currentTimeMillis())
    }

    /**
     * Schedules an exact alarm (plus a WorkManager fallback) for every upcoming
     * event. Existing alarms are left alone when the start time has not changed.
     */
    fun scheduleUpcomingEvents(context: Context, events: List<SearchResponse>) {
        if (!isEnabled(context)) return

        val now = System.currentTimeMillis()
        val scheduled = readScheduled(context)
        val currentKeys = mutableSetOf<String>()
        val scheduledSlots = mutableSetOf<String>()

        events.forEach { event ->
            val startTime = parseStartTime(event)
            val key = keyFor(event, startTime)

            // A match can come from both PlayFy and Cricify. Keep one scheduled
            // notification per event instead of notifying twice.
            if (startTime == null || startTime <= now + 5_000L) {
                currentKeys += key
                return@forEach
            }
            val eventSlot = "${event.name.trim().lowercase()}|$startTime"
            if (!scheduledSlots.add(eventSlot)) return@forEach

            currentKeys += key
            if (scheduled[key] != startTime) {
                val notification = event.toNotification(key)
                scheduleNotification(context, notification, startTime - now)
                scheduled[key] = startTime
            }
        }

        scheduled.keys
            .filter { it !in currentKeys }
            .forEach { key ->
                WorkManager.getInstance(context).cancelUniqueWork(workName(key))
                cancelAlarm(context, key)
                scheduled.remove(key)
            }
        writeScheduled(context, scheduled)
    }

    fun cancelScheduledEvents(context: Context) {
        readScheduled(context).keys.forEach { key ->
            cancelAlarm(context, key)
        }
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(SCHEDULED_KEYS)
            .apply()
    }

    fun clearScheduledEntry(context: Context, key: String) {
        cancelAlarm(context, key)
        val scheduled = readScheduled(context)
        scheduled.remove(key)
        writeScheduled(context, scheduled)
    }

    internal fun cancelScheduledFallback(context: Context, key: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(key))
    }

    internal suspend fun notifyScheduledEvent(
        context: Context,
        event: LiveEventNotification,
        loadPoster: Boolean = true
    ) {
        showIfAllowed(context, event, loadPoster)
    }

    internal fun notificationFromWorkData(data: Data): LiveEventNotification? =
        notificationFrom { key -> data.getString(key) }

    internal fun notificationFromAlarmIntent(intent: Intent): LiveEventNotification? =
        notificationFrom { key -> intent.getStringExtra(key) }

    private fun notificationFrom(getString: (String) -> String?): LiveEventNotification? {
        val key = getString(WORK_KEY_EVENT_KEY) ?: return null
        val name = getString(WORK_KEY_EVENT_NAME) ?: return null
        val url = getString(WORK_KEY_EVENT_URL) ?: return null
        val apiName = getString(WORK_KEY_EVENT_API_NAME) ?: return null
        return LiveEventNotification(
            key = key,
            name = name,
            url = url,
            apiName = apiName,
            posterUrl = getString(WORK_KEY_EVENT_POSTER),
            // Absent for notifications scheduled by older app versions; those
            // fall back to an unauthenticated poster request.
            posterHeaders = getString(WORK_KEY_EVENT_POSTER_HEADERS).toPosterHeaders(),
        )
    }

    private fun LiveEventNotification.toWorkData(): Data =
        Data.Builder()
            .putString(WORK_KEY_EVENT_KEY, key)
            .putString(WORK_KEY_EVENT_NAME, name)
            .putString(WORK_KEY_EVENT_URL, url)
            .putString(WORK_KEY_EVENT_API_NAME, apiName)
            .putString(WORK_KEY_EVENT_POSTER, posterUrl)
            .putString(WORK_KEY_EVENT_POSTER_HEADERS, posterHeaders.toPosterHeadersJson())
            .build()

    private fun LiveEventNotification.toAlarmIntent(context: Context): Intent =
        Intent(context, LiveEventNotificationAlarmReceiver::class.java).apply {
            putExtra(WORK_KEY_EVENT_KEY, key)
            putExtra(WORK_KEY_EVENT_NAME, name)
            putExtra(WORK_KEY_EVENT_URL, url)
            putExtra(WORK_KEY_EVENT_API_NAME, apiName)
            putExtra(WORK_KEY_EVENT_POSTER, posterUrl)
            putExtra(WORK_KEY_EVENT_POSTER_HEADERS, posterHeaders.toPosterHeadersJson())
        }

    /**
     * Poster headers are needed again hours later, in a fresh process, to download
     * protected images. They are stored as JSON so header values containing
     * separators survive the round trip.
     */
    private fun Map<String, String>?.toPosterHeadersJson(): String? {
        val headers = this
        if (headers.isNullOrEmpty()) return null
        val json = runCatching { JSONObject(headers).toString() }
            .onFailure { logError(it) }
            .getOrNull() ?: return null
        if (json.length > MAX_POSTER_HEADERS_JSON_LENGTH) {
            Log.w(TAG, "Poster headers too large to store; scheduling without them")
            return null
        }
        return json
    }

    private fun String?.toPosterHeaders(): Map<String, String>? {
        val raw = this
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence()
                .associateWith { key -> json.optString(key) }
        }.onFailure { logError(it) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun SearchResponse.toNotification(key: String) = LiveEventNotification(
        key = key,
        name = name,
        url = url,
        apiName = apiName,
        posterUrl = posterUrl,
        posterHeaders = posterHeaders,
    )

    private fun scheduleNotification(
        context: Context,
        event: LiveEventNotification,
        delayMillis: Long
    ) {
        scheduleAlarm(context, event, delayMillis)

        // WorkManager is a fallback if the exact alarm is unavailable or the device
        // rebooted. The small delay avoids posting twice at the same moment.
        val request = OneTimeWorkRequestBuilder<LiveEventNotificationWorker>()
            .setInitialDelay(delayMillis + FALLBACK_DELAY_MS, TimeUnit.MILLISECONDS)
            .setInputData(event.toWorkData())
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(event.key),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun scheduleAlarm(
        context: Context,
        event: LiveEventNotification,
        delayMillis: Long
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        val triggerAt = System.currentTimeMillis() + delayMillis
        val pendingIntent = alarmPendingIntent(context, event) ?: return

        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !alarmManager.canScheduleExactAlarms() -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                    } else {
                        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    }
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }

                else -> {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            }
        } catch (t: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    private fun alarmPendingIntent(
        context: Context,
        event: LiveEventNotification
    ): PendingIntent? {
        return PendingIntentCompat.getBroadcast(
            context,
            event.key.hashCode(),
            event.toAlarmIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            false
        )
    }

    private fun cancelAlarm(context: Context, key: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        val pendingIntent = PendingIntentCompat.getBroadcast(
            context,
            key.hashCode(),
            Intent(context, LiveEventNotificationAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            false
        ) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun readScheduled(context: Context): MutableMap<String, Long> =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(SCHEDULED_KEYS, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val separator = entry.lastIndexOf('|')
                if (separator <= 0) return@mapNotNull null
                val startTime = entry.substring(separator + 1).toLongOrNull()
                    ?: return@mapNotNull null
                entry.substring(0, separator) to startTime
            }
            .toMap()
            .toMutableMap()

    private fun writeScheduled(context: Context, scheduled: Map<String, Long>) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putStringSet(
                SCHEDULED_KEYS,
                scheduled.map { "${it.key}|${it.value}" }.toSet()
            )
            .apply()
    }

    private fun workName(key: String) = "live_cricket_event_${key.hashCode()}"

    private fun dateKey(): String = dayKey(System.currentTimeMillis())

    private fun isSameDay(first: Long, second: Long): Boolean =
        dayKey(first) == dayKey(second)

    private fun dayKey(time: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = eventTimeZone
        }
        return format.format(Date(time))
    }

    private fun keyFor(
        event: SearchResponse,
        startTime: Long? = parseStartTime(event)
    ): String {
        val eventDay = startTime?.let { dayKey(it) } ?: dateKey()
        return "$eventDay|${event.getSourcePlugin().orEmpty()}|${event.name}"
    }

    @Synchronized
    private fun markShown(context: Context, key: String): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val shown = prefs.getStringSet(SHOWN_KEYS, emptySet()).orEmpty()
        if (key in shown) return false
        prefs.edit().putStringSet(SHOWN_KEYS, shown + key).apply()
        return true
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * CNC live-event cards put the start time in the poster URL, for example
     * "time=Oct+29%2C+2026+7%3A30+PM+IST".
     */
    private fun parseStartTime(event: SearchResponse): Long? {
        val poster = event.posterUrl ?: return null
        if (poster.contains("isEnded=true")) return null
        val rawTime = timeRegex.find(poster)?.groupValues?.get(1) ?: return null
        val decoded = runCatching { URLDecoder.decode(rawTime, "UTF-8") }.getOrNull()
            ?: return null
        val normalized = decoded.replace(Regex("\\s+"), " ").trim()
        val zone = listOf("IST", "UTC", "GMT")
            .firstOrNull { normalized.endsWith(" $it", ignoreCase = true) }
        val withoutZone = if (zone == null) {
            normalized
        } else {
            normalized.dropLast(zone.length + 1).trim()
        }
        val timeZone = when (zone?.uppercase()) {
            "UTC", "GMT" -> TimeZone.getTimeZone("UTC")
            else -> eventTimeZone
        }

        return datePatterns.firstNotNullOfOrNull { pattern ->
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                this.timeZone = timeZone
            }
            runCatching { format.parse(withoutZone)?.time }.getOrNull()
        }
    }

    private suspend fun showIfAllowed(
        context: Context,
        event: LiveEventNotification,
        loadPoster: Boolean
    ) {
        if (!isEnabled(context) || !canPostNotifications(context)) return
        if (!markShown(context, event.key)) return
        showNotification(context, event, loadPoster)
    }

    /**
     * Loads the event poster for the notification. Everything here is best effort:
     * a missing, slow, broken or unauthorised image must never stop the
     * notification itself from being posted.
     */
    private suspend fun loadPosterBitmap(
        context: Context,
        event: LiveEventNotification
    ): Bitmap? {
        val posterUrl = event.posterUrl
        if (posterUrl.isNullOrBlank()) {
            Log.d(TAG, "Poster skipped: event '${event.key}' has no poster url")
            return null
        }

        // Only the host is logged; query strings can carry signed tokens.
        val host = runCatching { posterUrl.toUri().host }.getOrNull()
        val headerCount = event.posterHeaders?.size ?: 0
        Log.d(
            TAG,
            "Poster load: host=$host headers=$headerCount (${event.posterHeaders?.keys?.joinToString() ?: "none"})"
        )

        val startedAt = SystemClock.elapsedRealtime()
        val bitmap = withTimeoutOrNull(POSTER_LOAD_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.getImageBitmapFromUrl(
                        posterUrl,
                        event.posterHeaders,
                        BIG_PICTURE_MAX_PX
                    )
                }.onFailure { logError(it) }.getOrNull()
            }
        }
        val durationMs = SystemClock.elapsedRealtime() - startedAt

        if (bitmap == null) {
            Log.w(
                TAG,
                "Poster unavailable after ${durationMs}ms (timeout=${POSTER_LOAD_TIMEOUT_MS}ms); " +
                    "falling back to text-only notification"
            )
            return null
        }

        Log.d(
            TAG,
            "Poster loaded in ${durationMs}ms: ${bitmap.width}x${bitmap.height}, " +
                "${bitmap.byteCount} bytes"
        )
        return bitmap
    }

    /**
     * Bounds a bitmap for notification use while keeping its aspect ratio, so a
     * large poster cannot waste memory or exceed the notification IPC limits.
     */
    private fun Bitmap.scaledToFit(maxSizePx: Int): Bitmap {
        val longestSide = maxOf(width, height)
        if (longestSide <= maxSizePx) return this

        val scale = maxSizePx.toFloat() / longestSide
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
        }.onFailure { logError(it) }.getOrElse { this }
    }

    private suspend fun showNotification(
        context: Context,
        event: LiveEventNotification,
        loadPoster: Boolean
    ) {
        context.createNotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            CHANNEL_DESCRIPTION
        )

        val notificationId = event.key.hashCode()
        val intent = Intent(context, MainActivity::class.java).apply {
            data = event.url.toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.API_NAME_EXTRA_KEY, event.apiName)
            // Open the live event and start playback immediately instead of
            // stopping on the result page and waiting for a second tap.
            putExtra(MainActivity.START_ACTION_EXTRA_KEY, START_ACTION_RESUME_LATEST)
        }
        val pendingIntent = PendingIntentCompat.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            false
        )

        val poster = if (loadPoster) loadPosterBitmap(context, event) else null

        val notificationText = context.getString(R.string.live_event_started_text)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_notifications_active_24)
            .setContentTitle(context.getString(R.string.live_event_started_title, event.name))
            .setContentText(notificationText)
            .setColor(context.colorFromAttribute(R.attr.colorPrimary))
            .setColorized(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pendingIntent)

        if (poster != null) {
            // Collapsed: the small large-icon thumbnail. Android System UI and OEM
            // skins (including HyperOS) decide whether to draw it collapsed.
            builder.setLargeIcon(poster.scaledToFit(LARGE_ICON_MAX_PX))
            // Expanded: the same poster as the big picture. bigLargeIcon(null) keeps
            // the expanded view from shrinking the image into the icon slot.
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(poster)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText(notificationText)
            )
            Log.d(TAG, "Notification ${event.key.hashCode()} built with poster")
        } else {
            builder.setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.live_event_started_title, event.name) +
                        ". " + notificationText
                )
            )
            Log.d(TAG, "Notification $notificationId built text-only (no poster)")
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }
}
