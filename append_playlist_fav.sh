#!/bin/bash
cat << 'INNEREOF' > patch.kt
    fun togglePlaylistFavorite() {
        val currentUiState = _internalUiState.value
        if (currentUiState is PlaylistUiState.Success) {
            viewModelScope.launch {
                togglePlaylistFavoriteUseCase(currentUiState.details)
                _snackbarMessage.emit("Playlist favorite status changed")
            }
        }
    }
INNEREOF
# insert it before the last 10 lines
awk 'NR==FNR{a[NR]=$0; next} {if (FNR == (NR-FNR-12)) {for(i=1;i<=length(a);i++) print a[i]} print}' patch.kt app/src/main/java/com/rahul/vibetube/ui/screens/playlist/PlaylistViewModel.kt > temp.kt && mv temp.kt app/src/main/java/com/rahul/vibetube/ui/screens/playlist/PlaylistViewModel.kt
