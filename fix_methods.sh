#!/bin/bash
awk '
/    override suspend fun pauseAllActiveDownloads/ {
    in_methods = 1
}
in_methods && /    override suspend fun deleteDownload/ {
    in_methods = 0
}
in_methods { next }
/    override suspend fun deleteDownload/ {
    print "    override suspend fun pauseAllActiveDownloads() {"
    print "        withContext(Dispatchers.IO) {"
    print "            val missions = missionDao.getAllMissions().first()"
    print "            missions.forEach {"
    print "                if (it.status == MissionStatus.DOWNLOADING) {"
    print "                    pauseDownload(it.videoId)"
    print "                }"
    print "            }"
    print "        }"
    print "    }"
    print ""
    print "    override suspend fun resumeAllPausedDownloads() {"
    print "        withContext(Dispatchers.IO) {"
    print "            val missions = missionDao.getAllMissions().first()"
    print "            missions.forEach {"
    print "                if (it.status == MissionStatus.PAUSED) {"
    print "                    resumeDownload(it.videoId)"
    print "                }"
    print "            }"
    print "        }"
    print "    }"
    print ""
    print "    override suspend fun deleteDownload(videoId: String) {"
    next
}
{ print }
' app/src/main/java/com/rahul/vibetube/data/repository/DownloadRepositoryImpl.kt > temp.kt && mv temp.kt app/src/main/java/com/rahul/vibetube/data/repository/DownloadRepositoryImpl.kt
