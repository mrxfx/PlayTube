/*
 * VibeTube
 * Copyright (C) 2026 VibeTube contributors
 *
 * Licensed under GPL-3.0-or-later
 */
package com.rahul.vibetube.ui.screens.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rahul.vibetube.domain.repository.UpdateDownloadState
import com.rahul.vibetube.domain.repository.UpdateInfo
import com.rahul.vibetube.ui.theme.VibeTubeRed
import java.util.Locale

@Composable
fun MandatoryUpdateScreen(
    updateInfo: UpdateInfo,
    downloadState: UpdateDownloadState,
    canRequestPackageInstalls: () -> Boolean,
    onStartDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    onInstallApk: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Strictly block back-to-dismiss
    BackHandler(enabled = true) {
        // No action - mandatory update cannot be dismissed
    }

    var showPermissionDialog by remember { mutableStateOf(false) }
    var installErrorMessage by remember { mutableStateOf<String?>(null) }

    // Surface container styled to Material 3 light/dark
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("mandatory_update_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (downloadState) {
                    is UpdateDownloadState.Idle -> {
                        InitialUpdateRequiredView(
                            updateInfo = updateInfo,
                            onUpdateClick = {
                                onStartDownload()
                            }
                        )
                    }

                    is UpdateDownloadState.Downloading -> {
                        DownloadingUpdateView(
                            updateInfo = updateInfo,
                            progressPercent = downloadState.progressPercent,
                            downloadedBytes = downloadState.downloadedBytes,
                            totalBytes = downloadState.totalBytes
                        )
                    }

                    is UpdateDownloadState.Downloaded -> {
                        UpdateReadyView(
                            updateInfo = updateInfo,
                            onInstallClick = {
                                if (!canRequestPackageInstalls()) {
                                    showPermissionDialog = true
                                } else {
                                    onInstallApk()
                                }
                            }
                        )
                    }

                    is UpdateDownloadState.Failed -> {
                        DownloadFailedView(
                            errorMessage = downloadState.error,
                            onRetryClick = onRetryDownload
                        )
                    }
                }

                if (installErrorMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = installErrorMessage ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = {
                Text(
                    text = "Install Permission Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "To install the update, please allow VibeTube to install unknown apps in Android Settings.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionDialog = false
                        onOpenInstallSettings()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VibeTubeRed)
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = VibeTubeRed
                )
            }
        )
    }
}

@Composable
private fun InitialUpdateRequiredView(
    updateInfo: UpdateInfo,
    onUpdateClick: () -> Unit
) {
    Icon(
        imageVector = Icons.Default.SystemUpdate,
        contentDescription = "Update Required",
        tint = VibeTubeRed,
        modifier = Modifier.size(64.dp)
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "Update Required",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.testTag("update_required_title")
    )

    Spacer(modifier = Modifier.height(12.dp))

    val versionText = if (updateInfo.latestVersion.isNotBlank()) {
        "VibeTube ${updateInfo.latestVersion} is available."
    } else {
        "A new version of VibeTube is available."
    }

    Text(
        text = versionText,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "This update is required to continue using VibeTube.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (updateInfo.releaseNotes.isNotBlank()) {
        Spacer(modifier = Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "What's New:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = updateInfo.releaseNotes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = onUpdateClick,
        colors = ButtonDefaults.buttonColors(containerColor = VibeTubeRed),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("update_now_button")
    ) {
        Text(
            text = "Update Now",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DownloadingUpdateView(
    updateInfo: UpdateInfo,
    progressPercent: Int,
    downloadedBytes: Long,
    totalBytes: Long
) {
    Icon(
        imageVector = Icons.Default.Download,
        contentDescription = "Downloading Update",
        tint = VibeTubeRed,
        modifier = Modifier.size(64.dp)
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "Updating VibeTube",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground
    )

    Spacer(modifier = Modifier.height(8.dp))

    val versionText = if (updateInfo.latestVersion.isNotBlank()) {
        "VibeTube v${updateInfo.latestVersion}"
    } else {
        "VibeTube"
    }

    Text(
        text = versionText,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Downloading update...",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Real progress bar
    val progressFraction = if (totalBytes > 0) {
        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        progressPercent / 100f
    }

    if (totalBytes > 0) {
        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .testTag("download_progress_bar"),
            color = VibeTubeRed,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .testTag("download_progress_bar"),
            color = VibeTubeRed,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$progressPercent%",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        val sizeText = if (totalBytes > 0) {
            val mbDownloaded = String.format(Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
            val mbTotal = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
            "$mbDownloaded MB / $mbTotal MB"
        } else if (downloadedBytes > 0) {
            val mbDownloaded = String.format(Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
            "$mbDownloaded MB"
        } else {
            ""
        }

        if (sizeText.isNotBlank()) {
            Text(
                text = sizeText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UpdateReadyView(
    updateInfo: UpdateInfo,
    onInstallClick: () -> Unit
) {
    Icon(
        imageVector = Icons.Default.SystemUpdate,
        contentDescription = "Update Ready",
        tint = VibeTubeRed,
        modifier = Modifier.size(64.dp)
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "Update Ready",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.testTag("update_ready_title")
    )

    Spacer(modifier = Modifier.height(12.dp))

    val versionText = if (updateInfo.latestVersion.isNotBlank()) {
        "VibeTube v${updateInfo.latestVersion}"
    } else {
        "VibeTube update"
    }

    Text(
        text = "$versionText has been downloaded and is ready to install.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = onInstallClick,
        colors = ButtonDefaults.buttonColors(containerColor = VibeTubeRed),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("install_update_button")
    ) {
        Text(
            text = "Update Now",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DownloadFailedView(
    errorMessage: String,
    onRetryClick: () -> Unit
) {
    Icon(
        imageVector = Icons.Default.ErrorOutline,
        contentDescription = "Update Failed",
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(64.dp)
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = "Update failed",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.error
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = errorMessage.ifBlank { "Could not complete update download. Please check your internet connection and try again." },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = onRetryClick,
        colors = ButtonDefaults.buttonColors(containerColor = VibeTubeRed),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("retry_update_button")
    ) {
        Text(
            text = "Retry",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
