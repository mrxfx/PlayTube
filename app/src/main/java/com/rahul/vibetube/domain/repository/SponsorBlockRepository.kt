/*
 * VibeTube
 * Copyright (C) 2026 VibeTube contributors
 *
 * Licensed under GPL-3.0-or-later
 */
package com.rahul.vibetube.domain.repository

import com.rahul.vibetube.domain.model.SponsorSegment

interface SponsorBlockRepository {
    suspend fun getSponsorSegments(videoId: String): Result<List<SponsorSegment>>
}
