/*
 * VibeTube
 * Copyright (C) 2026 VibeTube contributors
 *
 * Licensed under GPL-3.0-or-later
 */
package com.rahul.vibetube.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.rahul.vibetube.data.local.ChunkType
import com.rahul.vibetube.data.local.DownloadDao
import com.rahul.vibetube.data.local.DownloadStatus
import com.rahul.vibetube.data.local.MissionDao
import com.rahul.vibetube.data.local.MissionStatus
import com.rahul.vibetube.data.network.ParallelDownloader
import com.rahul.vibetube.domain.repository.VideoRepository
import com.rahul.vibetube.utils.Constants
import com.rahul.vibetube.utils.Mp3AudioConverter
import com.rahul.vibetube.utils.StorageUtils
import com.rahul.vibetube.utils.NativeMediaMuxer
import com.rahul.vibetube.utils.PTLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class VideoDownloadService : android.app.Service() {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var missionDao: MissionDao
    @Inject lateinit var downloadDao: DownloadDao
    @Inject lateinit var videoRepository: VideoRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var downloader: ParallelDownloader
    private val muxer = NativeMediaMuxer()

    private val activeMissions = mutableMapOf<Long, Job>()
    private var foregroundMissionId: Long = -1L
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    private enum class NotificationType {
        DOWNLOADING, COMPLETED, FAILED
    }

    override fun onCreate() {
        super.onCreate()
        downloader = ParallelDownloader(okHttpClient, missionDao)
        createNotificationChannel()
        registerNetworkCallback()

        serviceScope.launch {
            try {
                val missions = missionDao.getAllMissions().first()
                missions.forEach { mission ->
                    if (mission.status == MissionStatus.DOWNLOADING) {
                        PTLog.d("VideoDownloadService", "Recovering stale mission ${mission.videoId}")
                        missionDao.updateStatus(mission.id, MissionStatus.PAUSED)
                        downloadDao.setDownloadStatus(mission.videoId, DownloadStatus.PAUSED)
                    }
                }
            } catch (e: Exception) {
                PTLog.e("VideoDownloadService", "Stale recovery failed", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val missionId = intent?.getLongExtra("missionId", -1L) ?: -1L

        when (action) {
            ACTION_START -> if (missionId != -1L) startMission(missionId)
            ACTION_STOP -> if (missionId != -1L) stopMission(missionId)
            ACTION_CANCEL -> if (missionId != -1L) cancelMission(missionId)
        }

        return START_REDELIVER_INTENT
    }

    private fun startMission(missionId: Long) {
        if (activeMissions.containsKey(missionId)) return

        val job = serviceScope.launch {
            var itemUriCreated: Uri? = null
            var missionTitle = "Video"
            var videoId = ""
            try {
                var mission = missionDao.getMissionById(missionId) ?: return@launch
                missionTitle = mission.title
                videoId = mission.videoId

                updateNotification(
                    missionId = missionId,
                    title = missionTitle,
                    content = "Preparing download...",
                    progress = 0,
                    isIndeterminate = true,
                    type = NotificationType.DOWNLOADING,
                    videoId = videoId
                )

                // Resolve Metadata if URLs are missing
                if (mission.videoUrl.isNullOrBlank() && mission.audioUrl.isNullOrBlank()) {
                    PTLog.d("VideoDownloadService", "Mission ${mission.videoId} missing URLs, resolving...")
                    val metadata = fetchStreamMetadata(mission.videoId, mission.quality)
                    if (metadata != null) {
                        mission = mission.copy(
                            videoUrl = metadata.videoUrl,
                            audioUrl = metadata.audioUrl,
                            format = metadata.format,
                            quality = metadata.quality
                        )
                        missionDao.updateMission(mission)

                        downloadDao.getDownloadById(mission.videoId)?.let { download ->
                            downloadDao.updateDownload(download.copy(
                                videoUrl = metadata.videoUrl,
                                audioUrl = metadata.audioUrl,
                                format = metadata.format,
                                quality = metadata.quality
                            ))
                        }
                    } else {
                        throw Exception("Failed to resolve stream metadata")
                    }
                }

                itemUriCreated = executeDownload(missionId, mission)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    PTLog.d("VideoDownloadService", "Mission $missionId cancelled/paused")
                    missionDao.updateStatus(missionId, MissionStatus.PAUSED)
                    missionDao.getMissionById(missionId)?.let {
                        downloadDao.setDownloadStatus(it.videoId, DownloadStatus.PAUSED)
                    }
                    return@launch
                }
                PTLog.e("VideoDownloadService", "Mission $missionId failed", e)

                // Cleanup corrupt MediaStore entry if creation failed mid-way
                itemUriCreated?.let { uri ->
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }

                if (e is ExpiredUrlException) {
                    try {
                        PTLog.w("VideoDownloadService", "URL expired for mission $missionId, retrying...")
                        val mission = missionDao.getMissionById(missionId) ?: throw e
                        val metadata = fetchStreamMetadata(mission.videoId, mission.quality) ?: throw e

                        val updatedMission = mission.copy(
                            videoUrl = metadata.videoUrl,
                            audioUrl = metadata.audioUrl,
                            format = metadata.format,
                            quality = metadata.quality
                        )
                        missionDao.updateMission(updatedMission)

                        downloadDao.getDownloadById(mission.videoId)?.let { download ->
                            downloadDao.updateDownload(download.copy(
                                videoUrl = metadata.videoUrl,
                                audioUrl = metadata.audioUrl,
                                format = metadata.format,
                                quality = metadata.quality
                            ))
                        }

                        executeDownload(missionId, updatedMission)
                        return@launch
                    } catch (retryEx: Exception) {
                        PTLog.e("VideoDownloadService", "Retry failed for $missionId", retryEx)
                    }
                }

                missionDao.updateStatus(missionId, MissionStatus.FAILED)
                missionDao.getMissionById(missionId)?.let {
                    downloadDao.setDownloadStatus(it.videoId, DownloadStatus.FAILED)
                }

                val thumbnail = fetchThumbnailBitmap(videoId)
                updateNotification(
                    missionId = missionId,
                    title = missionTitle,
                    content = "Download failed • Tap to retry",
                    progress = 0,
                    isIndeterminate = false,
                    type = NotificationType.FAILED,
                    thumbnail = thumbnail,
                    videoId = videoId
                )
            } finally {
                activeMissions.remove(missionId)
                if (foregroundMissionId == missionId) {
                    foregroundMissionId = -1L
                    val nextMissionId = activeMissions.keys.firstOrNull()
                    if (nextMissionId != null) {
                        promoteToForeground(nextMissionId)
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }

        activeMissions[missionId] = job

        if (foregroundMissionId == -1L) {
            foregroundMissionId = missionId
            val notification = createNotificationBuilder(
                missionId = missionId,
                title = "VibeTube",
                content = "Starting download...",
                progress = 0,
                isIndeterminate = true,
                type = NotificationType.DOWNLOADING
            ).build()
            startForeground(NOTIFICATION_ID_BASE + missionId.toInt(), notification)
        }
    }

    private val lastUpdateMap = mutableMapOf<Long, Long>()

    private suspend fun executeDownload(
        missionId: Long,
        mission: com.rahul.vibetube.data.local.DownloadMissionEntity
    ): Uri? {
        val isAudioOnly = mission.videoUrl == null && mission.audioUrl != null
        val thumbnail = fetchThumbnailBitmap(mission.videoId)

        updateNotification(
            missionId = missionId,
            title = mission.title,
            content = "Fetching size...",
            progress = 0,
            isIndeterminate = true,
            type = NotificationType.DOWNLOADING,
            thumbnail = thumbnail,
            videoId = mission.videoId
        )

        var totalVideoSizeRemote = 0L
        if (mission.videoUrl != null) {
            totalVideoSizeRemote = downloader.getFileSize(mission.videoUrl)
            if (totalVideoSizeRemote == -403L) throw ExpiredUrlException()
        }

        val totalAudioSizeRemote = mission.audioUrl?.let {
            val size = downloader.getFileSize(it)
            if (size == -403L) throw ExpiredUrlException()
            size
        } ?: 0L

        val combinedTotalSize = totalVideoSizeRemote + totalAudioSizeRemote
        val isSizeKnown = combinedTotalSize > 0

        // Storage Check
        val availableStorage = StorageUtils.getAvailableInternalStorage()
        if (combinedTotalSize > 0 && availableStorage < combinedTotalSize + (100 * 1024 * 1024)) { // 100MB buffer
            throw Exception("Not enough storage. Required: ${StorageUtils.formatSize(combinedTotalSize)}, Available: ${StorageUtils.formatSize(availableStorage)}")
        }

        missionDao.updateMission(mission.copy(totalBytes = combinedTotalSize, status = MissionStatus.DOWNLOADING))
        downloadDao.updateProgress(mission.videoId, DownloadStatus.DOWNLOADING, mission.downloadedBytes, combinedTotalSize)

        val videoFile = File(cacheDir, "${mission.videoId}_video.tmp")
        val audioFile = File(cacheDir, "${mission.videoId}_audio.tmp")

        // 1. Download Video
        var videoSize = 0L
        if (mission.videoUrl != null) {
            videoSize = try {
                downloader.download(mission.videoUrl, videoFile, missionId, ChunkType.VIDEO) { progress ->
                    val currentTime = System.currentTimeMillis()
                    val lastUpdate = lastUpdateMap[missionId] ?: 0L

                    if (currentTime - lastUpdate >= 800L || progress == combinedTotalSize) {
                        lastUpdateMap[missionId] = currentTime
                        val percent = if (isSizeKnown) (progress * 100 / combinedTotalSize).toInt() else 0
                        val statusText = if (isSizeKnown) {
                            "Downloading • ${formatBytes(progress)} / ${formatBytes(combinedTotalSize)}"
                        } else {
                            "Downloading • ${formatBytes(progress)}"
                        }

                        updateNotification(
                            missionId = missionId,
                            title = mission.title,
                            content = statusText,
                            progress = percent / (if (mission.audioUrl != null) 2 else 1),
                            isIndeterminate = !isSizeKnown,
                            type = NotificationType.DOWNLOADING,
                            thumbnail = thumbnail,
                            videoId = mission.videoId
                        )

                        serviceScope.launch(Dispatchers.IO) {
                            missionDao.updateProgress(missionId, progress)
                            downloadDao.updateProgress(mission.videoId, DownloadStatus.DOWNLOADING, progress, combinedTotalSize)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e.message?.contains("403") == true) throw ExpiredUrlException()
                throw e
            }
        }

        // 2. Download Audio if available
        var audioSize = 0L
        if (mission.audioUrl != null) {
            audioSize = try {
                downloader.download(mission.audioUrl, audioFile, missionId, ChunkType.AUDIO) { progress ->
                    val currentTotal = videoSize + progress
                    val currentTime = System.currentTimeMillis()
                    val lastUpdate = lastUpdateMap[missionId] ?: 0L

                    if (currentTime - lastUpdate >= 800L || currentTotal == combinedTotalSize) {
                        lastUpdateMap[missionId] = currentTime
                        val percent = if (isSizeKnown) (currentTotal * 100 / combinedTotalSize).toInt() else 0
                        val statusText = if (isSizeKnown) {
                            "Downloading • ${formatBytes(currentTotal)} / ${formatBytes(combinedTotalSize)}"
                        } else {
                            "Downloading • ${formatBytes(currentTotal)}"
                        }

                        updateNotification(
                            missionId = missionId,
                            title = mission.title,
                            content = statusText,
                            progress = percent,
                            isIndeterminate = !isSizeKnown,
                            type = NotificationType.DOWNLOADING,
                            thumbnail = thumbnail,
                            videoId = mission.videoId
                        )

                        serviceScope.launch(Dispatchers.IO) {
                            missionDao.updateProgress(missionId, currentTotal)
                            downloadDao.updateProgress(mission.videoId, DownloadStatus.DOWNLOADING, currentTotal, combinedTotalSize)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e.message?.contains("403") == true) throw ExpiredUrlException()
                throw e
            }
        }

        val finalTotal = videoSize + audioSize
        missionDao.updateMission(mission.copy(totalBytes = finalTotal))

        // 3. Processing / Muxing / Conversion
        missionDao.updateStatus(missionId, MissionStatus.MUXING)
        updateNotification(
            missionId = missionId,
            title = mission.title,
            content = if (isAudioOnly) "Converting audio to MP3..." else "Combining streams to MP4...",
            progress = 95,
            isIndeterminate = true,
            type = NotificationType.DOWNLOADING,
            thumbnail = thumbnail,
            videoId = mission.videoId
        )

        val isDeviceStorage = mission.outputFilePath == "DEVICE"
        
        // Determine correct extension based on format
        val formatExtension = mission.format?.lowercase()?.let { 
            when {
                it.contains("webm") -> "webm"
                it.contains("opus") -> "opus"
                it.contains("mp3") -> "mp3"
                it.contains("m4a") -> "m4a"
                it.contains("mp4") -> "mp4"
                else -> null
            }
        }
        
        val extension = if (isDeviceStorage) {
            if (isAudioOnly) "mp3" else "mp4"
        } else {
            formatExtension ?: (if (isAudioOnly) "m4a" else "mp4")
        }
        
        val finalTempFile = File(cacheDir, "${mission.videoId}_final.$extension")
        if (finalTempFile.exists()) finalTempFile.delete()

        if (isAudioOnly) {
            if (isDeviceStorage) {
                val sourceFile = audioFile
                val success = Mp3AudioConverter.convertToMp3(
                    inputFile = sourceFile,
                    outputFile = finalTempFile,
                    title = mission.title,
                    artist = "VibeTube"
                )
                if (!success || !finalTempFile.exists()) {
                    throw Exception("MP3 audio conversion failed")
                }
            } else {
                if (!audioFile.renameTo(finalTempFile)) {
                    audioFile.copyTo(finalTempFile, overwrite = true)
                    audioFile.delete()
                }
            }
        } else {
            if (mission.videoUrl != null && mission.audioUrl != null && audioFile.exists()) {
                try {
                    muxer.mux(videoFile, audioFile, finalTempFile)
                } catch (e: Exception) {
                    PTLog.e("VideoDownloadService", "Muxing failed, falling back to raw video file", e)
                    videoFile.copyTo(finalTempFile, overwrite = true)
                }
            } else {
                if (!videoFile.renameTo(finalTempFile)) {
                    videoFile.copyTo(finalTempFile, overwrite = true)
                    videoFile.delete()
                }
            }
        }

        val sanitizedTitle = sanitizeFilename(mission.title)
        var createdMediaStoreUri: Uri? = null

        if (isDeviceStorage) {
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$sanitizedTitle.$extension")
                put(MediaStore.MediaColumns.MIME_TYPE, if (isAudioOnly) "audio/mpeg" else "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isAudioOnly) Environment.DIRECTORY_MUSIC + "/VibeTube" else Environment.DIRECTORY_MOVIES + "/VibeTube"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (isAudioOnly) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = resolver.insert(collection, contentValues)
                ?: throw Exception("Failed to insert MediaStore record")

            createdMediaStoreUri = itemUri

            resolver.openOutputStream(itemUri)?.use { outStream ->
                FileInputStream(finalTempFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
            } ?: throw Exception("Failed to write to MediaStore output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }
        } else {
            val appFile = File(getExternalFilesDir(null), "${mission.videoId}.$extension")
            finalTempFile.copyTo(appFile, overwrite = true)
            
            // Update the download entity with the correct final path if it changed
            serviceScope.launch(Dispatchers.IO) {
                downloadDao.getDownloadById(mission.videoId)?.let { download ->
                    if (download.filePath != appFile.absolutePath) {
                        downloadDao.updateDownload(download.copy(filePath = appFile.absolutePath))
                    }
                }
            }
        }

        // Clean cache files
        finalTempFile.delete()
        videoFile.delete()
        audioFile.delete()

        missionDao.updateMission(mission.copy(totalBytes = finalTotal, status = MissionStatus.COMPLETED))
        downloadDao.updateProgress(mission.videoId, DownloadStatus.COMPLETED, finalTotal, finalTotal)

        val formatQualityStr = if (isAudioOnly) "MP3" else "MP4 • ${mission.quality}"
        updateNotification(
            missionId = missionId,
            title = mission.title,
            content = "Download complete • $formatQualityStr",
            progress = 100,
            isIndeterminate = false,
            type = NotificationType.COMPLETED,
            thumbnail = thumbnail,
            itemUri = createdMediaStoreUri,
            isAudio = isAudioOnly,
            videoId = mission.videoId
        )

        return createdMediaStoreUri
    }

    private suspend fun fetchStreamMetadata(videoId: String, preferredQuality: String?): StreamMetadata? {
        return try {
            val bundle = videoRepository.getStreamBundle(videoId)

            if (preferredQuality == "Audio") {
                val bestAudio = bundle.audioStreams.maxByOrNull { it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                return if (bestAudio != null) {
                    StreamMetadata(null, bestAudio.url, bestAudio.format, "Audio")
                } else null
            }

            // For Device downloads (MP4), we MUST have AVC/H264 video and AAC/M4A audio for MediaMuxer compatibility
            val isMP4Desired = true // We generally aim for MP4 for compatibility

            val videoStream = if (!preferredQuality.isNullOrBlank()) {
                // Try to find MP4 first for better muxing compatibility
                bundle.videoStreams.find { it.quality.contains(preferredQuality, ignoreCase = true) && it.format.contains("mp4", true) }
                    ?: bundle.videoStreams.find { it.quality.contains(preferredQuality, ignoreCase = true) }
                    ?: bundle.videoStreams.find {
                        val res = it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
                        val prefRes = preferredQuality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
                        res <= prefRes && it.format.contains("mp4", true)
                    }
                    ?: bundle.videoStreams.find {
                        val res = it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
                        val prefRes = preferredQuality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
                        res <= prefRes
                    } ?: bundle.videoStreams.firstOrNull()
            } else {
                bundle.videoStreams.find { it.quality.contains("1080") && it.format.contains("mp4", true) }
                    ?: bundle.videoStreams.find { it.quality.contains("720") && it.format.contains("mp4", true) }
                    ?: bundle.videoStreams.find { it.quality.contains("1080") }
                    ?: bundle.videoStreams.find { it.quality.contains("720") }
                    ?: bundle.videoStreams.firstOrNull()
            }

            if (videoStream == null) return null

            val videoUrl = videoStream.url
            val format = videoStream.format

            val audioUrl = if (videoStream.isAdaptive) {
                // For MP4 video, we MUST have M4A/AAC audio for MediaMuxer
                val bestAudio = if (videoStream.format.contains("mp4", true)) {
                    bundle.audioStreams.find { it.format.contains("m4a", true) || it.format.contains("aac", true) }
                        ?: bundle.audioStreams.maxByOrNull { it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                } else {
                    bundle.audioStreams.maxByOrNull { it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                }

                bestAudio?.url
            } else null

            StreamMetadata(videoUrl, audioUrl, format, videoStream.quality)
        } catch (e: Exception) {
            PTLog.e("VideoDownloadService", "Failed to fetch metadata for $videoId", e)
            null
        }
    }

    private data class StreamMetadata(val videoUrl: String?, val audioUrl: String?, val format: String, val quality: String)

    private class ExpiredUrlException : Exception("URL expired")

    private fun promoteToForeground(missionId: Long) {
        foregroundMissionId = missionId
        val notification = createNotificationBuilder(
            missionId = missionId,
            title = "VibeTube",
            content = "Downloading...",
            progress = 0,
            isIndeterminate = true,
            type = NotificationType.DOWNLOADING
        ).build()
        startForeground(NOTIFICATION_ID_BASE + missionId.toInt(), notification)
    }

    private fun stopMission(missionId: Long) {
        activeMissions[missionId]?.cancel()
        activeMissions.remove(missionId)

        serviceScope.launch {
            missionDao.updateStatus(missionId, MissionStatus.PAUSED)
            missionDao.getMissionById(missionId)?.let {
                downloadDao.setDownloadStatus(it.videoId, DownloadStatus.PAUSED)
            }
        }

        notificationManager.cancel(NOTIFICATION_ID_BASE + missionId.toInt())

        if (foregroundMissionId == missionId) {
            foregroundMissionId = -1L
            val nextMissionId = activeMissions.keys.firstOrNull()
            if (nextMissionId != null) {
                promoteToForeground(nextMissionId)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelMission(missionId: Long) {
        activeMissions[missionId]?.cancel()
        activeMissions.remove(missionId)

        serviceScope.launch {
            val mission = missionDao.getMissionById(missionId)
            if (mission != null) {
                downloadDao.getDownloadById(mission.videoId)?.let { downloadDao.deleteDownload(it) }
                missionDao.deleteMission(mission)

                // Clean temp files
                File(cacheDir, "${mission.videoId}_video.tmp").delete()
                File(cacheDir, "${mission.videoId}_audio.tmp").delete()
                File(cacheDir, "${mission.videoId}_final.mp4").delete()
                File(cacheDir, "${mission.videoId}_final.mp3").delete()
            }
        }

        notificationManager.cancel(NOTIFICATION_ID_BASE + missionId.toInt())

        if (foregroundMissionId == missionId) {
            foregroundMissionId = -1L
            val nextMissionId = activeMissions.keys.firstOrNull()
            if (nextMissionId != null) {
                promoteToForeground(nextMissionId)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun updateNotification(
        missionId: Long,
        title: String,
        content: String,
        progress: Int,
        isIndeterminate: Boolean = false,
        type: NotificationType,
        thumbnail: Bitmap? = null,
        itemUri: Uri? = null,
        isAudio: Boolean = false,
        videoId: String = ""
    ) {
        val builder = createNotificationBuilder(
            missionId = missionId,
            title = title,
            content = content,
            progress = progress,
            isIndeterminate = isIndeterminate,
            type = type,
            thumbnail = thumbnail,
            itemUri = itemUri,
            isAudio = isAudio,
            videoId = videoId
        )

        notificationManager.notify(NOTIFICATION_ID_BASE + missionId.toInt(), builder.build())
    }

    private fun createNotificationBuilder(
        missionId: Long,
        title: String,
        content: String,
        progress: Int,
        isIndeterminate: Boolean,
        type: NotificationType,
        thumbnail: Bitmap? = null,
        itemUri: Uri? = null,
        isAudio: Boolean = false,
        videoId: String = ""
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(this, Constants.DOWNLOAD_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(type == NotificationType.DOWNLOADING)
            .setOnlyAlertOnce(true)

        if (thumbnail != null) {
            builder.setLargeIcon(thumbnail)
        }

        when (type) {
            NotificationType.DOWNLOADING -> {
                builder.setSmallIcon(android.R.drawable.stat_sys_download)
                builder.setProgress(100, progress.coerceIn(0, 100), isIndeterminate)

                // Pause action
                val stopIntent = Intent(this, VideoDownloadService::class.java).apply {
                    action = ACTION_STOP
                    putExtra("missionId", missionId)
                }
                val stopPendingIntent = PendingIntent.getService(
                    this,
                    (missionId * 10 + 1).toInt(),
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", stopPendingIntent)

                // Cancel action
                val cancelIntent = Intent(this, VideoDownloadService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra("missionId", missionId)
                }
                val cancelPendingIntent = PendingIntent.getService(
                    this,
                    (missionId * 10 + 2).toInt(),
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            }

            NotificationType.COMPLETED -> {
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                builder.setProgress(0, 0, false)

                if (itemUri != null) {
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(itemUri, if (isAudio) "audio/mpeg" else "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        (missionId * 10 + 3).toInt(),
                        viewIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.setContentIntent(pendingIntent)
                    builder.setAutoCancel(true)
                }
            }

            NotificationType.FAILED -> {
                builder.setSmallIcon(android.R.drawable.stat_notify_error)
                builder.setProgress(0, 0, false)

                val retryIntent = Intent(this, VideoDownloadService::class.java).apply {
                    action = ACTION_START
                    putExtra("missionId", missionId)
                }
                val pendingIntent = PendingIntent.getService(
                    this,
                    (missionId * 10 + 4).toInt(),
                    retryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(pendingIntent)
                builder.setAutoCancel(true)
            }
        }

        return builder
    }

    private suspend fun fetchThumbnailBitmap(videoId: String): Bitmap? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null
        val download = downloadDao.getDownloadById(videoId)
        val url = download?.thumbnailUrl ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
            .ifBlank { "VibeTube_Media" }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.DOWNLOAD_CHANNEL_ID,
                "Video Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                PTLog.d("VideoDownloadService", "Network restored! Checking for pending missions to auto-resume...")
                serviceScope.launch {
                    try {
                        val missions = missionDao.getAllMissions().first()
                        missions.forEach { mission ->
                            if (mission.status == MissionStatus.DOWNLOADING || mission.status == MissionStatus.QUEUED) {
                                if (!activeMissions.containsKey(mission.id)) {
                                    PTLog.d("VideoDownloadService", "Auto-resuming mission ${mission.id} for ${mission.videoId}")
                                    startMission(mission.id)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        PTLog.e("VideoDownloadService", "Failed auto-resume on network available", e)
                    }
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            PTLog.w("VideoDownloadService", "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            PTLog.w("VideoDownloadService", "Error unregistering network callback: ${e.message}")
        } finally {
            networkCallback = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START = "com.rahul.vibetube.action.START_DOWNLOAD"
        const val ACTION_STOP = "com.rahul.vibetube.action.STOP_DOWNLOAD"
        const val ACTION_CANCEL = "com.rahul.vibetube.action.CANCEL_DOWNLOAD"
        const val NOTIFICATION_ID_BASE = 1000
    }
}
