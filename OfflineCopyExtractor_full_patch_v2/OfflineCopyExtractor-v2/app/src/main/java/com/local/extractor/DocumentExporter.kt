package com.local.extractor

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object DocumentExporter {

    enum class Format(val label: String, val ext: String, val mime: String) {
        TXT("TXT 纯文本文档 (.txt)", "txt", "text/plain"),
        WORD("Word 文档 (.doc)", "doc", "application/msword"),
        MARKDOWN("Markdown 文档 (.md)", "md", "text/markdown")
    }

    fun showExportDialog(context: Context, items: List<HistoryItem>) {
        if (items.isEmpty()) {
            Toast.makeText(context, "没有可导出的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(
            "导出为 TXT 纯文本 (.txt)",
            "导出为 Word 文档 (.doc)",
            "导出为 Markdown (.md)"
        )
        AlertDialog.Builder(context)
            .setTitle("选择导出文档格式（共 ${items.size} 条）")
            .setItems(options) { _, which ->
                val format = when (which) {
                    0 -> Format.TXT
                    1 -> Format.WORD
                    else -> Format.MARKDOWN
                }
                exportItems(context, items, format)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportItems(context: Context, items: List<HistoryItem>, format: Format) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "文案提取_$timestamp.${format.ext}"
            val contentBytes = when (format) {
                Format.TXT -> buildTxt(items).toByteArray(Charsets.UTF_8)
                Format.MARKDOWN -> buildMarkdown(items).toByteArray(Charsets.UTF_8)
                Format.WORD -> buildWordHtml(items).toByteArray(Charsets.UTF_8)
            }

            saveToDownloads(context, fileName, format.mime, contentBytes)

            val cacheFile = File(context.cacheDir, fileName).apply { writeBytes(contentBytes) }
            Toast.makeText(context, "已保存至「下载/文案提取/$fileName」", Toast.LENGTH_LONG).show()

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = format.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "发送或使用 WPS/Office 打开文档"))
        } catch (e: Exception) {
            Toast.makeText(context, "已保存至下载目录（分享调起失败: ${e.message}）", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToDownloads(context: Context, fileName: String, mime: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/文案提取")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) }
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "文案提取")
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeBytes(bytes)
        }
    }

    private fun buildMarkdown(items: List<HistoryItem>): String {
        val sb = StringBuilder()
        for (item in items) {
            sb.append("## ${item.time}\n\n")
            sb.append("**原始链接/来源：** ${item.sourceInfo}\n\n")
            sb.append("${item.content}\n\n")
            sb.append("---\n\n")
        }
        return sb.toString().trim()
    }

    private fun buildTxt(items: List<HistoryItem>): String {
        val sb = StringBuilder()
        for (item in items) {
            sb.append("识别时间：${item.time} (${item.type})\n")
            sb.append("原始链接：${item.sourceInfo}\n")
            sb.append("识别内容：\n${item.content}\n")
            sb.append("========================================\n\n")
        }
        return sb.toString().trim()
    }

    private fun buildWordHtml(items: List<HistoryItem>): String {
        val sb = StringBuilder()
        sb.append("\uFEFF")
        sb.append("<html><head><meta charset='utf-8'><style>")
        sb.append("body{font-family:'Microsoft YaHei',sans-serif;line-height:1.6;padding:20px;}")
        sb.append("h2{color:#1a73e8;border-bottom:1px solid #ddd;padding-bottom:6px;margin-top:24px;}")
        sb.append(".meta{color:#666;font-size:12pt;margin-bottom:12px;word-break:break-all;}")
        sb.append(".content{font-size:13pt;color:#222;white-space:pre-wrap;}")
        sb.append("</style></head><body>")
        for (item in items) {
            sb.append("<h2>${escapeHtml(item.time)}</h2>")
            sb.append("<div class='meta'><b>原始链接/来源：</b>${escapeHtml(item.sourceInfo)}</div>")
            sb.append("<div class='content'>${escapeHtml(item.content).replace("\n", "<br/>")}</div>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun escapeHtml(s: String): String {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
