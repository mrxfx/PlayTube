#!/bin/bash
awk '
/sealed interface PlayerUiState/ {
    in_state = 1
    print "sealed interface PlayerUiState {"
    print "    object Loading : PlayerUiState"
    print "    data class Success("
    print "        val bundle: StreamBundle,"
    print "        val details: VideoItem,"
    print "        val relatedStreams: List<VideoItem>,"
    print "        val hasNextPage: Boolean,"
    print "        val nextTrendingPage: org.schabi.newpipe.extractor.Page? = null"
    print "    ) : PlayerUiState"
    print "    data class Offline(val details: VideoItem) : PlayerUiState"
    print "    data class Error(val error: PlayTubeError) : PlayerUiState"
    print "    data class Upcoming(val title: String, val thumbnailUrl: String?, val uploader: String, val scheduledTime: String?) : PlayerUiState"
    print "}"
    next
}
in_state && /}/ {
    in_state = 0
    next
}
in_state { next }
{ print }
' app/src/main/java/com/rahul/vibetube/ui/screens/player/PlayerViewModel.kt > temp.kt && mv temp.kt app/src/main/java/com/rahul/vibetube/ui/screens/player/PlayerViewModel.kt
