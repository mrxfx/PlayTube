/*
 * VibeTube
 * Copyright (C) 2026 VibeTube contributors
 *
 * Licensed under GPL-3.0-or-later
 */
package com.rahul.vibetube

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.StrictMode
import androidx.work.Configuration
import com.rahul.vibetube.BuildConfig
import com.rahul.vibetube.data.network.YouTubeDownloader
import com.rahul.vibetube.utils.AppVersion
import com.rahul.vibetube.data.local.PreferencesManager
import com.rahul.vibetube.data.local.VibeTubeDatabase
import androidx.hilt.work.HiltWorkerFactory
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.rahul.vibetube.domain.repository.DownloadRepository
import com.rahul.vibetube.utils.ConnectivityObserver
import com.rahul.vibetube.utils.VTLog
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import java.io.File

@HiltAndroidApp
class VibeTubeApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var downloadRepository: DownloadRepository
    @Inject lateinit var connectivityObserver: ConnectivityObserver
    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var database: VibeTubeDatabase
    @Inject lateinit var shortsPreloadManager: com.rahul.vibetube.ui.screens.shorts.ShortsPreloadManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: Context): ImageLoader = imageLoader

    override fun onCreate() {
        super.onCreate()
        
        try {
            if (BuildConfig.DEBUG) {
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectNetwork()
                        .penaltyLog()
                        .build()
                )
                StrictMode.setVmPolicy(
                    StrictMode.VmPolicy.Builder()
                        .detectLeakedSqlLiteObjects()
                        .detectLeakedClosableObjects()
                        .penaltyLog()
                        .build()
                )
            }

            createNotificationChannel()
            observeConnectivity()
            checkVersionAndCleanup()
            checkActivityAndCleanup()
            shortsPreloadManager.clear()
            prewarmNetwork()
            schedulePeriodicUpdateCheck()
        } catch (e: Exception) {
            VTLog.e("VibeTubeApp", "Critical error during Application initialization", e)
        }
    }

    private fun checkVersionAndCleanup() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val lastVersion = preferencesManager.lastAppVersion.first()
                val currentVersion = AppVersion.code
                
                if (lastVersion != currentVersion) {
                    VTLog.i("VibeTubeApp", "Detected update from $lastVersion to $currentVersion. Performing cache cleanup.")
                    performUpdateCleanup()
                    preferencesManager.setLastAppVersion(currentVersion)
                }
            } catch (e: Exception) {
                VTLog.e("VibeTubeApp", "Version check or cleanup failed", e)
            }
        }
    }

    private suspend fun performUpdateCleanup() {
        try {
            // 1. Clear Feed Cache (Crucial for avoiding VideoItem serialization crashes)
            database.feedCacheDao().clearAll()
            
            // 2. Clear technical library caches (OkHttp, Coil, ExoPlayer)
            val cacheDir = applicationContext.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                deleteRecursively(file)
            }

            // 3. Clear pending update state and APKs
            preferencesManager.clearUpdateState()
            
            VTLog.i("VibeTubeApp", "Update cleanup completed successfully")
        } catch (e: Exception) {
            VTLog.e("VibeTubeApp", "Error during update cleanup", e)
        }
    }

    private fun schedulePeriodicUpdateCheck() {
        try {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            val periodicWork = androidx.work.PeriodicWorkRequestBuilder<com.rahul.vibetube.workers.AppUpdateCheckWorker>(
                24, java.util.concurrent.TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                com.rahul.vibetube.workers.AppUpdateCheckWorker.WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )
        } catch (e: Exception) {
            VTLog.w("VibeTubeApp", "Could not enqueue update check work: ${e.message}")
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    private fun prewarmNetwork() {
        val youtubeRequest = Request.Builder().url("https://www.youtube.com").head().build()
        val gVideoRequest = Request.Builder().url("https://www.googlevideo.com").head().build()

        val callback = object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                VTLog.w("VibeTubeApp", "Network pre-warming failed for ${call.request().url}: ${e.message}")
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
                VTLog.d("VibeTubeApp", "Network pre-warmed for ${call.request().url}")
            }
        }

        okHttpClient.newCall(youtubeRequest).enqueue(callback)
        okHttpClient.newCall(gVideoRequest).enqueue(callback)
    }

    private fun checkActivityAndCleanup() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val lastActive = preferencesManager.lastActiveAt.first()
                
                // Update last active time
                preferencesManager.setLastActiveAt(now)

                if (lastActive != 0L && (now - lastActive) >= 24 * 60 * 60 * 1000L) {
                    VTLog.i("VibeTubeApp", "24h inactivity detected. Cleaning temporary cache.")
                    
                    val sizeBefore = calculateDirSize(cacheDir)
                    
                    // Cleanup cache (images, temporary streams, etc)
                    cacheDir.listFiles()?.forEach { file ->
                        // Don't delete the entire cache dir, just its contents
                        deleteRecursively(file)
                    }
                    
                    val sizeAfter = calculateDirSize(cacheDir)
                    val freed = sizeBefore - sizeAfter
                    
                    if (freed > 10 * 1024 * 1024) { // Only notify if > 10MB freed
                        showCleanupNotification(freed)
                    }
                }
            } catch (e: Exception) {
                VTLog.e("VibeTubeApp", "Activity-based cleanup failed", e)
            }
        }
    }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) calculateDirSize(file) else file.length()
        }
        return size
    }

    private fun showCleanupNotification(freedBytes: Long) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val freedStr = com.rahul.vibetube.utils.StorageUtils.formatSize(freedBytes)
        
        val notification = androidx.core.app.NotificationCompat.Builder(this, "update_channel")
            .setSmallIcon(R.drawable.ic_splash_logo)
            .setContentTitle("Temporary cache cleaned")
            .setContentText("Freed $freedStr of storage. Your downloads are safe.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
            
        manager.notify(2001, notification)
    }

    private fun observeConnectivity() {
        connectivityObserver.observe()
            .onEach { status ->
                when (status) {
                    ConnectivityObserver.Status.Available -> {
                        downloadRepository.resumeAllPausedDownloads()
                    }
                    ConnectivityObserver.Status.Lost, ConnectivityObserver.Status.Unavailable -> {
                        downloadRepository.pauseAllActiveDownloads()
                    }
                    else -> {}
                }
            }
            .launchIn(applicationScope)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val downloadChannel = NotificationChannel(
                "download_channel",
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress"
            }
            notificationManager.createNotificationChannel(downloadChannel)

            val updateChannel = NotificationChannel(
                "update_channel",
                "App Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows app update status and download progress"
            }
            notificationManager.createNotificationChannel(updateChannel)
        }
    }
}
