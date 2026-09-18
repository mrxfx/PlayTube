#!/bin/bash
cat << 'INNEREOF' > patch.kt
    override suspend fun cancelDownload(videoId: String) {
        val mission = withContext(Dispatchers.IO) { missionDao.getMissionByVideoId(videoId) }
        mission?.let {
            val intent = Intent(context, VideoDownloadService::class.java).apply {
                action = VideoDownloadService.ACTION_STOP
                putExtra("missionId", it.id)
            }
            context.startService(intent)
        }
        withContext(Dispatchers.IO) {
            downloadDao.deleteDownload(videoId)
        }
    }

    override suspend fun pauseDownload(videoId: String) {
        val mission = withContext(Dispatchers.IO) { missionDao.getMissionByVideoId(videoId) }
        mission?.let {
            val intent = Intent(context, VideoDownloadService::class.java).apply {
                action = VideoDownloadService.ACTION_STOP
                putExtra("missionId", it.id)
            }
            context.startService(intent)
        }
    }

    override suspend fun resumeDownload(videoId: String) {
        val mission = withContext(Dispatchers.IO) { missionDao.getMissionByVideoId(videoId) }
        mission?.let { startDownloadService(it.id) }
    }

    override suspend fun pauseAllActiveDownloads() {
        withContext(Dispatchers.IO) {
            val missions = kotlinx.coroutines.flow.first(missionDao.getAllMissions())
            missions.forEach {
                if (it.status == MissionStatus.DOWNLOADING) {
                    pauseDownload(it.videoId)
                }
            }
        }
    }

    override suspend fun resumeAllPausedDownloads() {
        withContext(Dispatchers.IO) {
            val missions = kotlinx.coroutines.flow.first(missionDao.getAllMissions())
            missions.forEach {
                if (it.status == MissionStatus.PAUSED) {
                    resumeDownload(it.videoId)
                }
            }
        }
    }

    override suspend fun deleteDownload(videoId: String) {
        cancelDownload(videoId)
        withContext(Dispatchers.IO) {
            val entity = downloadDao.getDownloadById(videoId)
            entity?.filePath?.let { File(it).delete() }
            downloadDao.deleteDownload(videoId)
            val mission = missionDao.getMissionByVideoId(videoId)
            mission?.let { missionDao.deleteMission(it) }
        }
    }

    override suspend fun clearAllDownloads() {
        withContext(Dispatchers.IO) {
            val downloads = kotlinx.coroutines.flow.first(downloadDao.getAllDownloads())
            downloads.forEach { deleteDownload(it.videoId) }
        }
    }

    override suspend fun saveToPublicStorage(videoId: String): Result<Unit> {
        return Result.success(Unit)
    }
}
INNEREOF
sed -i '$d' app/src/main/java/com/rahul/vibetube/data/repository/DownloadRepositoryImpl.kt
cat patch.kt >> app/src/main/java/com/rahul/vibetube/data/repository/DownloadRepositoryImpl.kt
rm patch.kt
