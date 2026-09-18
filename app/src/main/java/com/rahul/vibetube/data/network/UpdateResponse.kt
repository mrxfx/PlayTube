/*
 * VibeTube
 * Copyright (C) 2026 VibeTube contributors
 *
 * Licensed under GPL-3.0-or-later
 */
package com.rahul.vibetube.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VersionInfo(
    @SerialName("versionName")
    val versionName: String? = null,
    @SerialName("version")
    val version: String? = null,
    @SerialName("tag_name")
    val tagName: String? = null,
    @SerialName("versionCode")
    val versionCode: Int? = null,
    @SerialName("version_code")
    val legacyVersionCode: Int? = null,
    @SerialName("title")
    val title: String? = null,
    @SerialName("message")
    val message: String? = null,
    @SerialName("release_notes")
    val releaseNotes: String? = null,
    @SerialName("changelog")
    val changelog: String? = null,
    @SerialName("body")
    val body: String? = null,
    @SerialName("downloadUrl")
    val downloadUrl: String? = null,
    @SerialName("download_url")
    val legacyDownloadUrl: String? = null,
    @SerialName("update_url")
    val updateUrl: String? = null,
    @SerialName("url")
    val url: String? = null,
    @SerialName("html_url")
    val htmlUrl: String? = null,
    @SerialName("mandatory")
    val mandatory: Boolean = false
) {
    val resolvedVersionName: String
        get() = (versionName ?: version ?: tagName ?: "").removePrefix("v").trim()

    val resolvedVersionCode: Int?
        get() = versionCode ?: legacyVersionCode

    val resolvedTitle: String
        get() = title ?: (if (resolvedVersionName.isNotEmpty()) "VibeTube v$resolvedVersionName" else "VibeTube")

    val resolvedMessage: String
        get() = message ?: releaseNotes ?: changelog ?: body ?: ""

    val resolvedDownloadUrl: String
        get() = downloadUrl ?: legacyDownloadUrl ?: updateUrl ?: url ?: htmlUrl ?: "https://github.com/RahulHaldar/VibeTube/releases/latest"
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String = "",
    @SerialName("body")
    val body: String = "",
    @SerialName("html_url")
    val htmlUrl: String = "",
    @SerialName("assets")
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    @SerialName("name")
    val name: String = "",
    @SerialName("browser_download_url")
    val browserDownloadUrl: String = "",
    @SerialName("size")
    val size: Long = 0L
)

