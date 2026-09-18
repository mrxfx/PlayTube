fun ShortsPager(
    shorts: List<VideoItem>,
    initialVideoId: String?,
    viewModel: ShortsViewModel,
    onChannelClick: (String) -> Unit,
    onVideoClick: (VideoItem) -> Unit
) {
    val initialPageIndex = remember(shorts, initialVideoId) {
        val index = shorts.indexOfFirst { it.id == initialVideoId }
        if (index != -1) index else 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialPageIndex,
        pageCount = { shorts.size }
    )

    val coroutineScope = rememberCoroutineScope()
    var waitingForNextPage by remember { mutableStateOf(false) }
    var nextPreloadTriggered by remember { mutableStateOf(-1) }

    LaunchedEffect(shorts.size) {
        if (waitingForNextPage && pagerState.currentPage < shorts.size - 1) {
            waitingForNextPage = false
            pagerState.animateScrollToPage(pagerState.currentPage + 1)
        }
    }

    // Trigger loading more when we approach the end of the feed
    LaunchedEffect(pagerState.currentPage) {
        waitingForNextPage = false
        val remaining = shorts.size - pagerState.currentPage
        Log.d("ShortsScreen", "[Shorts] Pager: current page = ${pagerState.currentPage}, total queue size = ${shorts.size}, remaining = $remaining")
        if (remaining <= 3) {
            Log.d("ShortsScreen", "[Shorts] Low queue detected. Loading more shorts in background")
            viewModel.loadMoreShorts()
        }
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        val video = shorts[page]
        val isPageActive = pagerState.currentPage == page
        val isPreloading = (pagerState.currentPage + 1 == page) && (nextPreloadTriggered == pagerState.currentPage)

        ShortPlayPage(
            video = video,
            viewModel = viewModel,
            isActive = isPageActive,
            isPreloading = isPreloading,
            onChannelClick = onChannelClick,
            onVideoClick = onVideoClick,
            onVideoEnded = {
                if (page == pagerState.currentPage) {
                    if (page < shorts.size - 1) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(page + 1)
                        }
                    } else {
                        waitingForNextPage = true
                        viewModel.loadMoreShorts()
                    }
                }
            },
            onNearEnd = {
                if (page == pagerState.currentPage) {
                    nextPreloadTriggered = page
                }
            }
        )
    }
}
