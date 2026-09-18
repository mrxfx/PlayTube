@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ShortPlayPage(
    video: VideoItem,
    viewModel: ShortsViewModel,
    isActive: Boolean,
    isPreloading: Boolean,
    onChannelClick: (String) -> Unit,
    onVideoClick: (VideoItem) -> Unit,
    onVideoEnded: () -> Unit = {},
    onNearEnd: () -> Unit = {}
) {
