/*
 * VibeTube
 * Copyright (C) 2026 VibeTube contributors
 *
 * Licensed under GPL-3.0-or-later
 */
package com.rahul.vibetube.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Mp3AudioConverter {

    fun convertToMp3(
        inputFile: File,
        outputFile: File,
        title: String? = null,
        artist: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        PTLog.d("Mp3AudioConverter", "Starting MP3 conversion: input=${inputFile.name}, output=${outputFile.name}")

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null

        try {
            extractor.setDataSource(inputFile.absolutePath)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || inputFormat == null) {
                PTLog.e("Mp3AudioConverter", "No audio track found in $inputFile")
                return false
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            if (outputFile.exists()) outputFile.delete()
            val fos = FileOutputStream(outputFile)

            // Write ID3v2.3 header for music player compatibility
            writeId3v3Header(fos, title ?: "Audio", artist ?: "VibeTube")

            val encoder = LameMp3Encoder()
            val bitrate = 192
            encoder.init(sampleRate, channelCount, bitrate)

            val bufferInfo = MediaCodec.BufferInfo()
            var isDecoderEOS = false
            val timeoutUs = 10000L

            while (!Thread.currentThread().isInterrupted) {
                if (!isDecoderEOS) {
                    val inputBufIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufIndex >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIndex)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isDecoderEOS = true
                            } else {
                                val presentationTime = extractor.sampleTime
                                decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputBufIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuf = decoder.getOutputBuffer(outputBufIndex)
                        if (outputBuf != null) {
                            outputBuf.position(bufferInfo.offset)
                            outputBuf.limit(bufferInfo.offset + bufferInfo.size)

                            val pcmBytes = ByteArray(bufferInfo.size)
                            outputBuf.get(pcmBytes)

                            val shorts = ShortArray(pcmBytes.size / 2)
                            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

                            val mp3Buffer = encoder.encode(shorts, channelCount)
                            if (mp3Buffer.isNotEmpty()) {
                                fos.write(mp3Buffer)
                            }

                            if (durationUs > 0) {
                                val currentUs = bufferInfo.presentationTimeUs
                                val progress = ((currentUs.toDouble() / durationUs) * 100).toInt().coerceIn(0, 100)
                                onProgress?.invoke(progress)
                            }
                        }
                    }

                    decoder.releaseOutputBuffer(outputBufIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }

            val flushBuf = encoder.flush()
            if (flushBuf.isNotEmpty()) {
                fos.write(flushBuf)
            }

            fos.flush()
            fos.close()
            encoder.close()

            PTLog.d("Mp3AudioConverter", "MP3 conversion finished successfully: output=${outputFile.length()} bytes")
            return outputFile.exists() && outputFile.length() > 0
        } catch (e: Exception) {
            PTLog.e("Mp3AudioConverter", "Conversion to MP3 failed", e)
            if (outputFile.exists()) outputFile.delete()
            return false
        } finally {
            try { decoder?.stop() } catch (e: Exception) {}
            try { decoder?.release() } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
        }
    }

    private fun writeId3v3Header(fos: FileOutputStream, title: String, artist: String) {
        try {
            val titleBytes = title.toByteArray(Charsets.UTF_8)
            val artistBytes = artist.toByteArray(Charsets.UTF_8)

            val titleFrameSize = 1 + titleBytes.size
            val artistFrameSize = 1 + artistBytes.size

            val totalFramesSize = (10 + titleFrameSize) + (10 + artistFrameSize)

            val header = ByteArray(10)
            header[0] = 'I'.code.toByte()
            header[1] = 'D'.code.toByte()
            header[2] = '3'.code.toByte()
            header[3] = 3 // ID3v2.3
            header[4] = 0
            header[5] = 0

            var size = totalFramesSize
            header[9] = (size and 0x7F).toByte()
            size = size shr 7
            header[8] = (size and 0x7F).toByte()
            size = size shr 7
            header[7] = (size and 0x7F).toByte()
            size = size shr 7
            header[6] = (size and 0x7F).toByte()

            fos.write(header)
            writeFrame(fos, "TIT2", titleBytes)
            writeFrame(fos, "TPE1", artistBytes)
        } catch (e: Exception) {
            PTLog.w("Mp3AudioConverter", "Failed to write ID3 header: ${e.message}")
        }
    }

    private fun writeFrame(fos: FileOutputStream, frameId: String, contentBytes: ByteArray) {
        val frameHeader = ByteArray(10)
        frameHeader[0] = frameId[0].code.toByte()
        frameHeader[1] = frameId[1].code.toByte()
        frameHeader[2] = frameId[2].code.toByte()
        frameHeader[3] = frameId[3].code.toByte()

        val frameContentSize = 1 + contentBytes.size
        frameHeader[4] = (frameContentSize shr 24 and 0xFF).toByte()
        frameHeader[5] = (frameContentSize shr 16 and 0xFF).toByte()
        frameHeader[6] = (frameContentSize shr 8 and 0xFF).toByte()
        frameHeader[7] = (frameContentSize and 0xFF).toByte()
        frameHeader[8] = 0
        frameHeader[9] = 0

        fos.write(frameHeader)
        fos.write(3) // UTF-8
        fos.write(contentBytes)
    }
}
