package com.local.extractor

import android.content.Context
import net.dankito.readability4j.Readability4J
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.regex.Pattern

object WebArticleParser {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    fun extractUrl(rawText: String): String? {
        val matcher = Pattern.compile("(https?://[a-zA-Z0-9./?=_%:-]+)").matcher(rawText)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun extractArticleText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()
        val html = client.newCall(request).execute().body?.string() ?: return "网页内容获取失败"
        val article = Readability4J(url, html).parse()
        val title = article.title ?: ""
        val content = article.textContent?.trim() ?: ""
        return if (title.isNotEmpty()) "【$title】\n\n$content" else content
    }

    fun downloadVideoFromLink(context: Context, shareText: String): File? {
        val url = extractUrl(shareText) ?: return null
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15")
            .build()
        val html = client.newCall(req).execute().body?.string() ?: return null

        val videoMatcher = Pattern.compile("(https?://[^\"'\\s]+\\.mp4[^\"'\\s]*)").matcher(html)
        val videoUrl = if (videoMatcher.find()) videoMatcher.group(1) else return null

        val videoReq = Request.Builder().url(videoUrl).build()
        val videoBytes = client.newCall(videoReq).execute().body?.bytes() ?: return null
        val outFile = File(context.cacheDir, "link_video_${System.currentTimeMillis()}.mp4")
        outFile.writeBytes(videoBytes)
        return outFile
    }
}
