#!/bin/bash
awk '
/fun downloadPlaylist/ {
    in_dl_pl = 1
}
in_dl_pl && /downloadVideoUseCase\(/ {
    in_block = 1
    print "            downloadVideoUseCase("
    print "                videoId = video.id,"
    print "                url = null,"
    print "                title = video.title,"
    print "                thumbnailUrl = video.thumbnailUrl,"
    print "                uploaderName = video.uploaderName,"
    print "                quality = quality,"
    print "                format = null,"
    print "                audioUrl = null,"
    print "                playlistId = details.id,"
    print "                playlistTitle = details.title,"
    print "                isAudioOnly = false,"
    print "                saveToDevice = false"
    print "            )"
    next
}
in_block && /^            \)/ {
    in_block = 0
    in_dl_pl = 0
    next
}
in_block { next }
{ print }
' app/src/main/java/com/rahul/vibetube/ui/screens/playlist/PlaylistViewModel.kt > temp.kt && mv temp.kt app/src/main/java/com/rahul/vibetube/ui/screens/playlist/PlaylistViewModel.kt
