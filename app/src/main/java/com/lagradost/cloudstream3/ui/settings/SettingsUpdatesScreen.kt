package com.lagradost.cloudstream3.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.AutoDownloadMode
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivityScreen
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.BackupUtils
import com.lagradost.cloudstream3.utils.BackupUtils.restoreFromPath
import com.lagradost.cloudstream3.utils.BackupUtils.restorePrompt
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.InAppUpdater.installPreReleaseIfNeeded
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.compose.ActionDialog
import com.lagradost.cloudstream4.compose.BlackButton
import com.lagradost.cloudstream4.compose.WhiteButton
import com.lagradost.cloudstream4.rememberAppSettings
import com.lagradost.cloudstream4.viewmodels.GithubAction
import com.lagradost.safefile.SafeFile
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import com.mihon.presentation.settings.collectAsState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

object SettingsUpdatesScreen : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = stringResource(R.string.category_updates)

    @Composable
    override fun getPreferences(): List<Preference> {
        val settings = rememberAppSettings()

        // TODO Refactor entirely to use a different file path selector ect like QuickNovel
        val selectFileSelector =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                // It lies, it can be null if file manager quits.
                if (uri == null) return@rememberLauncherForActivityResult
                val context = CloudStreamApp.context ?: return@rememberLauncherForActivityResult

                try {
                    val settings = AppSettings(context)
                    // RW perms for the path
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                    context.contentResolver.takePersistableUriPermission(uri, flags)

                    val filePath = SafeFile.fromUri(context, uri)?.filePath()
                    println("Selected URI path: $uri - Full path: $filePath")

                    // store the actual URI instead of the path due to permissions.
                    // filePath should only be used for cosmetic purposes.
                    val visual = filePath ?: uri.toString()
                    settings.backup.path.set(uri.toString())
                    settings.backup.visualPath.set(visual)
                } catch (t: Throwable) {
                    logError(t)
                }
            }

        val visualBackupPath by settings.backup.visualPath.collectAsState()
        val backupUrl by settings.backup.url.collectAsState()
        val backupDestination by settings.backup.destination.collectAsState()
        val isPathUrlDestination =
            backupDestination == BackupUtils.BACKUP_DESTINATION_PATH_URL
        var showDialog by remember { mutableStateOf(false) }
        var showBackupPathDialog by remember { mutableStateOf(false) }
        var showGitHubTokenDialog by remember { mutableStateOf(false) }
        var showResetDialog by remember { mutableStateOf(false) }
        var pendingBackupAction by remember { mutableStateOf<(() -> Unit)?>(null) }

        if (showDialog) {
            com.lagradost.cloudstream3.ui.settings.logcat.LogcatDialog {
                showDialog = false
            }
        }

        if (showBackupPathDialog) {
            val current = remember { settings.backup.url.get() }
            var value by remember { mutableStateOf(current) }
            AlertDialog(
                onDismissRequest = { showBackupPathDialog = false },
                containerColor = MaterialTheme.colorScheme.background,
                title = { Text(stringResource(R.string.backup_path_or_url_title)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                },
                confirmButton = {
                    WhiteButton(text = stringResource(R.string.ok), onClick = {
                        val trimmed = value.trim()
                        if (trimmed.isBlank()) {
                            showToast(R.string.backup_path_empty, Toast.LENGTH_SHORT)
                        } else {
                            settings.backup.url.set(trimmed)
                            showBackupPathDialog = false
                            pendingBackupAction?.invoke()
                            pendingBackupAction = null
                        }
                    })
                },
                dismissButton = {
                    BlackButton(text = stringResource(R.string.cancel), onClick = {
                        showBackupPathDialog = false
                        pendingBackupAction = null
                    })
                }
            )
        }

        if (showGitHubTokenDialog) {
            val current =
                remember { settings.backup.githubToken.get() }
            var value by remember { mutableStateOf(current) }
            AlertDialog(
                onDismissRequest = { showGitHubTokenDialog = false },
                containerColor = MaterialTheme.colorScheme.background,
                title = { Text(stringResource(R.string.github_token_title)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                },
                confirmButton = {
                    WhiteButton(text = stringResource(R.string.ok), onClick = {
                        val trimmed = value.trim()
                        if (trimmed.isBlank()) {
                            showToast(R.string.github_token_empty, Toast.LENGTH_SHORT)
                        } else {
                            settings.backup.githubToken.set(trimmed)
                            showGitHubTokenDialog = false
                            pendingBackupAction?.invoke()
                            pendingBackupAction = null
                        }
                    })
                },
                dismissButton = {
                    BlackButton(text = stringResource(R.string.cancel), onClick = {
                        showGitHubTokenDialog = false
                        pendingBackupAction = null
                    })
                }
            )
        }

        if (showResetDialog) {
            ActionDialog(
                title = stringResource(R.string.reset_app_title),
                text = stringResource(R.string.reset_app_confirm_message),
                confirmText = stringResource(R.string.reset_btn),
                dismissText = stringResource(R.string.cancel),
                dismiss = { showResetDialog = false },
                confirm = {
                    showResetDialog = false
                    val context = CloudStreamApp.context ?: return@ActionDialog
                    ioSafe {
                        BackupUtils.factoryReset(context)
                        main { BackupUtils.restartApp(context) }
                    }
                }
            )
        }

        fun isGitHubUrl(path: String): Boolean {
            return path.startsWith("https://github.com") ||
                path.startsWith("https://raw.githubusercontent.com")
        }
        fun runBackup() {
            val destination = settings.backup.destination.get()
            when (destination) {
                BackupUtils.BACKUP_DESTINATION_PATH_URL -> {
                    val path = settings.backup.url.get()
                    val appContext = CloudStreamApp.context
                    if (path.isBlank()) {
                        pendingBackupAction = { runBackup() }
                        showBackupPathDialog = true
                    } else if (isGitHubUrl(path) && appContext != null &&
                        BackupUtils.getBackupToken(appContext).isNullOrBlank()
                    ) {
                        pendingBackupAction = { runBackup() }
                        showGitHubTokenDialog = true
                    } else {
                        BackupUtils.backupToPath(activity, path)
                    }
                }
                else -> BackupUtils.backup(activity)
            }
        }
        fun runRestore() {
            val destination = settings.backup.destination.get()
            when (destination) {
                BackupUtils.BACKUP_DESTINATION_PATH_URL -> {
                    val path = settings.backup.url.get()
                    if (path.isBlank()) {
                        pendingBackupAction = { runRestore() }
                        showBackupPathDialog = true
                    } else {
                        (activity as? FragmentActivity)?.restoreFromPath(path)
                    }
                }
                else -> (activity as? FragmentActivity)?.restorePrompt()
            }
        }
        val githubViewModel = MainActivityScreen.githubViewModel()

        return persistentListOf(
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_app_updates),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.check_for_update),
                        subtitle = BuildConfig.VERSION_NAME,
                        icon = painterResource(R.drawable.mobile_arrow_down_24px),
                        onClick = {
                            githubViewModel?.onAction(GithubAction.SearchForUpdate)
                            /*ioSafe {
                                if (activity?.runAutoUpdate(false) == false) {
                                    activity?.runOnUiThread {
                                        showToast(
                                            R.string.no_update_found,
                                            Toast.LENGTH_SHORT
                                        )
                                    }
                                }
                            }*/
                        }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.install_prerelease),
                        icon = painterResource(R.drawable.mobile_code_24px),
                        enabled = BuildConfig.FLAVOR == "stable",
                        onClick = {
                            activity?.installPreReleaseIfNeeded()
                        }
                    ),

                    Preference.PreferenceItem.ListPreference(
                        title = stringResource(R.string.apk_installer_settings),
                        subtitle = stringResource(R.string.apk_installer_settings_des),
                        icon = painterResource(R.drawable.mobile_wrench_24px),
                        entries = integerArrayResource(R.array.apk_installer_values).zip(
                            stringArrayResource(R.array.apk_installer_pref)
                        ).toMap(),
                        preference = settings.updates.apkInstaller
                    ),

                    Preference.PreferenceItem.SwitchPreference(
                        title = stringResource(R.string.updates_settings),
                        subtitle = stringResource(R.string.updates_settings_des),
                        icon = painterResource(R.drawable.notifications_active_24px),
                        preference = settings.updates.showAppUpdates
                    )
                )
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_backup),
                preferenceItems = buildList<Preference.PreferenceItem<out Any?, out Any?>> {
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(R.string.backup_settings),
                            icon = painterResource(R.drawable.save_as_24px),
                            onClick = {
                                runBackup()
                            }
                        )
                    )
                    add(
                        Preference.PreferenceItem.ListPreference(
                            title = stringResource(R.string.backup_destination_title),
                            icon = painterResource(R.drawable.ic_baseline_cloud_upload_24),
                            entries = persistentListOf(
                                BackupUtils.BACKUP_DESTINATION_LOCAL to stringResource(R.string.backup_destination_local),
                                BackupUtils.BACKUP_DESTINATION_PATH_URL to stringResource(R.string.backup_destination_path_url),
                            ).toMap(),
                            subtitleProvider = { v, e -> e[v] },
                            preference = settings.backup.destination,
                        )
                    )
                    // Path/URL and GitHub token only apply to the Path/URL destination.
                    if (isPathUrlDestination) {
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = stringResource(R.string.backup_path_or_url_title),
                                icon = painterResource(R.drawable.ic_baseline_link_24),
                                subtitle = backupUrl.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.backup_destination_path_url),
                                onClick = {
                                    showBackupPathDialog = true
                                }
                            )
                        )
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = stringResource(R.string.github_token_title),
                                subtitle = stringResource(R.string.github_token_summary),
                                icon = painterResource(R.drawable.ic_baseline_vpn_key_24),
                                onClick = {
                                    showGitHubTokenDialog = true
                                }
                            )
                        )
                    }
                    add(
                        Preference.PreferenceItem.ListPreference(
                            title = stringResource(R.string.backup_frequency),
                            icon = painterResource(R.drawable.save_clock_24px),
                            entries = integerArrayResource(R.array.periodic_work_values).zip(
                                stringArrayResource(R.array.periodic_work_names)
                            ).toMap(),
                            preference = settings.backup.frequency
                        )
                    )
                    // The local folder picker only applies to the Local destination.
                    if (!isPathUrlDestination) {
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = stringResource(R.string.backup_path_title),
                                icon = painterResource(R.drawable.folder_24px),
                                subtitle = visualBackupPath,
                                onClick = {
                                    // This is not a ListPreference because the old selection system is
                                    // broken af. This needs to be refactored to QuickNovels download path
                                    // system.
                                    selectFileSelector.launch(Uri.EMPTY)
                                }
                            )
                        )
                    }
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(R.string.restore_settings),
                            icon = painterResource(R.drawable.restore_page_24px),
                            onClick = {
                                runRestore()
                            }
                        )
                    )
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(R.string.reset_app_title),
                            subtitle = stringResource(R.string.reset_app_summary),
                            icon = painterResource(R.drawable.ic_baseline_restart_24),
                            onClick = {
                                showResetDialog = true
                            }
                        )
                    )
                }.toPersistentList()
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_extensions),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        title = stringResource(R.string.automatic_plugin_updates),
                        icon = painterResource(R.drawable.extension_24px),
                        preference = settings.plugins.autoUpdate,
                    ),

                    Preference.PreferenceItem.ListPreference(
                        title = stringResource(R.string.automatic_plugin_download),
                        subtitle = "%s\n" + stringResource(R.string.automatic_plugin_download_summary),
                        icon = painterResource(R.drawable.extention_renew2),
                        entries = AutoDownloadMode.entries.map { it.value }.sorted()
                            .zip(stringArrayResource((R.array.auto_download_plugin))).toMap(),
                        preference = settings.plugins.autoDownload
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.update_plugins),
                        subtitle = stringResource(R.string.update_plugins_manually),
                        icon = painterResource(R.drawable.extention_download),
                        onClick = {
                            ioSafe {
                                PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_manuallyReloadAndUpdatePlugins(
                                    activity ?: return@ioSafe
                                )
                            }
                        }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.test_extensions),
                        subtitle = stringResource(R.string.test_extensions_summary),
                        icon = painterResource(R.drawable.baseline_network_ping_24),
                        onClick = {
                            activity?.navigate(R.id.navigation_test_providers)
                        })
                )
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_actions),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.show_log_cat),
                        icon = painterResource(R.drawable.article_24px),
                        onClick = {
                            showDialog = true
                        }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.redo_setup_process),
                        icon = painterResource(R.drawable.construction_24px),
                        onClick = {
                            activity?.navigate(R.id.navigation_setup_language)
                        }
                    ),
                )
            )
        )
    }
}