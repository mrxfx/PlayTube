#!/bin/bash
sed -i 's/_internalUiState.value/_uiState.value/g' app/src/main/java/com/rahul/vibetube/ui/screens/playlist/PlaylistViewModel.kt
