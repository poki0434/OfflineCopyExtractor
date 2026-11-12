package com.local.extractor

import android.app.AlertDialog
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var asrEngine: AsrEngine
    private lateinit var historyDb: HistoryDb
    private var lastExtractedText: String = ""

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processMediaUri(it, "视频转文字") }
    }

    private val pickAudioOrVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { processMediaUri(it, "音视频转文字") }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processImageUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        asrEngine = AsrEngine(this)
        historyDb = HistoryDb(this)

        lifecycleScope.launch(Dispatchers.IO) { asrEngine.initModel() }

        findViewById<View>(R.id.cardLink).setOnClickListener {
            showInputDialog("链接转文字", "粘贴抖音/快手/小红书分享口令或链接...") { linkText ->
                processVideoLink(linkText)
            }
        }

        findViewById<View>(R.id.cardVideo).setOnClickListener {
            pickVideo.launch("video/*")
        }

        findViewById<View>(R.id.cardAudio).setOnClickListener {
            pickAudioOrVideo.launch(arrayOf("audio/*", "video/*"))
        }

        findViewById<View>(R.id.cardArticle).setOnClickListener {
            showInputDialog("文章转文字", "粘贴公众号/网页文章链接...") { linkText ->
                processArticleLink(linkText)
            }
        }

        findViewById<View>(R.id.cardImage).setOnClickListener {
            pickImage.launch("image/*")
        }

        findViewById<View>(R.id.btnHistory).setOnClickListener {
            showHistoryDialog()
        }

        findViewById<View>(R.id.btnShare).setOnClickListener {
            if (lastExtractedText.isEmpty()) {
                Toast.makeText(this, "请先提取一段文案后再分享", Toast.LENGTH_SHORT).show()
            } else {
                shareText(lastExtractedText)
            }
        }
    }

    private fun processMediaUri(uri: Uri, typeName: String) {
        ExtractLogger.clear()
        ExtractLogger.log("LocalMedia", "开始处理本地文件: $uri")
        val loading = showLoading("正在本地抽取音轨并离线转写...")
        lifecycleScope.launch(Dispatchers.IO) {
            val wavFile = MediaAudioExtractor.extractTo16kWav(this@MainActivity, uri)
            val result = if (wavFile != null) {
                ExtractLogger.log("Audio", "WAV 导出成功，大小: ${wavFile.length() / 1024} KB")
                val text = asrEngine.transcribeWav(wavFile.absolutePath)
                wavFile.delete()
                ExtractLogger.log("ASR", "识别完成，字数: ${text.length}")
                text
            } else {
                ExtractLogger.log("AudioErr", "MediaAudioExtractor 返回 null")
                "音频提取失败，请检查文件格式"
            }
            withContext(Dispatchers.Main) {
                loading.dismiss()
                if (result.startsWith("音频提取失败")) {
                    showExtractError(typeName, result)
                } else {
                    onExtractSuccess(typeName, result)
                }
            }
        }
    }

    private fun processVideoLink(rawText: String) {
        if (rawText.contains("weixin.qq.com") || rawText.contains("channels")) {
            onExtractSuccess(
                "链接转文字",
                "提示：微信视频号受微信封闭生态保护，外部浏览器无法直接加载。\n\n操作建议：在微信内将视频保存至手机相册后，点击首页「视频转文字」即可秒级离线出字。"
            )
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val videoFile = WebArticleParser.downloadVideoFromLink(this@MainActivity, rawText)
            withContext(Dispatchers.Main) {
                if (videoFile == null) {
                    ExtractLogger.log("LinkFail", "downloadVideoFromLink 最终返回 null")
                    showExtractError("链接转文字", "未能获取到有效音视频流（可能未点击播放、未完成验证或流被拦截）。")
                    return@withContext
                }
                val videoSizeKb = videoFile.length() / 1024
                ExtractLogger.log("LinkOK", "收到媒体文件: ${videoFile.name}, 大小: ${videoSizeKb} KB")
                val loading = showLoading("已捕获媒体流 (${videoSizeKb}KB)，正在本地离线转写...")
                launch(Dispatchers.IO) {
                    val wavFile = MediaAudioExtractor.extractTo16kWav(this@MainActivity, Uri.fromFile(videoFile))
                    videoFile.delete()
                    if (wavFile == null) {
                        ExtractLogger.log("AudioErr", "从下载的 ${videoSizeKb}KB 文件中提取音轨失败（可能为无声纯视频轨或格式不支持）")
                        withContext(Dispatchers.Main) {
                            loading.dismiss()
                            showExtractError("链接转文字", "已下载 ${videoSizeKb}KB 文件，但该文件不含有效音轨（可能是无声纯视频轨）。")
                        }
                        return@launch
                    }
                    val wavSizeKb = wavFile.length() / 1024
                    val durationSec = (wavFile.length() - 44) / 32000.0
                    ExtractLogger.log("AudioOK", "音轨解码成功，WAV大小: ${wavSizeKb} KB，估算音频时长: ${String.format("%.1f", durationSec)} 秒")

                    val text = asrEngine.transcribeWav(wavFile.absolutePath).trim()
                    wavFile.delete()
                    ExtractLogger.log("ASR", "SenseVoice 原始输出: [$text]")

                    withContext(Dispatchers.Main) {
                        loading.dismiss()
                        if (text.isEmpty() || text == "." || text == "。") {
                            showExtractError(
                                "链接转文字",
                                "已下载 ${videoSizeKb}KB 文件（音频时长约 ${String.format("%.1f", durationSec)} 秒），但未识别到有效人声。\n请点击下方「复制错误日志」查看详细原因。"
                            )
                        } else {
                            onExtractSuccess("链接转文字", text)
                        }
                    }
                }
            }
        }
    }

    private fun processArticleLink(rawText: String) {
        val url = WebArticleParser.extractUrl(rawText) ?: return
        ExtractLogger.clear()
        val loading = showLoading("正在提取文章正文...")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                WebArticleParser.extractArticleText(url)
            } catch (e: Exception) {
                ExtractLogger.log("ArticleErr", e.stackTraceToString())
                "文章解析出错：${e.message}"
            }
            withContext(Dispatchers.Main) {
                loading.dismiss()
                if (result.startsWith("文章解析出错") || result == "网页内容获取失败") {
                    showExtractError("文章转文字", result)
                } else {
                    onExtractSuccess("文章转文字", result)
                }
            }
        }
    }

    private fun processImageUri(uri: Uri) {
        ExtractLogger.clear()
        val loading = showLoading("正在本地离线识别图片文字...")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                OcrEngine.recognizeImage(this@MainActivity, uri)
            } catch (e: Exception) {
                ExtractLogger.log("OcrErr", e.stackTraceToString())
                "图片识别失败：${e.message}"
            }
            withContext(Dispatchers.Main) {
                loading.dismiss()
                if (result.startsWith("图片识别失败")) {
                    showExtractError("图片转文字", result)
                } else {
                    onExtractSuccess("图片转文字", result)
                }
            }
        }
    }

    private fun onExtractSuccess(type: String, text: String) {
        lastExtractedText = text
        historyDb.insert(type, text)
        AlertDialog.Builder(this)
            .setTitle("$type · 提取结果")
            .setMessage(text)
            .setPositiveButton("一键复制") { _, _ ->
                copyToClipboard(text, "已复制到剪贴板")
            }
            .setNeutralButton("分享") { _, _ -> shareText(text) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showExtractError(type: String, reason: String) {
        AlertDialog.Builder(this)
            .setTitle("$type · 提取失败")
            .setMessage(reason)
            .setPositiveButton("复制错误日志") { _, _ ->
                copyToClipboard(ExtractLogger.dump(), "完整错误日志已复制到剪贴板")
            }
            .setNeutralButton("查看/分享日志") { _, _ ->
                showLogPreviewDialog()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showLogPreviewDialog() {
        val logText = ExtractLogger.dump()
        AlertDialog.Builder(this)
            .setTitle("诊断错误日志")
            .setMessage(logText)
            .setPositiveButton("复制日志") { _, _ ->
                copyToClipboard(logText, "日志已复制")
            }
            .setNeutralButton("分享发送") { _, _ ->
                shareText(logText)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun copyToClipboard(text: String, toastMsg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }

    private fun showInputDialog(title: String, hint: String, onConfirm: (String) -> Unit) {
        val input = EditText(this).apply { this.hint = hint }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("开始提取") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onConfirm(text)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHistoryDialog() {
        val items = historyDb.getAll()
        if (items.isEmpty()) {
            Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = items.map { "[${it.time}] ${it.type}：${it.content.take(20)}..." }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("历史记录")
            .setItems(titles) { _, which ->
                onExtractSuccess(items[which].type, items[which].content)
            }
            .show()
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "分享内容"))
    }

    private fun showLoading(msg: String): AlertDialog {
        return AlertDialog.Builder(this)
            .setMessage(msg)
            .setCancelable(false)
            .show()
    }
}
