/*
 * VibeTube
 * Copyright (C) 2026 VibeTube contributors
 *
 * Licensed under GPL-3.0-or-later
 */
package com.rahul.vibetube.ui.components

import com.rahul.vibetube.domain.model.VideoItem
import com.rahul.vibetube.domain.model.StreamBundle

sealed class DownloadDialogState {
    object Idle : DownloadDialogState()
    data class Loading(val video: VideoItem) : DownloadDialogState()
    data class ShowDialog(val video: VideoItem, val bundle: StreamBundle) : DownloadDialogState()
}
