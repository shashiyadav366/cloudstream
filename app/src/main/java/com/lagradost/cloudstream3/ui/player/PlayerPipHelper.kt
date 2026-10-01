package com.lagradost.cloudstream3.ui.player

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import kotlin.math.roundToInt

object PlayerPipHelper {
    /** Is pip (Player in Player) supported, and enabled? */
    fun Context.isPIPPossible() : Boolean {
        return try {
            this.hasPIPEnabled() && this.hasPIPFeature()
        } catch (t : Throwable) {
            // While both hasPIPEnabled and hasPIPFeature should never throw, this catches it just in case
            logError(t)
            false
        }
    }

    /** Is pip enabled in app settings? */
    private fun Context.hasPIPEnabled(): Boolean {
        return try {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
            settingsManager?.getBoolean(
                getString(R.string.pip_enabled_key),
                true
            ) ?: true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }


    /**
     * Is pip supported by the OS?
     *
     * Source:
     * https://stackoverflow.com/questions/52594181/how-to-know-if-user-has-disabled-picture-in-picture-feature-permission
     * https://developer.android.com/guide/topics/ui/picture-in-picture
     * */
    private fun Context.hasPIPFeature(): Boolean =
        // OS Support
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                // Might have the feature, but OS blocked due to power drain
                this.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                // Might have been disabled by the user
                this.hasPIPPermission()

    /** Is pip enabled in the OS settings? */
    private fun Context.hasPIPPermission(): Boolean {
        val appOps =
            getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                android.os.Process.myUid(),
                packageName
            ) == AppOpsManager.MODE_ALLOWED
        } else true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getPen(activity: Activity, code: Int): PendingIntent {
        return PendingIntent.getBroadcast(
            activity,
            code,
            Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, code),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getRemoteAction(
        activity: Activity,
        id: Int,
        @StringRes title: Int,
        event: CSPlayerEvent
    ): RemoteAction {
        val text = activity.getString(title)
        return RemoteAction(
            Icon.createWithResource(activity, id),
            text,
            text,
            getPen(activity, event.value)
        )
    }

    
fun updatePIPModeActions(
    activity: Activity?,
    status: CSPlayerLoading,
    pipEnabled: Boolean,
    aspectRatio: Rational?
) {
    val isPipDesired = when (status) {
        CSPlayerLoading.IsBuffering,
        CSPlayerLoading.IsPlaying -> pipEnabled
        else -> false
    }

    // Always update the global state first.
    CommonActivity.isPipDesired = isPipDesired

    if (activity == null) return

    // For Android 12+, this is the switch that controls automatic PiP.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        safe {
            val builder = PictureInPictureParams.Builder()

            builder.setAutoEnterEnabled(
                isPipDesired &&
                    activity.isPIPPossible() &&
                    !activity.isFinishing &&
                    !activity.isDestroyed
            )

            aspectRatio?.toFloat()
                ?.coerceIn(0.41841f, 2.39f)
                ?.let {
                    builder.setAspectRatio(
                        Rational(
                            (it * 100000).roundToInt(),
                            100000
                        )
                    )
                }

            activity.setPictureInPictureParams(builder.build())
        }

        return
    }

    // Android O–R: PiP is entered manually from onUserLeaveHint().
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        safe {
            activity.setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(
                        aspectRatio?.toFloat()
                            ?.coerceIn(0.41841f, 2.39f)
                            ?.let {
                                Rational(
                                    (it * 100000).roundToInt(),
                                    100000
                                )
                            }
                    )
                    .build()
            )
        }
    }
}
    
        

        
    

    fun exitPip(activity: Activity?) {
    if (activity == null) return

    CommonActivity.isPipDesired = false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        safe {
            activity.setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAutoEnterEnabled(false)
                    .build()
            )
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        activity.isInPictureInPictureMode
    ) {
        activity.finishAndRemoveTask()
    }
}

/**
 * Manually enter Picture-in-Picture mode.
 * Used by the Minimize button in the player.
 */
fun enterPip(activity: Activity?) {
    if (activity == null) return

    CommonActivity.isPipDesired = true

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        activity.isPIPPossible() &&
        !activity.isInPictureInPictureMode
    ) {
        safe {
            activity.enterPictureInPictureMode()
        }
    }
}

}
