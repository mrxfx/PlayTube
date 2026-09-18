#!/bin/bash
awk '
/fun downloadPlaylist/ {
    in_dl = 1
    print "    fun downloadPlaylist(quality: String) {"
    print "        val state = _internalUiState.value as? PlaylistUiState.Success ?: return"
    print "        val details = state.details"
    print "        viewModelScope.launch {"
    print "            _snackbarMessage.emit(\"Playlist download started (${quality})\")"
    print "            details.videos.forEach { video ->"
    print "                if (!downloadedVideoIds.value.contains(video.id)) {"
    print "                    downloadVideoUseCase("
    print "                        videoId = video.id,"
    print "                        url = null,"
    print "                        title = video.title,"
    print "                        thumbnailUrl = video.thumbnailUrl,"
    print "                        uploaderName = video.uploaderName,"
    print "                        quality = quality,"
    print "                        format = null,"
    print "                        audioUrl = null,"
    print "                        playlistId = details.id,"
    print "                        playlistTitle = details.title,"
    print "                        isAudioOnly = false,"
    print "                        saveToDevice = false"
    print "                    )"
    print "                }"
    print "            }"
    print "        }"
    print "    }"
    next
}
in_dl && /fun toggleVideoFavorite/ {
    in_dl = 0
}
in_dl { next }
{ print }
' app/src/main/java/com/rahul/vibetube/ui/screens/playlist/PlaylistViewModel.kt > temp.kt && mv temp.kt app/src/main/java/com/rahul/vibetube/ui/screens/playlist/PlaylistViewModel.kt
