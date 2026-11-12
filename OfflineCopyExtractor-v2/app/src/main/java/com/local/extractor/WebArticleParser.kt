package com.local.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import net.dankito.readability4j.Readability4J
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.coroutines.resume

object WebArticleParser {
    // 伪装成 Windows 10 桌面端 Chrome 浏览器，绕过“请用App观看”的移动端拦截页
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    // 1. 从抖音/快手/小红书的复杂分享口令中精准提取 URL
    fun extractUrl(rawText: String): String? {
        val cleaned = rawText.replace("\\s+".toRegex(), " ")
        val matcher = Pattern.compile("(https?://[a-zA-Z0-9./?=_%:&-]+)").matcher(cleaned)
        return if (matcher.find()) matcher.group(1) else null
    }

    // 2. 文章转文字：抓取网页并本地清洗出纯正文
    fun extractArticleText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .build()
        val html = client.newCall(request).execute().body?.string() ?: return "网页内容获取失败"
        val article = Readability4J(url, html).parse()
        val title = article.title ?: ""
        val content = article.textContent?.trim() ?: ""
        return if (title.isNotEmpty()) "【$title】\n\n$content" else content
    }

    // 3. 多平台视频链接解析与下载（支持抖音、快手、小红书）
    suspend fun downloadVideoFromLink(context: Context, shareText: String): File? {
        val shortUrl = extractUrl(shareText) ?: return null

        // 微信视频号检测提示（视频号链接封闭在微信内，外部无法直接请求直链）
        if (shortUrl.contains("weixin.qq.com") || shortUrl.contains("channels")) {
            return null
        }

        // 第一步：跟踪短链重定向，获取桌面端页面源码与真实落地 URL
        val initReq = Request.Builder()
            .url(shortUrl)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        var finalUrl = shortUrl
        var html = ""
        try {
            client.newCall(initReq).execute().use { resp ->
                finalUrl = resp.request.url.toString()
                html = resp.body?.string() ?: ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 第二步：针对不同平台从桌面端 HTML / JSON 状态树中提取无水印直链
        var videoUrl: String? = when {
            finalUrl.contains("douyin.com") || finalUrl.contains("iesdouyin.com") -> parseDouyinHtml(html)
            finalUrl.contains("kuaishou.com") || finalUrl.contains("chenzhongtech.com") -> parseKuaishouHtml(html)
            finalUrl.contains("xiaohongshu.com") || finalUrl.contains("xhslink.com") -> parseXiaohongshuHtml(html)
            else -> extractGenericMp4(html)
        }

        // 第三步：若静态 HTML 未直接暴露直链（如动态 JS 渲染页），启动后台桌面版 WebView 嗅探真实媒体流
        if (videoUrl.isNullOrEmpty()) {
            videoUrl = sniffVideoUrlWithWebView(context, finalUrl)
        }

        if (videoUrl.isNullOrEmpty()) return null

        // 清洗转义字符（如 \u002F -> /）
        val cleanVideoUrl = videoUrl
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        // 第四步：携带 Referer 和桌面端 UA 下载视频流到本地缓存
        return downloadStreamToFile(context, cleanVideoUrl, finalUrl)
    }

    // 抖音桌面端网页视频流解析
    private fun parseDouyinHtml(html: String): String? {
        if (html.isEmpty()) return null
        // 匹配 RENDER_DATA 或 SSR JSON 中的 play_addr / playApi
        val patterns = listOf(
            "\"playApi\":\"([^\"]+)\"",
            "\"play_addr\":\\{\"uri\":\"[^\"]+\",\"url_list\":\\[\"([^\"]+)\"",
            "src\":\"(https?:\\\\u002F\\\\u002F[^\"]+douyinvod\\.com[^\"]+)\"",
            "(https?://[^\"'\\s]+douyinvod\\.com/[^\"'\\s]+)"
        )
        for (p in patterns) {
            val m = Pattern.compile(p).matcher(html)
            if (m.find()) {
                var url = m.group(1) ?: continue
                if (url.startsWith("//")) url = "https:$url"
                // 将有水印 playwm 替换为无水印 play
                return url.replace("playwm", "play")
            }
        }
        return extractGenericMp4(html)
    }

    // 快手桌面端网页视频流解析
    private fun parseKuaishouHtml(html: String): String? {
        if (html.isEmpty()) return null
        val patterns = listOf(
            "\"photoUrl\":\"([^\"]+)\"",
            "\"mainMvUrls\":\\[\\{\"url\":\"([^\"]+)\"",
            "(https?://[^\"'\\s]+kwimgs\\.com/[^\"'\\s]+\\.mp4[^\"'\\s]*)"
        )
        for (p in patterns) {
            val m = Pattern.compile(p).matcher(html)
            if (m.find()) return m.group(1)
        }
        return extractGenericMp4(html)
    }

    // 小红书桌面端网页视频流解析
    private fun parseXiaohongshuHtml(html: String): String? {
        if (html.isEmpty()) return null
        val patterns = listOf(
            "\"originVideoKey\":\"([^\"]+)\"",
            "\"masterUrl\":\"([^\"]+)\"",
            "\"url\":\"(https?:\\\\u002F\\\\u002F[^\"']+xhscdn\\.com[^\"']+\\.mp4[^\"']*)\"",
            "(https?://[^\"'\\s]+xhscdn\\.com/[^\"'\\s]+\\.mp4[^\"'\\s]*)"
        )
        for (p in patterns) {
            val m = Pattern.compile(p).matcher(html)
            if (m.find()) {
                val matched = m.group(1) ?: continue
                if (p.contains("originVideoKey")) {
                    val cleanKey = matched.replace("\\u002F", "/")
                    return "https://sns-video-bd.xhscdn.com/$cleanKey"
                }
                return matched
            }
        }
        return extractGenericMp4(html)
    }

    private fun extractGenericMp4(html: String): String? {
        val m = Pattern.compile("(https?://[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*)").matcher(html)
        return if (m.find()) m.group(1) else null
    }

    // 后台隐藏桌面版 WebView：模拟电脑浏览器加载页面并拦截真实音视频流请求
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffVideoUrlWithWebView(context: Context, targetUrl: String): String? {
        return withTimeoutOrNull(15000L) {
            suspendCancellableCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    val webView = WebView(context)
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = DESKTOP_UA
                        mediaPlaybackRequiresUserGesture = false
                    }

                    var resolved = false
                    fun finishWith(url: String?) {
                        if (!resolved) {
                            resolved = true
                            webView.stopLoading()
                            webView.destroy()
                            if (cont.isActive) cont.resume(url)
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            val lower = reqUrl.lowercase()
                            val isMedia = (lower.contains(".mp4") ||
                                    lower.contains("douyinvod.com") ||
                                    lower.contains("kwimgs.com") ||
                                    lower.contains("xhscdn.com/stream") ||
                                    lower.contains("mime_type=video")) &&
                                    !lower.contains(".js") && !lower.contains(".css")

                            if (isMedia && !resolved) {
                                Handler(Looper.getMainLooper()).post { finishWith(reqUrl) }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    cont.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post {
                            webView.stopLoading()
                            webView.destroy()
                        }
                    }

                    webView.loadUrl(targetUrl)
                }
            }
        }
    }

    // 下载视频流并校验文件有效性
    private fun downloadStreamToFile(context: Context, videoUrl: String, refererUrl: String): File? {
        return try {
            val req = Request.Builder()
                .url(videoUrl)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", refererUrl)
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val outFile = File(context.cacheDir, "link_video_${System.currentTimeMillis()}.mp4")
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // 校验文件大小大于 50KB，防止下载到防盗链错误提示 HTML
                if (outFile.exists() && outFile.length() > 50 * 1024) {
                    outFile
                } else {
                    outFile.delete()
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
