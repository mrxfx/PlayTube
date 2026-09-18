    val onRefresh = remember(viewModel) { { viewModel.refresh() } }
    val onLoadMore = remember(viewModel) { { viewModel.loadNextTrendingPage() } }
    val onFavoriteClick = remember(viewModel) { { v: VideoItem -> viewModel.toggleFavorite(v) } }
    val onNotInterestedClick = remember(viewModel) { { v: VideoItem -> viewModel.markNotInterested(v) } }
    val onDownloadClick = remember(viewModel) { { v: VideoItem -> viewModel.prepareDownload(v) } }
    val onDownloadConfirm = remember(viewModel) { { v: VideoItem, b: com.rahul.vibetube.domain.model.StreamBundle, s1: String?, s2: String?, s3: String?, bool: Boolean -> viewModel.download(v, b, s1, s2, s3, bool) } }
    val onDismissDownload = remember(viewModel) { { viewModel.dismissDownloadDialog() } }
    val onPersonalizedNotifyShown = remember(viewModel) { { viewModel.onPersonalizedNotifyShown() } }

    HomeContent(
        state = state,
        isRefreshing = isRefreshing,
        downloadState = downloadState,
        downloadedIds = downloadedIds,
        favoriteIds = favoriteIds,
        savedVideoIds = savedVideoIds,
        snackbarMessage = viewModel.snackbarMessage,
        onRefresh = onRefresh,
        onLoadMore = onLoadMore,
        onFavoriteClick = onFavoriteClick,
        onNotInterestedClick = onNotInterestedClick,
        onDownloadClick = onDownloadClick,
        onDownloadConfirm = onDownloadConfirm,
        onDismissDownload = onDismissDownload,
        onAddToPlaylistClick = onAddToPlaylistClick,
        onPersonalizedNotifyShown = onPersonalizedNotifyShown,
        onBarsVisibilityChange = onBarsVisibilityChange,
        onVideoClick = onVideoClick,
        onChannelClick = onChannelClick,
        onNavigateToDownloads = onNavigateToDownloads
    )
