        // Seek Bar
        CustomSeekBar(
            progress = seekProgress,
            areControlsVisible = areControlsVisible,
            onProgressChange = { newValue ->
                isSeeking = true
                seekProgress = newValue
            },
            onProgressChangeFinished = {
                try {
                    val targetPos = (seekProgress * duration).toLong()
                    exoPlayer.seekTo(targetPos)
                    currentPosition = targetPos
                    isSeeking = false
                } catch (e: Exception) {
                    isSeeking = false
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
