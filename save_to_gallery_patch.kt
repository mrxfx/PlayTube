            val actualFinalSize = finalFile.length()
            downloadDao.updateProgress(videoId, DownloadStatus.COMPLETED, actualFinalSize, actualFinalSize)
            
            // Auto-save to gallery
            try {
                val extension = if (finalFile.name.contains("webm", ignoreCase = true)) "webm" else "mp4"
                val mimeType = if (extension == "webm") "video/webm" else "video/mp4"
                val displayName = "${title}_${videoId}.$extension"
                
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/VibeTube")
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                
                val resolver = applicationContext.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { output ->
                        finalFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                    }
                    PTLog.d("DownloadWorker", "Saved to gallery successfully: $uri")
                }
            } catch (e: Exception) {
                PTLog.e("DownloadWorker", "Failed to auto-save to gallery", e)
            }
