package com.lagradost.cloudstream3.ui.settings

import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.mihon.presentation.settings.SearchableSettings

/** Connects the navigation graph to the Compose customization screen. */
class SettingsCustomization2 : BaseComposeFragment(),
    SearchableSettings by SettingsCustomizationScreen
