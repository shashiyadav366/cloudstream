package com.lagradost.cloudstream3.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.receivers.PackageInstallerStatusReceiver
import com.lagradost.cloudstream3.utils.BackupUtils
import com.lagradost.cloudstream4.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestException
import java.security.MessageDigest

object ApkUpdater : AppUpdater {
    private const val TAG = "ApkUpdater"
    private const val APP_UPDATE_NAME = "CloudStream"
    private const val APP_UPDATE_SUFFIX = "apk"

    /**
     * The download headers are required to get the raw file from the API asset url,
     * and to authorize the download for private repositories. Uses the exact same
     * token as the backup section.
     */
    private fun getDownloadHeaders(context: Context): Map<String, String> {
        val token = BackupUtils.getBackupToken(context)
        val headers = mutableMapOf("Accept" to "application/octet-stream")
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    @Throws
    override suspend fun update(
        settings: AppSettings,
        url: String,
        digest: DigestPair?,
        downloadProgress: (Long, Long?) -> Unit
    ) {
        val activity = CommonActivity.activity ?: throw ErrorLoadingException("No activity found")
        clearOldFiles(activity)

        val request = app.get(url, headers = getDownloadHeaders(activity))
        if (!request.isSuccessful) {
            throw ErrorLoadingException("Update download failed (${request.code}) from $url")
        }
        val length = request.size
        val body = request.body
        body.use { body ->
            val length = length ?: body.contentLength()
            val readStream = body.byteStream()

            when (settings.updates.apkInstaller.get()) {
                0 -> {
                    packageInstallerDownloader(
                        activity,
                        readStream,
                        length,
                        digest,
                        downloadProgress
                    )
                }

                else -> {
                    legacyDownloader(activity, readStream, length, digest, downloadProgress)
                }
            }
        }
    }

    fun clearOldFiles(activity: Activity) {
        // Delete old files from both the cache dir and the external files dir, since the
        // legacy installer may have written the APK to either
        listOf(activity.cacheDir, activity.getExternalFilesDir(null))
            .filterNotNull()
            .forEach { dir ->
                dir.listFiles()?.filter {
                    it.name.startsWith(APP_UPDATE_NAME) && it.extension == APP_UPDATE_SUFFIX
                }?.forEach {
                    deleteFileOnExit(it)
                }
            }
    }

    /** https://medium.com/@solrudev/painless-building-of-an-android-package-installer-app-d5a09b5df432 */
    @SuppressLint("RequestInstallPackagesPolicy")
    @Throws
    suspend fun packageInstallerDownloader(
        activity: Activity,
        readStream: InputStream,
        length: Long?,
        digest: DigestPair?,
        downloadProgress: (Long, Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var sessionId: Int? = null
        val packageInstaller = activity.packageManager.packageInstaller
        try {
            val installParams =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                installParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (length != null) {
                installParams.setSize(length)
            }

            sessionId = packageInstaller.createSession(installParams)
            val session = packageInstaller.openSession(sessionId)

            // We do not need to buffer this because transfer has large writes
            session.openWrite(activity.packageName, 0, length ?: -1L)
                .use { writeStream ->
                    transfer(writeStream, readStream, length, downloadProgress, digest)
                    session.fsync(writeStream)
                }

            val receiverIntent = Intent(activity, PackageInstallerStatusReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val receiverPendingIntent =
                PendingIntent.getBroadcast(activity, 0, receiverIntent, flags)

            // Avoid delayed updates, and just commit instantly
            session.commit(receiverPendingIntent.intentSender)
            session.close()
        } catch (t: Throwable) {
            sessionId?.let { sessionId ->
                packageInstaller.abandonSession(sessionId)
            }
            throw t
        }
    }

    /**
     * Write the "readStream" to the "writeStream", while notifying the "downloadProgress" and
     * calculating the digest
     *
     * ------------------------------------------------------------------------------------------
     *
     * throws a DigestException if the Digest can be calculated (non-null + correct algorithm),
     * and is mismatched
     *
     * throws a CancellationException if canceled, but may not be "instant" if the read is blocking
     *
     * throws IOException and similar for reading
     *
     * ------------------------------------------------------------------------------------------
     *
     * In case of crashes from the digest, we ignore it as we do not want to "block" someone from
     * updating if they got a broken OS without the desired algorithm or implementation.
     *
     * This is because a malicious CDN should not be able to "crash" the algorithm, but a broken
     * OS will. And recovering from a broken OS by ignoring it is better than refusing it.
     * */
    @Throws
    suspend fun transfer(
        writeStream: OutputStream,
        readStream: InputStream,
        length: Long?,
        downloadProgress: (Long, Long?) -> Unit,
        digest: DigestPair?,
    ) = withContext(Dispatchers.IO) {
        val md = digest?.algorithm?.let { digestAlgorithm ->
            safe {
                MessageDigest.getInstance(digestAlgorithm)
            }
        }

        val context = currentCoroutineContext()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read: Int
        var transferred: Long = 0
        while ((readStream.read(buffer, 0, DEFAULT_BUFFER_SIZE)
                .also { read = it }) >= 0
        ) {
            writeStream.write(buffer, 0, read)

            safe {
                md?.update(buffer, 0, read)
            }

            transferred += read.toLong()
            downloadProgress(transferred, length)
            context.ensureActive()
        }
        writeStream.flush()

        val check = safe { md?.digest() }
        if (check != null && !check.contentEquals(digest?.digest)) {
            throw DigestException("Mismatched digest on transfer ${digest?.algorithm} : ${check.toHexString()} / ${digest?.digest?.toHexString()}")
        }
    }

    @Throws
    suspend fun legacyDownloader(
        activity: Activity,
        readStream: InputStream,
        length: Long?,
        digest: DigestPair?,
        downloadProgress: (Long, Long?) -> Unit
    ) = withContext(Dispatchers.IO) {
        // On Android 5 the package installer cannot read an APK from the app's private
        // cache dir, which surfaces as a bogus "parse error" (or an unresolvable intent).
        // Keep the legacy download in the external files dir, which the installer can read,
        // matching what InAppUpdater already does for the other update entry point.
        val targetDir = activity.getExternalFilesDir(null) ?: activity.cacheDir
        val downloadedFile =
            File.createTempFile(APP_UPDATE_NAME, ".$APP_UPDATE_SUFFIX", targetDir)

        // We do not need to buffer this because transfer has large writes
        downloadedFile.outputStream().use { writeStream ->
            transfer(writeStream, readStream, length, downloadProgress, digest)
        }

        openApk(activity, downloadedFile)
    }

    private fun installIntent(action: String, component: ComponentName?, contentUri: Uri) =
        Intent(action).apply {
            if (component != null) setComponent(component)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            // An explicit MIME type is mandatory, otherwise the package installer
            // does not match the intent and we get ActivityNotFoundException
            // ("No Activity found to handle Intent")
            setDataAndType(contentUri, APK_CONTENT_TYPE)
        }

    /**
     * Some ROMs (stripped TV boxes in particular) keep the installer activity but drop or
     * mis-declare its intent filters, so implicit resolution finds nothing to handle the
     * intent. Targeting the known components directly bypasses that lookup.
     */
    private fun installerComponents(context: Context): List<ComponentName> = listOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    ).mapNotNull { pkg ->
        val component = ComponentName(pkg, "$pkg.PackageInstallerActivity")
        if (safe { context.packageManager.getActivityInfo(component, 0) } != null) component else null
    }

    private fun installUri(context: Context, file: File): Uri = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // On Android 5/6 the installer resolves file:// but not our FileProvider
            // content:// authority, which is also how a file manager hands over an APK.
            Uri.fromFile(file)
        } else {
            // On Android 7+ file:// URIs are blocked outright by the framework.
            FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", file)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to build an install uri for $file", t)
        Uri.fromFile(file)
    }

    @Throws
    fun openApk(context: Context, file: File) {
        val contentUri = installUri(context, file)

        val candidates = buildList {
            for (component in listOf(null) + installerComponents(context)) {
                add(installIntent(Intent.ACTION_VIEW, component, contentUri))
                add(installIntent(Intent.ACTION_INSTALL_PACKAGE, component, contentUri))
            }
        }

        var last: Throwable? = null
        for (intent in candidates) {
            try {
                context.startActivity(intent)
                return
            } catch (t: Throwable) {
                last = t
                Log.w(
                    TAG,
                    "Unhandled ${intent.action} for ${intent.component ?: "implicit"}: ${t.message}"
                )
            }
        }

        // Nothing resolved the intent, so install through a session instead: the framework
        // PackageInstallerService handles that without any activity having to resolve it.
        Log.w(TAG, "No installer activity resolved, falling back to a session install")
        if (!installWithSession(context, file)) {
            Log.e(TAG, "Session install failed for $file", last)
        }
    }

    private fun installWithSession(context: Context, file: File): Boolean = safe {
        val packageInstaller = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(file.length())

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        session.openWrite(context.packageName, 0, file.length()).use { outputStream ->
            file.inputStream().use { it.copyTo(outputStream) }
            session.fsync(outputStream)
        }

        val receiverIntent = Intent(context, PackageInstallerStatusReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, sessionId, receiverIntent, flags)

        session.commit(pendingIntent.intentSender)
        session.close()
        true
    } ?: false
}