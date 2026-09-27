package com.aiqu.watermark.net.platform

import com.aiqu.watermark.data.model.AuthorInfo
import com.aiqu.watermark.data.model.MediaItem
import com.aiqu.watermark.data.model.ParseException
import com.aiqu.watermark.data.model.ParsedMedia
import com.aiqu.watermark.data.model.Platform
import com.aiqu.watermark.data.model.VideoInfo
import com.aiqu.watermark.net.Http
import com.aiqu.watermark.net.Platforms
import org.json.JSONObject

/**
 * 抖音适配器
 *
 * 背景（实测结论）：自 2026-09-14 起 aweme/detail 被 Argus 浏览器级门禁拦截，
 * 要求 x-secsdk-web-signature（只能由抖音网页内 SDK 生成），且要求 TLS 指纹与 UA 匹配。
 * 实测对照：同一份 a_bogus 签名，comment/list 与 related 均 200 且返回真实数据，
 * 而 aweme/detail 一律 403 → 是**接口级门禁**，不是签名问题。
 *
 * 因此 Android 端的抖音通道为：
 *   1) **真实浏览器**（默认）：DouyinCaptureActivity 里用系统 WebView（本身就是 Chromium）
 *      打开页面，抖音自己的 SDK 生成合法请求，我们读取结果 → buildFromAwemeDetail()
 *   2) 第三方接口（可选）：若在设置里填了接口地址，则作为备用
 */
object DouyinParser {

    private const val REFERER = "https://www.douyin.com/"

    private val AWEME_PATTERNS = listOf(
        Regex("/video/(\\d{15,})"),
        Regex("item_ids=(\\d{15,})"),
        Regex("modal_id=(\\d{15,})"),
        Regex("\\b(\\d{19})\\b"),
    )

    fun resolveAwemeId(url: String): String {
        var finalUrl = url
        if (Regex("v\\.douyin\\.com|douyin\\.com/[A-Za-z0-9]{6,}/?$", RegexOption.IGNORE_CASE)
                .containsMatchIn(url)
        ) {
            finalUrl = Http.resolveRedirect(url, ua = Http.MOBILE_UA, referer = REFERER)
        }
        for (p in AWEME_PATTERNS) {
            val m = p.find(finalUrl)
            if (m != null) return m.groupValues[1]
        }
        return ""
    }

    /**
     * 从分享文案里抽取搜索关键词。
     *
     * 抖音分享文案尾部常带口令噪声（如「09/06 daa:/ :1pm j@c.NJ」），
     * 逐个正则很难穷尽 → 按 token 丢弃含 `: / @` 的片段，并优先取
     * 「】之后、第一个 # 之前」的标题段。
     */
    fun extractSearchKeyword(text: String): String {
        val noUrl = text.replace(Regex("""https?://\S+"""), " ")

        fun clean(s: String): String = s
            .replace(Regex("#[^\\s#]+#?"), " ")
            // 去掉 emoji 与装饰符号。注意：Java 的 java.util.regex **不支持** `\u{1F000}`
            // 这种大括号写法（那是 JS/ICU 语法，会抛 PatternSyntaxException），
            // 只能用 \uXXXX；而 emoji 都落在代理对区间，故用 \uD800-\uDFFF 整体覆盖。
            .replace(Regex("[\\uD800-\\uDFFF\\u2190-\\u2BFF\\uFE0F\\u2B00-\\u2BFF]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && !it.contains(":") && !it.contains("/") && !it.contains("@") }
            .joinToString(" ")
            .replace(Regex("复制打开抖音|复制此链接|打开抖音|看看|的作品|抖音|小红书|哔哩哔哩|B站"), " ")
            .replace(Regex("[^\\u4e00-\\u9fa5a-zA-Z0-9]+"), " ")
            .trim()

        val bracketEnd = noUrl.indexOf('】')
        val titleSeg = if (bracketEnd >= 0) noUrl.substring(bracketEnd + 1).substringBefore('#') else ""
        val primary = clean(titleSeg).take(30)
        return primary.ifBlank { clean(noUrl).take(30) }
    }

    /** 从分享文案里抽取作者名（用于界面展示与搜索辅助） */
    fun extractAuthor(text: String): String {
        val m = Regex("【([^】]{1,30})】").find(text) ?: return ""
        return m.groupValues[1].replace(Regex("的作品|的主页|的笔记|的视频"), "").trim()
    }

    /** 把抖音 detail 作品对象映射成统一结构（bit_rate 取最高档 = 无水印） */
    fun buildFromAwemeDetail(aweme: JSONObject, sourceUrl: String): ParsedMedia {
        val steps = mutableListOf<String>()
        val video = aweme.optJSONObject("video")

        var videoUrl = ""
        var qualityLabel = ""
        var width = 0
        var height = 0
        var size = 0L

        val bitRates = mutableListOf<JSONObject>()
        video?.optJSONArray("bit_rate")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { bitRates += it }
        }
        bitRates.sortByDescending { it.optInt("bit_rate", 0) }

        if (bitRates.isNotEmpty()) {
            val top = bitRates.first()
            val addr = top.optJSONObject("play_addr")
            videoUrl = firstUrl(addr) ?: ""
            width = addr?.optInt("width", 0) ?: 0
            height = addr?.optInt("height", 0) ?: 0
            size = addr?.optLong("data_size", 0L) ?: 0L
            qualityLabel = "最高码率档（${top.optInt("bit_rate", 0)}）"
            steps += "bit_rate 档位 ${bitRates.size} 个，取最高码率"
        }
        if (videoUrl.isBlank()) {
            val addr = video?.optJSONObject("play_addr")
            videoUrl = firstUrl(addr) ?: ""
            width = addr?.optInt("width", 0) ?: width
            height = addr?.optInt("height", 0) ?: height
            size = addr?.optLong("data_size", 0L) ?: size
            if (videoUrl.isNotBlank()) steps += "回退到 play_addr"
        }
        if (videoUrl.isBlank()) {
            val addr = video?.optJSONObject("download_addr")
            videoUrl = firstUrl(addr) ?: ""
            if (videoUrl.isNotBlank()) {
                qualityLabel = "仅取得带水印版本"
                steps += "回退到 download_addr（带水印）"
            }
        }

        val images = mutableListOf<MediaItem>()
        aweme.optJSONArray("images")?.let { arr ->
            for (i in 0 until arr.length()) {
                val img = arr.optJSONObject(i) ?: continue
                val url = firstUrl(img) ?: ""
                val alt = img.optJSONArray("url_list")?.optString(1) ?: ""
                val live = img.optJSONObject("video")?.let { firstUrl(it.optJSONObject("play_addr")) }
                images += MediaItem(
                    url = url,
                    altUrl = alt,
                    previewUrl = url,
                    width = img.optInt("width", 0),
                    height = img.optInt("height", 0),
                    liveUrl = live ?: "",
                )
            }
        }

        val author = aweme.optJSONObject("author")
        val stats = aweme.optJSONObject("statistics")
        val music = aweme.optJSONObject("music")
        val desc = aweme.optString("desc")

        return ParsedMedia(
            platform = Platform.DOUYIN,
            type = when {
                videoUrl.isNotBlank() && images.isNotEmpty() -> "mixed"
                videoUrl.isNotBlank() -> "video"
                else -> "images"
            },
            title = desc,
            desc = desc,
            author = AuthorInfo(
                name = author?.optString("nickname") ?: author?.optString("nickName") ?: "",
                avatar = firstUrl(author?.optJSONObject("avatar_thumb")) ?: "",
                id = author?.optString("sec_uid") ?: "",
            ),
            cover = firstUrl(video?.optJSONObject("cover"))
                ?: firstUrl(video?.optJSONObject("origin_cover")) ?: "",
            durationMs = video?.optLong("duration", 0L) ?: 0L,
            statsText = buildStatsText(stats),
            images = images,
            video = if (videoUrl.isNotBlank()) {
                VideoInfo(
                    url = videoUrl,
                    qualityLabel = qualityLabel,
                    width = width,
                    height = height,
                    size = size,
                    durationMs = video?.optLong("duration", 0L) ?: 0L,
                    referer = REFERER,
                )
            } else null,
            sourceUrl = sourceUrl,
            channel = "browser",
            debug = steps,
        )
    }

    /**
     * 备用通道：第三方接口。
     * 响应字段名各家不同，故「已知字段优先 + 深度扫描兜底」。
     */
    fun parseViaProvider(rawUrl: String, providerUrl: String, token: String): ParsedMedia {
        if (providerUrl.isBlank()) throw ParseException("未配置抖音第三方接口地址")

        val steps = mutableListOf<String>()
        val headers = mutableMapOf<String, String>()
        if (token.isNotBlank()) headers["Authorization"] = "Bearer $token"
        val body = JSONObject().put("url", rawUrl).toString()

        val res = Http.postJson(providerUrl, body, extraHeaders = headers)
        val json = try {
            JSONObject(res.body)
        } catch (e: Exception) {
            throw ParseException("第三方接口返回非 JSON（HTTP ${res.code}）：${res.body.take(120)}")
        }

        val scan = DeepScan()
        scan.walk(json, 0)

        var videoUrl = ""
        listOf("playAddr", "play_addr", "video_url", "videoUrl", "video_Url", "url", "download_url", "mp4")
            .forEach { key ->
                if (videoUrl.isBlank()) {
                    val v = json.optString(key)
                    if (Platforms.looksUsable(v)) videoUrl = v
                }
            }
        if (videoUrl.isBlank()) {
            videoUrl = scan.videos.firstOrNull { Platforms.looksUsable(it) } ?: ""
        }

        val pics = mutableListOf<String>()
        listOf("pics", "images", "image_list", "imageList", "picList").forEach { key ->
            if (pics.isEmpty()) {
                json.optJSONArray(key)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i)
                        if (Platforms.looksUsable(s)) pics += s
                    }
                }
            }
        }
        if (pics.isEmpty()) pics += scan.images.filter { Platforms.looksUsable(it) }

        if (videoUrl.isBlank() && pics.isEmpty()) {
            val apiMsg = json.optString("message").ifBlank { json.optString("msg") }
            throw ParseException(
                "第三方接口未返回可用地址" + if (apiMsg.isNotBlank()) "。接口提示：$apiMsg" else ""
            )
        }
        steps += "第三方通道命中"

        val title = listOf("desc", "title", "text").firstNotNullOfOrNull {
            json.optString(it).takeIf { s -> s.isNotBlank() }
        } ?: ""

        return ParsedMedia(
            platform = Platform.DOUYIN,
            type = if (videoUrl.isNotBlank()) (if (pics.isNotEmpty()) "mixed" else "video") else "images",
            title = title,
            desc = title,
            cover = json.optString("cover").ifBlank { pics.firstOrNull() ?: "" },
            images = pics.map { MediaItem(url = it, previewUrl = it) },
            video = if (videoUrl.isNotBlank()) {
                VideoInfo(
                    url = videoUrl,
                    qualityLabel = "第三方接口",
                    // 抖音 CDN 有防盗链校验，缺少 Referer 会 403
                    referer = REFERER,
                )
            } else null,
            sourceUrl = rawUrl,
            channel = "provider",
            debug = steps,
        )
    }

    private fun firstUrl(node: JSONObject?): String? {
        if (node == null) return null
        val list = node.optJSONArray("url_list") ?: return node.optString("url").takeIf { it.isNotBlank() }
        for (i in 0 until list.length()) {
            val s = list.optString(i)
            if (s.isNotBlank()) return s
        }
        return null
    }

    private fun buildStatsText(stats: JSONObject?): String {
        if (stats == null) return ""
        val parts = mutableListOf<String>()
        val map = listOf(
            "digg_count" to "赞",
            "comment_count" to "评",
            "collect_count" to "藏",
            "share_count" to "转",
        )
        map.forEach { (k, label) ->
            val v = stats.optLong(k, 0L)
            if (v > 0) parts += "$label $v"
        }
        return parts.joinToString(" · ")
    }

    /** 深度扫描响应里所有像媒体地址的字符串 */
    private class DeepScan {
        val videos = mutableListOf<String>()
        val images = mutableListOf<String>()

        private val videoHint = Regex(
            """(\.mp4(\?|$)|douyinvod|aweme|ixigua|/video/tos)""",
            RegexOption.IGNORE_CASE,
        )
        private val imageHint = Regex(
            """(\.jpe?g(\?|$)|\.png(\?|$)|\.webp(\?|$)|douyinpic|byteimg)""",
            RegexOption.IGNORE_CASE,
        )

        fun walk(node: Any?, depth: Int) {
            if (node == null || depth > 8) return
            when (node) {
                is String -> if (node.startsWith("http")) {
                    if (videoHint.containsMatchIn(node)) videos += node
                    else if (imageHint.containsMatchIn(node)) images += node
                }
                is JSONObject -> node.keys().forEach { walk(node.opt(it), depth + 1) }
                is org.json.JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
            }
        }
    }
}
