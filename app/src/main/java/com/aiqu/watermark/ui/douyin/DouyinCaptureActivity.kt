package com.aiqu.watermark.ui.douyin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.aiqu.watermark.R
import com.aiqu.watermark.net.Http
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * 抖音真实浏览器解析页。
 *
 * 原理：系统 WebView 本身就是 Chromium，由它加载抖音页面，
 * **抖音自己的 SDK** 会生成合法请求（含 x-secsdk-web-signature），
 * 我们只读取结果 —— 不伪造签名、不内置任何第三方专有代码。
 * 这也绕开了「Node TLS 指纹与 Chrome 不一致」这道在服务端无法跨越的墙。
 *
 * 三道捕获手段（从可靠到兜底）：
 *   1) **文档注入钩子**：在 shouldInterceptRequest 里取回主文档 HTML，
 *      在 <head> 最前面插入 XHR/fetch 钩子，确保**早于页面自身脚本**执行，
 *      从而稳定捕获 /aweme/v1/web/aweme/detail/ 的响应
 *   2) onPageStarted 再注入一次（应对不走主文档的场景）
 *   3) 轮询页面：video.currentSrc / og:image / title 兜底
 *
 * 说明：本页刻意用经典 View 而非 Compose 绘制 —— 与 WebView 混用时，
 * Compose 的 AndroidView 会引入额外的生命周期与尺寸同步复杂度，
 * 而这一页只是「进度 + 浏览器 + 两个按钮」，收益为负。
 */
class DouyinCaptureActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var targetUrl: String = ""
    private var cookie: String = ""

    /**
     * 目标作品的 aweme_id。抖音 PC 页是**信息流**，会自动连播推荐视频，
     * 页面里随时可能同时存在多条作品的详情响应 —— 只有拿到目标 id 才能判断
     * 「现在抓到的是不是用户要的那一条」。为空时（短链还原失败）无法校验，
     * 会在状态栏明确提示用户自行确认。
     */
    private var targetId: String = ""

    /** 注入脚本与目标 id 绑定，因此按实例生成一次而不是写成常量 */
    private lateinit var hookScript: String

    private var finished = false
    private var attempts = 0
    private val maxAttempts = 150 // 约 120 秒
    private var mainDocIntercepted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        cookie = intent.getStringExtra(EXTRA_COOKIE) ?: ""
        targetId = (intent.getStringExtra(EXTRA_AWEME_ID) ?: "").trim()

        if (targetUrl.isBlank()) {
            Toast.makeText(this, R.string.douyin_capture_no_url, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        hookScript = buildHookJs(targetId)

        buildUi()
        setupWebView()

        // 让用户一眼看到「正在找哪一条」，并在需要时手动点播放
        statusView.text = if (targetId.isNotBlank()) {
            getString(R.string.douyin_capture_status_target, targetId)
        } else {
            getString(R.string.douyin_capture_status_unverified)
        }

        if (cookie.isNotBlank()) {
            listOf(".douyin.com", ".iesdouyin.com", "www.douyin.com").forEach { domain ->
                CookieManager.getInstance().setCookie(domain, cookie)
            }
            CookieManager.getInstance().flush()
        }

        webView.loadUrl(targetUrl)
        schedulePoll()
    }

    // ── UI ───────────────────────────────────────────────────────

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    /** 渐变圆角底：与 Compose 侧的 hero 渐变保持一致 */
    private fun gradient(vararg colors: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
        }

        // ── 渐变头部：顶部圆角为 0，底部收圆，与主界面的 hero 同语言 ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(22))
            background = gradient(COLOR_BRAND_A, COLOR_BRAND_B).apply {
                cornerRadii = floatArrayOf(
                    0f, 0f, 0f, 0f,
                    dp(26).toFloat(), dp(26).toFloat(),
                    dp(26).toFloat(), dp(26).toFloat(),
                )
            }
        }

        val title = TextView(this).apply {
            text = getString(R.string.douyin_capture_title)
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
        }

        statusView = TextView(this).apply {
            text = getString(R.string.douyin_capture_status_init)
            setTextColor(0xE8FFFFFF.toInt())
            textSize = 13f
        }

        header.addView(title)
        header.addView(statusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(7) })

        // 注意：addView(view) 会用 LinearLayout 的默认布局参数（宽度 WRAP_CONTENT），
        // 渐变头会缩成文字宽度而非通栏，因此这里必须显式给 MATCH_PARENT。
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // ── 说明 ──
        val hint = TextView(this).apply {
            text = getString(R.string.douyin_capture_hint)
            setTextColor(COLOR_MUTED)
            textSize = 12f
            setPadding(dp(20), dp(14), dp(20), dp(12))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        root.addView(hint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // ── WebView 容器：WebView 必须真实挂载到窗口才能正常工作 ──
        val webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f,
                )
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        webContainer.addView(webView)

        val webWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
        }
        webWrap.addView(webContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        root.addView(webWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        // ── 底部动作 ──
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(14), dp(20), dp(20))
        }

        val cancel = actionView(
            text = getString(R.string.douyin_capture_cancel),
            filled = false,
        ) { finishWithCancel() }

        val manual = actionView(
            text = getString(R.string.douyin_capture_manual),
            filled = true,
        ) { forceReadNow() }

        actions.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(manual, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(10) })
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        setContentView(root)
    }

    /** 用 TextView + 圆角背景手绘按钮：系统 Button 自带大写转换、额外内边距与阴影，风格不合 */
    private fun actionView(text: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(14))
            background = if (filled) {
                gradient(COLOR_BRAND_A, COLOR_BRAND_B).apply { cornerRadius = dp(15).toFloat() }
            } else {
                GradientDrawable().apply {
                    setColor(COLOR_SURFACE)
                    setStroke(dp(1), COLOR_OUTLINE)
                    cornerRadius = dp(15).toFloat()
                }
            }
            setTextColor(if (filled) Color.WHITE else COLOR_INK)
            setOnClickListener { onClick() }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // 允许自动播放：播放被触发时页面才会真正去取媒体地址
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 用桌面端 UA：抖音的 PC 页面会正常渲染播放器并请求详情接口
            userAgentString = DESKTOP_UA
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {

            /** 手段 1：拦截主文档，注入钩子后再交给 WebView —— 保证钩子早于页面脚本 */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (!request.isForMainFrame || mainDocIntercepted) return null
                if (!url.contains("douyin.com")) return null

                return try {
                    val res = Http.getText(
                        url = url,
                        ua = DESKTOP_UA,
                        referer = "https://www.douyin.com/",
                        cookie = cookie.takeIf { it.isNotBlank() },
                    )
                    if (res.code >= 400 || res.body.isBlank()) return null

                    mainDocIntercepted = true
                    val patched = injectHook(res.body, hookScript)
                    runOnUiThread {
                        statusView.text = getString(R.string.douyin_capture_status_loading)
                    }
                    WebResourceResponse(
                        "text/html",
                        "utf-8",
                        200,
                        "OK",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(patched.toByteArray(Charsets.UTF_8)),
                    )
                } catch (e: Exception) {
                    null
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 手段 2：再注入一次
                view?.evaluateJavascript(hookScript, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 不覆盖状态栏文案：它此时可能正在提示「属于其它作品，已忽略」
                view?.evaluateJavascript(hookScript, null)
            }
        }
    }

    // ── 轮询与捕获 ────────────────────────────────────────────

    private fun schedulePoll() {
        handler.postDelayed({
            if (finished) return@postDelayed
            attempts++
            if (attempts > maxAttempts) {
                statusView.text = getString(R.string.douyin_capture_timeout)
                return@postDelayed
            }
            readOnce { captured ->
                if (!captured && !finished) schedulePoll()
            }
        }, 800)
    }

    private fun forceReadNow() {
        readOnce { captured ->
            if (!captured) {
                Toast.makeText(this, R.string.douyin_capture_manual_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 读一次页面状态；captured=true 表示已成功并结束 */
    private fun readOnce(onDone: (Boolean) -> Unit) {
        // 顺便尝试播放，促使页面去请求媒体地址
        webView.evaluateJavascript(
            "(function(){try{var v=document.querySelector('video');if(v){v.muted=true;v.play().catch(function(){});}}catch(e){}return 1;})()",
            null,
        )

        webView.evaluateJavascript(READ_JS) { raw ->
            if (finished) {
                onDone(true)
                return@evaluateJavascript
            }
            val payload = decodeJsResult(raw)
            if (payload == null) {
                onDone(false)
                return@evaluateJavascript
            }

            // ── 通道 1：详情接口响应 ──
            // 必须校验 aweme_id：抖音 PC 页是信息流、会自动连播推荐视频，
            // 先到的响应很可能属于**别的**作品 —— 这正是「下载到的不是这条视频」的成因。
            val detail = payload.optClean("detail")
            if (detail.startsWith("{")) {
                when (verifyAwemeId(detail)) {
                    Match.OK -> {
                        finishWithResult(detail, "接口")
                        onDone(true)
                        return@evaluateJavascript
                    }
                    Match.OTHER -> {
                        val got = payload.optClean("seen").ifBlank { "未知" }
                        statusView.text = getString(R.string.douyin_capture_status_mismatch, got)
                        onDone(false)
                        return@evaluateJavascript
                    }
                    Match.UNKNOWN -> {
                        // 响应里没有 aweme_id，无法判定，按可用处理（总比拒绝好）
                        finishWithResult(detail, "接口")
                        onDone(true)
                        return@evaluateJavascript
                    }
                }
            }

            // ── 通道 2：页面 DOM 兜底 ──
            // 仅当页面地址仍含目标 id 时才接受：连播之后 location 会变成下一条作品，
            // 那时 DOM 里的 video 已经不是用户要的那条。
            val videoSrc = payload.optClean("videoSrc")
            val curUrl = payload.optClean("url")
            if (videoSrc.startsWith("http") && domAccepts(curUrl)) {
                finishWithResult(synthesizeAweme(payload), "页面")
                onDone(true)
                return@evaluateJavascript
            }

            statusView.text = when {
                targetId.isBlank() -> getString(R.string.douyin_capture_status_unverified)
                curUrl.isNotBlank() && !curUrl.contains(targetId) ->
                    getString(R.string.douyin_capture_status_mismatch, "页面已跳转")
                else -> getString(R.string.douyin_capture_status_target, targetId)
            }
            onDone(false)
        }
    }

    private enum class Match { OK, OTHER, UNKNOWN }

    /** 校验捕获到的详情是否就是目标作品 */
    private fun verifyAwemeId(detailJson: String): Match {
        if (targetId.isBlank()) return Match.OK   // 无目标 id 可比对
        val id = extractAwemeId(detailJson)
        if (id.isBlank()) return Match.UNKNOWN    // 响应里取不到 id，无法判定
        return if (id == targetId) Match.OK else Match.OTHER
    }

    /** 从详情 JSON 取 aweme_id，兼容 `aweme_detail.aweme_id` 嵌套结构 */
    private fun extractAwemeId(detailJson: String): String = try {
        val o = JSONObject(detailJson)
        val direct = o.optString("aweme_id")
        if (direct.isNotBlank() && direct != "null") {
            direct
        } else {
            val nested = o.optJSONObject("aweme_detail")?.optString("aweme_id").orEmpty()
            if (nested == "null") "" else nested
        }
    } catch (e: Exception) {
        ""
    }

    /** DOM 兜底是否可信：页面地址必须仍指向目标作品 */
    private fun domAccepts(curUrl: String): Boolean {
        if (targetId.isBlank()) return true
        return curUrl.contains(targetId)
    }

    /**
     * org.json 的 `optString` 对 JSON null 会返回字符串 `"null"`（不是空串），
     * 直接参与判空会得到「假非空」，因此统一还原为空串。
     */
    private fun JSONObject.optClean(key: String): String =
        optString(key).let { if (it == "null") "" else it }

    /**
     * evaluateJavascript 的返回值是「被 JSON 编码过的字符串」，
     * 例如 "\"{\\\"a\\\":1}\""，需要解两层。
     */
    private fun decodeJsResult(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val inner = org.json.JSONTokener(raw).nextValue()
            val text = when (inner) {
                is String -> inner
                is JSONObject -> return inner
                else -> inner.toString()
            }
            if (text.isBlank() || text == "null") null else JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    /** 兜底：仅拿到页面 video 地址时，拼一个最小化的 detail 结构交给上层 */
    private fun synthesizeAweme(payload: JSONObject): String {
        val obj = JSONObject()
        obj.put("desc", payload.optClean("title"))
        val video = JSONObject()
        val playAddr = JSONObject()
        val list = org.json.JSONArray()
        list.put(payload.optClean("videoSrc"))
        playAddr.put("url_list", list)
        video.put("play_addr", playAddr)
        val cover = payload.optClean("cover")
        if (cover.isNotBlank()) {
            val coverObj = JSONObject()
            val coverList = org.json.JSONArray()
            coverList.put(cover)
            coverObj.put("url_list", coverList)
            video.put("cover", coverObj)
        }
        obj.put("video", video)
        return obj.toString()
    }

    private fun finishWithResult(awemeJson: String, source: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        DouyinCaptureHolder.set(awemeJson, source)
        statusView.text = getString(R.string.douyin_capture_status_found)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SOURCE, source))
        finish()
    }

    private fun finishWithCancel() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        DouyinCaptureHolder.clear()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            if (::webView.isInitialized) {
                webView.stopLoading()
                webView.destroy()
            }
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_COOKIE = "extra_cookie"
        const val EXTRA_SOURCE = "extra_source"

        /** 目标作品的 aweme_id（用于校验抓到的是不是这一条） */
        const val EXTRA_AWEME_ID = "extra_aweme_id"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val COLOR_BG = Color.parseColor("#F5F6FA")
        private val COLOR_SURFACE = Color.parseColor("#FFFFFF")
        private val COLOR_INK = Color.parseColor("#12141A")
        private val COLOR_MUTED = Color.parseColor("#6C7284")
        private val COLOR_OUTLINE = Color.parseColor("#E3E6EE")

        // 注意：0xFFFF3D6E 超出 Int 范围，Kotlin 会推断为 Long，
        // 因此这里只能用普通 val（函数调用不是编译期常量，const val 会编译失败）。
        private val COLOR_BRAND_A = 0xFFFF3D6E.toInt()
        private val COLOR_BRAND_B = 0xFFFF7A4D.toInt()

        /** 把钩子插到 <head> 最前面，确保先于页面自身脚本执行 */
        private fun injectHook(html: String, script: String): String {
            val tag = "<script>$script</script>"
            val headIdx = html.indexOf("<head>")
            if (headIdx >= 0) {
                return html.substring(0, headIdx + 6) + tag + html.substring(headIdx + 6)
            }
            val htmlIdx = html.indexOf("<html")
            if (htmlIdx >= 0) {
                val gt = html.indexOf('>', htmlIdx)
                if (gt >= 0) return html.substring(0, gt + 1) + tag + html.substring(gt + 1)
            }
            return tag + html
        }

        /**
         * 生成注入脚本，并把**目标 aweme_id** 绑进去。
         *
         * 为什么必须绑 id：抖音 PC 页是信息流，会自动连播推荐视频，页面里可能先后
         * 出现多条 `/aweme/v1/web/aweme/detail/` 响应。钩子按请求 URL 上的
         * `aweme_id` 参数分别登记，只把**匹配目标**的那一条暴露给 Kotlin 侧，
         * 从而避免抓到推荐视频（这正是「下载到的不是这条视频」的成因）。
         *
         * `__aqSeen` 记录见过的所有 id，用于在界面上解释「为什么还没成功」。
         */
        private fun buildHookJs(targetId: String): String {
            // aweme_id 是纯数字，这里仍剔除引号，避免被拼接进 JS 字面量时破坏语法
            val literal = if (targetId.isBlank()) "''" else "'" + targetId.replace("'", "") + "'"
            return """
            (function(){
              try{
                if(window.__aqHooked){return 'already';}
                window.__aqHooked=true;
                window.__aqTarget=window.__aqTarget||'';
                window.__aqMatched=window.__aqMatched||'';
                window.__aqSeen=window.__aqSeen||[];
                var TARGET=$literal;
                var idOf=function(u){try{var m=String(u).match(/[?&]aweme_id=([0-9]{10,})/);return m?m[1]:'';}catch(e){return '';}};
                var isDetail=function(u){return !!u&&String(u).indexOf('/aweme/v1/web/aweme/detail/')>=0;};
                var take=function(u,t){
                  try{
                    var id=idOf(u);
                    if(id&&window.__aqSeen.indexOf(id)<0){window.__aqSeen.push(id);}
                    if(!TARGET||id===TARGET){window.__aqTarget=t;window.__aqMatched=id||'';}
                  }catch(e){}
                };
                var oo=XMLHttpRequest.prototype.open;
                var os=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open=function(m,u){try{this.__aqUrl=u;}catch(e){}return oo.apply(this,arguments);};
                XMLHttpRequest.prototype.send=function(){
                  var self=this;
                  this.addEventListener('load',function(){
                    try{
                      if(isDetail(self.__aqUrl)){
                        var t=(self.responseType===''||self.responseType==='text')?self.responseText:null;
                        if(t){take(self.__aqUrl,t);}
                      }
                    }catch(e){}
                  });
                  return os.apply(this,arguments);
                };
                if(window.fetch){
                  var of=window.fetch;
                  window.fetch=function(input,init){
                    var u=(typeof input==='string')?input:((input&&input.url)||'');
                    var p=of.apply(this,arguments);
                    try{
                      if(isDetail(u)){
                        p.then(function(r){
                          try{r.clone().text().then(function(t){take(u,t);});}catch(e){}
                        });
                      }
                    }catch(e){}
                    return p;
                  };
                }
                return 'hooked';
              }catch(e){return 'err';}
            })();
            """.trimIndent()
        }

        /**
         * 读取页面状态。
         *
         * 两处修正：
         *  1) `detail` 必须用 `||''` 兜底 —— 若直接给 `window.__aqTarget`，
         *     未捕获时该字段是 JSON null，经 evaluateJavascript 回传后 Kotlin 侧
         *     `optString()` 会读到字符串 `"null"`（不是空串），被当成有效作品 JSON。
         *  2) video 不能只取 `document.querySelector('video')` —— 抖音信息流页上
         *     会有多个 video 元素，第一个未必是正在播放的那条。改为遍历全部、
         *     取**可见且面积最大**的那个，并以首个非空 src 兜底。
         */
        private val READ_JS = """
            (function(){
              try{
                var out={detail:'',matched:'',seen:'',videoSrc:'',title:'',cover:'',url:''};
                out.url=(location&&location.href)?location.href:'';
                out.detail=(window.__aqTarget||'');
                out.matched=(window.__aqMatched||'');
                out.seen=(window.__aqSeen||[]).join(',');
                var best='',bestArea=0,fallback='';
                var vs=document.querySelectorAll('video');
                for(var i=0;i<vs.length;i++){
                  var v=vs[i];
                  var s=v.currentSrc||v.src||'';
                  if(!s){continue;}
                  if(!fallback){fallback=s;}
                  var r=v.getBoundingClientRect();
                  var area=r.width*r.height;
                  if(area>0&&r.top<window.innerHeight&&r.bottom>0&&area>bestArea){bestArea=area;best=s;}
                }
                out.videoSrc=best||fallback;
                var v0=document.querySelector('video');
                if(v0){var p=v0.getAttribute('poster');if(p){out.cover=p;}}
                var ogt=document.querySelector('meta[property="og:title"]');
                out.title=(ogt&&ogt.content)||document.title||'';
                var ogi=document.querySelector('meta[property="og:image"]');
                if(ogi&&ogi.content){out.cover=ogi.content;}
                return JSON.stringify(out);
              }catch(e){return JSON.stringify({error:String(e)});}
            })();
        """.trimIndent()
    }
}
