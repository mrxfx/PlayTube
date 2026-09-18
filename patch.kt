    fun togglePlaylistFavorite() {
        val currentUiState = _internalUiState.value
        if (currentUiState is PlaylistUiState.Success) {
            viewModelScope.launch {
                togglePlaylistFavoriteUseCase(currentUiState.details)
                _snackbarMessage.emit("Playlist favorite status changed")
            }
        }
    }
