package com.lagradost.cloudstream3.ui.settings.extensions

import android.view.KeyEvent
import android.view.View
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.R

/**
 * Makes a plugin/repository row and its icons fully usable with a D-pad on TV.
 *
 * - Every icon becomes focusable and clickable.
 * - The row stays marked as selected while it or one of its icons has the focus, so the active
 *   item is always visible. The icons use a focus only outline, so only the focused icon is
 *   highlighted.
 * - Left/right move the focus between the row and its icons, because the vertical list layout
 *   manager intercepts those keys and would otherwise never reach them.
 * - OK/enter clicks only the focused element, so the row and its icons never fire together.
 */
fun applyTvRowFocus(row: View, vararg icons: View) {
    val focusables = listOf(row) + icons.filter { it.isVisible }

    fun updateSelection() {
        // hasFocus() is true for the whole row while the row or one of its icons is focused.
        row.isSelected = row.hasFocus()
    }

    focusables.forEach { view ->
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnFocusChangeListener { v, _ ->
            // Posted, because the focus of the row is only updated after the child got it.
            v.post { updateSelection() }
        }
    }

    focusables.forEachIndexed { index, view ->
        view.setOnKeyListener { v, keyCode, event ->
            when (keyCode) {
                // Click only the focused element, so the row and its icons never fire together.
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP) {
                        v.performClick()
                    }
                    true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                    val next = focusables.getOrNull(index + 1)
                    if (next != null) {
                        next.requestFocus()
                        true
                    } else {
                        // The last icon jumps to the add repo button when it is on screen.
                        val addRepo = v.rootView.findViewById<View?>(R.id.add_repo_button_imageview)
                        if (addRepo != null) {
                            addRepo.requestFocus()
                            true
                        } else {
                            // Nothing to the right, let the list handle it.
                            false
                        }
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                    // The first element keeps the default behaviour so the user can leave the
                    // list to the navigation rail.
                    if (index > 0) {
                        focusables[index - 1].requestFocus()
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    updateSelection()
}
