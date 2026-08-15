package com.lagradost.cloudstream3.utils

import android.content.Context
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
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY_LOCAL
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi.Companion.ANILIST_CACHED_LIST
import com.lagradost.cloudstream3.syncproviders.providers.MALApi.Companion.MAL_CACHED_LIST
import com.lagradost.cloudstream3.syncproviders.providers.KitsuApi.Companion.KITSU_CACHED_LIST
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
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

object BackupUtils {

    const val BACKUP_DESTINATION_LOCAL = "local"
    const val BACKUP_DESTINATION_PATH_URL = "path_url"

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
        
        // Download headers are unintuitively used in the resume watching system.
        // We can therefore not prune download headers in backups.
        // DOWNLOAD_HEADER_CACHE_BACKUP,
        // DOWNLOAD_HEADER_CACHE,
        

        // This may overwrite valid local data with invalid data
        KEY_DOWNLOAD_INFO,

        // Prevent backups from automatically starting downloads
        KEY_RESUME_IN_QUEUE,
        KEY_RESUME_PACKAGES,
        QUEUE_KEY,

        // Prevent automatic plugin download after restoring backup
        "auto_download_plugins_key2"
    )

    /** false if key should not be contained in backup */
    private fun String.isTransferable(): Boolean {
        return !nonTransferableKeys.any { this.contains(it) }
    }

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
    )

    @Suppress("UNCHECKED_CAST")
    private fun getBackup(context: Context): BackupFile {
        val allData = context.getSharedPrefs().all.filter { it.key.isTransferable() }
        val allSettings = context.getDefaultSharedPrefs().all.filter { it.key.isTransferable() }

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
        }

        if (restoreDataStore) {
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

    private fun FragmentActivity.restoreFromText(text: String) {
        val restoredValue = parseJson<BackupFile>(text)

        restore(
            this,
            restoredValue,
            restoreSettings = true,
            restoreDataStore = true,
        )
        runOnUiThread { recreate() }
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
