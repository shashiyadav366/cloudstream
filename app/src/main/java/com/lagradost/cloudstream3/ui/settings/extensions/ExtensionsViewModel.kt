package com.lagradost.cloudstream3.ui.settings.extensions

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginManager.getPluginsOnline
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryData(
    @JsonProperty("iconUrl") @SerialName("iconUrl") val iconUrl: String?,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("url") @SerialName("url") val url: String,
    @JsonProperty("description") @SerialName("description") val description: String? = null,
) {
    constructor(name: String, url: String) : this(
        iconUrl = null,
        name = name,
        url = url,
        description = null
    )

    constructor(iconUrl: String?, name: String, url: String) : this(
        iconUrl = iconUrl,
        name = name,
        url = url,
        description = null
    )
}

const val REPOSITORIES_KEY = "REPOSITORIES_KEY"

class ExtensionsViewModel : ViewModel() {
    data class PluginStats(
        val total: Int,

        val downloaded: Int,
        val disabled: Int,
        val notDownloaded: Int,

        val downloadedText: UiText,
        val disabledText: UiText,
        val notDownloadedText: UiText,
    )

    private val _repositories = MutableLiveData<Array<RepositoryData>>()
    val repositories: LiveData<Array<RepositoryData>> = _repositories

    private val _pluginStats: MutableLiveData<PluginStats?> = MutableLiveData(null)
    val pluginStats: LiveData<PluginStats?> = _pluginStats

    //TODO CACHE GET REQUESTS
    // DO not use viewModelScope.launchSafe, it will ANR on slow internet
    fun loadStats() = ioSafe {
        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES

        val allOnlinePlugins = urls.toList().amap {
            RepositoryManager.getRepoPlugins(it)?.toList() ?: emptyList()
        }.flatten()

        // Treat the same plugin published by several repositories as one plugin.
        val onlinePlugins = allOnlinePlugins
            .groupBy {
                PluginManager.getPluginIdentityKey(it.plugin.internalName)
            }
            .map { (_, copies) ->
                copies.maxWithOrNull(
                    compareBy<PluginWrapper> { it.plugin.version }
                        .thenBy { it.plugin.fileSize ?: 0L }
                ) ?: copies.first()
            }

        val installedPluginIdentities = getPluginsOnline()
            .map { PluginManager.getPluginIdentityKey(it.internalName) }
            .toSet()
        val installedPlugins = onlinePlugins.filter {
            PluginManager.getPluginIdentityKey(it.plugin.internalName) in installedPluginIdentities
        }

        val total = onlinePlugins.count()
        val disabled = installedPlugins.count { it.plugin.status == PROVIDER_STATUS_DOWN }
        val downloadedTotal = installedPlugins.count()
        val downloaded = downloadedTotal - disabled
        val notDownloaded = total - downloadedTotal
        val stats = PluginStats(
            total,
            downloaded,
            disabled,
            notDownloaded,
            txt(R.string.plugins_downloaded, downloaded),
            txt(R.string.plugins_disabled, disabled),
            txt(R.string.plugins_not_downloaded, notDownloaded)
        )
        debugAssert({ stats.downloaded + stats.notDownloaded + stats.disabled != stats.total }) {
            "downloaded(${stats.downloaded}) + notDownloaded(${stats.notDownloaded}) + disabled(${stats.disabled}) != total(${stats.total})"
        }
        _pluginStats.postValue(stats)
    }

    private fun repos() = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
        ?: emptyArray()) + PREBUILT_REPOSITORIES

    fun loadRepositories() {
        val urls = repos()
        _repositories.postValue(urls)
    }
}
