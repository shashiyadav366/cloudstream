package com.lagradost.cloudstream3.ui.home

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.compose.ActionDialog
import com.lagradost.cloudstream4.compose.BlackButton
import com.lagradost.cloudstream4.compose.BlackTextField
import com.lagradost.cloudstream4.compose.WhiteButton
import com.lagradost.cloudstream4.compose.WhiteFilterChip
import com.lagradost.cloudstream4.compose.focusOutline
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import kotlinx.collections.immutable.persistentListOf

private data class CategoryChoice(
    val ref: CustomHomeSources.CategoryRef,
    val label: String,
)

/**
 * Creates and edits home entries that merge categories from multiple plugins.
 * The resulting entries appear in the home provider dropdown after None/Random.
 */
object CustomHomeSourceManagerScreen : SearchableSettings {
    private data class NameDialogState(val source: CustomHomeSources.Source?)

    private data class CategoryDialogState(
        val source: CustomHomeSources.Source,
        val choices: List<CategoryChoice>,
    )

    private data class DeleteDialogState(val source: CustomHomeSources.Source)

    @Composable
    override fun getTitleRes(): String = stringResource(R.string.custom_home_sources_title)

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        var revision by remember { mutableIntStateOf(0) }
        var nameDialog by remember { mutableStateOf<NameDialogState?>(null) }
        var categoryDialog by remember { mutableStateOf<CategoryDialogState?>(null) }
        var deleteDialog by remember { mutableStateOf<DeleteDialogState?>(null) }

        val sources = remember(revision) { CustomHomeSources.all(context) }

        fun confirmation(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        fun refreshHome() {
            MainActivity.reloadHomeEvent(true)
        }

        fun saveName(state: NameDialogState, rawName: String): Boolean {
            val name = rawName.trim()
            val reserved = name.equals(APIRepository.noneApi.name, true) ||
                name.equals(APIRepository.randomApi.name, true)
            if (name.isBlank()) {
                confirmation(context.getString(R.string.custom_home_sources_name_required))
                return false
            }
            if (reserved) {
                confirmation(context.getString(R.string.custom_home_sources_name_exists))
                return false
            }

            val existing = state.source
            if (existing == null) {
                val created = CustomHomeSources.create(context, name)
                if (created == null) {
                    confirmation(context.getString(R.string.custom_home_sources_name_exists))
                    return false
                }
                revision++
                confirmation(context.getString(R.string.custom_home_sources_created))
                return true
            }

            val updated = CustomHomeSources.update(context, existing.copy(name = name))
            if (!updated) {
                confirmation(context.getString(R.string.custom_home_sources_name_exists))
                return false
            }
            if (
                DataStoreHelper.currentHomePage == existing.name &&
                DataStoreHelper.currentHomeSource == null
            ) {
                DataStoreHelper.currentHomePage = name
            }
            revision++
            confirmation(context.getString(R.string.custom_home_sources_renamed))
            refreshHome()
            return true
        }

        fun showCategoriesDialog(source: CustomHomeSources.Source) {
            val available = buildChoices(context)
            if (available.isEmpty()) {
                confirmation(
                    context.getString(R.string.custom_home_sources_no_available_categories)
                )
                return
            }
            categoryDialog = CategoryDialogState(source, available)
        }

        fun saveCategories(
            state: CategoryDialogState,
            selection: List<CustomHomeSources.CategoryRef>
        ): Boolean {
            // Keep selections from plugins that are temporarily blocked or
            // uninstalled. They disappear from the UI now but return with
            // their previous choices when the plugin is installed again.
            val availableKeys = state.choices
                .mapTo(HashSet(state.choices.size)) { it.ref.stableKey() }
            val unavailable = state.source.categories.filterNot {
                it.stableKey() in availableKeys
            }
            // Categories that stay selected keep their stored position, so the
            // custom plugin's home rows stay in the order they were added first.
            // Newly toggled categories are appended in the order they were tapped.
            val selectionKeys = selection
                .mapTo(HashSet(selection.size)) { it.stableKey() }
            val kept = state.source.categories.filter { it.stableKey() in selectionKeys }
            val keptKeys = kept.mapTo(HashSet(kept.size)) { it.stableKey() }
            val added = selection.filter { it.stableKey() !in keptKeys }
            val categories = kept + added + unavailable
            if (categories.isEmpty()) {
                confirmation(context.getString(R.string.custom_home_sources_no_categories))
                return false
            }

            val updated = CustomHomeSources.update(
                context,
                state.source.copy(categories = categories)
            )
            if (!updated) return false
            revision++
            confirmation(context.getString(R.string.custom_home_sources_categories_saved))
            refreshHome()
            return true
        }

        val preferences = mutableListOf<Preference>()

        preferences += Preference.PreferenceGroup(
            title = stringResource(R.string.custom_home_sources_title),
            subtitle = stringResource(R.string.custom_home_sources_description),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(R.string.custom_home_sources_create),
                    subtitle = stringResource(R.string.custom_home_sources_create_description),
                    onClick = { nameDialog = NameDialogState(null) }
                )
            )
        )

        if (sources.isEmpty()) {
            preferences += Preference.PreferenceItem.InfoPreference(
                stringResource(R.string.custom_home_sources_empty)
            )
        }

        sources.forEach { source ->
            val activeCategories = CustomHomeSources.activeCategories(source)
            val providerCount = activeCategories
                .map { it.providerKey }
                .distinct()
                .size
            val summary = stringResource(
                R.string.custom_home_sources_summary,
                activeCategories.size,
                providerCount
            )

            preferences += Preference.PreferenceGroup(
                title = source.name,
                subtitle = summary,
                titleBold = true,
                titlePrimary = true,
                spacingAfterDp = 24,
                showDividerAfter = true,
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.custom_home_sources_choose_categories),
                        subtitle = stringResource(
                            R.string.custom_home_sources_choose_categories_description,
                            activeCategories.size
                        ),
                        icon = painterResource(R.drawable.filter_alt_24px),
                        onClick = { showCategoriesDialog(source) }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.custom_home_sources_rename),
                        icon = painterResource(R.drawable.edit_24px),
                        onClick = { nameDialog = NameDialogState(source) }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.custom_home_sources_delete),
                        subtitle = stringResource(R.string.custom_home_sources_delete_description),
                        icon = painterResource(R.drawable.delete_24px),
                        onClick = { deleteDialog = DeleteDialogState(source) }
                    )
                )
            )
        }

        nameDialog?.let { state ->
            val title = stringResource(
                if (state.source == null) R.string.custom_home_sources_create
                else R.string.custom_home_sources_rename
            )
            var name by remember(state) { mutableStateOf(state.source?.name.orEmpty()) }
            val inputFocus = remember(state) { FocusRequester() }
            val confirmFocus = remember(state) { FocusRequester() }
            val dismissFocus = remember(state) { FocusRequester() }

            AlertDialog(
                onDismissRequest = { nameDialog = null },
                properties = DialogProperties(usePlatformDefaultWidth = true),
                containerColor = MaterialTheme.colorScheme.background,
                title = { Text(text = title) },
                text = {
                    BlackTextField(
                        value = name,
                        placeHolder = stringResource(R.string.custom_home_sources_name_hint),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocus)
                            .focusProperties { down = confirmFocus },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (saveName(state, name)) nameDialog = null
                            }
                        ),
                        singleLine = true,
                        onValueChange = { name = it }
                    )
                },
                confirmButton = {
                    WhiteButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.focusRequester(confirmFocus)
                    ) {
                        if (saveName(state, name)) nameDialog = null
                    }
                },
                dismissButton = {
                    BlackButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.focusRequester(dismissFocus)
                    ) {
                        nameDialog = null
                    }
                }
            )

            LaunchedEffect(state) {
                if (isLayout(TV)) {
                    inputFocus.requestFocus()
                }
            }
        }

        categoryDialog?.let { state ->
            CategoryPickerDialog(
                sourceName = state.source.name,
                choices = state.choices,
                selectedRefs = state.source.categories,
                onDismiss = { categoryDialog = null },
                onConfirm = { selection ->
                    if (saveCategories(state, selection)) {
                        categoryDialog = null
                    }
                }
            )
        }

        deleteDialog?.let { state ->
            ActionDialog(
                title = state.source.name,
                text = stringResource(R.string.custom_home_sources_delete_description),
                confirmText = stringResource(R.string.delete),
                dismissText = stringResource(R.string.cancel),
                dismiss = { deleteDialog = null },
                confirm = {
                    if (
                        DataStoreHelper.currentHomePage == state.source.name &&
                        DataStoreHelper.currentHomeSource == null
                    ) {
                        DataStoreHelper.currentHomePage = null
                    }
                    CustomHomeSources.delete(context, state.source.id)
                    revision++
                    confirmation(context.getString(R.string.custom_home_sources_deleted))
                    refreshHome()
                    deleteDialog = null
                }
            )
        }

        return preferences
    }

    private fun buildChoices(context: Context): List<CategoryChoice> {
        val choices = mutableListOf<CategoryChoice>()
        APIHolder.apis.asSequence()
            .filter {
                it.hasMainPage &&
                    it.name.isNotBlank() &&
                    it.name != APIRepository.noneApi.name &&
                    it.name != APIRepository.randomApi.name &&
                    !CustomHomeSources.isBlockedProvider(it) &&
                    !HomeCategorySettings.isProviderFullyHidden(context, it)
            }
            .forEach { api ->
                val providerKey = HomeCategorySettings.providerKey(api)
                val categories = (
                    api.mainPage.map { it.name } +
                        HomeCategorySettings.knownFor(context, providerKey)
                    )
                    .filter { it.isNotBlank() }
                    .filterNot {
                        HomeCategorySettings.isHidden(context, providerKey, it)
                    }
                    .distinctBy { HomeCategorySettings.normalize(it) }

                categories.forEach { category ->
                    choices += CategoryChoice(
                        ref = CustomHomeSources.CategoryRef(
                            providerKey = providerKey,
                            providerName = api.name,
                            category = category,
                        ),
                        label = "${api.name} · $category",
                    )
                }
            }

        return choices.sortedWith(
            compareBy(
                { it.ref.providerName.lowercase() },
                { it.ref.category.lowercase() }
            )
        )
    }
}

@Composable
private fun CategoryPickerDialog(
    sourceName: String,
    choices: List<CategoryChoice>,
    selectedRefs: List<CustomHomeSources.CategoryRef>,
    onDismiss: () -> Unit,
    onConfirm: (List<CustomHomeSources.CategoryRef>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selected = remember(selectedRefs, choices) {
        selectedRefs
            .filter { ref -> choices.any { it.ref == ref } }
            .toMutableStateList()
    }
    val filtered = remember(query, choices) {
        val text = query.trim()
        if (text.isEmpty()) {
            choices
        } else {
            choices.filter {
                it.ref.category.contains(text, ignoreCase = true) ||
                    it.ref.providerName.contains(text, ignoreCase = true)
            }
        }
    }
    val grouped = remember(filtered) {
        filtered.groupBy { it.ref.providerName }
    }
    val firstFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val dismissFocus = remember { FocusRequester() }
    val firstKey = filtered.firstOrNull()?.ref?.stableKey()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
        containerColor = MaterialTheme.colorScheme.background,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.custom_home_sources_choose_categories),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.custom_home_sources_search_categories),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.custom_home_sources_selected_count,
                            selected.size
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(
                        onClick = {
                            filtered.forEach { choice ->
                                if (selected.none { it == choice.ref }) {
                                    selected.add(choice.ref)
                                }
                            }
                        },
                        enabled = filtered.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.select_all))
                    }
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = { selected.clear() }) {
                            Text(stringResource(R.string.custom_home_sources_clear_selection))
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    // Show which categories are currently picked (and in what
                    // order) without having to scroll through every plugin.
                    if (selected.isNotEmpty()) {
                        item(key = "selection-summary") {
                            val duplicated = selected
                                .groupBy { it.category }
                                .filterValues { it.size > 1 }
                                .keys
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                selected.forEachIndexed { index, ref ->
                                    WhiteFilterChip(
                                        selected = true,
                                        label = buildString {
                                            append(index + 1).append(". ").append(ref.category)
                                            if (ref.category in duplicated) {
                                                append(" · ").append(ref.providerName)
                                            }
                                        },
                                        onClick = { selected.remove(ref) },
                                    )
                                }
                            }
                        }
                    }

                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(
                                    R.string.custom_home_sources_no_search_results
                                ),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    grouped.forEach { (provider, providerChoices) ->
                        item(key = "provider:$provider") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = provider,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = providerChoices.size.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(
                            items = providerChoices,
                            key = { it.ref.stableKey() },
                        ) { choice ->
                            CategoryChoiceRow(
                                label = choice.ref.category,
                                checked = selected.contains(choice.ref),
                                order = selected.indexOf(choice.ref)
                                    .takeIf { it >= 0 }
                                    ?.plus(1),
                                modifier = if (choice.ref.stableKey() == firstKey) {
                                    Modifier.focusRequester(firstFocus)
                                } else {
                                    Modifier
                                },
                                onToggle = {
                                    if (!selected.remove(choice.ref)) {
                                        selected.add(choice.ref)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            WhiteButton(
                text = stringResource(R.string.ok),
                modifier = Modifier.focusRequester(confirmFocus),
                onClick = { onConfirm(selected.toList()) },
            )
        },
        dismissButton = {
            BlackButton(
                text = stringResource(R.string.cancel),
                modifier = Modifier.focusRequester(dismissFocus),
                onClick = onDismiss,
            )
        },
    )

    LaunchedEffect(Unit) {
        if (isLayout(TV)) {
            withFrameNanos { }
            runCatching {
                if (filtered.isNotEmpty()) {
                    firstFocus.requestFocus()
                } else {
                    confirmFocus.requestFocus()
                }
            }
        }
    }
}

@Composable
private fun CategoryChoiceRow(
    label: String,
    checked: Boolean,
    /** 1-based position of this category in the home page order, null when unselected. */
    order: Int? = null,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val background = if (checked) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .focusOutline()
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = MaterialTheme.colorScheme.outline,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (order != null) {
            Text(
                text = order.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
