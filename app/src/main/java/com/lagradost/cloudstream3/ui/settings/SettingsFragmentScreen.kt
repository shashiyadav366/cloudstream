package com.lagradost.cloudstream3.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.profileImages
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream4.compose.Screen
import com.lagradost.cloudstream4.compose.TV
import com.lagradost.cloudstream4.compose.circleBorder
import com.lagradost.cloudstream4.compose.focusOutline
import com.lagradost.cloudstream4.compose.isLayout
import com.lagradost.cloudstream4.theme.CloudStreamPreviewTheme
import com.mihon.material.padding
import com.mihon.presentation.settings.SearchableSettings
import com.mihon.presentation.settings.SettingSearchResults
import com.mihon.presentation.settings.SettingsData
import com.mihon.presentation.settings.widget.TextPreferenceWidget
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SettingsFragmentScreen : Screen {
    val screens = persistentListOf(
        SettingsNavigation(
            title = R.string.category_general,
            navigation = R.id.action_navigation_global_to_navigation_settings_general,
            screen = SettingsGeneralScreen,
            icon = R.drawable.build_24px,
            subtitle = persistentListOf(
                R.string.extension_language,
                R.string.title_downloads,
                R.string.pref_category_bypass,
                R.string.pref_category_links
            )
        ),
        SettingsNavigation(
            title = R.string.category_player,
            navigation = R.id.action_navigation_global_to_navigation_settings_player,
            screen = SettingsPlayerScreen,
            icon = R.drawable.play_arrow_24px,
            subtitle = persistentListOf(
                R.string.pref_category_subtitles,
                R.string.pref_category_player_features,
                R.string.pref_category_gestures,
                R.string.pref_category_player_layout,
                R.string.pref_category_cache
            )
        ),
        /*SettingsNavigation(
            title = R.string.category_providers,
            navigation = R.id.action_navigation_global_to_navigation_settings_providers,
            screen = SettingsProvidersScreen,
            icon = R.drawable.build_24px,
        ),*/
        SettingsNavigation(
            title = R.string.category_ui,
            navigation = R.id.action_navigation_global_to_navigation_settings_ui,
            screen = SettingsUIScreen,
            icon = R.drawable.format_paint_24px,
            subtitle = persistentListOf(
                R.string.pref_category_looks,
                R.string.pref_category_ui_features,
                R.string.search_poster_img_des,
                R.string.poster_ui_settings
            )
        ),
        SettingsNavigation(
            title = R.string.category_updates,
            navigation = R.id.action_navigation_global_to_navigation_settings_updates,
            screen = SettingsUpdatesScreen,
            icon = R.drawable.mobile_arrow_down_24px,
            subtitle = persistentListOf(
                R.string.pref_category_app_updates,
                R.string.pref_category_backup,
                R.string.pref_category_extensions,
                R.string.pref_category_actions
            )
        ),
        SettingsNavigation(
            title = R.string.category_account,
            navigation = R.id.action_navigation_global_to_navigation_settings_account,
            screen = SettingsAccountScreen,
            icon = R.drawable.encrypted_24px,
            subtitle = persistentListOf(
                R.string.pref_category_accounts,
                R.string.pref_category_security
            )
        ),
        SettingsNavigation(
            title = R.string.pref_category_extensions,
            navigation = R.id.action_navigation_global_to_navigation_settings_extensions,
            screen = null,
            icon = R.drawable.extension_24px,
            subtitle = persistentListOf(R.string.add_repository)
        ),
    )

    @Immutable
    data class SettingsNavigation(
        val title: Int,
        val navigation: Int,
        val screen: SearchableSettings?,
        val icon: Int,
        val subtitle: PersistentList<Int>,
    )


    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun Content() {
        val textFieldState = rememberTextFieldState()

        val outerListState = rememberScrollState()

        // The screen we are leaving must not be able to take the focus back. The focus is cleared
        // when the navigation happens and the framework then looks for a new focus target. This
        // screen is still visible during the transition, so it would highlight the wrong item
        // (the search bar or the "General" row) until the next screen is ready.
        // Making the view not focusable keeps it out of the focus search of the framework, so
        // the focus goes straight to the screen that is opened.
        val view = LocalView.current as? View
        val viewGroup = LocalView.current as? ViewGroup
        fun leaveScreen(navigation: Int) {
            view?.isFocusable = false
            viewGroup?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            activity?.navigate(navigation)
        }

        val parentFirstScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val delta = available.y
                    return if (delta < 0 && outerListState.canScrollForward) {
                        val consumed = outerListState.dispatchRawDelta(-delta)
                        Offset(0f, -consumed)
                    } else {
                        Offset.Zero
                    }
                }
            }
        }

        Scaffold { _ ->
            Column(modifier = Modifier.verticalScroll(outerListState)) {
                Spacer(modifier = Modifier.height(MaterialTheme.padding.small))

                val default = DataStoreHelper.getDefaultAccount(
                    LocalContext.current
                )
                val flow by DataStoreHelper.selectedAccountNumberFlow.collectAsState()
                val account = remember(flow) {
                    DataStoreHelper.getCurrentAccount() ?: default
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusOutline()
                        .clickable {
                            activity.navigate(
                                R.id.accountSelectActivity,
                                Bundle().apply { putBoolean("isFromMainActivity", true) }
                            )
                        }
                        .padding(
                            vertical = MaterialTheme.padding.large,
                            horizontal = MaterialTheme.padding.medium
                        )
                ) {
                    val image =
                        account.customImage ?: profileImages.getOrNull(account.defaultImageIndex)
                        ?: profileImages.first()

                    AsyncImage(
                        contentScale = ContentScale.Crop,
                        model = image,
                        modifier = Modifier.circleBorder(50.dp),
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(MaterialTheme.padding.medium))
                    Column {
                        Text(
                            text = account.name,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.title_settings),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(MaterialTheme.padding.small))
                SettingsSearch(textFieldState = textFieldState)
                Spacer(modifier = Modifier.height(MaterialTheme.padding.small))

                SettingSearchResults(
                    nestedScrollConnection = parentFirstScrollConnection,
                    searchKey = textFieldState.text.toString(),
                    deferredItems = ::generateSearchItems,
                    onItemClick = { item ->
                        SearchableSettings.highlightKey = item.highlightKey
                        leaveScreen(item.navigation)
                    }, empty = {
                        Column(
                            modifier = Modifier
                                .nestedScroll(parentFirstScrollConnection)
                        ) {
                            screens.forEach { settingsTab ->
                                SettingsTab(settingsTab) {
                                    // Clear it if we have already set it but navigated back instantly
                                    SearchableSettings.highlightKey = null
                                    leaveScreen(settingsTab.navigation)
                                }
                            }
                            BuildStamp()
                        }
                    })

            }
        }
    }

    @Composable
    @NonRestartableComposable
    fun generateSearchItems() =
        screens.mapNotNull { item ->
            val contents = item.screen?.getPreferences() ?: return@mapNotNull null
            SettingsData(
                title = stringResource(item.title),
                navigation = item.navigation,
                contents = contents
            )
        }.toPersistentList()

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun SettingsSearch(textFieldState : TextFieldState) {
        var hasFocus by remember { mutableStateOf(false) }
        val isTv = isLayout(TV)
        // On TV the field starts collapsed and read-only so that merely navigating onto it shows
        // focus but does NOT expand the bar or open the on-screen keyboard (which would trap the
        // D-pad). It only expands + opens the keyboard once the user actually selects it (OK).
        var isEditing by remember { mutableStateOf(false) }
        val isActive = if (isTv) isEditing else hasFocus
        val focusProgress by animateFloatAsState(targetValue = if (isActive) 1.0f else 0.0f)
        val focusManager = LocalFocusManager.current
        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        // Entering edit mode happens while the field already has focus, so we re-request focus and
        // explicitly ask for the IME, since a focus change is what normally triggers the keyboard.
        LaunchedEffect(isEditing) {
            if (isEditing) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
        }

        TextField(
            state = textFieldState,
            // Read-only text fields are still focusable but never launch the IME, which is what
            // keeps the keyboard from popping up while the user only navigates onto the bar.
            readOnly = isTv && !isEditing,
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Search,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp - 12.dp * focusProgress)
                .focusOutline(enabled = isTv, CircleShape)
                .onFocusChanged { newFocus ->
                    hasFocus = newFocus.hasFocus
                    // Leaving the field collapses it back to the non-editing state.
                    if (isTv && !newFocus.hasFocus) isEditing = false
                }.focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                    // Selecting the field expands it and opens the keyboard; navigating onto it
                    // does nothing, so the rows below stay reachable with the D-pad.
                    if (isTv && (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        if (!isEditing) {
                            isEditing = true
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }

                    // A focused text field consumes the D-pad keys for cursor movement, which
                    // traps the user in the search bar on TV. Forward them to the Compose focus
                    // system instead so focus can move up/down/left/right again.
                    val direction = when (event.key) {
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionLeft -> FocusDirection.Left
                        Key.DirectionRight -> FocusDirection.Right
                        else -> return@onPreviewKeyEvent false
                    }
                    if (isTv) {
                        keyboardController?.hide()
                        isEditing = false
                        if (!focusManager.moveFocus(direction)) {
                            focusManager.clearFocus()
                        }
                    } else {
                        focusManager.moveFocus(direction)
                    }
                    true
                },
            placeholder = {
                Text(text = stringResource(R.string.search_hint))
            },
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTrailingIconColor = MaterialTheme.colorScheme.onBackground,
                focusedTrailingIconColor = MaterialTheme.colorScheme.onBackground,
                focusedLeadingIconColor = MaterialTheme.colorScheme.onBackground,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cursorColor = MaterialTheme.colorScheme.onBackground,
            ),
            leadingIcon = {
                Crossfade(
                    targetState = isActive,
                    label = "leftsearch",
                ) { value ->
                    if (value) {
                        IconButton(onClick = {
                            textFieldState.edit { replace(0, length, "") }
                            isEditing = false
                            focusManager.clearFocus()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.keyboard_arrow_left_24px),
                                contentDescription = null
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            if (isTv) isEditing = true else focusRequester.requestFocus()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.search_icon),
                                contentDescription = null
                            )
                        }
                    }
                }
            },
            trailingIcon = {
                Crossfade(
                    targetState = isActive,
                    label = "rightsearch",
                ) { value ->
                    if (value) {
                        IconButton(onClick = {
                            textFieldState.edit { replace(0, length, "") }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.close_24px),
                                contentDescription = null
                            )
                        }
                    }
                }
            },
        )

        DisposableEffect(Unit) {
            onDispose {
                keyboardController?.hide()
            }
        }
    }

    @Composable
    fun SettingsTab(settingsTab: SettingsNavigation, onClick: () -> Unit) {
        TextPreferenceWidget(
            title = stringResource(settingsTab.title),
            icon = painterResource(settingsTab.icon),
            // This can not be converted to joinToString due to stringResource being composable
            subtitle =
                @Suppress("SimplifiableCallChain")
                settingsTab.subtitle.map { stringResource(it) }.joinToString(),
            onPreferenceClick = onClick)
    }

    @Composable
    fun BuildStamp() {
        val (commitHash, buildTimestamp) = remember {
            val commitHash = activity?.currentCommitHash() ?: ""
            val buildTimestamp = SimpleDateFormat.getDateTimeInstance(
                DateFormat.LONG, DateFormat.MEDIUM,
                Locale.getDefault()
            ).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(BuildConfig.BUILD_DATE)).replace("UTC", "")
            commitHash to buildTimestamp
        }

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .focusOutline()
                .clickable {
                    clipboardHelper(
                        txt(R.string.extension_version),
                        "${BuildConfig.VERSION_NAME} $commitHash $buildTimestamp"
                    )
                },
        ) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                Text(text = BuildConfig.VERSION_NAME)
                if (commitHash != "") {
                    Text("•")
                    Text(text = commitHash)
                }
                Text("•")
                Text(text = buildTimestamp)
            }
        }
    }
}


@PreviewLightDark
@Composable
fun Preview() {
    CloudStreamPreviewTheme {
        SettingsFragmentScreen.Content()
    }
}