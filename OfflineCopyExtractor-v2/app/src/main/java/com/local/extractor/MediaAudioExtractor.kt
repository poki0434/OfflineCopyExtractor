package com.local.extractor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MediaAudioExtractor {
    fun extractTo16kWav(context: Context, inputUri: Uri): File? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, inputUri, null)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }
            if (audioTrackIndex == -1 || inputFormat == null) return null

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false

            while (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                while (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)!!
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.get(chunk)
                    outputBuffer.clear()
                    pcmOut.write(chunk)
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEOS = true
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val resampledPcm = resampleTo16kMono(pcmOut.toByteArray(), sampleRate, channelCount)
            val outWav = File(context.cacheDir, "audio_${System.currentTimeMillis()}.wav")
            writeWavFile(outWav, resampledPcm, 16000, 1)
            return outWav
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun resampleTo16kMono(pcmBytes: ByteArray, srcRate: Int, srcChannels: Int): ByteArray {
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalSamples = shortBuffer.limit() / srcChannels
        val monoSamples = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            var sum = 0
            for (c in 0 until srcChannels) {
                sum += shortBuffer.get(i * srcChannels + c)
            }
            monoSamples[i] = (sum / srcChannels).toShort()
        }

        val ratio = srcRate.toDouble() / 16000.0
        val outLength = (totalSamples / ratio).toInt()
        val outBytes = ByteBuffer.allocate(outLength * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outLength) {
            val srcIdx = (i * ratio).toInt().coerceAtMost(totalSamples - 1)
            outBytes.putShort(monoSamples[srcIdx])
        }
        return outBytes.array()
    }

    private fun writeWavFile(file: File, pcmData: ByteArray, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        val totalDataLen = pcmData.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(pcmData.size)
        }.array()

        FileOutputStream(file).use {
            it.write(header)
            it.write(pcmData)
        }
    }
}
