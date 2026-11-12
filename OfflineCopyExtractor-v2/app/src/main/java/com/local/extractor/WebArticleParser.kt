package com.local.extractor

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import net.dankito.readability4j.Readability4J
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.coroutines.resume

object WebArticleParser {
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun extractUrl(rawText: String): String? {
        val cleaned = rawText.replace("\\s+".toRegex(), " ")
        val matcher = Pattern.compile("(https?://[a-zA-Z0-9./?=_%:&-]+)").matcher(cleaned)
        return if (matcher.find()) matcher.group(1) else null
    }

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

    suspend fun downloadVideoFromLink(context: Context, shareText: String): File? {
        val targetUrl = extractUrl(shareText) ?: return null
        if (targetUrl.contains("weixin.qq.com") || targetUrl.contains("channels")) {
            return null
        }
        return extractVideoWithWebView(context, targetUrl)
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private suspend fun extractVideoWithWebView(
        context: Context,
        targetUrl: String
    ): File? = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            var resolved = false
            val outFile = File(context.cacheDir, "tm_video_${System.currentTimeMillis()}.mp4")
            var fileStream: FileOutputStream? = null

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 16)
            }

            val tipText = TextView(context).apply {
                text = "正在加载网页并自动捕获视频流...\n（如遇滑块或登录提示请直接在下方操作，视频播出后将自动关闭窗口）"
                textSize = 13f
                setPadding(0, 0, 0, 16)
            }

            val webView = WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (context.resources.displayMetrics.heightPixels * 0.58).toInt()
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    userAgentString = DESKTOP_UA
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            container.addView(tipText)
            container.addView(webView)

            lateinit var dialog: AlertDialog

            fun finishWith(file: File?) {
                if (!resolved) {
                    resolved = true
                    try { fileStream?.close() } catch (_: Exception) {}
                    cookieManager.flush()
                    webView.stopLoading()
                    webView.destroy()
                    if (dialog.isShowing) dialog.dismiss()
                    if (cont.isActive) cont.resume(file)
                }
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onStatus(msg: String) {
                    Handler(Looper.getMainLooper()).post {
                        tipText.text = msg
                    }
                }

                @JavascriptInterface
                fun onChunk(base64Chunk: String) {
                    if (resolved) return
                    try {
                        if (fileStream == null) {
                            fileStream = FileOutputStream(outFile)
                        }
                        val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
                        fileStream?.write(bytes)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                @JavascriptInterface
                fun onDownloadComplete() {
                    Handler(Looper.getMainLooper()).post {
                        try { fileStream?.flush(); fileStream?.close() } catch (_: Exception) {}
                        if (outFile.exists() && outFile.length() > 50 * 1024) {
                            finishWith(outFile)
                        } else {
                            outFile.delete()
                            finishWith(null)
                        }
                    }
                }

                @JavascriptInterface
                fun onDownloadError(err: String) {
                    Handler(Looper.getMainLooper()).post {
                        tipText.text = "同源拉流重试中($err)..."
                    }
                }
            }, "AndroidBridge")

            dialog = AlertDialog.Builder(context)
                .setTitle("视频提取窗口")
                .setView(container)
                .setNegativeButton("取消提取") { _, _ ->
                    outFile.delete()
                    finishWith(null)
                }
                .setOnCancelListener {
                    outFile.delete()
                    finishWith(null)
                }
                .create()

            // 与控制台验证通过的脚本完全一致：
            // 1. 读取内嵌状态/video直链
            // 2. 按时间倒序扫描最新加载的媒体资源及 >300KB 大体积流
            // 3. 使用不带 credentials 的同源 fetch 规避 CORS 通配符冲突并分片回传本地
            val injectScript = """
                (function() {
                    if (window.__tmInjected) return;
                    window.__tmInjected = true;
                    window.__tmDownloading = false;
                    window.__tmTriedUrls = new Set();

                    try {
                        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                    } catch(e) {}

                    function getCandidates() {
                        const candidates = [];

                        // 1. 小红书/通用页面内嵌状态数据
                        try {
                            const m = window.__INITIAL_STATE__?.note?.noteDetailMap;
                            if (m) {
                                for (const k in m) {
                                    const v = m[k]?.note?.video;
                                    if (v?.consumer?.originVideoKey) {
                                        candidates.push('https://sns-video-bd.xhscdn.com/' + v.consumer.originVideoKey);
                                    } else if (v?.media?.stream?.h264?.[0]?.masterUrl) {
                                        candidates.push(v.media.stream.h264[0].masterUrl);
                                    }
                                }
                            }
                        } catch (e) {}

                        // 2. 当前播放的 <video> 标签直链
                        document.querySelectorAll('video').forEach(v => {
                            const s = v.currentSrc || v.src;
                            if (s && s.startsWith('http') && !s.includes('uuu_265.mp4')) {
                                candidates.push(s.replace('playwm', 'play'));
                            }
                        });

                        // 3. 按加载时间倒序扫描最新的媒体流与 >300KB 大体积资源
                        try {
                            const res = performance.getEntriesByType('resource')
                                .filter(r => !/uuu_265\.mp4|\.(js|css|png|jpg|jpeg|webp|svg|gif|woff|json)/i.test(r.name))
                                .filter(r => /douyinvod|bytecdn|volccdn|snssdk|kwimgs|kwaicdn|xhscdn|mime_type=video|mime_type=audio|\.mp4|\.m4a/i.test(r.name) || (r.transferSize && r.transferSize > 300000))
                                .sort((a, b) => b.startTime - a.startTime)
                                .map(r => r.name);
                            candidates.push(...res);
                        } catch (e) {}

                        return [...new Set(candidates)];
                    }

                    async function tryFetchAndSave(url) {
                        if (window.__tmDownloading || window.__tmTriedUrls.has(url)) return;
                        window.__tmTriedUrls.add(url);

                        try {
                            const head = await fetch(url, { method: 'HEAD' });
                            if (!head.ok) return;
                            const type = head.headers.get('content-type') || '';
                            const len = parseInt(head.headers.get('content-length') || '0', 10);

                            if (len > 50 * 1024 || /video|audio|octet-stream/i.test(type)) {
                                window.__tmDownloading = true;
                                const mb = len > 0 ? (len / 1024 / 1024).toFixed(2) + 'MB' : '未知大小';
                                AndroidBridge.onStatus("已锁定真实视频流 (" + mb + ")，正在高速下载并转写...");

                                const resp = await fetch(url);
                                if (!resp.ok) throw new Error("HTTP " + resp.status);
                                const reader = resp.body.getReader();
                                while (true) {
                                    const { done, value } = await reader.read();
                                    if (done) break;
                                    let binary = '';
                                    const chunkLen = value.byteLength;
                                    for (let i = 0; i < chunkLen; i += 8192) {
                                        binary += String.fromCharCode.apply(null, value.subarray(i, Math.min(i + 8192, chunkLen)));
                                    }
                                    AndroidBridge.onChunk(btoa(binary));
                                }
                                AndroidBridge.onDownloadComplete();
                            }
                        } catch (err) {
                            window.__tmDownloading = false;
                            AndroidBridge.onDownloadError(err.message || "fetch error");
                        }
                    }

                    setInterval(async () => {
                        if (window.__tmDownloading) return;
                        const list = getCandidates();
                        for (const u of list) {
                            if (window.__tmDownloading) break;
                            await tryFetchAndSave(u);
                        }
                    }, 1000);
                })();
            """.trimIndent()

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(injectScript, null)
                }
            }

            cont.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post {
                    outFile.delete()
                    finishWith(null)
                }
            }

            dialog.show()
            webView.loadUrl(targetUrl)
        }
    }
}
