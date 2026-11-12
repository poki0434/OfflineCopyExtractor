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
            showInputDialog("链接转文字", "粘贴视频分享链接...") { linkText ->
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
                onExtractSuccess(typeName, result)
            }
        }
    }

    private fun processVideoLink(rawText: String) {
        val loading = showLoading("正在解析下载视频并本地转写...")
        lifecycleScope.launch(Dispatchers.IO) {
            val videoFile = WebArticleParser.downloadVideoFromLink(this@MainActivity, rawText)
            val result = if (videoFile != null) {
                val wavFile = MediaAudioExtractor.extractTo16kWav(this@MainActivity, Uri.fromFile(videoFile))
                videoFile.delete()
                if (wavFile != null) {
                    val text = asrEngine.transcribeWav(wavFile.absolutePath)
                    wavFile.delete()
                    text
                } else "音轨解析失败"
            } else {
                "未能从链接直接抓取到视频流（部分加密短链需先保存至相册再使用「视频转文字」）"
            }
            withContext(Dispatchers.Main) {
                loading.dismiss()
                onExtractSuccess("链接转文字", result)
            }
        }
    }

    private fun processArticleLink(rawText: String) {
        val url = WebArticleParser.extractUrl(rawText) ?: return
        val loading = showLoading("正在提取文章正文...")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                WebArticleParser.extractArticleText(url)
            } catch (e: Exception) {
                "文章解析出错：${e.message}"
            }
            withContext(Dispatchers.Main) {
                loading.dismiss()
                onExtractSuccess("文章转文字", result)
            }
        }
    }

    private fun processImageUri(uri: Uri) {
        val loading = showLoading("正在本地离线识别图片文字...")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                OcrEngine.recognizeImage(this@MainActivity, uri)
            } catch (e: Exception) {
                "图片识别失败：${e.message}"
            }
            withContext(Dispatchers.Main) {
                loading.dismiss()
                onExtractSuccess("图片转文字", result)
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
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("extracted", text))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("分享") { _, _ -> shareText(text) }
            .setNegativeButton("关闭", null)
            .show()
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
        startActivity(Intent.createChooser(intent, "分享提取的文案"))
    }

    private fun showLoading(msg: String): AlertDialog {
        return AlertDialog.Builder(this)
            .setMessage(msg)
            .setCancelable(false)
            .show()
    }
}
