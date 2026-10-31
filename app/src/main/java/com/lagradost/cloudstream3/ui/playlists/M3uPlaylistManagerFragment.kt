package com.lagradost.cloudstream3.ui.playlists

import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.mihon.presentation.settings.SearchableSettings

/** Connects the navigation graph to the Compose playlist manager. */
class M3uPlaylistManagerFragment : BaseComposeFragment(),
    SearchableSettings by M3uPlaylistManagerScreen
