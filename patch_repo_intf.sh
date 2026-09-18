#!/bin/bash
awk '
/playlistTitle: String\? = null/ {
    print "        playlistTitle: String? = null,"
    print "        saveToDevice: Boolean = false,"
    print "        isAudioOnly: Boolean = false"
    next
}
{ print }
' app/src/main/java/com/rahul/vibetube/domain/repository/DownloadRepository.kt > temp.kt && mv temp.kt app/src/main/java/com/rahul/vibetube/domain/repository/DownloadRepository.kt
