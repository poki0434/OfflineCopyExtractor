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
        val stream = recognizer!!.createStream()
        stream.acceptWaveform(samples, 16000)
        recognizer!!.decode(stream)
        val result = recognizer!!.getResult(stream).text
        stream.release()
        return result.trim()
    }

    private fun readWavToFloatArray(path: String): FloatArray {
        val file = File(path)
        val bytes = FileInputStream(file).use { it.readBytes() }
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
