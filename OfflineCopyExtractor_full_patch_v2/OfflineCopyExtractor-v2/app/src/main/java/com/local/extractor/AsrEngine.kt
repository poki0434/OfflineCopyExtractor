package com.local.extractor

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AsrEngine(private val context: Context) {
    private var recognizer: OfflineRecognizer? = null

    fun initModel() {
        if (recognizer != null) return
        val modelDir = File(context.filesDir, "sensevoice").apply { mkdirs() }
        val modelFile = copyAssetIfNeeded("sensevoice/model.int8.onnx", File(modelDir, "model.int8.onnx"))
        val tokensFile = copyAssetIfNeeded("sensevoice/tokens.txt", File(modelDir, "tokens.txt"))

        val senseVoiceConfig = OfflineSenseVoiceModelConfig(
            model = modelFile.absolutePath,
            language = "auto",
            useInverseTextNormalization = true
        )
        val modelConfig = OfflineModelConfig(
            senseVoice = senseVoiceConfig,
            tokens = tokensFile.absolutePath,
            numThreads = 4,
            debug = false
        )
        val config = OfflineRecognizerConfig(modelConfig = modelConfig)
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    fun transcribeWav(wavPath: String): String {
        initModel()
        val samples = readWavToFloatArray(wavPath)
        if (samples.isEmpty()) return ""

        val chunkSize = 16000 * 20
        val sb = StringBuilder()
        var offset = 0
        var segIdx = 0

        while (offset < samples.size) {
            val end = minOf(offset + chunkSize, samples.size)
            val slice = samples.copyOfRange(offset, end)
            val stream = recognizer!!.createStream()
            stream.acceptWaveform(slice, 16000)
            recognizer!!.decode(stream)
            val segText = recognizer!!.getResult(stream).text.trim()
            stream.release()

            segIdx++
            ExtractLogger.log("ASR", "第${segIdx}段(${(end - offset) / 16000}s)识别: [$segText]")
            if (segText.isNotEmpty() && segText != "." && segText != "。") {
                sb.append(segText)
            }
            offset = end
        }
        return sb.toString().trim()
    }

    private fun readWavToFloatArray(path: String): FloatArray {
        val file = File(path)
        val bytes = FileInputStream(file).use { it.readBytes() }
        if (bytes.size <= 44) return FloatArray(0)
        val pcmBytes = bytes.copyOfRange(44, bytes.size)
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val floatArray = FloatArray(shortBuffer.limit())
        for (i in floatArray.indices) {
            floatArray[i] = shortBuffer.get(i) / 32768.0f
        }
        return floatArray
    }

    private fun copyAssetIfNeeded(assetPath: String, outFile: File): File {
        if (!outFile.exists() || outFile.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return outFile
    }
}
