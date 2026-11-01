package com.lagradost.cloudstream3.ui.subtitles

import androidx.fragment.app.FragmentActivity
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.lagradost.cloudstream3.mvvm.logError

/**
 * [ColorPickerDialog] inherits the default [androidx.fragment.app.DialogFragment.onDestroyView],
 * which dismisses its AppCompat dialog. When the host activity's window is already detached
 * (for example when the player is closed while the picker is open), that dismiss throws
 * "View ... not attached to window manager" and crashes the app while the activity is being
 * destroyed. This subclass makes the cleanup safe.
 */
class SafeColorPickerDialog : ColorPickerDialog() {
    override fun onDestroyView() {
        try {
            super.onDestroyView()
        } catch (e: IllegalArgumentException) {
            logError(e)
        }
    }
}

/** Shows this color picker using [SafeColorPickerDialog] instead of the crash-prone default. */
fun ColorPickerDialog.Builder.showSafe(
    activity: FragmentActivity,
    tag: String = "color-picker-dialog"
) {
    val source = create()
    SafeColorPickerDialog().apply {
        arguments = source.arguments
    }.show(activity.supportFragmentManager, tag)
}
