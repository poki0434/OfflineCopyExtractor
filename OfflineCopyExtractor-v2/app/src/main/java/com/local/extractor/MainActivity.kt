private fun processVideoLink(rawText: String) {
        if (rawText.contains("weixin.qq.com") || rawText.contains("channels")) {
            onExtractSuccess(
                "链接转文字",
                "提示：微信视频号受微信封闭生态加密保护，外部无法直接抓取直链。\n\n操作建议：在微信内将视频保存至手机相册后，点击首页「视频转文字」即可秒级离线出字。"
            )
            return
        }
        val loading = showLoading("正在模拟桌面浏览器解析并下载视频...")
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
                "未能抓取出有效视频流（可能触发了平台滑块验证），建议先保存到相册再使用「视频转文字」。"
            }
            withContext(Dispatchers.Main) {
                loading.dismiss()
                onExtractSuccess("链接转文字", result)
            }
        }
    }
