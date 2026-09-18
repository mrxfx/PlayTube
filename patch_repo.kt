    override suspend fun getStreamBundle(videoId: String, forceRefresh: Boolean): StreamBundle {
        if (videoId.isBlank()) throw IllegalArgumentException("Video ID cannot be blank")
        ensureInit()
        val isIncognito = preferencesManager.isIncognitoMode.first()
        
        if (!forceRefresh && !isIncognito) {
            streamCache.get(videoId)?.let { 
                if (!it.isExpired()) return it 
            }
        }
        
        val deferred = inFlightMutex.withLock {
            inFlightStreamRequests[videoId] ?: kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
                try {
                    val service = ServiceList.YouTube
                    val videoUrl = Constants.YouTube.VIDEO_URL_PREFIX + videoId
                    val streamInfo = StreamInfo.getInfo(service, videoUrl)

                    val isLive = streamInfo.streamType == StreamType.LIVE_STREAM || 
                                 streamInfo.streamType == StreamType.AUDIO_LIVE_STREAM ||
                                 streamInfo.streamType.name == "LIVE"

                    val videoStreamsDeferred = async { extractVideoStreams(streamInfo, isLive) }
                    val audioStreamsDeferred = async { extractAudioStreams(streamInfo) }
                    val subtitlesDeferred = async { extractSubtitles(streamInfo) }

                    val bestAudioStream = selectBestAudioStream(streamInfo)

                    val videoStreams = videoStreamsDeferred.await()
                    val isUpcoming = (isLive && videoStreams.isEmpty()) || streamInfo.viewCount == -1L
                    val scheduledStartTime = if (isUpcoming) {
                        streamInfo.uploadDate?.offsetDateTime()?.toString() ?: streamInfo.textualUploadDate
                    } else null

                    StreamBundle(
                        videoStreams = videoStreams,
                        audioStreams = audioStreamsDeferred.await(),
                        title = streamInfo.name ?: "Unknown",
                        uploaderName = streamInfo.uploaderName ?: "Unknown",
                        uploaderUrl = streamInfo.uploaderUrl,
                        uploaderThumbnailUrl = streamInfo.uploaderAvatars.maxByOrNull { it.width }?.url ?: streamInfo.uploaderAvatars.firstOrNull()?.url,
                        uploaderSubscriberCount = streamInfo.uploaderSubscriberCount,
                        description = VideoUtils.sanitizeDescription(streamInfo.description?.content),
                        viewCount = streamInfo.viewCount,
                        uploadDate = streamInfo.textualUploadDate ?: streamInfo.uploadDate?.offsetDateTime()?.toLocalDate()?.toString(),
                        thumbnailUrl = streamInfo.thumbnails.maxByOrNull { it.width }?.url ?: VideoUtils.getBestThumbnailUrl(videoId),
                        isLive = isLive,
                        isUpcoming = isUpcoming,
                        scheduledStartTime = scheduledStartTime,
                        relatedVideos = streamInfo.relatedItems?.filterIsInstance<StreamInfoItem>()?.map { mapToVideoItem(it, null) } ?: emptyList(),
                        nextRelatedVideosPage = null,
                        bestAudioStreamUrl = bestAudioStream?.url,
                        subtitles = subtitlesDeferred.await()
                    )
                } finally {
                    inFlightMutex.withLock { inFlightStreamRequests.remove(videoId) }
                }
            }.also { inFlightStreamRequests[videoId] = it }
        }

        return try {
            val bundle = deferred.await()
            if (!isIncognito) streamCache.put(videoId, bundle)
            bundle
        } catch (e: Exception) {
            PTLog.e("VideoRepository", "Error fetching stream bundle for $videoId", e)
            throw e
        }
    }
