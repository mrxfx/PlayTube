#!/bin/bash
cat << 'INNEREOF' > app/src/main/java/com/rahul/vibetube/data/repository/DownloadRepositoryImpl.kt
/*
 * VibeTube
 * Copyright (C) 2026 VibeTube contributors
 *
 * Licensed under GPL-3.0-or-later
 */
package com.rahul.vibetube.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.*
import com.rahul.vibetube.data.local.*
import com.rahul.vibetube.domain.repository.DownloadRepository
import com.rahul.vibetube.services.VideoDownloadService
import com.rahul.vibetube.utils.PTLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val missionDao: MissionDao
) : DownloadRepository {

    private val workManager = WorkManager.getInstance(context)

    override fun getAllDownloads(): Flow<List<DownloadEntity>> = downloadDao.getAllDownloads()

    override suspend fun getDownloadByVideoId(videoId: String): DownloadEntity? = 
        downloadDao.getDownloadById(videoId)

    override suspend fun getDownloadByVideoIdResilient(videoId: String): DownloadEntity? = withContext(Dispatchers.IO) {
        val entity = downloadDao.getDownloadById(videoId) ?: return@withContext null
        if (entity.status != DownloadStatus.COMPLETED) return@withContext entity

        val file = File(entity.filePath)
        if (file.exists()) return@withContext entity

        // Resilience: Try fallback extensions for existing broken playlist downloads
        val baseDir = context.getExternalFilesDir(null)
        val webmFile = File(baseDir, "$videoId.webm")
        val mp4File = File(baseDir, "$videoId.mp4")
        
        val fixedFile = when {
            webmFile.exists() -> webmFile
            mp4File.exists() -> mp4File
            else -> null
        }

        if (fixedFile != null) {
            val updated = entity.copy(filePath = fixedFile.absolutePath)
            downloadDao.updateDownload(updated)
            return@withContext updated
        }

        entity
    }

    override suspend fun startDownload(
        videoId: String,
        url: String?,
        title: String,
        thumbnailUrl: String,
        uploaderName: String,
        quality: String?,
        format: String?,
        audioUrl: String?,
        playlistId: String?,
        playlistTitle: String?,
        saveToDevice: Boolean,
        isAudioOnly: Boolean
    ) {
        val extension = if (format?.contains("webm", ignoreCase = true) == true) "webm" else "mp4"
        val filePath = File(context.getExternalFilesDir(null), "$videoId.$extension").absolutePath
        
        if (!saveToDevice) {
            val entity = DownloadEntity(
                videoId = videoId,
                title = title,
                thumbnailUrl = thumbnailUrl,
                uploaderName = uploaderName,
                filePath = filePath,
                totalSize = 0,
                downloadedSize = 0,
                status = DownloadStatus.WAITING,
                quality = quality,
                format = format,
                videoUrl = if (isAudioOnly) null else url,
                audioUrl = audioUrl ?: (if (isAudioOnly) url else null),
                playlistId = playlistId,
                playlistTitle = playlistTitle
            )
            downloadDao.insertDownload(entity)
        }
        
        val destination = if (saveToDevice) "DEVICE" else "VIBETUBE"
        val mission = DownloadMissionEntity(
            videoId = videoId,
            title = title,
            quality = quality ?: "Unknown",
            videoUrl = if (isAudioOnly) null else url,
            audioUrl = audioUrl ?: (if (isAudioOnly) url else null),
            format = format,
            outputFilePath = destination
        )
        val missionId = missionDao.insertMission(mission)
        
        startDownloadService(missionId)
    }

    private fun startDownloadService(missionId: Long) {
        val intent = Intent(context, VideoDownloadService::class.java).apply {
            action = VideoDownloadService.ACTION_START
            putExtra("missionId", missionId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
INNEREOF
