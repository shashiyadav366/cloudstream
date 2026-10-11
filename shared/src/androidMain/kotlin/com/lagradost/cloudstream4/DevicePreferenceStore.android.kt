package com.lagradost.cloudstream4

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream4.compose.DeviceLayout
import com.lagradost.cloudstream4.compose.TV
import com.mihon.common.preference.AndroidPreferenceStore


@Composable
actual fun rememberAppSettings() : AppSettings {
    val context = LocalContext.current
    val value = remember(context) { AppSettings(context) }
    return value
}

fun AppSettings(context: Context) : AppSettings {
    // -1 = auto detect, 0 = phone, 1 = TV, 2 = emulator (see R.string.app_layout_key)
    val layoutPreference =
        PreferenceManager.getDefaultSharedPreferences(context).getInt("app_layout_key", -1)
    return AppSettings(
        preferences = AndroidPreferenceStore(context),
        // Older TVs commonly fail to decode anything above 1080p, so default to capping there.
        defaultMaxVideoHeight =
            if (DeviceLayout.layoutToFlag(context, layoutPreference) == TV) Qualities.P1080.value else 0,
    )
}
