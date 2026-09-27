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
 * 小红书适配器（纯自研，已在原小程序版用真实笔记验证通过）
 *
 * 两个决定性要点（缺一不可）：
 *  1) **必须用移动端 UA**：PC UA 会被 302 到 /login，拿不到数据
 *  2) 数据在 `__INITIAL_STATE__.noteData.data.noteData`（不是网上流传的 note.noteDetailMap）
 *
 * 无水印原图（像素级实测）：
 *  - `...!h5_1080jpg`          1080×2348 JPEG（CDN 压缩变体）
 *  - 去掉 `!` 后缀             403（不可用）
 *  - `sns-img-qc.xhscdn.com/{fileId}`   1206×2622 PNG ← **无损原图**
 *  - `ci.xiaohongshu.com/{fileId}`      等价备用
 */
object XhsParser {

    private const val REFERER = "https://www.xiaohongshu.com/"
    private const val ORIGIN_HOST = "https://sns-img-qc.xhscdn.com/"
    private const val ORIGIN_HOST_ALT = "https://ci.xiaohongshu.com/"
    private const val VIDEO_HOST = "https://sns-video-qc.xhscdn.com/"

    private val LINK_RE = Regex(
        """https?://(?:www\.)?(?:xiaohongshu\.com/[^\s"'<>，。、）]+|xhslink\.com/[^\s"'<>，。、）]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val NOTE_ID_RE = Regex("/(?:explore|discovery/item)/([0-9a-fA-F]{24})")

    fun parse(rawUrl: String, cookie: String): ParsedMedia {
        val steps = mutableListOf<String>()
        var url = LINK_RE.find(rawUrl)?.value ?: rawUrl.trim()

        if (Regex("xhslink\\.com", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            url = Http.resolveRedirect(url, ua = Http.MOBILE_UA, referer = REFERER)
            steps += "短链还原 → ${url.take(120)}"
        }

        val noteId = NOTE_ID_RE.find(url)?.groupValues?.get(1)
            ?: throw ParseException("未能识别小红书笔记 ID，请使用 App 内「分享 → 复制链接」得到的链接")

        // 关键：必须移动端 UA
        val res = Http.getText(url, ua = Http.MOBILE_UA, referer = REFERER, cookie = cookie)
        steps += "页面 HTTP ${res.code}，长度 ${res.body.length}"

        if (res.finalUrl.contains("/login?redirectPath=")) {
            throw ParseException("小红书判定为桌面端并要求登录。请用 App 内「分享 → 复制链接」重新获取链接。")
        }
        if (res.finalUrl.contains("/404/sec_") || res.body.take(3000).contains("/404/sec_")) {
            throw ParseException("小红书安全令牌已失效。请在 App 内重新「分享 → 复制链接」。")
        }

        val stateText = extractInitialState(res.body)
            ?: throw ParseException("页面中未找到 __INITIAL_STATE__，小红书结构可能已变更")

        val state = try {
            JSONObject(stateText)
        } catch (e: Exception) {
            throw ParseException("__INITIAL_STATE__ 解析失败：${e.message?.take(80)}")
        }

        val note = pickNote(state)
            ?: throw ParseException("未取到笔记数据（可能需登录或笔记已删除）。noteId=$noteId")

        steps += "noteId=$noteId，图片 ${note.optJSONArray("imageList")?.length() ?: 0} 张"

        val images = mutableListOf<MediaItem>()
        note.optJSONArray("imageList")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { images += buildImage(it) }
            }
        }

        val video = buildVideo(note.optJSONObject("video"))

        val user = note.optJSONObject("user")
        val interact = note.optJSONObject("interactInfo")
        val title = note.optString("title")
        val desc = note.optString("desc")

        return ParsedMedia(
            platform = Platform.XHS,
            type = when {
                video != null && images.isNotEmpty() -> "mixed"
                video != null -> "video"
                else -> "images"
            },
            title = title,
            desc = listOf(title, desc).filter { it.isNotBlank() }.joinToString("\n"),
            author = AuthorInfo(
                name = user?.optString("nickName") ?: user?.optString("nickname") ?: "",
                avatar = user?.optString("avatar") ?: "",
                id = user?.optString("userId") ?: "",
            ),
            cover = images.firstOrNull()?.previewUrl?.takeIf { it.isNotBlank() }
                ?: images.firstOrNull()?.url ?: "",
            durationMs = if (video != null) video.durationMs else 0L,
            statsText = buildStatsText(interact),
            images = images,
            video = video,
            sourceUrl = rawUrl,
            channel = "native",
            debug = steps,
        )
    }

    /** 该段是 JS 字面量而非严格 JSON，含 undefined，需先替换为 null */
    private fun extractInitialState(html: String): String? {
        val marker = "__INITIAL_STATE__"
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val eq = html.indexOf('=', idx + marker.length)
        if (eq < 0) return null
        var end = html.indexOf("</script>", eq)
        if (end < 0) end = html.length
        var raw = html.substring(eq + 1, end).trim()
        if (raw.endsWith(";")) raw = raw.dropLast(1)
        return raw.replace(Regex("\\bundefined\\b"), "null")
    }

    /** 兼容两种前端结构：新版移动端 noteData.data.noteData / 旧版 note.noteDetailMap */
    private fun pickNote(state: JSONObject): JSONObject? {
        val direct = state.optJSONObject("noteData")
            ?.optJSONObject("data")
            ?.optJSONObject("noteData")
        if (direct != null && direct.length() > 0) return direct

        val noteRoot = state.optJSONObject("note") ?: return null
        val map = noteRoot.optJSONObject("noteDetailMap") ?: return null
        val keys = map.keys().asSequence().toList()
        val first = noteRoot.optString("firstNoteId")
        val order = if (first.isNotBlank() && map.has(first)) listOf(first) + keys.filter { it != first } else keys
        for (k in order) {
            val n = map.optJSONObject(k)?.optJSONObject("note")
            if (n != null) return n
        }
        return null
    }

    private fun buildImage(img: JSONObject): MediaItem {
        val fileId = img.optString("fileId")

        val variants = mutableListOf<Pair<String, String>>()
        img.optJSONArray("infoList")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url")
                if (u.isNotBlank()) variants += (o.optString("imageScene") to u)
            }
        }
        // 不同前端版本字段名不一致：新版 urlDefault / 老版 url / 预览 urlPre，
        // 三者都收集起来，fileId 取不到时仍有可用地址。
        listOf("urlDefault", "url", "urlPre").forEach { key ->
            val u = img.optString(key)
            if (u.isNotBlank()) variants += (key to u)
        }

        val preview = variants.firstOrNull {
            it.first.contains("PRV", true) || it.first.contains("THUMB", true) ||
                it.first.equals("urlPre", true)
        }?.second ?: variants.firstOrNull()?.second ?: ""

        val fallback = variants.firstOrNull {
            it.second.contains("h5_1080") || it.second.contains("1080")
        }?.second ?: variants.firstOrNull()?.second ?: ""

        val live = img.optJSONObject("video")
            ?.optJSONObject("media")
            ?.optJSONObject("stream")
            ?.let { pickBestStream(it) }

        return MediaItem(
            url = if (fileId.isNotBlank()) ORIGIN_HOST + fileId else fallback,
            altUrl = if (fileId.isNotBlank()) ORIGIN_HOST_ALT + fileId else "",
            fallbackUrl = fallback,
            previewUrl = preview,
            width = img.optInt("width", 0),
            height = img.optInt("height", 0),
            liveUrl = live?.optString("masterUrl") ?: "",
        )
    }

    private fun buildVideo(videoNode: JSONObject?): VideoInfo? {
        if (videoNode == null) return null

        val media = videoNode.optJSONObject("media")
        val stream = media?.optJSONObject("stream")
        val best = stream?.let { pickBestStream(it) }
        if (best != null) {
            val backups = mutableListOf<String>()
            best.optJSONArray("backupUrls")?.let { arr ->
                for (i in 0 until arr.length()) backups += arr.optString(i)
            }
            return VideoInfo(
                url = best.optString("masterUrl"),
                backupUrls = backups,
                qualityLabel = best.optString("qualityType").ifBlank { best.optString("videoCodec") },
                width = best.optInt("width", 0),
                height = best.optInt("height", 0),
                size = best.optLong("size", 0L),
                durationMs = best.optLong("duration", 0L),
                referer = REFERER,
            )
        }

        val key = videoNode.optJSONObject("consumer")?.optString("originVideoKey")
        if (!key.isNullOrBlank()) {
            return VideoInfo(
                url = VIDEO_HOST + key,
                qualityLabel = "原始画质",
                durationMs = videoNode.optJSONObject("capa")?.optLong("duration", 0L) ?: 0L,
                referer = REFERER,
            )
        }
        return null
    }

    /** 从 stream.{h264,h265,av1}[] 里挑体积最大的一档 */
    private fun pickBestStream(stream: JSONObject): JSONObject? {
        val all = mutableListOf<JSONObject>()
        listOf("h264", "h265", "av1").forEach { codec ->
            stream.optJSONArray(codec)?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { all += it }
            }
        }
        return all.maxByOrNull { it.optLong("size", 0L) }
    }

    private fun buildStatsText(interact: JSONObject?): String {
        if (interact == null) return ""
        val parts = mutableListOf<String>()
        listOf(
            "likedCount" to "赞",
            "commentCount" to "评",
            "collectedCount" to "藏",
            "shareCount" to "转",
        ).forEach { (key, label) ->
            val v = interact.optString(key)
            if (v.isNotBlank() && v != "0") parts += "$label $v"
        }
        return parts.joinToString(" · ")
    }
}
