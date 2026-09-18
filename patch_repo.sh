#!/bin/bash
awk '
/playlistTitle: String\?/ {
    print "        playlistTitle: String?,"
    print "        saveToDevice: Boolean = false,"
    print "        isAudioOnly: Boolean = false"
    next
}
/val entity = DownloadEntity/ {
    in_block = 1
    print "        val effectiveVideoId = if (isAudioOnly) \"${videoId}_audio\" else videoId"
    print "        val entity = DownloadEntity("
    print "            videoId = effectiveVideoId,"
    next
}
in_block && /videoId = videoId,/ {
    next
}
in_block && /audioUrl = audioUrl,/ {
    print "            audioUrl = audioUrl,"
    print "            playlistId = playlistId,"
    print "            playlistTitle = playlistTitle"
    print "        )"
    in_block = 0
    next
}
in_block && /playlistId = playlistId,/ { next }
in_block && /playlistTitle = playlistTitle/ { next }
in_block && /^        \)/ { next }

/val mission = DownloadMissionEntity/ {
    in_mission = 1
    print "        val destination = if (saveToDevice) \"DEVICE\" else \"VIBETUBE\""
    print "        val mission = DownloadMissionEntity("
    print "            videoId = videoId,"
    next
}
in_mission && /videoId = videoId,/ {
    next
}
in_mission && /outputFilePath = filePath/ {
    print "            outputFilePath = destination,"
    print "            videoUrl = if (isAudioOnly) null else url,"
    print "            audioUrl = audioUrl ?: (if (isAudioOnly) url else null),"
    print "            format = format"
    print "        )"
    in_mission = 0
    next
}
in_mission && /videoUrl = url,/ { next }
in_mission && /audioUrl = audioUrl,/ { next }
in_mission && /format = format,/ { next }
in_mission && /^        \)/ { next }
{ print }
' app/src/main/java/com/rahul/vibetube/data/repository/DownloadRepositoryImpl.kt > temp.kt && mv temp.kt app/src/main/java/com/rahul/vibetube/data/repository/DownloadRepositoryImpl.kt
