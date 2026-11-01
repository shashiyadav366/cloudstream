package com.lagradost.cloudstream3.ui.playlists

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.home.HomeSearchField
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream4.compose.ActionDialog
import com.lagradost.cloudstream4.compose.BlackButton
import com.lagradost.cloudstream4.compose.BlackTextField
import com.lagradost.cloudstream4.compose.WhiteButton
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adds and removes the M3U playlists the M3UPlaylistPlayerProvider plugin plays.
 *
 * Entries are written straight into the preference file the plugin reads while
 * it loads, so a playlist added here shows up as a provider without retyping
 * anything, and the same list travels with the backup file to other devices.
 */
object M3uPlaylistManagerScreen : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = stringResource(R.string.m3u_playlists_title)

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // Bumped whenever a playlist is added or removed so the list redraws.
        var revision by remember { mutableIntStateOf(0) }
        // Bumped after the plugin reloaded, so its installed state is redrawn.
        var pluginRevision by remember { mutableIntStateOf(0) }
        var searchQuery by remember { mutableStateOf("") }
        var addDialog by remember { mutableStateOf(false) }
        var pendingDelete by remember { mutableStateOf<M3uPlaylist?>(null) }

        val playlists = remember(revision) { M3uPlaylistSettings.list(context) }
        val pluginInstalled = remember(pluginRevision) { M3uPlaylistSettings.isPluginInstalled() }

        fun confirmation(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        fun refreshHome() {
            MainActivity.reloadHomeEvent(true)
        }

        // The plugin picks the stored list up when it loads, so re-running it is
        // the whole apply step — no app restart and no re-entering playlists.
        fun applyChange(message: String) {
            revision++
            scope.launch {
                val reloaded = withContext(Dispatchers.IO) {
                    runCatching { M3uPlaylistSettings.reload(context) }
                        .getOrDefault(false)
                }
                if (reloaded) {
                    pluginRevision++
                    refreshHome()
                    confirmation(message)
                } else {
                    confirmation(
                        context.getString(R.string.m3u_playlists_reload_failed, message)
                    )
                }
            }
        }

        fun savePlaylist(name: String, url: String): Boolean {
            if (name.isBlank() || url.isBlank()) {
                confirmation(context.getString(R.string.m3u_playlists_invalid))
                return false
            }
            if (!M3uPlaylistSettings.add(context, name, url)) {
                confirmation(context.getString(R.string.m3u_playlists_invalid))
                return false
            }
            applyChange(context.getString(R.string.m3u_playlists_added))
            return true
        }

        val query = searchQuery.trim()
        val filtered = if (query.isEmpty()) {
            playlists
        } else {
            playlists.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.url.contains(query, ignoreCase = true)
            }
        }

        val preferences = mutableListOf<Preference>()

        preferences += Preference.PreferenceItem.CustomPreference(
            title = stringResource(R.string.m3u_playlists_search_hint),
            content = {
                HomeSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.m3u_playlists_search_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        )

        if (!pluginInstalled) {
            preferences += Preference.PreferenceItem.InfoPreference(
                stringResource(R.string.m3u_playlists_plugin_missing)
            )
        }

        val groupItems = buildList {
            add(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(R.string.m3u_playlists_add),
                    subtitle = stringResource(R.string.m3u_playlists_add_description),
                    icon = painterResource(R.drawable.add_24px),
                    onClick = { addDialog = true },
                )
            )
            filtered.forEach { playlist ->
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = playlist.name,
                        subtitle = playlist.url,
                        compactTitle = true,
                        onClick = { pendingDelete = playlist },
                        onLongClick = { pendingDelete = playlist },
                    )
                )
            }
        }.toPersistentList()

        preferences += Preference.PreferenceGroup(
            title = stringResource(R.string.m3u_playlists_title),
            subtitle = stringResource(R.string.m3u_playlists_group_description, playlists.size),
            titleBold = true,
            preferenceItems = groupItems,
        )

        if (playlists.isEmpty()) {
            preferences += Preference.PreferenceItem.InfoPreference(
                stringResource(R.string.m3u_playlists_empty)
            )
        } else if (filtered.isEmpty()) {
            preferences += Preference.PreferenceItem.InfoPreference(
                stringResource(R.string.home_categories_filter_empty)
            )
        }

        if (addDialog) {
            AddPlaylistDialog(
                onDismiss = { addDialog = false },
                onConfirm = { name, url ->
                    if (savePlaylist(name, url)) addDialog = false
                }
            )
        }

        pendingDelete?.let { playlist ->
            ActionDialog(
                title = playlist.name,
                text = stringResource(R.string.m3u_playlists_delete_description),
                confirmText = stringResource(R.string.delete),
                dismissText = stringResource(R.string.cancel),
                dismiss = { pendingDelete = null },
                confirm = {
                    M3uPlaylistSettings.remove(context, playlist)
                    pendingDelete = null
                    applyChange(context.getString(R.string.m3u_playlists_removed))
                }
            )
        }

        return preferences
    }
}

/**
 * Theme-matched add dialog: one field for the name, one for the M3U8 URL,
 * in the same black-field/white-button style as the other customisation
 * dialogs, and TV-safe focus so the remote lands on the first field.
 */
@Composable
private fun AddPlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val dismissFocus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
        containerColor = MaterialTheme.colorScheme.background,
        title = { Text(text = stringResource(R.string.m3u_playlists_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BlackTextField(
                    value = name,
                    placeHolder = stringResource(R.string.m3u_playlists_name_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocus)
                        .focusProperties { down = urlFocus },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { urlFocus.requestFocus() }),
                    singleLine = true,
                    onValueChange = { name = it },
                )
                BlackTextField(
                    value = url,
                    placeHolder = stringResource(R.string.m3u_playlists_url_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(urlFocus)
                        .focusProperties { down = confirmFocus },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(name, url) }),
                    singleLine = true,
                    onValueChange = { url = it },
                )
            }
        },
        confirmButton = {
            WhiteButton(
                text = stringResource(R.string.ok),
                modifier = Modifier.focusRequester(confirmFocus),
            ) {
                onConfirm(name, url)
            }
        },
        dismissButton = {
            BlackButton(
                text = stringResource(R.string.cancel),
                modifier = Modifier.focusRequester(dismissFocus),
            ) {
                onDismiss()
            }
        }
    )

    LaunchedEffect(Unit) {
        if (isLayout(TV)) nameFocus.requestFocus()
    }
}
