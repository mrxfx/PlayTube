/*
 * VibeTube
 * Copyright (C) 2026 VibeTube contributors
 *
 * Licensed under GPL-3.0-or-later
 */
package com.rahul.vibetube.data.repository

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.rahul.vibetube.BuildConfig
import com.rahul.vibetube.MainActivity
import com.rahul.vibetube.utils.AppVersion
import com.rahul.vibetube.data.local.PreferencesManager
import com.rahul.vibetube.data.network.GitHubRelease
import com.rahul.vibetube.data.network.VersionInfo
import com.rahul.vibetube.domain.repository.UpdateDownloadState
import com.rahul.vibetube.domain.repository.UpdateInfo
import com.rahul.vibetube.domain.repository.UpdateRepository
import com.rahul.vibetube.domain.repository.UpdateStatus
import com.rahul.vibetube.utils.Constants
import com.rahul.vibetube.utils.PTLog
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: HttpClient,
    private val okHttpClient: OkHttpClient,
    private val preferencesManager: PreferencesManager
) : UpdateRepository {

    private val _updateInfo = MutableStateFlow(UpdateInfo())
    override val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    private val _downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    override val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

    private val downloadMutex = Mutex()
    private var downloadJob: Job? = null
    private val repositoryScope = CoroutineScope(Dispatchers.IO + Job())

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        private const val TAG = "UpdateRepository"
        private const val REMOTE_VERSION_JSON_URL =
            "https://raw.githubusercontent.com/RahulHaldar/VibeTube/main/version.json"
        private const val GITHUB_RELEASES_API_URL =
            "https://api.github.com/repos/RahulHaldar/VibeTube/releases/latest"
        private const val CHANNEL_ID = "update_channel"
        private const val NOTIFICATION_ID = 99901
        private const val MIN_CHECK_INTERVAL_MS = 60 * 60 * 1000L // 1 hour throttling
    }

    init {
        // Restore persistent update state on startup
        repositoryScope.launch {
            restoreSavedUpdateState()
        }
    }

    private suspend fun restoreSavedUpdateState() {
        try {
            val savedCode = preferencesManager.updateLatestVersionCode.first()
            val savedName = preferencesManager.updateLatestVersionName.first()
            val savedMandatory = preferencesManager.isUpdateMandatory.first()
            val savedNotes = preferencesManager.updateReleaseNotes.first()
            val savedUrl = preferencesManager.updateDownloadUrl.first()

            val currentCode = AppVersion.code
            val currentVersion = AppVersion.name

            val hasUpdate = evaluateHasUpdate(currentCode, currentVersion, savedCode, savedName)

            if (hasUpdate && savedMandatory) {
                _updateInfo.value = UpdateInfo(
                    hasUpdate = true,
                    latestVersion = savedName,
                    latestVersionCode = savedCode,
                    mandatory = true,
                    releaseNotes = savedNotes,
                    updateUrl = savedUrl,
                    downloadUrl = savedUrl,
                    title = "VibeTube v$savedName",
                    status = UpdateStatus.MANDATORY_UPDATE
                )
                checkExistingDownloadedApk()
            } else if (!hasUpdate && savedCode > 0) {
                // Already updated to or past this version
                preferencesManager.clearUpdateState()
            }
        } catch (e: Exception) {
            PTLog.w(TAG, "Could not restore saved update state: ${e.message}")
        }
    }

    override fun checkExistingDownloadedApk() {
        val currentInfo = _updateInfo.value
        if (!currentInfo.hasUpdate) return

        val validApk = getValidDownloadedApk(currentInfo.latestVersion, currentInfo.latestVersionCode)
        if (validApk != null) {
            _downloadState.value = UpdateDownloadState.Downloaded(
                apkFile = validApk,
                versionName = currentInfo.latestVersion,
                versionCode = currentInfo.latestVersionCode
            )
        }
    }

    override suspend fun checkForUpdates(force: Boolean) {
        val currentVersion = AppVersion.name
        val currentVersionCode = AppVersion.code

        // Throttling for automatic checks
        if (!force) {
            val lastCheck = preferencesManager.lastUpdateCheckTime.first()
            val elapsed = System.currentTimeMillis() - lastCheck
            if (elapsed in 0 until MIN_CHECK_INTERVAL_MS && _updateInfo.value.hasUpdate) {
                return
            }
        }

        // Primary: Check remote version.json
        var checkedSuccessfully = false
        try {
            val versionInfo: VersionInfo = client.get(REMOTE_VERSION_JSON_URL).body()
            val remoteVersion = versionInfo.resolvedVersionName
            val remoteVersionCode = versionInfo.resolvedVersionCode
            val notes = versionInfo.resolvedMessage
            val url = versionInfo.resolvedDownloadUrl
            val title = versionInfo.resolvedTitle
            val isMandatory = versionInfo.mandatory

            // Validate HTTPS and security
            if (!isUrlSecure(url)) {
                PTLog.w(TAG, "Insecure or invalid download URL in version.json: $url")
            }

            val hasUpdate = evaluateHasUpdate(currentVersionCode, currentVersion, remoteVersionCode, remoteVersion)
            val status = when {
                !hasUpdate -> UpdateStatus.UP_TO_DATE
                isMandatory -> UpdateStatus.MANDATORY_UPDATE
                else -> UpdateStatus.OPTIONAL_UPDATE
            }

            val newInfo = UpdateInfo(
                hasUpdate = hasUpdate,
                latestVersion = if (remoteVersion.isNotBlank()) remoteVersion else latestVersionFallback(remoteVersionCode),
                latestVersionCode = remoteVersionCode ?: 0,
                mandatory = isMandatory,
                releaseNotes = notes,
                updateUrl = url,
                downloadUrl = url,
                title = title,
                status = status
            )

            _updateInfo.value = newInfo
            preferencesManager.setLastUpdateCheckTime(System.currentTimeMillis())

            if (hasUpdate) {
                preferencesManager.saveUpdateState(
                    versionCode = remoteVersionCode ?: 0,
                    versionName = remoteVersion,
                    mandatory = isMandatory,
                    notes = notes,
                    downloadUrl = url
                )
                checkExistingDownloadedApk()
            } else {
                preferencesManager.clearUpdateState()
            }

            checkedSuccessfully = true
            return
        } catch (e: Exception) {
            PTLog.w(TAG, "version.json check failed, falling back to GitHub Releases API: ${e.message}")
        }

        // Secondary / Fallback: Check GitHub releases API
        try {
            val response: GitHubRelease = client.get(GITHUB_RELEASES_API_URL).body()
            val latestVersion = response.tagName.removePrefix("v").trim()
            val notes = response.body
            var downloadUrl = response.htmlUrl

            // Prefer direct APK asset URL from release assets
            val apkAsset = response.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            if (apkAsset != null && isUrlSecure(apkAsset.browserDownloadUrl)) {
                downloadUrl = apkAsset.browserDownloadUrl
            }

            val hasUpdate = isVersionNewer(currentVersion, latestVersion)
            // GitHub Releases API does not carry mandatory flag directly, preserve existing mandatory state if same or higher
            val existingMandatory = _updateInfo.value.mandatory && _updateInfo.value.hasUpdate
            val status = when {
                !hasUpdate -> UpdateStatus.UP_TO_DATE
                existingMandatory -> UpdateStatus.MANDATORY_UPDATE
                else -> UpdateStatus.OPTIONAL_UPDATE
            }

            val newInfo = UpdateInfo(
                hasUpdate = hasUpdate,
                latestVersion = latestVersion,
                latestVersionCode = 0,
                mandatory = existingMandatory,
                releaseNotes = notes,
                updateUrl = downloadUrl,
                downloadUrl = downloadUrl,
                title = "VibeTube v$latestVersion",
                status = status
            )

            _updateInfo.value = newInfo
            preferencesManager.setLastUpdateCheckTime(System.currentTimeMillis())

            if (hasUpdate) {
                checkExistingDownloadedApk()
            }
            checkedSuccessfully = true
        } catch (e: Exception) {
            PTLog.e(TAG, "All update checks failed: ${e.message}")
        }

        // If both failed (offline / no internet), do NOT crash!
        if (!checkedSuccessfully) {
            restoreSavedUpdateState()
        }
    }

    private fun evaluateHasUpdate(
        currentCode: Int,
        currentVersion: String,
        remoteCode: Int?,
        remoteVersion: String
    ): Boolean {
        if (remoteCode != null && remoteCode > 0) {
            if (remoteCode > currentCode) return true
            if (remoteCode < currentCode) return false
            // Same code, fallback to semantic comparison
            return isVersionNewer(currentVersion, remoteVersion)
        }
        return isVersionNewer(currentVersion, remoteVersion)
    }

    fun isVersionNewer(current: String, latest: String): Boolean {
        val cleanCurrent = current.removePrefix("v").trim()
        val cleanLatest = latest.removePrefix("v").trim()
        if (cleanLatest.isBlank() || cleanCurrent == cleanLatest) return false

        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }

        if (latestParts.isEmpty()) return false

        val size = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until size) {
            val currentPart = currentParts.getOrElse(i) { 0 }
            val latestPart = latestParts.getOrElse(i) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    private fun isUrlSecure(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!url.startsWith("https://", ignoreCase = true)) return false
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: return false
            host == "github.com" ||
            host == "raw.githubusercontent.com" ||
            host == "api.github.com" ||
            host == "objects.githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")
        } catch (e: Exception) {
            false
        }
    }

    private fun latestVersionFallback(code: Int?): String {
        return if (code != null && code > 0) "code-$code" else ""
    }

    override fun getValidDownloadedApk(versionName: String, versionCode: Int): File? {
        return try {
            val dir = File(context.cacheDir, "apk_updates")
            if (!dir.exists()) return null
            val file = File(dir, "vibetube_${versionName}_${versionCode}.apk")
            if (!file.exists() || file.length() <= 0) return null

            val packageArchiveInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            if (packageArchiveInfo == null) {
                file.delete()
                return null
            }

            // Verify expected package identity
            if (packageArchiveInfo.packageName != context.packageName) {
                PTLog.w(TAG, "APK package name mismatch: ${packageArchiveInfo.packageName} vs ${context.packageName}")
                file.delete()
                return null
            }

            // Verify versionCode if available
            val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageArchiveInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageArchiveInfo.versionCode
            }
            if (versionCode > 0 && archiveVersionCode != versionCode) {
                PTLog.w(TAG, "APK versionCode mismatch: $archiveVersionCode vs $versionCode")
                file.delete()
                return null
            }

            file
        } catch (e: Exception) {
            PTLog.e(TAG, "Error validating APK: ${e.message}")
            null
        }
    }

    override suspend fun startApkDownload() {
        downloadMutex.withLock {
            if (_downloadState.value is UpdateDownloadState.Downloading) {
                return
            }

            val currentInfo = _updateInfo.value
            if (!currentInfo.hasUpdate) return

            val versionName = currentInfo.latestVersion
            val versionCode = currentInfo.latestVersionCode

            // Check if already downloaded and valid
            val existingApk = getValidDownloadedApk(versionName, versionCode)
            if (existingApk != null) {
                _downloadState.value = UpdateDownloadState.Downloaded(
                    apkFile = existingApk,
                    versionName = versionName,
                    versionCode = versionCode
                )
                showDownloadCompleteNotification(versionName)
                return
            }

            downloadJob?.cancel()
            downloadJob = repositoryScope.launch {
                executeApkDownload(currentInfo)
            }
        }
    }

    override fun retryDownload() {
        repositoryScope.launch {
            startApkDownload()
        }
    }

    private suspend fun executeApkDownload(info: UpdateInfo) = withContext(Dispatchers.IO) {
        val versionName = info.latestVersion
        val versionCode = info.latestVersionCode
        val updatesDir = File(context.cacheDir, "apk_updates").apply { mkdirs() }
        val tempFile = File(updatesDir, "vibetube_${versionName}_${versionCode}.tmp")
        val destinationFile = File(updatesDir, "vibetube_${versionName}_${versionCode}.apk")

        try {
            _downloadState.value = UpdateDownloadState.Downloading(
                progressPercent = 0,
                downloadedBytes = 0L,
                totalBytes = -1L
            )
            showDownloadProgressNotification(versionName, 0)

            // Resolve direct APK URL
            var directApkUrl = info.downloadUrl
            if (!directApkUrl.endsWith(".apk", ignoreCase = true)) {
                // Try resolving asset from GitHub Releases API
                try {
                    val release: GitHubRelease = client.get(GITHUB_RELEASES_API_URL).body()
                    val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    if (apkAsset != null && isUrlSecure(apkAsset.browserDownloadUrl)) {
                        directApkUrl = apkAsset.browserDownloadUrl
                    }
                } catch (e: Exception) {
                    PTLog.w(TAG, "Could not resolve APK asset from release API: ${e.message}")
                }
            }

            if (!isUrlSecure(directApkUrl)) {
                throw SecurityException("Insecure download URL: $directApkUrl")
            }

            val request = Request.Builder()
                .url(directApkUrl)
                .header("User-Agent", Constants.DEFAULT_USER_AGENT)
                .header("Accept", "application/octet-stream")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Unexpected HTTP response: ${response.code}")
            }

            val body = response.body
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            tempFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var lastProgressUpdate = System.currentTimeMillis()
                    var lastPercent = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val percent = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }

                        val now = System.currentTimeMillis()
                        if (percent != lastPercent || now - lastProgressUpdate > 250) {
                            lastPercent = percent
                            lastProgressUpdate = now
                            _downloadState.value = UpdateDownloadState.Downloading(
                                progressPercent = percent,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes
                            )
                            showDownloadProgressNotification(versionName, percent)
                        }
                    }
                    output.flush()
                }
            }

            // Rename temp to destination APK
            if (destinationFile.exists()) destinationFile.delete()
            if (!tempFile.renameTo(destinationFile)) {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
            }

            // Verify integrity and expected package identity
            val pkgInfo = context.packageManager.getPackageArchiveInfo(destinationFile.absolutePath, 0)
            if (pkgInfo == null) {
                destinationFile.delete()
                throw IOException("Corrupted APK file downloaded.")
            }
            if (pkgInfo.packageName != context.packageName) {
                destinationFile.delete()
                throw SecurityException("Package identity mismatch: ${pkgInfo.packageName} (expected ${context.packageName})")
            }

            // Persistence
            preferencesManager.setDownloadedApk(destinationFile.absolutePath, true)

            _downloadState.value = UpdateDownloadState.Downloaded(
                apkFile = destinationFile,
                versionName = versionName,
                versionCode = versionCode
            )
            showDownloadCompleteNotification(versionName)
        } catch (e: Exception) {
            PTLog.e(TAG, "Download failed", e)
            if (tempFile.exists()) tempFile.delete()
            cancelNotification()
            _downloadState.value = UpdateDownloadState.Failed(
                error = e.localizedMessage ?: "Failed to download update APK.",
                canRetry = true
            )
        }
    }

    private fun showDownloadProgressNotification(versionName: String, progressPercent: Int) {
        try {
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("VibeTube Update")
                .setContentText("Downloading v$versionName ($progressPercent%)")
                .setProgress(100, progressPercent, progressPercent == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            PTLog.w(TAG, "Failed to post progress notification: ${e.message}")
        }
    }

    private fun showDownloadCompleteNotification(versionName: String) {
        try {
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("VibeTube Update")
                .setContentText("v$versionName is ready to install")
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            PTLog.w(TAG, "Failed to post complete notification: ${e.message}")
        }
    }

    private fun cancelNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            PTLog.w(TAG, "Failed to cancel notification: ${e.message}")
        }
    }

    override fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    override fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun installDownloadedApk(): Result<Unit> {
        return try {
            val currentInfo = _updateInfo.value
            val apkFile = (_downloadState.value as? UpdateDownloadState.Downloaded)?.apkFile
                ?: getValidDownloadedApk(currentInfo.latestVersion, currentInfo.latestVersionCode)
                ?: return Result.failure(FileNotFoundException("Downloaded APK file not found"))

            if (!canRequestPackageInstalls()) {
                openInstallPermissionSettings()
                return Result.failure(SecurityException("Permission to install unknown apps is required."))
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            PTLog.e(TAG, "Error installing APK", e)
            Result.failure(e)
        }
    }
}
