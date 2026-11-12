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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.coroutines.resume

object ExtractLogger {
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun clear() {
        logs.clear()
    }

    fun log(tag: String, msg: String) {
        val line = "[${fmt.format(Date())}][$tag] $msg"
        logs.add(line)
        if (logs.size > 200) logs.removeAt(0)
    }

    fun dump(): String {
        return if (logs.isEmpty()) "暂无日志记录" else logs.joinToString("\n")
    }
}

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
        ExtractLogger.log("Article", "开始请求文章 URL: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .build()
        val html = client.newCall(request).execute().body?.string() ?: return "网页内容获取失败"
        val article = Readability4J(url, html).parse()
        val title = article.title ?: ""
        val content = article.textContent?.trim() ?: ""
        ExtractLogger.log("Article", "文章提取完成，字数: ${content.length}")
        return if (title.isNotEmpty()) "【$title】\n\n$content" else content
    }

    suspend fun downloadVideoFromLink(context: Context, shareText: String): File? {
        ExtractLogger.clear()
        ExtractLogger.log("Link", "原始输入文本: ${shareText.take(80)}")
        val targetUrl = extractUrl(shareText)
        if (targetUrl == null) {
            ExtractLogger.log("Link", "错误：未能从输入中解析出有效 http 链接")
            return null
        }
        ExtractLogger.log("Link", "提取出目标 URL: $targetUrl")

        if (targetUrl.contains("weixin.qq.com") || targetUrl.contains("channels")) {
            ExtractLogger.log("Link", "识别为微信视频号链接，外部无法加载")
            return null
        }
        return extractVideoWithWebView(context, targetUrl)
    }

    private fun isValidMediaFile(file: File): Boolean {
        if (!file.exists() || file.length() < 80 * 1024) return false
        return try {
            val header = ByteArray(32)
            val read = file.inputStream().use { it.read(header) }
            if (read < 8) return false
            val headStr = String(header, 0, read, Charsets.ISO_8859_1).trimStart()
            if (headStr.startsWith("<") || headStr.startsWith("{") || headStr.contains("html", ignoreCase = true)) {
                ExtractLogger.log("Verify", "拦截到伪装成媒体的 HTML/JSON 错误响应")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
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
                setPadding(8, 12, 8, 8)
            }

            val tipText = TextView(context).apply {
                text = "已开启超广角桌面视野（双指可自由放大缩小），如遇登录/滑块请直接操作"
                textSize = 12f
                setPadding(12, 0, 12, 10)
            }

            val webView = WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (context.resources.displayMetrics.heightPixels * 0.80).toInt()
                )
                setInitialScale(42)
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
                fun onLog(msg: String) {
                    ExtractLogger.log("WebViewJS", msg)
                }

                @JavascriptInterface
                fun onStatus(msg: String) {
                    ExtractLogger.log("Status", msg)
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
                        ExtractLogger.log("ChunkErr", e.message ?: "decode error")
                    }
                }

                @JavascriptInterface
                fun onDownloadComplete() {
                    Handler(Looper.getMainLooper()).post {
                        try { fileStream?.flush(); fileStream?.close() } catch (_: Exception) {}
                        fileStream = null
                        val size = if (currentFile.exists()) currentFile.length() else 0L
                        ExtractLogger.log("Download", "分片写入结束，文件实际大小: ${size} bytes (${size / 1024} KB)")
                        if (isValidMediaFile(currentFile)) {
                            ExtractLogger.log("Download", "文件头与体积校验通过 (>80KB)，进入音轨提取")
                            finishWith(currentFile)
                        } else {
                            ExtractLogger.log("Download", "文件过小 (<80KB 初始化头) 或非媒体文件，已丢弃并继续扫描")
                            currentFile.delete()
                        }
                    }
                }

                @JavascriptInterface
                fun onDownloadError(err: String) {
                    try { fileStream?.close() } catch (_: Exception) {}
                    fileStream = null
                    currentFile.delete()
                    ExtractLogger.log("DownloadErr", err)
                    Handler(Looper.getMainLooper()).post {
                        tipText.text = "正在切换下一条媒体流 ($err)..."
                    }
                }
            }, "AndroidBridge")

            dialog = AlertDialog.Builder(context)
                .setTitle("音视频提取窗口")
                .setView(container)
                .setNegativeButton("取消提取") { _, _ ->
                    ExtractLogger.log("User", "用户手动取消了提取窗口")
                    currentFile.delete()
                    finishWith(null)
                }
                .setOnCancelListener {
                    ExtractLogger.log("User", "用户关闭了提取窗口")
                    currentFile.delete()
                    finishWith(null)
                }
                .create()

            val injectScript = """
                (function() {
                    if (window.__tmInjected) return;
                    window.__tmInjected = true;
                    window.__tmDownloading = false;
                    window.__tmTriedUrls = new Set();
                    window.__lastScanCount = -1;
                    window.__dashWaitTicks = 0;

                    try {
                        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                    } catch(e) {}

                    function unlockViewportZoom() {
                        try {
                            let meta = document.querySelector('meta[name="viewport"]');
                            if (!meta) {
                                meta = document.createElement('meta');
                                meta.name = 'viewport';
                                document.head.appendChild(meta);
                            }
                            meta.setAttribute('content', 'width=device-width, initial-scale=0.42, minimum-scale=0.15, maximum-scale=5.0, user-scalable=yes');
                        } catch (e) {}
                    }
                    unlockViewportZoom();

                    function forceActivatePlayer() {
                        try {
                            document.querySelectorAll('video').forEach(v => {
                                if (v.paused) {
                                    v.muted = true;
                                    v.play().then(() => { v.muted = false; }).catch(() => {});
                                }
                            });
                            const playBtn = document.querySelector('.xgplayer-start, .play-btn, [class*="play-icon"], [aria-label="播放"]');
                            if (playBtn && !window.__clickedPlay) {
                                window.__clickedPlay = true;
                                playBtn.click();
                            }
                        } catch (e) {}
                    }

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

                        const merged = [...new Set([...audioList, ...videoList])];
                        if (merged.length !== window.__lastScanCount) {
                            window.__lastScanCount = merged.length;
                            AndroidBridge.onLog("候选流更新: 总数=" + merged.length + " (独立音频=" + audioList.length + ", 视频=" + videoList.length + ")");
                        }
                        return { audioList: [...new Set(audioList)], videoList: [...new Set(videoList)] };
                    }

                    async function tryFetchAndSave(url, isExplicitAudio) {
                        if (window.__tmDownloading || window.__tmTriedUrls.has(url)) return;
                        window.__tmTriedUrls.add(url);

                        try {
                            const head = await fetch(url, { method: 'HEAD' });
                            const type = head.headers.get('content-type') || '';
                            const len = parseInt(head.headers.get('content-length') || '0', 10);
                            AndroidBridge.onLog("探测 [" + head.status + "] type=" + type + " len=" + len + " url=" + url.slice(0, 65));

                            if (!head.ok) return;
                            if (/text\/html|application\/json/i.test(type)) return;

                            if (len > 0 && len < 80 * 1024) {
                                AndroidBridge.onLog("跳过小分片 (<80KB): " + len + " bytes");
                                return;
                            }

                            if (len >= 80 * 1024 || /video|audio|octet-stream/i.test(type)) {
                                window.__tmDownloading = true;
                                AndroidBridge.onStartNewFile();
                                const isAudio = isExplicitAudio || /audio/i.test(type);
                                const label = isAudio ? "人声音频流" : "完整音视频流";
                                const kb = len > 0 ? (len / 1024).toFixed(0) + 'KB' : '未知大小';
                                AndroidBridge.onStatus("已锁定" + label + " (" + kb + ")，正在下载...");

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
                            AndroidBridge.onDownloadError((err.message || "fetch error") + " | " + url.slice(0, 50));
                        }
                    }

                    setInterval(async () => {
                        unlockViewportZoom();
                        forceActivatePlayer();
                        if (window.__tmDownloading) return;

                        const { audioList, videoList } = getCandidates();

                        if (audioList.length > 0) {
                            for (const u of audioList) {
                                if (window.__tmDownloading) break;
                                await tryFetchAndSave(u, true);
                            }
                            return;
                        }

                        if (videoList.length > 0) {
                            window.__dashWaitTicks++;
                            if (window.__dashWaitTicks <= 4) {
                                AndroidBridge.onStatus("已检测到画面流，正在等待人声音轨加载 (" + window.__dashWaitTicks + "/4)...");
                                return;
                            }
                            for (const u of videoList) {
                                if (window.__tmDownloading) break;
                                await tryFetchAndSave(u, false);
                            }
                        }
                    }, 900);
                })();
            """.trimIndent()

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    ExtractLogger.log("WebView", "开始加载页面: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    ExtractLogger.log("WebView", "页面加载完成: $url")
                    view?.evaluateJavascript(injectScript, null)
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        ExtractLogger.log("WebViewErr", "主页面加载报错: ${error?.description}")
                    }
                }
            }

            cont.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post {
                    currentFile.delete()
                    finishWith(null)
                }
            }

            dialog.show()
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            webView.loadUrl(targetUrl)
        }
    }
}
