package com.lagradost.cloudstream3.plugins

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.removePluginMapping
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.AutoDownloadMode
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.InternalAPI
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainAPI.Companion.settingsForProvider
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainActivity.Companion.lastError
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.PROVIDER_STATUS_OK
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.debugPrint
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.RepositoryManager.ONLINE_PLUGINS_FOLDER
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.plugins.RepositoryManager.downloadPluginToFile
import com.lagradost.cloudstream3.plugins.RepositoryManager.getRepoPlugins
import com.lagradost.cloudstream3.plugins.RepositoryManager.sha256
import com.lagradost.cloudstream3.ui.home.LiveEventNotifier
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.sanitizeFilename
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.txt
import dalvik.system.PathClassLoader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStreamReader

// Different keys for local and not since local can be removed at any time without app knowing, hence the local are getting rebuilt on every app start
const val PLUGINS_KEY = "PLUGINS_KEY"
const val PLUGINS_KEY_LOCAL = "PLUGINS_KEY_LOCAL"

/**
 * Set of plugin-scoped keys the user marked as "ignored". Ignored plugins are never
 * auto-downloaded, never auto-updated, never bulk-downloaded and never bulk-deleted.
 *
 * A key is the plugin's download url and [PluginData.internalName] separated by a pipe (`|`).
 * The plugin url is part of the key because different repositories may contain plugins with the
 * same internal name, and blocking one must not block the other. Local plugins (whose url is a
 * file path) are keyed by internal name only. Legacy entries without the separator are still
 * accepted.
 *
 * Stored as a JSON string through the normal [setKey] path, so it is included in app backups
 * automatically and restored on import.
 *
 * NOTE: the name deliberately avoids the substring "PLUGINS_KEY" because [BackupUtils] filters
 * non-transferable keys by substring match.
 */
const val IGNORED_PLUGINS_KEY = "IGNORED_EXTENSION_SKIP_LIST"

const val EXTENSIONS_CHANNEL_ID = "cloudstream3.extensions"
const val EXTENSIONS_CHANNEL_NAME = "Extensions"
const val EXTENSIONS_CHANNEL_DESCRIPT = "Extension notification channel"

// Data class for internal storage
@Serializable
data class PluginData(
    @JsonProperty("internalName") @SerialName("internalName") val internalName: String,
    @JsonProperty("url") @SerialName("url") val url: String?,
    @JsonProperty("isOnline") @SerialName("isOnline") val isOnline: Boolean,
    @JsonProperty("filePath") @SerialName("filePath") val filePath: String,
    @JsonProperty("version") @SerialName("version") val version: Int,
) {
    @WorkerThread
    fun toSitePlugin(): SitePlugin {
        return SitePlugin(
            this.filePath,
            PROVIDER_STATUS_OK,
            maxOf(1, version),
            1,
            internalName,
            internalName,
            emptyList(),
            File(this.filePath).name,
            null,
            null,
            null,
            null,
            File(this.filePath).length(),
            // No file hash for local plugins. Local plugins have no use for the hash, and it's expensive to compute.
            null
        )
    }
}

// This is used as a placeholder / not set version
const val PLUGIN_VERSION_NOT_SET = Int.MIN_VALUE

// This always updates
const val PLUGIN_VERSION_ALWAYS_UPDATE = -1

object PluginManager {
    // Prevent multiple writes at once
    val lock = Mutex()

    const val TAG = "PluginManager"

    private var hasCreatedNotChanel = false

    /**
     * Store data about the plugin for fetching later
     * */
    private suspend fun setPluginData(data: PluginData) {
        lock.withLock {
            if (data.isOnline) {
                val plugins = getPluginsOnline()
                val newPlugins = plugins.filter { it.filePath != data.filePath } + data
                setKey(PLUGINS_KEY, newPlugins)
            } else {
                val plugins = getPluginsLocal()
                setKey(PLUGINS_KEY_LOCAL, plugins.filter { it.filePath != data.filePath } + data)
            }
        }
    }

    private suspend fun deletePluginData(data: PluginData?) {
        if (data == null) return
        lock.withLock {
            if (data.isOnline) {
                val plugins = getPluginsOnline().filter { it.url != data.url }
                setKey(PLUGINS_KEY, plugins)
            } else {
                val plugins = getPluginsLocal().filter { it.filePath != data.filePath }
                setKey(PLUGINS_KEY_LOCAL, plugins)
            }
        }
    }

    /**
     * Deletes the external local plugins folder (/Cloudstream3/plugins) and the copies
     * the app made in its own external files folder. Returns true when the folder is gone.
     */
    fun deleteLocalPluginsFolder(): Boolean {
        return try {
            File(LOCAL_PLUGINS_PATH).deleteRecursively()
        } catch (t: Throwable) {
            logError(t)
            false
        }
    }

    suspend fun deleteRepositoryData(repositoryPath: String) {
        lock.withLock {
            val plugins = getPluginsOnline().filter {
                !it.filePath.contains(repositoryPath)
            }
            val file = File(repositoryPath)
            safe {
                if (file.exists()) file.deleteRecursively()
            }
            setKey(PLUGINS_KEY, plugins)
        }
    }

    /**
     * Deletes all generated oat files which will force Android to recompile the dex extensions.
     * This might fix unrecoverable SIGSEGV exceptions when old oat files are loaded in a new app update.
     */
    fun deleteAllOatFiles(context: Context) {
        File("${context.filesDir}/${ONLINE_PLUGINS_FOLDER}").listFiles()?.forEach { repo ->
            repo.listFiles { file -> file.name == "oat" && file.isDirectory }?.forEach { file ->
                val success = file.deleteRecursively()
                Log.i(TAG, "Deleted oat directory: ${file.absolutePath} Success=$success")
            }
        }
    }


    fun getPluginsOnline(): Array<PluginData> {
        return getKey<Array<PluginData>>(PLUGINS_KEY) ?: emptyArray()
    }

    fun getPluginsLocal(): Array<PluginData> {
        return getKey<Array<PluginData>>(PLUGINS_KEY_LOCAL) ?: emptyArray()
    }

    /**
     * Separates the plugin url from the internal name in the ignore (skip) list.
     *
     * Different repositories can contain plugins with the same internal name. Scoping the entry
     * by plugin url ensures that blocking one of those plugins does not block the other one too.
     */
    private const val IGNORED_PLUGIN_KEY_SEPARATOR = "|"

    private fun getIgnoredPluginKey(internalName: String, pluginUrl: String?): String {
        return if (pluginUrl.isNullOrBlank() || !pluginUrl.startsWith("http")) {
            internalName
        } else {
            "$pluginUrl$IGNORED_PLUGIN_KEY_SEPARATOR$internalName"
        }
    }

    /** Raw keys of all plugins the user marked as ignored (the skip list). */
    fun getIgnoredPluginNames(): Set<String> {
        return getKey<Set<String>>(IGNORED_PLUGINS_KEY) ?: emptySet()
    }

    /**
     * Checks whether the plugin identified by [pluginUrl] and [internalName] is ignored.
     *
     * Entries without a plugin url prefix are legacy entries from before ignore was plugin-scoped;
     * they are still honoured so existing skip lists keep working.
     */
    fun isPluginIgnored(internalName: String, pluginUrl: String? = null): Boolean {
        val ignored = getIgnoredPluginNames()
        return ignored.contains(getIgnoredPluginKey(internalName, pluginUrl)) ||
                ignored.contains(internalName)
    }

    /**
     * Uninstalls every installed plugin that is on the ignore (skip) list.
     *
     * A blocked plugin must never stay installed, but restoring a backup can produce exactly
     * that: the skip list is part of the backup while plugin files are not, so a plugin that
     * was unblocked when the backup was made can end up blocked yet still installed. This
     * removes those copies so a plugin can never be shown as both blocked and installed.
     */
    suspend fun uninstallIgnoredPlugins() {
        val ignoredPlugins = (getPluginsLocal() + getPluginsOnline()).filter {
            isPluginIgnored(it.internalName, it.url)
        }

        ignoredPlugins.forEach { data ->
            val file = File(data.filePath)
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete ignored plugin file: ${data.filePath}")
            }
            unloadPlugin(file.absolutePath)
            deletePluginData(data)
        }
    }

    /**
     * Marks a plugin as ignored and uninstalls it if present. Being ignored means the plugin is
     * skipped by auto-download, auto-update and batch install/uninstall until it is un-ignored.
     *
     * @param internalName the plugin's stable id, added to the skip list even if not installed.
     * @param pluginUrl the plugin's download url, so another plugin with the same [internalName]
     * stays unaffected. Null or a file path for local plugins.
     * @param file the installed plugin file, deleted if it exists. Null if it is not installed.
     * @return true if the plugin is now ignored.
     */
    suspend fun ignorePluginAndUninstall(
        internalName: String,
        pluginUrl: String?,
        file: File?,
    ): Boolean {
        // Add to the skip list first, so even an uninstalled plugin is remembered as ignored.
        // Drop any legacy unscoped entry for this name so blocking is plugin-specific from now on.
        val previousIgnored = getIgnoredPluginNames()
        val key = getIgnoredPluginKey(internalName, pluginUrl)
        setKey(IGNORED_PLUGINS_KEY, (previousIgnored - internalName) + key)

        if (file == null) return true
        val list =
            (getPluginsLocal() + getPluginsOnline()).filter { it.filePath == file.absolutePath }
        if (!file.exists()) return true

        return try {
            if (file.delete()) {
                unloadPlugin(file.absolutePath)
                list.forEach { deletePluginData(it) }
                true
            } else {
                // Delete failed; restore the skip list so we do not leave it half-ignored.
                setKey(IGNORED_PLUGINS_KEY, previousIgnored)
                false
            }
        } catch (e: Exception) {
            logError(e)
            setKey(IGNORED_PLUGINS_KEY, previousIgnored)
            false
        }
    }

    /** Removes a plugin from the ignore (skip) list. Does not install it. */
    fun unignorePlugin(internalName: String, pluginUrl: String? = null) {
        val ignored = getIgnoredPluginNames()
        // Also remove a legacy unscoped entry so an old global block can be cleared.
        setKey(
            IGNORED_PLUGINS_KEY,
            ignored - getIgnoredPluginKey(internalName, pluginUrl) - internalName
        )
    }

    private val CLOUD_STREAM_FOLDER =
        Environment.getExternalStorageDirectory().absolutePath + "/Cloudstream3/"

    private val LOCAL_PLUGINS_PATH = CLOUD_STREAM_FOLDER + "plugins"

    private const val PLUGIN_LOAD_BATCH_SIZE = 3

    var currentlyLoading: String? = null

    // Maps filepath to plugin
    val plugins: MutableMap<String, BasePlugin> =
        LinkedHashMap<String, BasePlugin>()

    // Maps urls to plugin
    val urlPlugins: MutableMap<String, BasePlugin> =
        LinkedHashMap<String, BasePlugin>()

    private val classLoaders: MutableMap<PathClassLoader, BasePlugin> =
        HashMap<PathClassLoader, BasePlugin>()

    var loadedLocalPlugins = false
        private set

    var loadedOnlinePlugins = false
        private set

    private suspend fun maybeLoadPlugin(context: Context, file: File) {
        val name = file.name
        if (file.extension == "zip" || file.extension == "cs3") {
            loadPlugin(
                context,
                file,
                PluginData(name, null, false, file.absolutePath, PLUGIN_VERSION_NOT_SET)
            )
        } else {
            Log.i(TAG, "Skipping invalid plugin file: $file")
        }
    }


    // Helper class for updateAllOnlinePluginsAndLoadThem
    data class OnlinePluginData(
        val savedData: PluginData,
        val onlineData: PluginWrapper,
    ) {
        val isOutdated =
            onlineData.plugin.version > savedData.version || onlineData.plugin.version == PLUGIN_VERSION_ALWAYS_UPDATE
        val isDisabled = onlineData.plugin.status == PROVIDER_STATUS_DOWN

        fun validOnlineData(context: Context): Boolean {
            return getPluginPath(
                context,
                savedData.internalName,
                onlineData.repositoryData.url
            ).absolutePath == savedData.filePath
        }
    }

    // var allCurrentOutDatedPlugins: Set<OnlinePluginData> = emptySet()

    /**
     * Matches a plugin file against a provider name. Most internal names end with
     * "Provider", which is not part of the api name, and the two are not guaranteed to
     * share casing ("playfy" vs "PlayFy"), so compare case-insensitively. Only widens
     * matching: everything that matched before still matches.
     */
    private fun matchesApiName(data: PluginData, apiName: String): Boolean =
        data.internalName.replace("provider", "", ignoreCase = true)
            .equals(apiName, ignoreCase = true)

    suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean {
        return (getPluginsOnline().firstOrNull { matchesApiName(it, apiName) }
            ?: getPluginsLocal().firstOrNull { matchesApiName(it, apiName) })?.let { savedData ->
            // OnlinePluginData(savedData, onlineData)
            loadPlugin(
                context,
                File(savedData.filePath),
                savedData
            )
        } ?: false
    }

    /**
     * Loads the downloaded live-event providers, PlayFy before Cricify. Local
     * plugins are intentionally not touched here: their persisted paths can be
     * stale, and the normal local-plugin loader copies and loads them safely later.
     */
    suspend fun loadLiveEventPlugins(context: Context) {
        // The row is opt-in. When it is off these two plugins are still loaded by the normal
        // loader later; loading them first would only delay the home provider by two dex loads.
        if (!LiveEventNotifier.isEnabled(context)) return

        val livePlugins = getPluginsOnline()
            .filter {
                it.internalName.contains("playfy", ignoreCase = true) ||
                    it.internalName.contains("cricify", ignoreCase = true)
            }
            .sortedBy {
                if (it.internalName.contains("playfy", ignoreCase = true)) 0 else 1
            }

        for (plugin in livePlugins) {
            val file = File(plugin.filePath)
            if (!file.exists()) continue
            // This is an early priority load. If it fails, the normal loader will
            // retry after the update check, so don't show a misleading toast yet.
            loadPlugin(context, file, plugin, showErrorToast = false)
        }
    }

    /**
     * Needs to be run before other plugin loading because plugin loading can not be overwritten
     * 1. Gets all online data about the downloaded plugins
     * 2. If disabled do nothing
     * 3. If outdated download and load the plugin
     * 4. Else load the plugin normally
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_updateAllOnlinePluginsAndLoadThem(activity: Activity) {
        assertNonRecursiveCallstack()

        // Load all plugins as fast as possible!
        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(activity)
        afterPluginsLoadedEvent.invoke(false)

        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES

        val onlinePlugins = urls.toList().amap {
            getRepoPlugins(it) ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        // Iterates over all offline plugins, compares to remote repo and returns the plugins which are outdated
        val outdatedPlugins = getPluginsOnline().map { savedData ->
            onlinePlugins
                .filter { onlineData -> savedData.internalName == onlineData.plugin.internalName }
                .map { onlineData ->
                    OnlinePluginData(savedData, onlineData)
                }.filter {
                    it.validOnlineData(activity)
                }
        }.flatten().distinctBy { it.onlineData.plugin.url }

        debugPrint {
            "Outdated plugins: ${outdatedPlugins.filter { it.isOutdated }}"
        }

        val updatedPlugins = mutableListOf<String>()

        outdatedPlugins.amap { pluginData ->
            if (isPluginIgnored(
                    pluginData.savedData.internalName,
                    pluginData.savedData.url ?: pluginData.onlineData.plugin.url
                )
            ) {
                // Never update an ignored plugin; it stays exactly as installed.
                return@amap
            }
            if (pluginData.isDisabled) {
                //updatedPlugins.add(activity.getString(R.string.single_plugin_disabled, pluginData.onlineData.second.name))
                unloadPlugin(pluginData.savedData.filePath)
            } else if (pluginData.isOutdated) {
                downloadPlugin(
                    activity,
                    pluginData.onlineData.plugin.url,
                    pluginData.onlineData.plugin.fileHash,
                    pluginData.savedData.internalName,
                    File(pluginData.savedData.filePath),
                    true
                ).let { success ->
                    if (success)
                        updatedPlugins.add(pluginData.onlineData.plugin.name)
                }
            }
        }

        main {
            val uitext = txt(R.string.plugins_updated, updatedPlugins.size)
            createNotification(activity, uitext, updatedPlugins)
            /*val navBadge = (activity as MainActivity).binding?.navRailView?.getOrCreateBadge(R.id.navigation_settings)
            navBadge?.isVisible = true
            navBadge?.number = 5*/
        }

        // ioSafe {
        loadedOnlinePlugins = true
        afterPluginsLoadedEvent.invoke(false)
        // }

        Log.i(TAG, "Plugin update done!")
    }

    /**
     * Automatically download plugins not yet existing on local
     * 1. Gets all online data from online plugins repo
     * 2. Fetch all not downloaded plugins
     * 3. Download them and reload plugins
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_downloadNotExistingPluginsAndLoad(
        activity: Activity,
        mode: AutoDownloadMode
    ) {
        assertNonRecursiveCallstack()

        val newDownloadPlugins = mutableListOf<String>()
        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES
        val onlinePlugins = urls.toList().amap {
            getRepoPlugins(it)?.toList() ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        val providerLang = activity.getApiProviderLangSettings()
        //Log.i(TAG, "providerLang => ${providerLang.toJson()}")

        // Iterate online repos and returns not downloaded plugins
        val notDownloadedPlugins = onlinePlugins.mapNotNull { onlineData ->
            val sitePlugin = onlineData.plugin
            val tvtypes = sitePlugin.tvTypes ?: listOf()

            //Don't include empty urls
            if (sitePlugin.url.isBlank()) {
                return@mapNotNull null
            }
            if (sitePlugin.repositoryUrl.isNullOrBlank()) {
                return@mapNotNull null
            }

            //Omit already existing plugins
            if (getPluginPath(activity, sitePlugin.internalName, onlineData.repositoryData.url).exists()) {
                Log.i(TAG, "Skip > ${sitePlugin.internalName}")
                return@mapNotNull null
            }

            //Omit non-NSFW if mode is set to NSFW only
            if (mode == AutoDownloadMode.NsfwOnly) {
                if (!tvtypes.contains(TvType.NSFW.name)) {
                    return@mapNotNull null
                }
            }
            //Omit NSFW, if disabled
            if (!settingsForProvider.enableAdult) {
                if (tvtypes.contains(TvType.NSFW.name)) {
                    return@mapNotNull null
                }
            }

            //Omit lang not selected on language setting
            if (mode == AutoDownloadMode.FilterByLang) {
                val lang = sitePlugin.language ?: return@mapNotNull null
                //If set to 'universal', don't skip any language
                if (!providerLang.contains(AllLanguagesName) && !providerLang.contains(lang)) {
                    return@mapNotNull null
                }
                //Log.i(TAG, "sitePlugin lang => $lang")
            }

            val savedData = PluginData(
                url = sitePlugin.url,
                internalName = sitePlugin.internalName,
                isOnline = true,
                filePath = "",
                version = sitePlugin.version
            )
            OnlinePluginData(savedData, onlineData)
        }
        //Log.i(TAG, "notDownloadedPlugins => ${notDownloadedPlugins.toJson()}")

        notDownloadedPlugins.amap { pluginData ->
            if (isPluginIgnored(
                    pluginData.savedData.internalName,
                    pluginData.onlineData.plugin.url
                )
            ) {
                return@amap
            }
            downloadPlugin(
                activity,
                pluginData.onlineData.plugin.url,
                pluginData.onlineData.plugin.fileHash,
                pluginData.savedData.internalName,
                pluginData.onlineData.repositoryData.url,
                !pluginData.isDisabled
            ).let { success ->
                if (success)
                    newDownloadPlugins.add(pluginData.onlineData.plugin.name)
            }
        }

        main {
            val uitext = txt(R.string.plugins_downloaded, newDownloadPlugins.size)
            createNotification(activity, uitext, newDownloadPlugins)
        }

        // ioSafe {
        afterPluginsLoadedEvent.invoke(false)
        // }

        Log.i(TAG, "Plugin download done!")
    }

    @Throws
    private fun assertNonRecursiveCallstack() {
        if (Thread.currentThread().stackTrace.any { it.methodName == "loadPlugin" }) {
            throw Error("You tried to call a function that will recursively call loadPlugin, this will cause crashes or memory leaks. Do not do this, there is better ways to implement the feature than reloading plugins. Are you sure you read the compile error or docs?")
        }
    }

    /**
     * Use updateAllOnlinePluginsAndLoadThem
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context: Context) {
        assertNonRecursiveCallstack()

        // Load only a few plugins at a time. Loading every .cs3 at once can make
        // class loading fail on slower devices and during startup.
        (getPluginsOnline()).toList().chunked(PLUGIN_LOAD_BATCH_SIZE).forEach { batch ->
            batch.amap { pluginData ->
                loadPlugin(
                    context,
                    File(pluginData.filePath),
                    pluginData
                )
            }
        }
    }

    /**
     * Reloads all local plugins and forces a page update, used for hot reloading with deployWithAdb
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_hotReloadAllLocalPlugins(activity: FragmentActivity?) {
        assertNonRecursiveCallstack()

        Log.d(TAG, "Reloading all local plugins!")
        if (activity == null) return
        getPluginsLocal().forEach {
            unloadPlugin(it.filePath)
        }
        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(activity, true)
    }

    /**
     * @param forceReload see afterPluginsLoadedEvent, basically a way to load all local plugins
     * and reload all pages even if they are previously valid
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(context: Context, forceReload: Boolean) {
        assertNonRecursiveCallstack()

        val dir = File(LOCAL_PLUGINS_PATH)

        if (!dir.exists()) {
            val res = dir.mkdirs()
            if (!res) {
                Log.w(TAG, "Failed to create local directories")
                // We have tried to load local plugins, but exit early.
                // This needs to be true to prevent the downloader waiting for plugins.
                loadedLocalPlugins = true
                return
            }
        }

        val sortedPlugins = dir.listFiles()
        // Always sort plugins alphabetically for reproducible results

        Log.d(TAG, "Files in '${LOCAL_PLUGINS_PATH}' folder: ${sortedPlugins?.size}")

        // Use app-specific external files directory and copy the file there.
        // We have to do this because on Android 14+, it otherwise gives SecurityException
        // due to dex files and setReadOnly seems to have no effect unless it it here.
        val pluginDirectory = File(context.getExternalFilesDir(null), "plugins")
        if (!pluginDirectory.exists()) {
            pluginDirectory.mkdirs() // Ensure the plugins directory exists
        }

        // Make sure all local plugins are fully refreshed.
        removeKey(PLUGINS_KEY_LOCAL)

        sortedPlugins?.sortedBy { it.name }
            ?.chunked(PLUGIN_LOAD_BATCH_SIZE)
            ?.forEach { batch ->
                batch.amap { file ->
                    try {
                        val destinationFile = File(pluginDirectory, file.name)

                        // Only copy the file if the destination file doesn't exist or if it
                        // has been modified (check file length and modification time).
                        if (!destinationFile.exists() ||
                            destinationFile.length() != file.length() ||
                            destinationFile.lastModified() != file.lastModified()
                        ) {

                            // Copy the file to the app-specific plugin directory
                            file.copyTo(destinationFile, overwrite = true)

                            // After copying, set the destination file's modification time
                            // to match the source file. We do this for performance so that we
                            // can check the modification time and not make redundant writes.
                            destinationFile.setLastModified(file.lastModified())
                        }

                        // Load the plugin after it has been copied
                        maybeLoadPlugin(context, destinationFile)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to copy the file")
                        logError(t)
                    }
                }
            }

        loadedLocalPlugins = true
        afterPluginsLoadedEvent.invoke(forceReload)
    }

    /** @return true if safe mode is enabled in any possible way. */
    fun isSafeMode(): Boolean {
        return checkSafeModeFile() || lastError != null
    }

    /**
     * This can be used to override any extension loading to fix crashes!
     * @return true if safe mode file is present
     **/
    fun checkSafeModeFile(): Boolean {
        return safe {
            val folder = File(CLOUD_STREAM_FOLDER)
            if (!folder.exists()) return@safe false
            val files = folder.listFiles { _, name ->
                name.equals("safe", ignoreCase = true)
            }
            files?.any()
        } ?: false
    }

    /**
     * @return True if successful, false if not
     * */
    internal suspend fun loadPlugin(
        context: Context,
        file: File,
        data: PluginData,
        showErrorToast: Boolean = true,
    ): Boolean {
        // Blocked plugins must never become active, even if a file for them still exists.
        if (isPluginIgnored(data.internalName, data.url)) {
            Log.i(TAG, "Skipping load of ignored plugin: ${data.internalName}")
            return false
        }

        // Skip everything below for an already loaded file: the class loader, manifest
        // parse and plugin construction are wasted work, and the priority (live event)
        // load and the normal loader can both reach the same file.
        val filePath = file.absolutePath
        if (plugins.containsKey(filePath)) {
            Log.i(TAG, "Plugin ${data.internalName} is already loaded")
            return true
        }

        val fileName = file.nameWithoutExtension
        currentlyLoading = fileName
        Log.i(TAG, "Loading plugin: $data")

        return try {
            // In case of Android 14+ then
            try {
                // Set the file as read-only and log if it fails
                if (!file.setReadOnly()) {
                    Log.e(TAG, "Failed to set read-only on plugin file: ${file.name}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to set dex as read-only")
                logError(t)
            }

            val loader = PathClassLoader(filePath, context.classLoader)
            var manifest: BasePlugin.Manifest
            loader.getResourceAsStream("manifest.json").use { stream ->
                if (stream == null) {
                    Log.e(TAG, "Failed to load plugin  $fileName: No manifest found")
                    return false
                }
                InputStreamReader(stream).use { reader ->
                    manifest = parseJson<BasePlugin.Manifest>(reader.readText())
                }
            }

            val name: String = manifest.name ?: "NO NAME".also {
                Log.d(TAG, "No manifest name for ${data.internalName}")
            }
            val version: Int = manifest.version ?: PLUGIN_VERSION_NOT_SET.also {
                Log.d(TAG, "No manifest version for ${data.internalName}")
            }

            @Suppress("UNCHECKED_CAST")
            val pluginClass: Class<*> =
                loader.loadClass(manifest.pluginClassName) as Class<out BasePlugin?>
            val pluginInstance: BasePlugin =
                pluginClass.getDeclaredConstructor().newInstance() as BasePlugin

            // Sets with the proper version
            setPluginData(data.copy(version = version))

            if (plugins.containsKey(filePath)) {
                Log.i(TAG, "Plugin with name $name already exists")
                return true
            }

            pluginInstance.filename = file.absolutePath
            if (manifest.requiresResources) {
                Log.d(TAG, "Loading resources for ${data.internalName}")
                // based on https://stackoverflow.com/questions/7483568/dynamic-resource-loading-from-other-apk
                val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                val addAssetPath =
                    AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                addAssetPath.invoke(assets, file.absolutePath)

                @Suppress("DEPRECATION")
                (pluginInstance as? Plugin)?.resources = Resources(
                    assets,
                    context.resources.displayMetrics,
                    context.resources.configuration
                )
            }
            synchronized(plugins) {
                plugins[filePath] = pluginInstance
            }
            synchronized(classLoaders) {
                classLoaders[loader] = pluginInstance
            }
            synchronized(urlPlugins) {
                urlPlugins[data.url ?: filePath] = pluginInstance
            }
            if (pluginInstance is Plugin) {
                pluginInstance.load(context)
            } else {
                pluginInstance.load()
            }
            Log.i(TAG, "Loaded plugin ${data.internalName} successfully")
            currentlyLoading = null
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $file: ${Log.getStackTraceString(e)}")
            if (showErrorToast) {
                showToast(
                    // context.getActivity(), // we are not always on the main thread
                    context.getString(R.string.plugin_load_fail).format(fileName),
                    Toast.LENGTH_LONG
                )
            }
            currentlyLoading = null
            false
        }
    }

    fun unloadPlugin(absolutePath: String) {
        Log.i(TAG, "Unloading plugin: $absolutePath")
        val plugin = plugins[absolutePath]
        if (plugin == null) {
            Log.w(TAG, "Couldn't find plugin $absolutePath")
            return
        }

        try {
            plugin.beforeUnload()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to run beforeUnload $absolutePath: ${Log.getStackTraceString(e)}")
        }

        // remove all registered apis
        APIHolder.apis.filter { api -> api.sourcePlugin == plugin.filename }.forEach {
            removePluginMapping(it)
        }

        APIHolder.allProviders.withLock {
            APIHolder.allProviders.removeAll { provider -> provider.sourcePlugin == plugin.filename }
        }

        extractorApis.withLock {
            extractorApis.removeAll { provider -> provider.sourcePlugin == plugin.filename }
        }

        VideoClickActionHolder.allVideoClickActions.withLock {
            VideoClickActionHolder.allVideoClickActions.removeAll { action -> action.sourcePlugin == plugin.filename }
        }

        synchronized(classLoaders) {
            classLoaders.values.removeIf { v -> v == plugin }
        }

        synchronized(plugins) {
            plugins.remove(absolutePath)
        }

        synchronized(urlPlugins) {
            urlPlugins.values.removeIf { v -> v == plugin }
        }
    }

    /**
     * Spits out a unique and safe filename based on name.
     * Used for repo folders (using repo url) and plugin file names (using internalName)
     * */
    fun getPluginSanitizedFileName(name: String): String {
        return sanitizeFilename(
            name,
            true
        ) + "." + name.hashCode()
    }

    /**
     * Stable plugin identity used to collapse the same plugin published by several
     * repositories. It ignores case, spaces and punctuation, but keeps the full
     * name. Names like "MovieBox" and "MovieBoxProvider" are different plugins,
     * and a variant such as "MovieBoxProviderIN" stays separate too.
     */
    fun getPluginIdentityKey(internalName: String): String {
        val normalized = internalName
            .lowercase()
            .filter { it.isLetterOrDigit() }
        return normalized.ifBlank { internalName.lowercase() }
    }

    /**
     * Unloads duplicate copies of the same installed plugin, keeping the best copy
     * (highest version, then largest file). The files are not deleted; this only
     * prevents duplicate providers, extractors, settings and search entries from
     * being active at the same time.
     */
    fun unloadDuplicatePlugins() {
        val dataByPath = (getPluginsOnline() + getPluginsLocal())
            .associateBy { it.filePath }
        val loadedPaths = synchronized(plugins) { plugins.keys.toList() }

        loadedPaths.groupBy { path ->
            dataByPath[path]?.internalName?.let { getPluginIdentityKey(it) } ?: path
        }.forEach { (_, paths) ->
            if (paths.size < 2) return@forEach

            val bestPath = paths.maxWithOrNull(
                compareBy<String> { path ->
                    dataByPath[path]?.version ?: Int.MIN_VALUE
                }.thenBy { path ->
                    File(path).length()
                }
            ) ?: return@forEach

            paths.filter { it != bestPath }.forEach { unloadPlugin(it) }
        }
    }

    /**
     * This should not be changed as it is used to also detect if a plugin is installed!
     **/
    fun getPluginPath(
        context: Context,
        internalName: String,
        repositoryUrl: String
    ): File {
        val folderName = getPluginSanitizedFileName(repositoryUrl) // Guaranteed unique
        val fileName = getPluginSanitizedFileName(internalName)
        return File("${context.filesDir}/${ONLINE_PLUGINS_FOLDER}/${folderName}/$fileName.cs3")
    }

    suspend fun downloadPlugin(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        repositoryUrl: String,
        loadPlugin: Boolean
    ): Boolean {
        val file = getPluginPath(activity, internalName, repositoryUrl)
        return downloadPlugin(activity, pluginUrl, pluginHash, internalName, file, loadPlugin)
    }

    suspend fun downloadPlugin(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        file: File,
        loadPlugin: Boolean,
    ): Boolean {
        // Central ignore guard: every download path (manual, bulk, auto-download, auto-update)
        // funnels through here, so an ignored plugin can never be (re)installed by any of them.
        if (isPluginIgnored(internalName, pluginUrl)) {
            Log.i(TAG, "Skipping download of ignored plugin: $internalName")
            return false
        }

        try {
            Log.d(TAG, "Downloading plugin: $pluginUrl to ${file.absolutePath}")
            // The plugin file needs to be salted with the repository url hash as to allow multiple repositories with the same internal plugin names
            val newFile = downloadPluginToFile(activity, pluginUrl, file, pluginHash) ?: return false

            val data = PluginData(
                internalName,
                pluginUrl,
                true,
                newFile.absolutePath,
                PLUGIN_VERSION_NOT_SET
            )

            return if (loadPlugin) {
                unloadPlugin(file.absolutePath)
                loadPlugin(
                    activity,
                    newFile,
                    data
                )
            } else {
                setPluginData(data)
                true
            }
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }

    suspend fun deletePlugin(file: File): Boolean {
        val list =
            (getPluginsLocal() + getPluginsOnline()).filter { it.filePath == file.absolutePath }

        // Central ignore guard: an ignored plugin is already uninstalled and skipped, so it must
        // never be touched by batch uninstall either.
        val isIgnored = list.any { pluginData ->
            isPluginIgnored(pluginData.internalName, pluginData.url)
        }
        if (isIgnored) {
            Log.i(TAG, "Skipping delete of ignored plugin: ${file.absolutePath}")
            return false
        }

        return try {
            if (File(file.absolutePath).delete()) {
                unloadPlugin(file.absolutePath)
                list.forEach { deletePluginData(it) }
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_manuallyReloadAndUpdatePlugins(activity: Activity) {
        assertNonRecursiveCallstack()

        showToast(activity.getString(R.string.starting_plugin_update_manually), Toast.LENGTH_LONG)

        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(activity)
        afterPluginsLoadedEvent.invoke(false)

        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES
        val onlinePlugins = urls.toList().amap {
            getRepoPlugins(it) ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        val allPlugins = getPluginsOnline().flatMap { savedData ->
            onlinePlugins
                .filter { it.plugin.internalName == savedData.internalName }
                .mapNotNull { onlineData ->
                    OnlinePluginData(savedData, onlineData).takeIf { it.validOnlineData(activity) }
                }
        }.distinctBy { it.onlineData.plugin.url }

        val updatedPlugins = mutableListOf<String>()

        allPlugins.amap { pluginData ->
            // Blocked/ignored plugins must never be (re)installed: uninstall them and skip.
            if (isPluginIgnored(
                    pluginData.savedData.internalName,
                    pluginData.savedData.url ?: pluginData.onlineData.plugin.url
                )
            ) {
                val existingFile = File(pluginData.savedData.filePath)
                if (existingFile.exists()) {
                    existingFile.delete()
                    unloadPlugin(pluginData.savedData.filePath)
                }
                deletePluginData(pluginData.savedData)
                return@amap
            }
            if (pluginData.isDisabled) {
                Log.e(
                    "PluginManager",
                    "Unloading disabled plugin: ${pluginData.onlineData.plugin.name}"
                )
                unloadPlugin(pluginData.savedData.filePath)
            } else {
                val existingFile = File(pluginData.savedData.filePath)
                if (existingFile.exists()) existingFile.delete()

                if (downloadPlugin(
                        activity,
                        pluginData.onlineData.plugin.url,
                        pluginData.onlineData.plugin.fileHash,
                        pluginData.savedData.internalName,
                        existingFile,
                        true
                    )
                ) {
                    updatedPlugins.add(pluginData.onlineData.plugin.name)
                }
            }
        }.also {
            main {
                val message = if (updatedPlugins.isNotEmpty()) {
                    activity.getString(R.string.plugins_updated_manually, updatedPlugins.size)
                } else {
                    activity.getString(R.string.no_plugins_updated_manually)
                }
                showToast(message, Toast.LENGTH_LONG)

                val notificationText = UiText.StringResource(
                    R.string.plugins_updated_manually,
                    listOf(updatedPlugins.size)
                )
                createNotification(activity, notificationText, updatedPlugins)

            }
        }

        loadedOnlinePlugins = true
        afterPluginsLoadedEvent.invoke(false)

        Log.i("PluginManager", "Plugin update done!")
    }

    private fun Context.createNotificationChannel() {
        hasCreatedNotChanel = true
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = EXTENSIONS_CHANNEL_NAME //getString(R.string.channel_name)
            val descriptionText =
                EXTENSIONS_CHANNEL_DESCRIPT//getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(EXTENSIONS_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        context: Context,
        uitext: UiText,
        extensions: List<String>
    ): Notification? {
        try {

            if (extensions.isEmpty()) return null

            val content = extensions.joinToString(", ")
//        main { // DON'T WANT TO SLOW IT DOWN
            val builder = NotificationCompat.Builder(context, EXTENSIONS_CHANNEL_ID)
                .setAutoCancel(false)
                .setColorized(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setColor(context.colorFromAttribute(R.attr.colorPrimary))
                .setContentTitle(uitext.asString(context))
                //.setContentTitle(context.getString(title, extensionNames.size))
                .setSmallIcon(R.drawable.ic_baseline_extension_24)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(content)
                )
                .setContentText(content)

            if (!hasCreatedNotChanel) {
                context.createNotificationChannel()
            }

            val notification = builder.build()
            // notificationId is a unique int for each notification that you must define
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context)
                    .notify((System.currentTimeMillis() / 1000).toInt(), notification)
            }
            return notification
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }
}
