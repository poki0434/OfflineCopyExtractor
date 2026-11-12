package com.local.extractor

import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
            startActivity(Intent(this, HistoryActivity::class.java))
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
        val sourceLabel = "本地相册/文件 (${uri.lastPathSegment ?: uri.toString()})"
        val loading = showLoading("正在本地抽取音轨并离线转写...")
        lifecycleScope.launch(Dispatchers.IO) {
            val wavFile = MediaAudioExtractor.extractTo16kWav(this@MainActivity, uri)
            val result = if (wavFile != null) {
                val text = asrEngine.transcribeWav(wavFile.absolutePath)
                wavFile.delete()
                text
            } else {
                "音频提取失败，请检查文件格式"
            }
            withContext(Dispatchers.Main) {
                loading.dismiss()
                if (result.startsWith("音频提取失败")) {
                    showExtractError(typeName, result)
                } else {
                    onExtractSuccess(typeName, sourceLabel, result)
                }
            }
        }
    }

    private fun processVideoLink(rawText: String) {
        if (rawText.contains("weixin.qq.com") || rawText.contains("channels")) {
            onExtractSuccess(
                "链接转文字",
                rawText,
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
                val loading = showLoading("已捕获媒体流 (${videoSizeKb}KB)，正在本地离线转写...")
                launch(Dispatchers.IO) {
                    val wavFile = MediaAudioExtractor.extractTo16kWav(this@MainActivity, Uri.fromFile(videoFile))
                    videoFile.delete()
                    if (wavFile == null) {
                        withContext(Dispatchers.Main) {
                            loading.dismiss()
                            showExtractError("链接转文字", "已下载 ${videoSizeKb}KB 文件，但该文件不含有效音轨（可能是无声纯视频轨）。")
                        }
                        return@launch
                    }
                    val durationSec = (wavFile.length() - 44) / 32000.0
                    val text = asrEngine.transcribeWav(wavFile.absolutePath).trim()
                    wavFile.delete()

                    withContext(Dispatchers.Main) {
                        loading.dismiss()
                        if (text.isEmpty() || text == "." || text == "。") {
                            showExtractError(
                                "链接转文字",
                                "已下载 ${videoSizeKb}KB 文件（音频时长约 ${String.format("%.1f", durationSec)} 秒），但未识别到有效人声。\n请点击下方「复制错误日志」查看详细原因。"
                            )
                        } else {
                            onExtractSuccess("链接转文字", rawText, text)
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
                    onExtractSuccess("文章转文字", rawText, result)
                }
            }
        }
    }

    private fun processImageUri(uri: Uri) {
        ExtractLogger.clear()
        val sourceLabel = "本地相册图片 (${uri.lastPathSegment ?: uri.toString()})"
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
                    onExtractSuccess("图片转文字", sourceLabel, result)
                }
            }
        }
    }

    private fun onExtractSuccess(type: String, sourceInfo: String, text: String) {
        val extractTime = historyDb.insert(type, sourceInfo, text)
        lastExtractedText = text
        val currentItem = HistoryItem(0, extractTime, type, sourceInfo, text)

        val rootBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 16)
        }

        val timeLine = TextView(this).apply {
            this.text = "识别时间：$extractTime   |   $type"
            textSize = 11.5f
            setTextColor(Color.parseColor("#86909C"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(4, 0, 4, 16)
        }
        rootBox.addView(timeLine)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55).toInt()
            )
        }

        val cardsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun buildCopyableCard(title: String, body: String, isMain: Boolean): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 22, 28, 22)
                background = GradientDrawable().apply {
                    setColor(if (isMain) Color.parseColor("#F7F8FA") else Color.parseColor("#F0F6FF"))
                    cornerRadius = 20f
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 20 }

                val header = TextView(this@MainActivity).apply {
                    this.text = "$title（长按此板块复制）"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#1A73E8"))
                    setPadding(0, 0, 0, 8)
                }

                val contentTv = TextView(this@MainActivity).apply {
                    this.text = body
                    textSize = if (isMain) 14.5f else 12.5f
                    setTextColor(Color.parseColor("#1F2329"))
                    setLineSpacing(6f, 1.15f)
                }

                val longClickAction = View.OnLongClickListener {
                    copyToClipboard(body, "已复制「$title」")
                    true
                }
                setOnLongClickListener(longClickAction)
                header.setOnLongClickListener(longClickAction)
                contentTv.setOnLongClickListener(longClickAction)

                addView(header)
                addView(contentTv)
            }
        }

        cardsBox.addView(buildCopyableCard("原始链接 & 口令", sourceInfo, isMain = false))
        cardsBox.addView(buildCopyableCard("识别的内容", text, isMain = true))
        scroll.addView(cardsBox)
        rootBox.addView(scroll)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 4)
        }

        val btnExport = TextView(this).apply {
            this.text = "导出文档"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F9D58"))
            setPadding(12, 16, 20, 16)
            setOnClickListener {
                DocumentExporter.showExportDialog(this@MainActivity, listOf(currentItem))
            }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val btnCopyAll = TextView(this).apply {
            this.text = "复制全部"
            textSize = 13.5f
            setTextColor(Color.parseColor("#4E5969"))
            setPadding(20, 16, 24, 16)
            setOnClickListener {
                val allText = "识别时间：$extractTime\n原始链接：$sourceInfo\n\n$text"
                copyToClipboard(allText, "已复制全部内容")
            }
        }

        val btnCopyMain = TextView(this).apply {
            this.text = "一键复制正文"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A73E8"))
                cornerRadius = 16f
            }
            setPadding(28, 16, 28, 16)
            setOnClickListener {
                copyToClipboard(text, "已一键复制识别正文")
            }
        }

        bottomBar.addView(btnExport)
        bottomBar.addView(spacer)
        bottomBar.addView(btnCopyAll)
        bottomBar.addView(btnCopyMain)
        rootBox.addView(bottomBar)

        AlertDialog.Builder(this)
            .setView(rootBox)
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
