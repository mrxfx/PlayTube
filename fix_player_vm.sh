#!/bin/bash
cat << 'INNEREOF' > patch.kt
            _snackbarMessage.emit("Downloading started")
            _downloadState.value = DownloadDialogState.Idle
        }
    }

    fun dismissDownloadDialog() {
        _downloadState.value = DownloadDialogState.Idle
    }
}

sealed interface PlayerUiState {
    object Loading : PlayerUiState
    data class Success(
        val bundle: StreamBundle, 
        val details: VideoItem, 
        val relatedStreams: List<VideoItem>, 
        val hasNextPage: Boolean, 
        val nextTrendingPage: org.schabi.newpipe.extractor.Page? = null
    ) : PlayerUiState
    data class Offline(val details: VideoItem) : PlayerUiState
    data class Error(val error: PlayTubeError) : PlayerUiState
}
INNEREOF
cat patch.kt >> app/src/main/java/com/rahul/vibetube/ui/screens/player/PlayerViewModel.kt
rm patch.kt
