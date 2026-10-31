package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.IGNORED_PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY_LOCAL
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi.Companion.ANILIST_CACHED_LIST
import com.lagradost.cloudstream3.syncproviders.providers.MALApi.Companion.MAL_CACHED_LIST
import com.lagradost.cloudstream3.syncproviders.providers.KitsuApi.Companion.KITSU_CACHED_LIST
import com.lagradost.cloudstream3.ui.home.HomeCategorySettings
import com.lagradost.cloudstream3.ui.home.ChildProviderSettings
import com.lagradost.cloudstream3.ui.home.CustomHomeSources
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.UIHelper.checkWrite
import com.lagradost.cloudstream3.utils.UIHelper.requestRW
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.setupStream
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager.QUEUE_KEY
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_DOWNLOAD_INFO
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_IN_QUEUE
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_PACKAGES
import com.lagradost.safefile.MediaFileContentType
import com.lagradost.safefile.SafeFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.closeQuietly
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PrintWriter
import java.lang.System.currentTimeMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

object BackupUtils {

    const val BACKUP_DESTINATION_LOCAL = "local"
    const val BACKUP_DESTINATION_PATH_URL = "path_url"

    /**
     * One-shot flag set when a restore finishes. The activity is recreated right after a
     * restore, so the confirmation is consumed by the next MainActivity instead of being
     * tied to the activity that is about to be destroyed.
     */
    const val RESTORE_CONFIRMATION_KEY = "restore_confirmation_pending"

    fun getBackupDestination(context: Context): String? {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.backup_destination_key), null)
    }

    fun getBackupUrl(context: Context): String? {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.backup_url_key), null)
    }

    fun getBackupToken(context: Context): String? {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(context.getString(R.string.github_token_key), null)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * No sensitive or breaking data in the backup
     */
    private val nonTransferableKeys = listOf(
        ANILIST_CACHED_LIST,
        MAL_CACHED_LIST,
        KITSU_CACHED_LIST,

        // The plugins themselves are not backed up
        PLUGINS_KEY,
        PLUGINS_KEY_LOCAL,

        AccountManager.ACCOUNT_TOKEN,
        AccountManager.ACCOUNT_IDS,

        // TODO proper getter for string res keys to ensure that they are updated
        "biometric_key", // can lock down users if backup is shared on a incompatible device
        "nginx_user", // Nginx user key

        // No access rights after restore data from backup
        "download_path_key",
        "download_path_key_visual",
        "backup_path_key",
        "backup_dir_path_key",

        // GitHub token must not leave the device
        "github_token",

        // When sharing backup we do not want to transfer what is essentially the password
        // Note that this is deprecated, and can be removed after all tokens have expired
        "anilist_token",
        "anilist_user",
        "mal_user",
        "mal_token",
        "mal_refresh_token",
        "mal_unixtime",
        "open_subtitles_user",
        "subdl_user",
        "simkl_token",


        // Downloads can not be restored from backups.
        // The download path URI can not be transferred.
        // In the future we may potentially write metadata to files in the download directory
        // and make it possible to restore download folders using that metadata.
        DOWNLOAD_EPISODE_CACHE_BACKUP,
        DOWNLOAD_EPISODE_CACHE,
        
        // The download header cache is not backed up as a whole: it holds download metadata
        // and would grow with every title. The entries needed by Continue Watching are added
        // explicitly in getBackup, so only the cards the user actually watched travel with
        // a backup.

        // This may overwrite valid local data with invalid data
        KEY_DOWNLOAD_INFO,

        // Prevent backups from automatically starting downloads
        KEY_RESUME_IN_QUEUE,
        KEY_RESUME_PACKAGES,
        QUEUE_KEY,

        // Prevent automatic plugin download after restoring backup
        "auto_download_plugins_key2",

        // One-shot UI flag, must neither be backed up nor restored
        RESTORE_CONFIRMATION_KEY,
    )

    /** false if key should not be contained in backup */
    private fun String.isTransferable(): Boolean {
        return !nonTransferableKeys.any { this.contains(it) }
    }

    /**
     * Keys that are already transferable but are explicitly whitelisted because
     * losing them would change the home screen after a restore.
     */
    private val mustBackupKeys = setOf(IGNORED_PLUGINS_KEY)

    private var restoreFileSelector: ActivityResultLauncher<Array<String>>? = null

    // Kinda hack, but I couldn't think of a better way
    @Serializable
    data class BackupVars(
        @JsonProperty("_Bool") @SerialName("_Bool") val bool: Map<String, Boolean>?,
        @JsonProperty("_Int") @SerialName("_Int") val int: Map<String, Int>?,
        @JsonProperty("_String") @SerialName("_String") val string: Map<String, String>?,
        @JsonProperty("_Float") @SerialName("_Float") val float: Map<String, Float>?,
        @JsonProperty("_Long") @SerialName("_Long") val long: Map<String, Long>?,
        @JsonProperty("_StringSet") @SerialName("_StringSet") val stringSet: Map<String, Set<String>?>?,
    )

    @Serializable
    data class BackupFile(
        @JsonProperty("datastore") @SerialName("datastore") val datastore: BackupVars,
        @JsonProperty("settings") @SerialName("settings") val settings: BackupVars,
        // Child-provider checklists live in each plugin's own preference file,
        // not in the datastore/settings above. Keyed by preference file name.
        @JsonProperty("plugin_prefs") @SerialName("plugin_prefs")
        val pluginPrefs: Map<String, Map<String, Boolean>>? = null,
    )

    /** Keys of the cached card details that Continue Watching needs to show an entry. */
    private fun String.isHeaderCacheKey(): Boolean =
        startsWith("$DOWNLOAD_HEADER_CACHE/") || startsWith("$DOWNLOAD_HEADER_CACHE_BACKUP/")

    @Suppress("UNCHECKED_CAST")
    private fun getBackup(context: Context): BackupFile {
        val dataPrefs = context.getSharedPrefs().all

        // A Continue Watching entry is just a reference to a cached "header" (name, poster,
        // card URL, type). Back up only the headers that a resume entry points at, instead
        // of the whole cache, so restoring actually brings Continue Watching back.
        val resumeSuffixes = dataPrefs.keys
            .filter { it.contains("/$RESULT_RESUME_WATCHING/") }
            .mapNotNull { it.substringAfterLast('/').takeIf(String::isNotBlank) }
            .toSet()

        val allData = dataPrefs.filter { (key, _) ->
            if (key.isHeaderCacheKey()) {
                key.substringAfterLast('/') in resumeSuffixes
            } else {
                key.isTransferable() || key in mustBackupKeys
            }
        }
        val allSettings = context.getDefaultSharedPrefs().all.filter {
            // Category rules travel with the regular settings section and are
            // explicitly whitelisted so they cannot be dropped by future filters.
            it.key.isTransferable() || it.key in HomeCategorySettings.backupKeys ||
                it.key in CustomHomeSources.backupKeys
        }

        val allDataSorted = BackupVars(
            allData.filter { it.value is Boolean } as? Map<String, Boolean>,
            allData.filter { it.value is Int } as? Map<String, Int>,
            allData.filter { it.value is String } as? Map<String, String>,
            allData.filter { it.value is Float } as? Map<String, Float>,
            allData.filter { it.value is Long } as? Map<String, Long>,
            allData.filter { it.value as? Set<String> != null } as? Map<String, Set<String>>,
        )

        val allSettingsSorted = BackupVars(
            allSettings.filter { it.value is Boolean } as? Map<String, Boolean>,
            allSettings.filter { it.value is Int } as? Map<String, Int>,
            allSettings.filter { it.value is String } as? Map<String, String>,
            allSettings.filter { it.value is Float } as? Map<String, Float>,
            allSettings.filter { it.value is Long } as? Map<String, Long>,
            allSettings.filter { it.value as? Set<String> != null } as? Map<String, Set<String>>,
        )

        return BackupFile(
            allDataSorted,
            allSettingsSorted,
            ChildProviderSettings.backup(context),
        )
    }

    @WorkerThread
    fun restore(
        context: Context?,
        backupFile: BackupFile,
        restoreSettings: Boolean,
        restoreDataStore: Boolean,
    ) {
        if (context == null) return
        if (restoreSettings) {
            context.restoreMap(backupFile.settings.bool, true)
            context.restoreMap(backupFile.settings.int, true)
            context.restoreMap(backupFile.settings.string, true)
            context.restoreMap(backupFile.settings.float, true)
            context.restoreMap(backupFile.settings.long, true)
            context.restoreMap(backupFile.settings.stringSet, true)

            // Child-provider selections are stored separately by plugins such as
            // Cricify/PlayFy, so restore them explicitly.
            backupFile.pluginPrefs?.let {
                ChildProviderSettings.restore(context, it)
            }
        }

        if (restoreDataStore) {
            // Restore replaces Continue Watching instead of merging it. Otherwise entries
            // created after the backup (and their cached cards) keep showing up next to
            // the restored ones, making the backup look wrong.
            val dataPrefs = context.getSharedPrefs()
            val watchHistoryKeys = dataPrefs.all.keys.filter { key ->
                key.contains("/$RESULT_RESUME_WATCHING/") ||
                    key.contains("/$VIDEO_POS_DUR/")
            }
            if (watchHistoryKeys.isNotEmpty()) {
                dataPrefs.edit { watchHistoryKeys.forEach { remove(it) } }
            }

            context.restoreMap(backupFile.datastore.bool)
            context.restoreMap(backupFile.datastore.int)
            context.restoreMap(backupFile.datastore.string)
            context.restoreMap(backupFile.datastore.float)
            context.restoreMap(backupFile.datastore.long)
            context.restoreMap(backupFile.datastore.stringSet)
        }

        // Make sure the library is fresh
        for(api in AccountManager.syncApis) {
            api.requireLibraryRefresh = true
        }

        // Category rules are stored in the regular settings section, so they are
        // already part of the backup. Normalise restored provider keys to the
        // portable plugin-file key so they match on this device too.
        HomeCategorySettings.migrateProviderKeys(context)
        // The selected default homepage provider is stored in the datastore. Its
        // plugin path comes from the other device, so resolve it to the local path.
        DataStoreHelper.normalizeCurrentHomeSource()
    }

    /**
     * Wipes all app data (settings, datastore, plugins, databases, caches and the external
     * local plugins folder) so the app behaves as if it was freshly installed. Downloaded
     * videos in shared storage are left untouched, mirroring what uninstalling the app
     * would do.
     */
    @WorkerThread
    fun factoryReset(context: Context) {
        // SharedPreferences: DataStore and app settings
        runCatching { context.getSharedPrefs().edit(commit = true) { clear() } }
            .onFailure { logError(it) }
        runCatching { context.getDefaultSharedPrefs().edit(commit = true) { clear() } }
            .onFailure { logError(it) }

        // Databases
        runCatching { context.databaseList()?.forEach { context.deleteDatabase(it) } }
            .onFailure { logError(it) }

        // Internal/external app files (plugins live in filesDir/Extensions) and caches
        listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.codeCacheDir,
            context.noBackupFilesDir,
            context.getExternalFilesDir(null),
        ).forEach { dir ->
            runCatching { dir.listFiles()?.forEach { it.deleteRecursively() } }
                .onFailure { logError(it) }
        }

        // External local plugins (/Cloudstream3/plugins)
        runCatching { PluginManager.deleteLocalPluginsFolder() }
            .onFailure { logError(it) }
    }

    /** Restarts the app so all in-memory state is dropped, e.g. after a factory reset. */
    fun restartApp(context: Context) {
        val component =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
        if (component != null) {
            context.startActivity(Intent.makeRestartActivityTask(component))
        }
        exitProcess(0)
    }

    fun backup(context: Context?) = ioSafe {
        if (context == null) return@ioSafe
        if (getBackupDestination(context) == BACKUP_DESTINATION_PATH_URL) {
            val path = getBackupUrl(context)
            if (!path.isNullOrBlank()) {
                backupToPathInternal(context, path)
                return@ioSafe
            }
        }
        backupLocalInternal(context)
    }

    /** Writes the backup to the given path or URL instead of the local backup folder. */
    fun backupToPath(context: Context?, path: String) = ioSafe {
        if (context == null) return@ioSafe
        backupToPathInternal(context, path)
    }

    private fun backupLocalInternal(context: Context) {
        var fileStream: OutputStream? = null
        var printStream: PrintWriter? = null

        try {
            if (!context.checkWrite()) {
                showToast(R.string.backup_failed, Toast.LENGTH_LONG)
                context.getActivity()?.requestRW()
                return
            }

            val date = SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.getDefault()).format(Date(currentTimeMillis()))
            val displayName = "CS3_Backup_${date}"
            val backupFile = getBackup(context)
            val stream = setupBackupStream(context, displayName)

            fileStream = stream.openNew()
            printStream = PrintWriter(fileStream)
            printStream.print(backupFile.toJson())
            showToast(R.string.backup_success, Toast.LENGTH_LONG)
        } catch (e: Exception) {
            logError(e)
            try {
                showToast(
                    txt(R.string.backup_failed_error_format, e.toString()),
                    Toast.LENGTH_LONG,
                )
            } catch (e: Exception) {
                logError(e)
            }
        } finally {
            printStream?.closeQuietly()
            fileStream?.closeQuietly()
        }
    }

    private fun backupToPathInternal(context: Context, path: String) {
        var fileStream: OutputStream? = null
        var printStream: PrintWriter? = null

        try {
            val backupFile = getBackup(context)
            val json = backupFile.toJson()
            if (path.startsWith("http://") || path.startsWith("https://")) {
                val resolvedUrl = when (val gh = parseGitHubPath(path)) {
                    null -> uploadToUrl(context, path, json)
                    else -> githubUpload(context, gh, json)
                }
                if (resolvedUrl != path) {
                    // Hosts like 0x0.st / paste.rs assign a new URL on upload,
                    // remember it so future backups and restores use it automatically.
                    PreferenceManager.getDefaultSharedPreferences(context).edit {
                        putString(context.getString(R.string.backup_url_key), resolvedUrl)
                    }
                }
                showToast(txt(R.string.backup_uploaded_format, resolvedUrl), Toast.LENGTH_LONG)
                return
            }

            fileStream = backupPathToFile(context, path).openOutputStreamOrThrow(false)
            printStream = PrintWriter(fileStream)
            printStream.print(json)
            showToast(txt(R.string.backup_saved_to_format, path), Toast.LENGTH_LONG)
        } catch (e: Exception) {
            logError(e)
            try {
                showToast(
                    txt(R.string.backup_failed_error_format, e.toString()),
                    Toast.LENGTH_LONG,
                )
            } catch (e: Exception) {
                logError(e)
            }
        } finally {
            printStream?.closeQuietly()
            fileStream?.closeQuietly()
        }
    }

    /**
     * Uploads the backup with PUT, falling back to POST for endpoints that do
     * not accept PUT requests. Returns the URL the file ended up at, which is
     * usually the same as [url] but may be a new URL returned by the host.
     */
    @Throws(IOException::class)
    private fun uploadToUrl(context: Context, url: String, json: String): String {
        val body = json.toRequestBody("text/plain; charset=utf-8".toMediaType())
        val putResponse =
            app.baseClient.newCall(Request.Builder().url(url).put(body).build()).execute()
        var response = putResponse
        if (!putResponse.isSuccessful && putResponse.code in listOf(404, 405)) {
            putResponse.close()
            val postResponse =
                app.baseClient.newCall(Request.Builder().url(url).post(body).build()).execute()
            response = postResponse
        }
        return response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            // The response body may contain the final file URL (e.g. paste hosts)
            it.body?.string()?.let { bodyText ->
                Regex("https?://[^\\s\"<>]+")
                    .find(bodyText)
                    ?.value
                    ?.trimEnd('.', ',', ')', ']', '"', '\'')
            } ?: url
        }
    }

    private data class GitHubPath(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    @Serializable
    private data class GitHubContentsResponse(
        val sha: String? = null,
        val message: String? = null,
    )

    private val githubRawRegex =
        Regex("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)$")
    private val githubBlobRegex =
        Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$")

    /** Extracts owner, repo, branch and file path from a GitHub file URL. */
    private fun parseGitHubPath(url: String): GitHubPath? {
        val match = githubRawRegex.find(url) ?: githubBlobRegex.find(url) ?: return null
        return GitHubPath(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
    }

    /**
     * Uploads the backup to a GitHub repository via the Contents API using the
     * token stored in settings. Returns the raw file URL used for restoring.
     */
    @Throws(IOException::class)
    private fun githubUpload(context: Context, gh: GitHubPath, json: String): String {
        val token = getBackupToken(context)
            ?: throw IOException("GitHub token missing")
        val apiUrl = "https://api.github.com/repos/${gh.owner}/${gh.repo}/contents/${gh.path}"

        // Existing files need their sha to be updated
        val existingSha = runCatching {
            val response = app.baseClient.newCall(
                Request.Builder()
                    .url("$apiUrl?ref=${gh.branch}")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
            ).execute()
            response.use {
                if (it.isSuccessful) {
                    parseJson<GitHubContentsResponse>(it.body?.string() ?: "").sha
                } else {
                    null
                }
            }
        }.getOrNull()

        val contentB64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val bodyJson = buildString {
            append("{\"message\":\"backup\",\"content\":\"")
            append(contentB64)
            append("\",\"branch\":\"")
            append(gh.branch.replace("\"", "\\\""))
            if (existingSha != null) {
                append("\",\"sha\":\"")
                append(existingSha)
            }
            append("\"}")
        }
        val response = app.baseClient.newCall(
            Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer $token")
                .put(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        ).execute()
        response.use {
            if (!it.isSuccessful) {
                throw IOException("HTTP ${it.code} ${it.body?.string()?.take(200)}")
            }
        }
        return "https://raw.githubusercontent.com/${gh.owner}/${gh.repo}/refs/heads/${gh.branch}/${gh.path}"
    }

    /**
     * Resolves the given path to a file. If the path points to a directory
     * (ends with a separator) a timestamped backup file is created in it.
     */
    @Throws(IOException::class)
    private fun backupPathToFile(context: Context, path: String): SafeFile {
        if (path.endsWith(File.separator) || path.endsWith("/")) {
            val dir = SafeFile.fromFilePath(context, path)
                ?: throw IOException("Bad path: $path")
            val date = SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.getDefault()).format(Date(currentTimeMillis()))
            return dir.createFileOrThrow("CS3_Backup_${date}.txt")
        }
        return SafeFile.fromFilePath(context, path) ?: throw IOException("Bad path: $path")
    }

    @Throws(IOException::class)
    private fun setupBackupStream(context: Context, name: String, ext: String = "txt"): DownloadObjects.StreamData {
        return setupStream(
            baseFile = getCurrentBackupDir(context).first ?: getDefaultBackupDir(context)
            ?: throw IOException("Bad config"),
            name,
            folder = null,
            extension = ext,
            tryResume = false,
        )
    }

    fun FragmentActivity.setUpBackup() {
        try {
            restoreFileSelector =
                registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri == null) return@registerForActivityResult
                    val activity = this
                    ioSafe {
                        try {
                            val input = activity.contentResolver.openInputStream(uri)
                                ?: return@ioSafe

                            val text = input.bufferedReader().readText()
                            activity.restoreFromText(text)
                        } catch (e: Exception) {
                            logError(e)
                            main { // smth can fail in .format
                                showToast(
                                    getString(R.string.restore_failed_format).format(e.toString())
                                )
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            logError(e)
        }
    }

    /** Restores a backup from the given local path or http(s) URL. */
    fun FragmentActivity.restoreFromPath(path: String) {
        val activity = this
        ioSafe {
            try {
                val text = fetchPathText(activity, path)
                activity.restoreFromText(text)
            } catch (e: Exception) {
                logError(e)
                main { // smth can fail in .format
                    showToast(
                        getString(R.string.restore_failed_format).format(e.toString())
                    )
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun fetchPathText(context: Context, path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> {
            val response = app.baseClient.newCall(Request.Builder().url(path).build()).execute()
            response.use {
                if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                it.body?.string() ?: throw IOException("Empty response")
            }
        }
        else -> {
            val file = SafeFile.fromFilePath(context, path)
                ?: throw IOException("Bad path: $path")
            file.openInputStreamOrThrow().bufferedReader().readText()
        }
    }

    private suspend fun FragmentActivity.restoreFromText(text: String) {
        val restoredValue = parseJson<BackupFile>(text)

        restore(
            this,
            restoredValue,
            restoreSettings = true,
            restoreDataStore = true,
        )

        // The skip list is restored from the backup, but plugin files are not. Uninstall any
        // plugin the restored skip list now blocks, so nothing is both blocked and installed.
        PluginManager.uninstallIgnoredPlugins()

        // Set before recreate() so the user still gets told the data was recovered
        setKey(RESTORE_CONFIRMATION_KEY, true)
        runOnUiThread { recreate() }
    }

    /**
     * Returns true if a restore completed since the last call, clearing the flag so the
     * confirmation is only ever shown once per restore.
     */
    fun consumeRestoreConfirmation(context: Context): Boolean {
        val restored = context.getKey(RESTORE_CONFIRMATION_KEY, false) == true
        if (restored) context.removeKey(RESTORE_CONFIRMATION_KEY)
        return restored
    }

    fun FragmentActivity.restorePrompt() {
        runOnUiThread {
            try {
                restoreFileSelector?.launch(
                    arrayOf(
                        "text/plain",
                        "text/str",
                        "text/x-unknown",
                        "application/json",
                        "unknown/unknown",
                        "content/unknown",
                        "application/octet-stream",
                    )
                )
            } catch (e: Exception) {
                showToast(e.message)
                logError(e)
            }
        }
    }

    private fun <T> Context.restoreMap(
        map: Map<String, T>?,
        isEditingAppSettings: Boolean = false,
    ) {
        val editor = DataStore.editor(this, isEditingAppSettings)
        map?.forEach {
            if (it.key.isTransferable()) {
                editor.setKeyRaw(it.key, it.value)
            }
        }
        editor.apply()
    }

    /**
     * Copy of [com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getDefaultDir],
     * modified for backup-specific paths.
     */
    fun getDefaultBackupDir(context: Context): SafeFile? {
        return SafeFile.fromMedia(context, MediaFileContentType.Downloads)
    }

    /**
     * Copy of [com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath],
     * modified for backup-specific paths.
     */
    fun getCurrentBackupDir(context: Context): Pair<SafeFile?, String?> {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
        val basePathSetting = settingsManager.getString(context.getString(R.string.backup_path_key), null)
        return baseBackupPathToFile(context, basePathSetting) to basePathSetting
    }

    /**
     * Copy of [com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.basePathToFile],
     * modified for backup-specific paths.
     */
    private fun baseBackupPathToFile(context: Context, path: String?): SafeFile? {
        return when {
            path.isNullOrBlank() -> getDefaultBackupDir(context)
            path.startsWith("content://") -> SafeFile.fromUri(context, path.toUri())
            else -> SafeFile.fromFilePath(context, path)
        }
    }
}
