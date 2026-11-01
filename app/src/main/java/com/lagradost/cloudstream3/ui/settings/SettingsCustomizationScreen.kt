package com.lagradost.cloudstream3.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream4.rememberAppSettings
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.collections.immutable.persistentListOf

/** Groups the home-category editor and the custom home provider creator. */
object SettingsCustomizationScreen : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = stringResource(R.string.category_customization)

    @Composable
    override fun getPreferences(): List<Preference> {
        val settings = rememberAppSettings()
        return persistentListOf(
            Preference.PreferenceGroup(
                title = stringResource(R.string.customization_home_title),
                subtitle = stringResource(R.string.customization_home_description),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.home_categories_title),
                        subtitle = stringResource(R.string.home_categories_manage_description),
                        icon = painterResource(R.drawable.filter_alt_24px),
                        onClick = {
                            activity?.navigate(
                                R.id.action_navigation_settings_customization_to_navigation_home_categories
                            )
                        }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.custom_home_sources_title),
                        subtitle = stringResource(R.string.custom_home_sources_description),
                        icon = painterResource(R.drawable.add_24px),
                        onClick = {
                            activity?.navigate(
                                R.id.action_navigation_settings_customization_to_navigation_custom_home_sources
                            )
                        }
                    )
                )
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.title_home),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        title = stringResource(R.string.live_cricket_events),
                        subtitle = stringResource(R.string.live_cricket_events_summary),
                        icon = painterResource(R.drawable.ic_baseline_tv_24),
                        preference = settings.general.liveCricketEvents
                    )
                )
            ),
            // Playlists keep their own section below the home settings, so the
            // M3U player entries are not mixed into Home categories or Custom
            // home plugins.
            Preference.PreferenceGroup(
                title = stringResource(R.string.customization_playlists_title),
                subtitle = stringResource(R.string.customization_playlists_description),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.m3u_playlists_title),
                        subtitle = stringResource(R.string.m3u_playlists_manage_description),
                        icon = painterResource(R.drawable.ic_baseline_playlist_play_24),
                        onClick = {
                            activity?.navigate(
                                R.id.action_navigation_settings_customization_to_navigation_m3u_playlists
                            )
                        }
                    )
                )
            )
        )
    }
}
