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
import kotlin.math.abs

object MediaAudioExtractor {
    fun extractTo16kWav(context: Context, inputUri: Uri): File? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
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
            if (audioTrackIndex == -1 || inputFormat == null) {
                ExtractLogger.log("Extractor", "未找到 audio/ 音轨")
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            ExtractLogger.log("Extractor", "音轨格式: mime=$mime, rate=$sampleRate, ch=$channelCount")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false

            while (!isOutputEOS) {
                if (!isInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)!!
                    if (bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        pcmOut.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFmt = codec.outputFormat
                    if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    ExtractLogger.log("Extractor", "解码实际输出格式变更: rate=$sampleRate, ch=$channelCount")
                }
            }

            val rawPcm = pcmOut.toByteArray()
            if (rawPcm.isEmpty()) {
                ExtractLogger.log("Extractor", "解码后 PCM 数据为空")
                return null
            }

            val resampledPcm = resampleTo16kMono(rawPcm, sampleRate, channelCount)
            val outWav = File(context.cacheDir, "audio_${System.currentTimeMillis()}.wav")
            writeWavFile(outWav, resampledPcm, 16000, 1)
            return outWav
        } catch (e: Exception) {
            ExtractLogger.log("ExtractorErr", e.stackTraceToString())
            return null
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
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
        var maxAmp = 0

        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, totalSamples - 1)
            val idx1 = (idx0 + 1).coerceIn(0, totalSamples - 1)
            val frac = srcPos - idx0
            val sample = ((monoSamples[idx0] * (1.0 - frac)) + (monoSamples[idx1] * frac)).toInt().toShort()
            val amp = abs(sample.toInt())
            if (amp > maxAmp) maxAmp = amp
            outBytes.putShort(sample)
        }
        ExtractLogger.log("Extractor", "重采样完成: 采样点=$outLength, 最大音量振幅=$maxAmp/32767")
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
