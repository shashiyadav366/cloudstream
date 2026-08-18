
package com.lagradost.cloudstream3.ui.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.enableEdgeToEdgeCompat

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private var currentInstance: PlayerActivity? = null
    }

    private var wasInPip = false
    private var isActivityResumed = false
    private var generatorPlayer: GeneratorPlayer? = null

    private val pipCloseHandler = Handler(Looper.getMainLooper())

    // Long enough to outlast the full-screen transition when the PiP is expanded
    // back to the player (onResume cancels the runnable), but short enough that a
    // real PiP dismissal still releases the player quickly.
    private val pipCloseDelayMs = 1000L

    /**
     * Runs after the PiP window has been closed (❌ button, swipe-to-dismiss, or the
     * system dismissing a mostly off-screen window). It fully releases the player and
     * removes the media notification so nothing keeps playing in the background.
     *
     * This is scheduled from [onStop] and from [onPictureInPictureModeChanged] when the
     * PiP exits. That callback also fires when the PiP is expanded back to the
     * full-screen player, but [onResume] then runs first (well within the delay above)
     * and cancels this runnable while clearing [wasInPip], so expanded playback is
     * never killed. While the PiP window is still shown (e.g. the user pressed Home),
     * [isInPictureInPictureMode] is true, so the runnable is a no-op.
     */
    private val pipCloseRunnable = Runnable {
        if (wasInPip && !isInPictureInPictureMode && !isActivityResumed) {
            // Release the player (not just pause) so an in-flight notification
            // play/pause action cannot resume playback.
            resolveGeneratorPlayer()?.closePlayerForActivityDestroy()

            // Safety net: the player/fragment may already be torn down while the
            // media session notification lingers in the shade, so cancel it
            // directly to guarantee the notification is removed.
            NotificationManagerCompat.from(this).cancel(GeneratorPlayer.NOTIFICATION_ID)

            if (!isFinishing) {
                // PiP was closed but the system did not finish the activity, so
                // close it to avoid a hidden activity playing audio.
                finishAndRemoveTask()
            }
        }
    }

    private fun schedulePipClose() {
        pipCloseHandler.removeCallbacks(pipCloseRunnable)
        pipCloseHandler.postDelayed(pipCloseRunnable, pipCloseDelayMs)
    }

    // The fragment tag lookup can return null while the activity is being torn
    // down, so prefer the direct reference captured at creation time.
    private fun resolveGeneratorPlayer(): GeneratorPlayer? {
        return generatorPlayer
            ?: supportFragmentManager.findFragmentByTag("GeneratorPlayer") as? GeneratorPlayer
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // If another PlayerActivity is still alive/in PiP,
        // remove it before creating this player.
        currentInstance?.let { oldActivity ->
            if (oldActivity !== this && !oldActivity.isFinishing) {
                oldActivity.finishAndRemoveTask()
            }
        }

        currentInstance = this

        super.onCreate(savedInstanceState)

        CommonActivity.loadThemes(this)
        CommonActivity.init(this)
        enableEdgeToEdgeCompat()

        setContentView(R.layout.activity_player)

        if (savedInstanceState == null) {
            val player = GeneratorPlayer().apply {
                arguments = intent.extras
            }
            generatorPlayer = player

            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.player_container,
                    player,
                    "GeneratorPlayer"
                )
                .commit()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        if (!CommonActivity.isPipDesired) return

        CommonActivity.onUserLeaveHint(this)
    }

    override fun onResume() {
        super.onResume()

        isActivityResumed = true

        // PiP was expanded back to the player.
        // Cancel the pending cleanup so playback remains seamless.
        wasInPip = false
        pipCloseHandler.removeCallbacks(pipCloseRunnable)

        CommonActivity.setActivityInstance(this)
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)

        if (isInPictureInPictureMode) {
            wasInPip = true
            pipCloseHandler.removeCallbacks(pipCloseRunnable)
        } else {
            // PiP window was dismissed (❌ / swipe / gesture). This also fires on
            // expand, but onResume() runs right after and cancels the runnable, so
            // expanded playback is never killed. Scheduling here is required because
            // some OS variants dismiss the PiP without ever calling onStop().
            schedulePipClose()
        }
    }

    override fun onStop() {
        super.onStop()

        // Safety net for OS variants that dismiss the PiP without firing
        // onPictureInPictureModeChanged(false), or that finish the activity on
        // PiP close. When the PiP is instead expanded back to the full-screen
        // player, onResume() cancels this runnable before it can execute.
        // While the PiP window is still shown, isInPictureInPictureMode is true
        // so the runnable is a no-op.
        if (wasInPip && !isChangingConfigurations) {
            schedulePipClose()
        }
    }

    override fun onDestroy() {
        pipCloseHandler.removeCallbacks(pipCloseRunnable)

        if (!isChangingConfigurations) {
            resolveGeneratorPlayer()?.closePlayerForActivityDestroy()

            // Guarantee the media notification is removed even if the player was
            // already torn down before the fragment lookup could resolve it.
            NotificationManagerCompat.from(this).cancel(GeneratorPlayer.NOTIFICATION_ID)
        }

        generatorPlayer = null

        if (currentInstance === this) {
            currentInstance = null
        }

        super.onDestroy()
    }
}
