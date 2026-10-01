package com.lagradost.cloudstream3.ui.settings

import androidx.compose.runtime.Immutable
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import kotlin.jvm.Throws

object GithubReleases {
    @Serializable
    private data class GithubAsset(
        @JsonProperty("id") @SerialName("id") val id: Long,
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("size") @SerialName("size") val size: Int, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String, // application/vnd.android.package-archive
        @JsonProperty("digest") @SerialName("digest") val digest: String? = null, // sha256:..., may be null
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String, // Version code
        @JsonProperty("body") @SerialName("body") val body: String, // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
        @JsonProperty("created_at") @SerialName("created_at") val createdAt: String, // YYYY-MM-DDTHH:MM:SSZ
    )

    @Serializable
    private data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String, // SHA-256 hash
        //@JsonProperty("type") @SerialName("type") val type: String,
        ///@JsonProperty("url") @SerialName("url") val url: String,
    )

    @Serializable
    private data class GithubTag(
        //@JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject,
    )

    /** GitHub file update package */
    @Immutable
    data class GithubFile(
        /** File digest, sha:xxx */
        val digest: String?,
        /** File url for download */
        val downloadUrl: String,
        /** Filename without the extension */
        val displayName: String,
        /** Changelog, aka the commit message */
        val changeLog: String,
        /** Name of the tag, aka unique release name like vX.X.X or pre-release */
        val tagName: String,
        /** Unique node id */
        val nodeId: String,
    )

    /**
     * Builds the GitHub API headers, adding the token as a Bearer token when set,
     * which is required to access private repositories.
     */
    private fun githubHeaders(token: String?, accept: String = "application/vnd.github.v3+json"): Map<String, String> {
        val headers = mutableMapOf("Accept" to accept)
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    /** Throw a readable error instead of trying to parse a GitHub error body as JSON */
    private fun failedRequest(code: Int): Nothing =
        throw IOException("GitHub API request failed ($code). If the repository is private, set a GitHub token in the update settings.")

    @Throws
    suspend fun getShaFromTag(
        userName: String,
        repository: String,
        tag: String,
        token: String? = null,
    ): String {
        val response = app.get(
            url = "https://api.github.com/repos/$userName/$repository/git/ref/tags/$tag",
            headers = githubHeaders(token)
        )
        if (!response.isSuccessful) {
            failedRequest(response.code)
        }
        return response.parsed<GithubTag>().githubObject.sha
    }

    @Throws
    suspend fun getLatestReleaseFile(
        userName: String,
        repository: String,
        prerelease: Boolean,
        prereleaseTag: String,
        contentType: String,
        token: String? = null,
    ): GithubFile? {
        val latestReleaseUrl = if (prerelease) {
            // Find the release object
            // https://docs.github.com/en/rest/releases/releases?apiVersion=2026-03-10#get-a-release-by-tag-name
            "https://api.github.com/repos/$userName/$repository/releases/tags/$prereleaseTag"
        } else {
            // Just get the latest release
            // https://docs.github.com/en/rest/releases/releases?apiVersion=2026-03-10#get-the-latest-release
            // The latest release is the most recent non-prerelease, non-draft release, sorted by the created_at attribute
            "https://api.github.com/repos/$userName/$repository/releases/latest"
        }

        val response = app.get(
            url = latestReleaseUrl,
            headers = githubHeaders(token)
        )
        if (!response.isSuccessful) {
            failedRequest(response.code)
        }

        val latestRelease = response.parsed<GithubRelease>()

        // Find the correct asset, given that we might have other binaries we release in the same version.
        // When on prerelease prefer the dedicated prerelease build so the prerelease flavor always
        // downloads its own APK instead of the stable one, mirroring the old InAppUpdater behavior.
        val apkAssets = latestRelease.assets.filter { asset ->
            asset.contentType == contentType
        }
        val foundAsset = if (prerelease) {
            apkAssets.firstOrNull { it.name == "app-prerelease-release.apk" }
                ?: apkAssets.firstOrNull()
        } else {
            apkAssets.firstOrNull { it.name != "app-prerelease-release.apk" }
                ?: apkAssets.firstOrNull()
        }

        if (foundAsset == null) {
            return null
        }

        return GithubFile(
            digest = foundAsset.digest,
            // The API asset url works for both public and private repositories,
            // browser_download_url only works for public repositories.
            // This requires the download to send "Accept: application/octet-stream".
            downloadUrl = "https://api.github.com/repos/$userName/$repository/releases/assets/${foundAsset.id}",
            displayName = foundAsset.name.substringBeforeLast("."),
            changeLog = latestRelease.body,
            tagName = latestRelease.tagName,
            nodeId = latestRelease.nodeId
        )
    }
}