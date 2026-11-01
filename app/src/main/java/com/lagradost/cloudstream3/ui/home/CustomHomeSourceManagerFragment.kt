package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.mihon.presentation.settings.SearchableSettings

/** Connects the navigation graph to the Compose custom home source editor. */
class CustomHomeSourceManagerFragment : BaseComposeFragment(),
    SearchableSettings by CustomHomeSourceManagerScreen
