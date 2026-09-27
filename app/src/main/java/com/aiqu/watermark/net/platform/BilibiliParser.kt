package com.aiqu.watermark.net.platform

import com.aiqu.watermark.data.model.AuthorInfo
import com.aiqu.watermark.data.model.MediaItem
import com.aiqu.watermark.data.model.ParseException
import com.aiqu.watermark.data.model.ParsedMedia
import com.aiqu.watermark.data.model.Platform
import com.aiqu.watermark.data.model.VideoInfo
import com.aiqu.watermark.net.Http
import org.json.JSONObject

/**
 * 哔哩哔哩适配器（纯自研，已在原小程序版用真实视频验证通过）
 *
 * 要点：
 *  - 免登录：platform=html5&high_quality=1 取最高 720P 的 mp4 整合流（durl）
 *  - 带 SESSDATA：qn=80&platform=pc 取 1080P 整合流
 *  - 媒体直链必须带 Referer: https://www.bilibili.com/，否则 403
 *  - 4K/1080P+ 为 DASH 音视频分离流，需 ffmpeg 合流，本应用不做
 */
object BilibiliParser {

    private const val REFERER = "https://www.bilibili.com/"

    private val QUALITY_LABEL = mapOf(
        127 to "8K", 126 to "杜比视界", 125 to "HDR", 120 to "4K",
        116 to "1080P60", 112 to "1080P+", 80 to "1080P", 74 to "720P60",
        64 to "720P", 32 to "480P", 16 to "360P", 6 to "240P",
    )

    fun parse(rawUrl: String, cookie: String): ParsedMedia {
        val steps = mutableListOf<String>()
        var url = rawUrl

        if (Regex("b23\\.tv|bili2233\\.cn", RegexOption.IGNORE_CASE).containsMatchIn(rawUrl)) {
            url = Http.resolveRedirect(rawUrl, ua = Http.PC_UA, referer = REFERER)
            steps += "短链还原 → ${url.take(120)}"
        }

        val bv = Regex("BV[0-9A-Za-z]{10}").find(url)?.value
        val viewUrl = if (bv != null) {
            "https://api.bilibili.com/x/web-interface/view?bvid=$bv"
        } else {
            val av = Regex("/video/av(\\d+)").find(url)?.groupValues?.get(1)
                ?: Regex("\\bav(\\d{4,})\\b").find(url)?.groupValues?.get(1)
                ?: throw ParseException("未能识别 B 站视频号（BV/av）：${url.take(100)}")
            "https://api.bilibili.com/x/web-interface/view?aid=$av"
        }

        val view = Http.getJson(viewUrl, referer = REFERER, cookie = cookie)
        val viewCode = view.optInt("code", -1)
        if (viewCode != 0) {
            throw ParseException("B站接口返回 code=$viewCode ${view.optString("message")}")
        }
        val data = view.optJSONObject("data")
            ?: throw ParseException("B站接口未返回 data")

        val bvid = data.optString("bvid")
        val pages = data.optJSONArray("pages")
        val cid = pages?.optJSONObject(0)?.optLong("cid", 0L) ?: data.optLong("cid", 0L)
        if (cid == 0L) throw ParseException("未能获取 cid")

        val loggedIn = cookie.contains("SESSDATA=")
        data class Candidate(val qn: Int, val platform: String, val ua: String)
        val candidates = buildList {
            if (loggedIn) {
                add(Candidate(80, "pc", Http.PC_UA))
                add(Candidate(116, "pc", Http.PC_UA))
            }
            add(Candidate(64, "html5", Http.MOBILE_UA))
            add(Candidate(32, "html5", Http.MOBILE_UA))
        }

        var bestQuality = -1
        var bestData: JSONObject? = null
        for (c in candidates) {
            val playUrl = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid" +
                "&qn=${c.qn}&fnval=0&fnver=0&fourk=1&platform=${c.platform}&high_quality=1"
            try {
                val r = Http.getJson(playUrl, ua = c.ua, referer = REFERER, cookie = cookie)
                val durl = r.optJSONObject("data")?.optJSONArray("durl")
                val ok = r.optInt("code", -1) == 0 && durl != null && durl.length() > 0
                if (ok) {
                    val q = r.optJSONObject("data")?.optInt("quality", 0) ?: 0
                    steps += "playurl qn=${c.qn} → quality=$q"
                    if (q > bestQuality) {
                        bestQuality = q
                        bestData = r.optJSONObject("data")
                    }
                } else {
                    steps += "playurl qn=${c.qn} 不可用"
                }
            } catch (e: Exception) {
                steps += "playurl qn=${c.qn} 异常：${e.message?.take(60)}"
            }
        }

        val playData = bestData ?: throw ParseException("B站未返回可用播放流。请稍后重试。")
        val durl = playData.optJSONArray("durl") ?: throw ParseException("B站播放流结构异常")
        val first = durl.optJSONObject(0) ?: throw ParseException("B站播放流为空")
        val videoUrl = first.optString("url")
        if (videoUrl.isBlank()) throw ParseException("B站播放地址为空")

        val backup = mutableListOf<String>()
        first.optJSONArray("backup_url")?.let { arr ->
            for (i in 0 until arr.length()) backup += arr.optString(i)
        }

        val dimension = data.optJSONObject("dimension")
        val owner = data.optJSONObject("owner")
        val stat = data.optJSONObject("stat")

        return ParsedMedia(
            platform = Platform.BILIBILI,
            type = "video",
            title = data.optString("title"),
            desc = listOf(data.optString("title"), data.optString("desc"))
                .filter { it.isNotBlank() }.joinToString("\n"),
            author = AuthorInfo(
                name = owner?.optString("name") ?: "",
                avatar = owner?.optString("face") ?: "",
                id = owner?.optLong("mid", 0L)?.toString() ?: "",
            ),
            cover = data.optString("pic"),
            durationMs = data.optLong("duration", 0L) * 1000L,
            statsText = buildStatsText(stat),
            images = emptyList(),
            video = VideoInfo(
                url = videoUrl,
                backupUrls = backup,
                qualityLabel = QUALITY_LABEL[bestQuality] ?: bestQuality.toString(),
                width = dimension?.optInt("width", 0) ?: 0,
                height = dimension?.optInt("height", 0) ?: 0,
                size = first.optLong("size", 0L),
                durationMs = first.optLong("length", 0L),
                referer = REFERER,
            ),
            sourceUrl = rawUrl,
            channel = "native",
            debug = steps,
        )
    }

    private fun buildStatsText(stat: JSONObject?): String {
        if (stat == null) return ""
        val parts = mutableListOf<String>()
        val view = stat.optLong("view", 0L)
        val like = stat.optLong("like", 0L)
        val danmaku = stat.optLong("danmaku", 0L)
        if (view > 0) parts += "播放 ${wan(view)}"
        if (like > 0) parts += "点赞 ${wan(like)}"
        if (danmaku > 0) parts += "弹幕 ${wan(danmaku)}"
        return parts.joinToString(" · ")
    }

    private fun wan(n: Long): String =
        if (n >= 10000) String.format("%.1f万", n / 10000.0) else n.toString()
}
