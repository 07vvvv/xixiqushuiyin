package com.aiqu.watermark.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiqu.watermark.data.model.HistoryEntry
import com.aiqu.watermark.data.model.ParseException
import com.aiqu.watermark.data.model.ParsedMedia
import com.aiqu.watermark.data.model.Platform
import com.aiqu.watermark.media.MediaSaver
import com.aiqu.watermark.net.Http
import com.aiqu.watermark.net.Platforms
import com.aiqu.watermark.net.platform.BilibiliParser
import com.aiqu.watermark.net.platform.DouyinParser
import com.aiqu.watermark.net.platform.XhsParser
import com.aiqu.watermark.store.HistoryStore
import com.aiqu.watermark.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 全应用共用的状态容器
 *
 * 只用一个 ViewModel：解析 / 保存 / 历史 / 设置都要读写同一批状态，
 * 拆成多个反而要额外做跨 ViewModel 同步。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SettingsStore(app)
    private val history = HistoryStore(app)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 首页状态 ──────────────────────────────────────────
    var input by mutableStateOf("")
        private set
    var parsing by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    var errorHint by mutableStateOf("")
        private set
    var result by mutableStateOf<ParsedMedia?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var savingText by mutableStateOf("")
        private set
    var saveProgress by mutableStateOf("")
        private set
    var toastMessage by mutableStateOf("")
        private set

    /** 需要启动抖音浏览器解析时，UI 读取此值并拉起 DouyinCaptureActivity */
    var pendingDouyinUrl by mutableStateOf<String?>(null)
        private set

    /**
     * 免责声明是否已确认。
     *
     * 必须做成 Compose 状态：SharedPreferences 本身**不会**触发重组，
     * 若直接在界面上读 `settings.disclaimerAccepted`，用户点「我知道了」后
     * 只是写入了磁盘，弹窗不会消失（要等其它 state 变化才偶然消失）。
     */
    var disclaimerAccepted by mutableStateOf(settings.disclaimerAccepted)
        private set

    // ── 历史与设置状态 ────────────────────────────────────
    var historyList by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    private var douyinSourceRaw = ""
    private var parseStartedAt = 0L

    // ── 输入与解析 ────────────────────────────────────────

    fun onInput(value: String) {
        input = value
    }

    fun acceptDisclaimer() {
        disclaimerAccepted = true
        settings.disclaimerAccepted = true
    }

    fun resetDisclaimer() {
        disclaimerAccepted = false
        settings.disclaimerAccepted = false
    }

    fun detectPlatform(): Platform = Platforms.detect(input)

    fun parse() {
        val text = input.trim()
        if (text.isBlank()) {
            showToast("请先粘贴分享链接")
            return
        }
        if (parsing) return

        val platform = Platforms.detect(text)
        if (platform == Platform.UNKNOWN) {
            error = "暂不支持该链接，当前支持：抖音 / 小红书 / 哔哩哔哩"
            errorHint = "请在对应 App 内点「分享 → 复制链接」，整段分享文案也可以。"
            return
        }

        parseStartedAt = System.currentTimeMillis()

        if (platform == Platform.DOUYIN) {
            startDouyin(text)
            return
        }

        parsing = true
        error = ""
        errorHint = ""
        result = null

        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    when (platform) {
                        Platform.BILIBILI -> BilibiliParser.parse(text, settings.bilibiliCookie)
                        Platform.XHS -> XhsParser.parse(text, settings.xhsCookie)
                        else -> throw ParseException("未知平台")
                    }
                }
                onParsed(parsed, text)
            } catch (e: Exception) {
                failParse(e)
            }
        }
    }

    /**
     * 抖音：优先用已配置的第三方接口；未配置或失败则回退到真实浏览器。
     * 浏览器通道是可靠的默认方案，且零成本。
     */
    private fun startDouyin(text: String) {
        douyinSourceRaw = text
        error = ""
        errorHint = ""
        result = null

        // 抖音分享文案是一整段文字（含口令与表情），必须抽出纯链接再交给 WebView：
        // 否则 loadUrl 会把整段文字当作搜索词，页面根本不会打开这条作品。
        val shareUrl = Platforms.extractUrl(text)

        val providerUrl = settings.douyinProviderUrl
        if (providerUrl.isBlank()) {
            pendingDouyinUrl = shareUrl
            return
        }

        parsing = true
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    DouyinParser.parseViaProvider(shareUrl, providerUrl, settings.douyinProviderToken)
                }
                onParsed(parsed, text)
            } catch (e: Exception) {
                // 第三方失败不是终点：交给浏览器通道
                parsing = false
                error = "第三方接口未成功：${e.message ?: "未知错误"}"
                errorHint = "正在改用本机浏览器解析…"
                pendingDouyinUrl = shareUrl
            }
        }
    }

    fun consumePendingDouyin() {
        pendingDouyinUrl = null
    }

    /** 浏览器解析返回后调用 */
    fun onDouyinCaptured(awemeJson: String) {
        parsing = true
        error = ""
        errorHint = ""
        // 浏览器通道的耗时里含用户的浏览/点击时间，不构成性能指标，故不参与计时展示
        parseStartedAt = 0L
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) {
                    DouyinParser.buildFromAwemeDetail(JSONObject(awemeJson), douyinSourceRaw.ifBlank { input.trim() })
                }
                if (!parsed.hasVideo && !parsed.isImages) {
                    throw ParseException("浏览器已加载页面，但未取到媒体地址。可在页面上点一下视频播放后，回到解析页重试。")
                }
                onParsed(parsed, douyinSourceRaw.ifBlank { input.trim() })
            } catch (e: Exception) {
                failParse(e)
            }
        }
    }

    fun onDouyinCancelled() {
        parsing = false
        showToast("已取消抖音解析")
    }

    private fun onParsed(parsed: ParsedMedia, sourceUrl: String) {
        parsing = false
        error = ""
        errorHint = ""

        // 自研 / 接口通道是纯网络耗时，可作为性能指标；浏览器通道已在入口清零
        val elapsed = if (parseStartedAt > 0L) System.currentTimeMillis() - parseStartedAt else 0L
        parseStartedAt = 0L

        // 封面与头像统一转 https，避免明文流量被系统拦截
        val normalized = parsed.copy(
            costMs = elapsed,
            cover = Platforms.toHttps(parsed.cover),
            author = parsed.author.copy(avatar = Platforms.toHttps(parsed.author.avatar)),
            images = parsed.images.map {
                it.copy(
                    url = Platforms.toHttps(it.url),
                    altUrl = Platforms.toHttps(it.altUrl),
                    fallbackUrl = Platforms.toHttps(it.fallbackUrl),
                    previewUrl = Platforms.toHttps(it.previewUrl.ifBlank { it.fallbackUrl.ifBlank { it.url } }),
                )
            },
            // 视频地址同样优先 https，原始 http 地址降级为备用：
            // Android 9+ 默认禁止明文流量，而抖音 CDN 大量返回 http 地址。
            // 两条路都保留，避免任何一条被策略拦截时整体失败。
            video = parsed.video?.let { v ->
                val https = Platforms.toHttps(v.url)
                if (https == v.url) {
                    v
                } else {
                    v.copy(url = https, backupUrls = (listOf(v.url) + v.backupUrls).distinct())
                }
            },
        )
        result = normalized

        history.add(
            HistoryEntry(
                id = "h_${System.currentTimeMillis()}_${(0..999).random()}",
                sourceUrl = sourceUrl,
                platformKey = normalized.platform.key,
                title = normalized.title.ifBlank { "（无标题）" },
                author = normalized.author.name,
                cover = normalized.cover,
                hasVideo = normalized.hasVideo,
                imageCount = normalized.images.size,
                createdAt = System.currentTimeMillis(),
            )
        )
        refreshHistory()
    }

    private fun failParse(e: Exception) {
        parsing = false
        val msg = e.message ?: "解析失败"
        error = msg
        errorHint = hintFor(msg)
    }

    /** 把后端错误翻译成「下一步该做什么」 */
    private fun hintFor(message: String): String = when {
        message.contains("需要登录") || message.contains("登录") ->
            "可到「设置」页填写该平台的 Cookie 后重试。"
        message.contains("令牌") || message.contains("失效") || message.contains("404") ->
            "请在 App 内重新「分享 → 复制链接」，旧链接的令牌可能已过期。"
        message.contains("结构") || message.contains("未找到") || message.contains("未取到") ->
            "平台页面结构可能已变更，需要更新对应适配器。"
        message.contains("不支持") -> ""
        else -> ""
    }

    // ── 保存到相册 ────────────────────────────────────────

    fun saveVideo() {
        val r = result ?: return
        val v = r.video ?: return
        if (saving) return
        saving = true
        savingText = "下载中…"

        viewModelScope.launch {
            try {
                val path = MediaSaver.saveVideo(
                    context = getApplication<Application>(),
                    video = v,
                    baseName = baseName(r),
                    cookie = settings.cookieFor(r.platform),
                    ua = uaFor(r.platform),
                ) { written, total ->
                    val text = if (total > 0) {
                        "下载中 ${(written * 100 / total).coerceIn(0, 100)}%"
                    } else {
                        "下载中 ${written / 1024}KB"
                    }
                    mainHandler.post { savingText = text }
                }
                saving = false
                savingText = ""
                showToast("已保存到相册：$path")
            } catch (e: Exception) {
                saving = false
                savingText = ""
                error = e.message ?: "保存失败"
                errorHint = ""
            }
        }
    }

    fun saveImageAt(index: Int) {
        val r = result ?: return
        val item = r.images.getOrNull(index) ?: return
        if (saving) return
        saving = true
        savingText = "下载中…"

        viewModelScope.launch {
            try {
                MediaSaver.saveImage(
                    context = getApplication<Application>(),
                    item = item,
                    baseName = baseName(r),
                    cookie = settings.cookieFor(r.platform),
                    index = if (r.images.size > 1) index + 1 else 0,
                    referer = refererFor(r.platform),
                    ua = uaFor(r.platform),
                )
                saving = false
                savingText = ""
                showToast("已保存第 ${index + 1} 张")
            } catch (e: Exception) {
                saving = false
                savingText = ""
                error = e.message ?: "保存失败"
                errorHint = ""
            }
        }
    }

    fun saveAllImages() {
        val r = result ?: return
        if (r.images.isEmpty() || saving) return
        saving = true
        savingText = "保存中 0/${r.images.size}"
        saveProgress = ""

        viewModelScope.launch {
            try {
                val res = MediaSaver.saveImages(
                    context = getApplication<Application>(),
                    items = r.images,
                    baseName = baseName(r),
                    cookie = settings.cookieFor(r.platform),
                    referer = refererFor(r.platform),
                    ua = uaFor(r.platform),
                ) { done, total ->
                    mainHandler.post {
                        savingText = "保存中 $done/$total"
                        saveProgress = "已完成 $done/$total"
                    }
                }
                saving = false
                savingText = ""
                saveProgress = ""
                if (res.failed > 0) {
                    error = "成功 ${res.saved} 张，失败 ${res.failed} 张"
                    errorHint = res.errors.firstOrNull() ?: ""
                } else {
                    showToast("已保存 ${res.saved} 张到相册")
                }
            } catch (e: Exception) {
                saving = false
                savingText = ""
                saveProgress = ""
                error = e.message ?: "保存失败"
            }
        }
    }

    private fun baseName(r: ParsedMedia): String {
        val parts = listOf(r.author.name, r.title).filter { it.isNotBlank() }
        val base = parts.joinToString("_").ifBlank { "aq_media" }
        val safe = base.replace(Regex("\\s+"), "_").take(60)
        return "${safe}_${System.currentTimeMillis() % 1000000}"
    }

    /** 图片直链的防盗链 Referer：B站与小红书对图片来源校验 Referer，缺失会 403 */
    private fun refererFor(platform: Platform): String? = when (platform) {
        Platform.BILIBILI -> "https://www.bilibili.com/"
        Platform.XHS -> "https://www.xiaohongshu.com/"
        Platform.DOUYIN -> "https://www.douyin.com/"
        Platform.UNKNOWN -> null
    }

    /**
     * 图片下载 UA。
     *
     * 小红书对移动端 UA 更友好（PC UA 在网页侧会被判定为桌面端），
     * 抖音 CDN 同样以移动端 UA 命中率更高；B站图片无所谓，沿用 PC。
     */
    private fun uaFor(platform: Platform): String = when (platform) {
        Platform.XHS, Platform.DOUYIN -> Http.MOBILE_UA
        else -> Http.PC_UA
    }

    // ── 历史 ─────────────────────────────────────────────

    fun refreshHistory() {
        historyList = history.list()
    }

    fun removeHistory(id: String) {
        history.remove(id)
        refreshHistory()
    }

    fun clearHistory() {
        history.clear()
        refreshHistory()
    }

    fun historyEntry(id: String): HistoryEntry? = history.get(id)

    fun loadIntoInput(sourceUrl: String) {
        input = sourceUrl
        parse()
    }

    // ── 杂项 ─────────────────────────────────────────────

    fun showToast(message: String) {
        toastMessage = message
    }

    fun consumeToast() {
        toastMessage = ""
    }

    fun consumeError() {
        error = ""
        errorHint = ""
    }
}
