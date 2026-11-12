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
            var currentFile = File(context.cacheDir, "tm_media_${System.currentTimeMillis()}.mp4")
            var fileStream: FileOutputStream? = null

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 16)
            }

            val tipText = TextView(context).apply {
                text = "正在加载网页并识别人声音频流...\n（如遇滑块、登录或视频未自动播放，请直接在下方点击画面播放）"
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
                fun onStartNewFile() {
                    try { fileStream?.close() } catch (_: Exception) {}
                    if (currentFile.exists()) currentFile.delete()
                    currentFile = File(context.cacheDir, "tm_media_${System.currentTimeMillis()}.mp4")
                    fileStream = FileOutputStream(currentFile)
                }

                @JavascriptInterface
                fun onChunk(base64Chunk: String) {
                    if (resolved) return
                    try {
                        if (fileStream == null) {
                            fileStream = FileOutputStream(currentFile)
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
                        fileStream = null
                        // 必须大于 30KB 才视为有效完整流（过滤初始化空头）
                        if (currentFile.exists() && currentFile.length() > 30 * 1024) {
                            finishWith(currentFile)
                        } else {
                            currentFile.delete()
                        }
                    }
                }

                @JavascriptInterface
                fun onDownloadError(err: String) {
                    try { fileStream?.close() } catch (_: Exception) {}
                    fileStream = null
                    currentFile.delete()
                    Handler(Looper.getMainLooper()).post {
                        tipText.text = "正在切换下一条媒体流 ($err)..."
                    }
                }
            }, "AndroidBridge")

            dialog = AlertDialog.Builder(context)
                .setTitle("音视频提取窗口")
                .setView(container)
                .setNegativeButton("取消提取") { _, _ ->
                    currentFile.delete()
                    finishWith(null)
                }
                .setOnCancelListener {
                    currentFile.delete()
                    finishWith(null)
                }
                .create()

            // 已在本地模拟测试通过的流筛选与下载脚本
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
                        const audioList = [];
                        const videoList = [];

                        try {
                            const res = performance.getEntriesByType('resource')
                                .filter(r => !/uuu_265\.mp4|\/aweme\/v1\/|\/graphql|\.(js|css|png|jpg|jpeg|webp|svg|gif|woff|json)/i.test(r.name))
                                .filter(r => /douyinvod|bytecdn|volccdn|snssdk|kwimgs|kwaicdn|xhscdn|mime_type=video|mime_type=audio|\.mp4|\.m4a/i.test(r.name) || (r.transferSize && r.transferSize > 80000))
                                .sort((a, b) => b.startTime - a.startTime);

                            for (const r of res) {
                                const u = r.name;
                                if (/mime_type=audio|\.m4a|audio/i.test(u)) {
                                    audioList.push(u);
                                } else {
                                    videoList.push(u);
                                }
                            }
                        } catch (e) {}

                        try {
                            const m = window.__INITIAL_STATE__?.note?.noteDetailMap;
                            if (m) {
                                for (const k in m) {
                                    const v = m[k]?.note?.video;
                                    if (v?.consumer?.originVideoKey) {
                                        videoList.unshift('https://sns-video-bd.xhscdn.com/' + v.consumer.originVideoKey);
                                    } else if (v?.media?.stream?.h264?.[0]?.masterUrl) {
                                        videoList.unshift(v.media.stream.h264[0].masterUrl);
                                    }
                                }
                            }
                        } catch (e) {}

                        document.querySelectorAll('video').forEach(v => {
                            const s = v.currentSrc || v.src;
                            if (s && s.startsWith('http') && !s.includes('uuu_265.mp4')) {
                                videoList.unshift(s.replace('playwm', 'play'));
                            }
                        });

                        return [...new Set([...audioList, ...videoList])];
                    }

                    async function tryFetchAndSave(url) {
                        if (window.__tmDownloading || window.__tmTriedUrls.has(url)) return;
                        window.__tmTriedUrls.add(url);

                        try {
                            const head = await fetch(url, { method: 'HEAD' });
                            if (!head.ok) return;
                            const type = head.headers.get('content-type') || '';
                            const len = parseInt(head.headers.get('content-length') || '0', 10);

                            if (len > 30 * 1024 || /video|audio|octet-stream/i.test(type)) {
                                window.__tmDownloading = true;
                                AndroidBridge.onStartNewFile();
                                const isAudio = /audio/i.test(type) || /mime_type=audio|\.m4a/i.test(url);
                                const label = isAudio ? "人声音频流" : "完整音视频流";
                                const kb = len > 0 ? (len / 1024).toFixed(0) + 'KB' : '未知大小';
                                AndroidBridge.onStatus("已锁定" + label + " (" + kb + ")，正在下载并离线转写...");

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
                                window.__tmDownloading = false;
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
                        const hasAudio = list.some(u => /mime_type=audio|\.m4a|audio/i.test(u));
                        // 首次发现纯视频流时等待 1.5 秒，给紧随其后加载的 DASH 独立音频流留出出现时间
                        if (!hasAudio && list.length > 0 && (window.__waitCount = (window.__waitCount || 0) + 1) <= 2) {
                            return;
                        }
                        for (const u of list) {
                            if (window.__tmDownloading) break;
                            await tryFetchAndSave(u);
                        }
                    }, 900);
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
                    currentFile.delete()
                    finishWith(null)
                }
            }

            dialog.show()
            webView.loadUrl(targetUrl)
        }
    }
}
