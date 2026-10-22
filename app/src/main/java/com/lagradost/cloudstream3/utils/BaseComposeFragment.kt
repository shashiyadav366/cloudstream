package com.lagradost.cloudstream3.utils

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream4.compose.Screen
import com.lagradost.cloudstream4.compose.createComposeView
import com.mihon.presentation.settings.SearchableSettings

abstract class BaseComposeFragment : Fragment(), Screen {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = createComposeView(inflater, container, savedInstanceState)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        this.view?.let { view ->
            fixSystemBarsPadding(
                view,
                padLeft = isLayout(TV or EMULATOR),
                padBottom = isLandscape()
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fixSystemBarsPadding(
            view,
            padLeft = isLayout(TV or EMULATOR),
            padBottom = isLandscape()
        )
        if (this is SearchableSettings && isLayout(TV or EMULATOR)) {
            // The screen we are coming from gives up the focus, so this screen has to take it,
            // otherwise the d-pad would not do anything here.
            requestFocusWhenReady(view)
        }
    }

    /**
     * Requests the focus for this screen until it has it.
     *
     * The content of a compose screen is not available right away (the preferences are loaded
     * asynchronously), so a single request can fail and has to be retried.
     */
    private fun requestFocusWhenReady(view: View) {
        var attempts = 0
        val request = object : Runnable {
            override fun run() {
                if (!view.isAttachedToWindow || view.hasFocus()) return
                view.requestFocus()
                if (!view.hasFocus() && attempts++ < 15) {
                    view.postDelayed(this, 200)
                }
            }
        }
        view.postDelayed(request, 200)
    }
}

abstract class BaseDialogComposeFragment : DialogFragment(), Screen {
    var systemBarsAddPadding = isLayout(TV or EMULATOR)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = createComposeView(inflater, container, savedInstanceState)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dialog?.window?.decorView?.let { view ->
            fixSystemBarsPadding(
                view,
                padLeft = systemBarsAddPadding,
                padBottom = systemBarsAddPadding || isLandscape()
            )
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setWindowAnimations(R.style.DialogFullscreenPlayer)
    }

    override fun getTheme(): Int {
        return R.style.DialogFullscreenPlayer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.decorView?.let { view ->
            fixSystemBarsPadding(
                view,
                padLeft = systemBarsAddPadding,
                padBottom = systemBarsAddPadding || isLandscape()
            )
        }
    }
}