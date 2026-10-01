package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.PackageInstallerService
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/**
 * Builds the headers used to talk to the GitHub API. If the user has set a
 * GitHub token (Settings -> Updates) it is sent as a Bearer token so that
 * private repositories work too.
 */
internal fun githubApiHeaders(context: Context): Map<String, String> =
    githubHeaders(context, "application/vnd.github.v3+json")

/**
 * Headers used when downloading release assets. The GitHub asset endpoint
 * requires "application/octet-stream" to return the file instead of JSON.
 */
internal fun githubDownloadHeaders(context: Context): Map<String, String> =
    githubHeaders(context, "application/octet-stream")

private fun githubHeaders(context: Context, accept: String): Map<String, String> {
    val token = PreferenceManager.getDefaultSharedPreferences(context)
        .getString(context.getString(R.string.github_token_key), null)
        ?.takeIf { it.isNotBlank() }
    val headers = mutableMapOf("Accept" to accept)
    if (token != null) headers["Authorization"] = "Bearer $token"
    return headers
}

object InAppUpdater {
    private const val GITHUB_USER_NAME = "shashiyadav366"
    private const val GITHUB_REPO = "cloudstream"

    private const val PRERELEASE_PACKAGE_NAME = "com.lagradost.cloudstream3.prerelease"
    private const val LOG_TAG = "InAppUpdater"

    private fun assetDownloadUrl(asset: GithubAsset) =
        "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases/assets/${asset.id}"

    @Serializable
    private data class GithubAsset(
        @JsonProperty("id") @SerialName("id") val id: Long,
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("size") @SerialName("size") val size: Int, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String, // application/vnd.android.package-archive
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String, // Version code
        @JsonProperty("body") @SerialName("body") val body: String, // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
    )

    @Serializable
    private data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String, // SHA-256 hash
        @JsonProperty("type") @SerialName("type") val type: String,
        @JsonProperty("url") @SerialName("url") val url: String,
    )

    @Serializable
    private data class GithubTag(
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject,
    )

    @Serializable
    private data class Update(
        @JsonProperty("shouldUpdate") @SerialName("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") @SerialName("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") @SerialName("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") @SerialName("changelog") val changelog: String?,
        @JsonProperty("updateNodeId") @SerialName("updateNodeId") val updateNodeId: String?,
    )

    private suspend fun Activity.getAppUpdate(installPrerelease: Boolean): Update {
        return try {
            when {
                // No updates on debug version
                BuildConfig.DEBUG -> Update(false, null, null, null, null)
                BuildConfig.FLAVOR == "prerelease" || installPrerelease -> getPreReleaseUpdate()
                else -> getReleaseUpdate()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, Log.getStackTraceString(e))
            Update(false, null, null, null, null)
        }
    }

    private fun String?.toVersionNumber(): Int? {
        return this?.let {
            Regex("""(\d+)\.(\d+)\.(\d+)""").find(it)?.groupValues?.let { g ->
                g[1].toInt() * 100_000_000 + g[2].toInt() * 10_000 + g[3].toInt()
            }
        }
    }

    private suspend fun Activity.getReleaseUpdate(): Update {
        val url = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = githubApiHeaders(this)
        val response = parseJson<Array<GithubRelease>>(
            app.get(url, headers = headers).text
        ).toList()

        // Detect the newest non-prerelease release whose APK asset or tag name
        // contains a version like X.Y.Z (e.g. "4.9.0.apk" or tag "v4.9.0")
        val found = response.filter { rel ->
            !rel.prerelease
        }.mapNotNull { rel ->
            val apkAssets = rel.assets.filter {
                it.contentType == "application/vnd.android.package-archive"
            }
            val versionAsset = apkAssets.firstOrNull { it.name.toVersionNumber() != null }
            val versionNumber =
                (versionAsset?.name ?: rel.tagName).toVersionNumber() ?: return@mapNotNull null
            Triple(versionNumber, rel, versionAsset ?: apkAssets.firstOrNull())
        }.maxByOrNull { it.first }

        val rel = found?.second ?: return Update(false, null, null, null, null)
        val apkAsset = found.third ?: return Update(false, null, null, null, null)
        val foundVersion = (apkAsset.name ?: rel.tagName)

        val currentVersion = packageName?.let {
            packageManager.getPackageInfo(it, 0)
        }

        val shouldUpdate = if (apkAsset.browserDownloadUrl.isBlank()) {
            false
        } else {
            val currentVersionNumber =
                currentVersion?.versionName?.toVersionNumber() ?: return Update(false, null, null, null, null)
            val foundVersionNumber =
                foundVersion.toVersionNumber() ?: return Update(false, null, null, null, null)
            currentVersionNumber < foundVersionNumber
        }

        return Update(
            shouldUpdate,
            assetDownloadUrl(apkAsset),
            foundVersion,
            rel.body,
            rel.nodeId
        )
    }

    private suspend fun Activity.getPreReleaseUpdate(): Update {
        val tagUrl =
            "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/git/ref/tags/pre-release"
        val releaseUrl = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = githubApiHeaders(this)
        val response = parseJson<Array<GithubRelease>>(
            app.get(releaseUrl, headers = headers).text
        ).toList()

        val found = response.firstOrNull { rel ->
            rel.prerelease || rel.tagName == "pre-release"
        } ?: return Update(false, null, null, null, null)

        // Prefer the dedicated prerelease build so the prerelease flavor
        // always downloads its own APK instead of the stable one
        val foundAsset = found.assets.filter { it ->
            it.contentType == "application/vnd.android.package-archive"
        }.firstOrNull { it.name == "app-prerelease-release.apk" }
            ?: found.assets.firstOrNull {
                it.contentType == "application/vnd.android.package-archive"
            }

        if (foundAsset == null) {
            return Update(false, null, null, null, null)
        }

        val tagResponse = parseJson<GithubTag>(app.get(tagUrl, headers = headers).text)
        val updateCommitHash = tagResponse.githubObject.sha.trim().take(7)
        Log.d(LOG_TAG, "Fetched GitHub tag: $updateCommitHash")

        return Update(
            currentCommitHash() != updateCommitHash,
            assetDownloadUrl(foundAsset),
            updateCommitHash,
            found.body,
            found.nodeId
        )
    }

    private val updateLock = Mutex()

    private suspend fun Activity.downloadUpdate(url: String): Boolean {
        try {
            Log.d(LOG_TAG, "Downloading update: $url")
            val appUpdateName = "CloudStream"
            val appUpdateSuffix = "apk"

            // Delete all old updates
            this.cacheDir.listFiles()?.filter {
                it.name.startsWith(appUpdateName) && it.extension == appUpdateSuffix
            }?.forEach { deleteFileOnExit(it) }

            val downloadedFile = File.createTempFile(appUpdateName, ".$appUpdateSuffix")
            val sink: BufferedSink = downloadedFile.sink().buffer()

            updateLock.withLock {
                val response = app.get(url, headers = githubDownloadHeaders(this))
                val badContentType = response.headers["Content-Type"]?.let {
                    it.contains("html") || it.contains("json") || it.contains("text")
                } == true
                if (!response.isSuccessful || badContentType) {
                    Log.d(LOG_TAG, "Update download failed (${response.code}) from $url")
                    sink.close()
                    return false
                }
                sink.writeAll(response.body.source())
                sink.close()
                openApk(this, Uri.fromFile(downloadedFile))
            }

            return true
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }

    private fun openApk(context: Context, uri: Uri) = safe {
        val path = uri.path ?: return@safe
        val contentUri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".provider", File(path)
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            data = contentUri
        }
        context.startActivity(installIntent)
    }

    fun Activity.installPreReleaseIfNeeded() = ioSafe {
        val isInstalled = try {
            packageManager.getPackageInfo(PRERELEASE_PACKAGE_NAME, 0)
            true
        } catch (_: NameNotFoundException) {
            false
        }

        if (isInstalled) {
            showToast(R.string.prerelease_already_installed)
        } else if (!runAutoUpdate(checkAutoUpdate = false, installPrerelease = true)) {
            showToast(R.string.prerelease_install_failed)
        }
    }


    /**
     * @param checkAutoUpdate if the update check was launched automatically
     * @param installPrerelease if we want to install the pre-release version
     */
    suspend fun Activity.runAutoUpdate(
        checkAutoUpdate: Boolean = true, installPrerelease: Boolean = false
    ): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val autoUpdateEnabled =
            settingsManager.getBoolean(getString(R.string.auto_update_key), true)
        if (checkAutoUpdate && !autoUpdateEnabled) {
            return false
        }

        val update = getAppUpdate(installPrerelease)
        if (!update.shouldUpdate || update.updateURL == null) {
            return false
        }

        // Check if update should be skipped
        val updateNodeId = settingsManager.getString(
            getString(R.string.skip_update_key), ""
        )

        // Skips the update if its an automatic update and the update is skipped
        // This allows updating manually
        if (update.updateNodeId.equals(updateNodeId) && checkAutoUpdate) {
            return false
        }

        runOnUiThread {
            safe {
                val currentVersion = packageName?.let {
                    packageManager.getPackageInfo(it, 0)
                }

                val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                builder.setTitle(
                    getString(R.string.new_update_format).format(
                        currentVersion?.versionName, update.updateVersion
                    )
                )

                val logRegex = Regex("\\[(.*?)]\\((.*?)\\)")
                val sanitizedChangelog = update.changelog?.replace(logRegex) { matchResult ->
                    matchResult.groupValues[1]
                } // Sanitized because it looks cluttered

                builder.setMessage(sanitizedChangelog)
                builder.apply {
                    setPositiveButton(R.string.update) { _, _ ->
                        // Forcefully start any delayed installations
                        if (ApkInstaller.delayedInstaller?.startInstallation() == true) return@setPositiveButton

                        showToast(R.string.download_started, Toast.LENGTH_LONG)

                        // Check if the setting hasn't been changed
                        if (settingsManager.getInt(
                                getString(R.string.apk_installer_key), -1
                            ) == -1
                        ) {
                            // Set to legacy installer if using MIUI
                            if (isMiUi()) {
                                settingsManager.edit {
                                    putInt(getString(R.string.apk_installer_key), 1)
                                }
                            }
                        }

                        val currentInstaller = settingsManager.getInt(
                            getString(R.string.apk_installer_key), 1
                        )

                        when (currentInstaller) {
                            // New method
                            0 -> {
                                val intent = PackageInstallerService.Companion.getIntent(
                                    this@runAutoUpdate, update.updateURL
                                )
                                ContextCompat.startForegroundService(
                                    this@runAutoUpdate, intent
                                )
                            }
                            // Legacy
                            1 -> {
                                ioSafe {
                                    if (!downloadUpdate(update.updateURL)) {
                                        runOnUiThread {
                                            showToast(
                                                R.string.download_failed, Toast.LENGTH_LONG
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    setNegativeButton(R.string.cancel) { _, _ -> }

                    if (checkAutoUpdate) {
                        setNeutralButton(R.string.skip_update) { _, _ ->
                            settingsManager.edit {
                                putString(
                                    getString(R.string.skip_update_key), update.updateNodeId ?: ""
                                )
                            }
                        }
                    }
                }
                builder.show().setDefaultFocus()
            }
        }
        return true
    }

    private fun isMiUi(): Boolean = !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    private fun getSystemProperty(propName: String): String? = try {
        val p = Runtime.getRuntime().exec("getprop $propName")
        BufferedReader(InputStreamReader(p.inputStream), 1024).use {
            it.readLine()
        }
    } catch (_: IOException) {
        null
    }
}
