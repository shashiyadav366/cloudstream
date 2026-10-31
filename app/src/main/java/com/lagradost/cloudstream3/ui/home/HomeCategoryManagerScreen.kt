package com.lagradost.cloudstream3.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream4.compose.SingleSelectDialog
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One place to show/hide the home-page categories of every installed plugin.
 *
 * The rows match exactly what the home screen uses: declared main-page
 * categories plus any category names a plugin actually served (saved by
 * [HomeCategorySettings.rememberCategories]). Plugins without a declared
 * main page get a "Load categories" row that fetches their homepage once.
 */
object HomeCategoryManagerScreen : SearchableSettings {
    private enum class CategoryFilter(val labelRes: Int) {
        ALL(R.string.home_categories_filter_all),
        HIDDEN_CATEGORIES(R.string.home_categories_filter_hidden_categories),
        SHOWN_CATEGORIES(R.string.home_categories_filter_shown_categories),
        HIDDEN_PLUGINS(R.string.home_categories_filter_hidden_plugins),
        SHOWN_PLUGINS(R.string.home_categories_filter_shown_plugins),
    }

    private data class ProviderCategories(
        val api: MainAPI,
        val providerKey: String,
        val categories: List<String>,
        val hiddenNormalized: Set<String>,
        val hiddenCount: Int,
        val totalCount: Int,
        val isFullyHidden: Boolean,
    )

    @Composable
    override fun getTitleRes(): String = stringResource(R.string.home_categories_title)

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // Bumped whenever a category is toggled or fetched so the switches redraw.
        var revision by remember { mutableIntStateOf(0) }
        // Bumped after a child-provider plugin is reloaded, so the provider list
        // picks up APIs that were just registered or removed.
        var pluginRevision by remember { mutableIntStateOf(0) }
        var filter by remember { mutableStateOf(CategoryFilter.ALL) }
        var filterDialog by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }

        val dynamicCategories = remember { mutableStateMapOf<String, List<String>>() }
        val fetching = remember { mutableStateMapOf<String, Boolean>() }

        val providers = remember(pluginRevision) {
            APIHolder.apis.toList()
                .filter { api ->
                    api.name.isNotBlank() &&
                        api.hasMainPage &&
                        api.name != APIRepository.noneApi.name &&
                        !CustomHomeSources.isBlockedProvider(api)
                }
                .distinctBy { HomeCategorySettings.providerKey(it) }
                .sortedBy { it.name.lowercase() }
        }

        // Build the state once per change instead of re-reading and normalising the
        // whole preference snapshot for every provider row on every recomposition.
        val providerStates = remember(revision) {
            providers.map { api ->
                val providerKey = HomeCategorySettings.providerKey(api)
                val declared = api.mainPage
                    .map { it.name }
                    .filter { it.isNotBlank() }
                val categories = (
                    declared +
                        HomeCategorySettings.knownFor(context, providerKey) +
                        dynamicCategories[providerKey].orEmpty()
                    )
                    .distinctBy { HomeCategorySettings.normalize(it) }
                val hiddenNormalized = HomeCategorySettings
                    .hiddenFor(context, providerKey)
                    .mapTo(HashSet()) { HomeCategorySettings.normalize(it) }
                val hiddenCount = categories.count {
                    HomeCategorySettings.normalize(it) in hiddenNormalized
                }
                ProviderCategories(
                    api = api,
                    providerKey = providerKey,
                    categories = categories,
                    hiddenNormalized = hiddenNormalized,
                    hiddenCount = hiddenCount,
                    totalCount = categories.size,
                    isFullyHidden = categories.isNotEmpty() &&
                        hiddenCount == categories.size,
                )
            }
        }

        val typeFilteredStates = when (filter) {
            CategoryFilter.ALL -> providerStates
            CategoryFilter.HIDDEN_CATEGORIES -> providerStates.mapNotNull { state ->
                val categories = state.categories.filter {
                    HomeCategorySettings.normalize(it) in state.hiddenNormalized
                }
                if (categories.isEmpty()) null else state.copy(categories = categories)
            }
            CategoryFilter.SHOWN_CATEGORIES -> providerStates.mapNotNull { state ->
                val categories = state.categories.filterNot {
                    HomeCategorySettings.normalize(it) in state.hiddenNormalized
                }
                if (categories.isEmpty()) null else state.copy(categories = categories)
            }
            CategoryFilter.HIDDEN_PLUGINS -> providerStates.filter { it.isFullyHidden }
            CategoryFilter.SHOWN_PLUGINS -> providerStates.filterNot { it.isFullyHidden }
        }

        val query = searchQuery.trim()
        val filteredStates = if (query.isEmpty()) {
            typeFilteredStates
        } else {
            typeFilteredStates.mapNotNull { state ->
                if (state.api.name.contains(query, ignoreCase = true)) {
                    state
                } else {
                    val categories = state.categories.filter {
                        it.contains(query, ignoreCase = true)
                    }
                    if (categories.isEmpty()) null else state.copy(categories = categories)
                }
            }
        }

        val categoryLabel = stringResource(R.string.home_categories_category_label)
        val categoryHiddenLabel =
            stringResource(R.string.home_categories_category_label_hidden)
        val pluginLabel = stringResource(R.string.home_categories_plugin_label)
        val pluginCountFormat =
            stringResource(R.string.home_categories_plugin_label_count)
        val pluginHiddenFormat =
            stringResource(R.string.home_categories_plugin_label_hidden_count)
        val loadCategories = stringResource(R.string.home_categories_load)
        val loadCategoriesDescription =
            stringResource(R.string.home_categories_load_description)
        val loadingCategories = stringResource(R.string.home_categories_loading)
        val resetAll = stringResource(R.string.home_categories_reset)
        val resetAllDescription =
            stringResource(R.string.home_categories_reset_description)
        val categoryHiddenMessage = stringResource(R.string.home_category_hidden)
        val categoryUnhiddenMessage = stringResource(R.string.home_category_unhidden)
        val allHiddenMessage = stringResource(R.string.home_categories_all_hidden)
        val allUnhiddenMessage = stringResource(R.string.home_categories_all_unhidden)
        val categoriesLoadedMessage = stringResource(R.string.home_categories_loaded)
        val categoriesLoadFailedMessage =
            stringResource(R.string.home_categories_load_failed)
        val filterTitle = stringResource(R.string.home_categories_filter)
        val filterDescription =
            stringResource(R.string.home_categories_filter_description)
        val filterEmpty = stringResource(R.string.home_categories_filter_empty)
        val filterLabel = stringResource(filter.labelRes)
        val searchHint = stringResource(R.string.home_categories_search_hint)
        val filterSummaryFormat =
            stringResource(R.string.home_categories_filter_summary)
        val filterSummary = filterSummaryFormat.format(
            filteredStates.size,
            filteredStates.sumOf { it.categories.size }
        )

        fun confirmation(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        fun refreshHome() {
            MainActivity.reloadHomeEvent(true)
        }

        fun toggleChildProvider(group: ChildProviderGroup, child: ChildProviderInfo) {
            val enabled = !child.enabled
            group.setEnabled(child.title, enabled)
            revision++
            scope.launch {
                val reloaded = withContext(Dispatchers.IO) {
                    runCatching { ChildProviderSettings.reload(context, group) }
                        .getOrDefault(false)
                }
                if (reloaded) {
                    pluginRevision++
                    refreshHome()
                    confirmation(
                        context.getString(
                            if (enabled) R.string.home_categories_child_provider_enabled
                            else R.string.home_categories_child_provider_disabled,
                            child.title
                        )
                    )
                } else {
                    confirmation(
                        context.getString(
                            R.string.home_categories_child_provider_reload_failed,
                            child.title
                        )
                    )
                }
            }
        }

        val childProviderGroups = remember(revision, pluginRevision) {
            ChildProviderSettings.groups(context)
        }

        val preferences = mutableListOf<Preference>()

        preferences += Preference.PreferenceItem.CustomPreference(
            title = searchHint,
            content = {
                HomeSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = searchHint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        )

        preferences += Preference.PreferenceGroup(
            title = filterTitle,
            subtitle = filterSummary,
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = filterLabel,
                    subtitle = filterDescription,
                    onClick = { filterDialog = true }
                )
            )
        )

        preferences += Preference.PreferenceGroup(
            title = stringResource(R.string.home_categories_actions),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = resetAll,
                    subtitle = resetAllDescription,
                    onClick = {
                        HomeCategorySettings.clearHidden(context)
                        filter = CategoryFilter.ALL
                        revision++
                        refreshHome()
                        confirmation(allUnhiddenMessage)
                    }
                )
            )
        )

        childProviderGroups.forEach { group ->
            val matchingChildren = if (query.isEmpty()) {
                group.children
            } else {
                group.children.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        group.pluginName.contains(query, ignoreCase = true)
                }
            }
            if (matchingChildren.isEmpty()) return@forEach

            preferences += Preference.PreferenceGroup(
                title = stringResource(
                    R.string.home_categories_child_providers
                ) + " · " + group.pluginName,
                subtitle = stringResource(
                    R.string.home_categories_child_providers_description,
                    group.pluginName
                ),
                titleBold = true,
                preferenceItems = matchingChildren.map { child ->
                    Preference.PreferenceItem.TextPreference(
                        title = child.title,
                        subtitle = stringResource(
                            if (child.enabled) R.string.home_categories_child_provider_enabled
                            else R.string.home_categories_child_provider_disabled,
                            child.title
                        ),
                        compactTitle = true,
                        widget = {
                            Switch(
                                checked = child.enabled,
                                onCheckedChange = null
                            )
                        },
                        onClick = { toggleChildProvider(group, child) },
                        onLongClick = { toggleChildProvider(group, child) },
                    )
                }.toPersistentList()
            )
        }

        if (filteredStates.isEmpty()) {
            preferences += Preference.PreferenceItem.InfoPreference(filterEmpty)
        }

        if (filterDialog) {
            val values = CategoryFilter.values()
            SingleSelectDialog(
                title = filterTitle,
                entries = values.map { context.getString(it.labelRes) },
                selectedIndex = filter.ordinal,
                properties = DialogProperties(usePlatformDefaultWidth = true),
                confirmText = null,
                dismissText = stringResource(R.string.cancel),
                dismiss = { filterDialog = false },
                confirm = { index ->
                    filter = values[index]
                    filterDialog = false
                }
            )
        }

        filteredStates.forEach { state ->
            val items = mutableListOf<Preference.PreferenceItem<*, *>>()

            if (state.categories.isEmpty()) {
                val isLoading = fetching[state.providerKey] == true
                items += Preference.PreferenceItem.TextPreference(
                    title = if (isLoading) loadingCategories else loadCategories,
                    subtitle = if (isLoading) null else loadCategoriesDescription,
                    enabled = !isLoading,
                    onClick = {
                        if (fetching[state.providerKey] != true) {
                            fetching[state.providerKey] = true
                            scope.launch {
                                val names = withContext(Dispatchers.IO) {
                                    fetchCategories(state.api)
                                }
                                HomeCategorySettings.rememberCategories(
                                    context,
                                    state.providerKey,
                                    names
                                )
                                dynamicCategories[state.providerKey] = names
                                fetching[state.providerKey] = false
                                revision++
                                confirmation(
                                    if (names.isEmpty()) categoriesLoadFailedMessage
                                    else categoriesLoadedMessage
                                )
                            }
                        }
                    }
                )
            } else {
                state.categories.forEach { category ->
                    val isHidden =
                        HomeCategorySettings.normalize(category) in state.hiddenNormalized
                    items += Preference.PreferenceItem.TextPreference(
                        title = category,
                        subtitle = if (isHidden) categoryHiddenLabel else categoryLabel,
                        compactTitle = true,
                        widget = {
                            Switch(
                                checked = !isHidden,
                                onCheckedChange = null
                            )
                        },
                        onClick = {
                            HomeCategorySettings.setHidden(
                                context,
                                state.providerKey,
                                category,
                                !isHidden
                            )
                            revision++
                            refreshHome()
                            confirmation(
                                if (isHidden) categoryUnhiddenMessage
                                else categoryHiddenMessage
                            )
                        },
                        // Long-press toggles the category as well, keeping the
                        // action reachable on TV where a hold is the reliable way
                        // to trigger it through the remote.
                        onLongClick = {
                            HomeCategorySettings.setHidden(
                                context,
                                state.providerKey,
                                category,
                                !isHidden
                            )
                            revision++
                            refreshHome()
                            confirmation(
                                if (isHidden) categoryUnhiddenMessage
                                else categoryHiddenMessage
                            )
                        },
                    )
                }
            }

            val pluginSubtitle = when {
                state.hiddenCount > 0 ->
                    pluginHiddenFormat.format(state.hiddenCount, state.totalCount)
                state.totalCount > 0 -> pluginCountFormat.format(state.totalCount)
                else -> pluginLabel
            }

            preferences += Preference.PreferenceGroup(
                title = state.api.name,
                subtitle = pluginSubtitle,
                titleBold = true,
                titlePrimary = true,
                // Sit the first category row right under the plugin name.
                headerBottomPaddingDp = 0,
                spacingAfterDp = 24,
                showDividerAfter = true,
                // The hide/shown switch lives on the plugin name row itself, so
                // there is no separate "show plugin on home" label row.
                headerWidget = if (state.categories.isEmpty()) {
                    // Nothing to hide yet, the row below loads the categories.
                    null
                } else {
                    {
                        val allHidden = state.isFullyHidden
                        Switch(
                            checked = !allHidden,
                            onCheckedChange = { showOnHome ->
                                HomeCategorySettings.setAllHidden(
                                    context,
                                    state.providerKey,
                                    state.categories,
                                    !showOnHome
                                )
                                revision++
                                refreshHome()
                                confirmation(
                                    if (showOnHome) allUnhiddenMessage else allHiddenMessage
                                )
                            }
                        )
                    }
                },
                preferenceItems = items.toPersistentList()
            )
        }

        return preferences
    }

    private suspend fun fetchCategories(api: MainAPI): List<String> {
        val result = APIRepository(api).getMainPage(1, null)
        if (result !is Resource.Success) return emptyList()
        return result.value
            .filterNotNull()
            .flatMap { it.items }
            .map { it.name }
            .filter { it.isNotBlank() }
            .distinctBy { HomeCategorySettings.normalize(it) }
    }
}
