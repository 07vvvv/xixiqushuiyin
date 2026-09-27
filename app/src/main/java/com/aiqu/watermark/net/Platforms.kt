package com.aiqu.watermark.net

import com.aiqu.watermark.data.model.Platform

/** 平台识别与通用地址工具 */
object Platforms {

    /**
     * 从整段分享文案里抽出第一个链接。
     *
     * 字符类用 `[!-~]`（可打印 ASCII，0x21–0x7E）而不是「排除空白与引号」：
     * 分享文案常把链接紧贴汉字或表情（如 `http://xhslink.com/a/xxxx复制本条信息`、
     * `...xxxx😆`），只排除空白会把后面的汉字/表情一起吃进 URL。
     * URL 本身就是 ASCII（中文会被百分号编码），因此只收 ASCII 既简单又不会误伤。
     *
     * 注意：这里**不做** http→https 升级。是否升级由各平台适配器决定 ——
     * 例如小红书短链必须升级（明文会被 Android 拦），
     * 而抖音播放地址反而是 https 优先、原始 http 作备用。
     */
    private val URL_RE = Regex("""https?://[!-~]+""", RegexOption.IGNORE_CASE)
    private val BV_RE = Regex("BV[0-9A-Za-z]{10}")

    /** 从整段分享文案里抽出第一个链接 */
    fun extractUrl(text: String): String {
        val m = URL_RE.find(text)
        return m?.value ?: text.trim()
    }

    fun detect(text: String): Platform {
        val url = extractUrl(text)
        return when {
            Regex("douyin\\.com|iesdouyin\\.com", RegexOption.IGNORE_CASE).containsMatchIn(url) -> Platform.DOUYIN
            Regex("xiaohongshu\\.com|xhslink\\.com", RegexOption.IGNORE_CASE).containsMatchIn(url) -> Platform.XHS
            Regex("bilibili\\.com|b23\\.tv|bili2233\\.cn", RegexOption.IGNORE_CASE).containsMatchIn(url) -> Platform.BILIBILI
            else -> Platform.UNKNOWN
        }
    }

    /** 小程序 image 组件与 Android 都更偏好 https；部分平台返回 http://，这里统一升级 */
    fun toHttps(url: String?): String =
        (url ?: "").replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://")

    /**
     * 拒绝「形似而残缺」的地址。
     *
     * 必要性来自实战：部分第三方接口解析失败也返回 HTTP 200，只把地址拼成
     * `https://www.douyin.com/aweme/v1/play/?video_id=`（参数为空）。
     * 这类残串含平台关键字，不校验会被误判为「解析成功」，最终在保存时才报错。
     */
    private val ID_PARAMS = listOf("video_id", "vid", "aweme_id", "item_id", "id", "uri")
    private val EMPTY_ID_RE = ID_PARAMS.map { Regex("[?&]$it=(?=&|$)", RegexOption.IGNORE_CASE) }

    /**
     * 路径整体残缺：例如 `https://www.douyin.com/aweme/v1/play/`、`.../video/`。
     *
     * 注意这里**只允许以路径结尾或紧随一个空查询串**，不能写成 `(\?|$)`。
     * 因为合法的抖音播放地址正是 `.../play/?video_id=v0200f...&line=0` 这种形态，
     * 若允许 `?` 紧随其后，会把**有效地址一并误杀**（参数为空的场景已由 EMPTY_ID_RE 覆盖）。
     */
    private val EMPTY_PATH_RE = Regex("/(?:play|video|item|detail)/?(?:\\?)?$", RegexOption.IGNORE_CASE)

    fun looksUsable(url: String?): Boolean {
        val u = url ?: return false
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u)) return false
        if (EMPTY_ID_RE.any { it.containsMatchIn(u) }) return false
        if (EMPTY_PATH_RE.containsMatchIn(u)) return false
        return true
    }

    fun hasBv(url: String): Boolean = BV_RE.containsMatchIn(url)

    fun extractBv(url: String): String? = BV_RE.find(url)?.value
}
