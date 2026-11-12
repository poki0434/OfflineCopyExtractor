package com.local.extractor

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

object MediaAudioExtractor {
    fun extractTo16kWav(context: Context, inputUri: Uri): File? {
        val tempInput = File(context.cacheDir, "input_${System.currentTimeMillis()}.tmp")
        context.contentResolver.openInputStream(inputUri)?.use { input ->
            tempInput.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        val outWav = File(context.cacheDir, "audio_${System.currentTimeMillis()}.wav")
        val cmd = "-y -i \"${tempInput.absolutePath}\" -vn -acodec pcm_s16le -ar 16000 -ac 1 \"${outWav.absolutePath}\""
        val session = FFmpegKit.execute(cmd)
        tempInput.delete()

        return if (ReturnCode.isSuccess(session.returnCode) && outWav.exists()) outWav else null
    }
}
