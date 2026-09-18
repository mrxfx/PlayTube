import re

with open("app/src/main/java/com/rahul/vibetube/ui/components/SelectionSheets.kt", "r") as f:
    content = f.read()

start_str = "fun DownloadSelectionSheet("
end_str = "fun SubtitleSelectionSheet("

start_idx = content.find(start_str)
end_idx = content.rfind("@OptIn", start_idx, content.find(end_str))

if end_idx == -1:
    end_idx = content.rfind("@Composable", start_idx, content.find(end_str))

new_func = """@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSelectionSheet(
    videoStreams: List<StreamItem>,
    audioStreams: List<StreamItem>,
    onDismiss: () -> Unit,
    onDownload: (StreamItem, Boolean, Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("vibetube_prefs", android.content.Context.MODE_PRIVATE) }
    
    var saveToDevice by remember { mutableStateOf(sharedPreferences.getBoolean("download_save_to_device", false)) }
    var selectedTab by remember { mutableStateOf(if (videoStreams.isNotEmpty()) 0 else 1) }
    
    val processedVideoStreams = remember(videoStreams) {
        videoStreams
            .filter { it.quality.isNotEmpty() && it.quality.first().isDigit() }
            .sortedByDescending { it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    }
    
    val processedAudioStreams = remember(audioStreams) {
        audioStreams
            .distinctBy { it.format }
            .sortedByDescending { it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    }
    
    var selectedVideoStream by remember { mutableStateOf<StreamItem?>(processedVideoStreams.firstOrNull()) }
    var selectedAudioStream by remember { mutableStateOf<StreamItem?>(processedAudioStreams.firstOrNull()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Download",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Choose format & quality",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(androidx.compose.material.icons.Icons.Default.Close, contentDescription = "Close")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Save to",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = saveToDevice,
                    onClick = { 
                        saveToDevice = true 
                        sharedPreferences.edit().putBoolean("download_save_to_device", true).apply()
                    },
                    label = { Text("Device") },
                    leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = !saveToDevice,
                    onClick = { 
                        saveToDevice = false 
                        sharedPreferences.edit().putBoolean("download_save_to_device", false).apply()
                    },
                    label = { Text("VibeTube") },
                    leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("VIDEO") },
                    leadingIcon = { Icon(Icons.Default.VideoFile, contentDescription = null) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("AUDIO") },
                    leadingIcon = { Icon(Icons.Default.AudioFile, contentDescription = null) },
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedTab == 0) {
                    items(processedVideoStreams) { stream ->
                        val isSelected = selectedVideoStream == stream
                        val resLabel = getResolutionLabel(stream.quality)
                        val sizeLabel = if (stream.size > 0) formatFileSize(stream.size) else "Size unavailable"
                        
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            onClick = { selectedVideoStream = stream },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "${stream.quality} • $resLabel",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = stream.format.uppercase(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Text(
                                    text = sizeLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(processedAudioStreams) { stream ->
                        val isSelected = selectedAudioStream == stream
                        val sizeLabel = if (stream.size > 0) formatFileSize(stream.size) else "Size unavailable"
                        val qualityLabel = stream.quality
                        
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            onClick = { selectedAudioStream = stream },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = stream.format.uppercase(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = qualityLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Text(
                                    text = sizeLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            val activeStream = if (selectedTab == 0) selectedVideoStream else selectedAudioStream
            Button(
                onClick = {
                    activeStream?.let {
                        onDownload(it, selectedTab == 1, saveToDevice)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = activeStream != null,
                colors = ButtonDefaults.buttonColors(containerColor = com.rahul.vibetube.ui.theme.VibeTubeRed)
            ) {
                val labelText = if (activeStream != null) {
                    val quality = activeStream.quality
                    val label = if (selectedTab == 0) getResolutionLabel(quality) else activeStream.format.uppercase()
                    "Download • $quality".trim() + if (label.isNotBlank()) " $label" else ""
                } else {
                    "Download"
                }
                Text(text = labelText, color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

fun getResolutionLabel(quality: String): String {
    val res = quality.filter { it.isDigit() }.toIntOrNull() ?: 0
    return when {
        res >= 4320 -> "8K"
        res >= 2160 -> "4K"
        res >= 1440 -> "2K"
        res >= 1080 -> "Full HD"
        res >= 720 -> "HD"
        else -> ""
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "Size unavailable"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups > 4) digitGroups = 4
    return java.text.DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}
"""

new_content = content[:start_idx] + new_func + "\n" + content[end_idx:]

# Need to ensure Icons.Default.Close is imported
if "import androidx.compose.material.icons.filled.Close" not in new_content:
    new_content = new_content.replace("import androidx.compose.material.icons.filled.ClosedCaption", "import androidx.compose.material.icons.filled.ClosedCaption\nimport androidx.compose.material.icons.filled.Close")

with open("app/src/main/java/com/rahul/vibetube/ui/components/SelectionSheets.kt", "w") as f:
    f.write(new_content)

