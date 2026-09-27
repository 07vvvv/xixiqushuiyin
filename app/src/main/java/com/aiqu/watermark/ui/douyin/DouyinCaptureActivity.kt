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
    private var finished = false
    private var attempts = 0
    private val maxAttempts = 150 // 约 120 秒
    private var mainDocIntercepted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        cookie = intent.getStringExtra(EXTRA_COOKIE) ?: ""

        if (targetUrl.isBlank()) {
            Toast.makeText(this, R.string.douyin_capture_no_url, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        buildUi()
        setupWebView()

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
                    val patched = injectHook(res.body)
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
                view?.evaluateJavascript(HOOK_JS, null)
                statusView.text = getString(R.string.douyin_capture_status_loading)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(HOOK_JS, null)
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

            val detail = payload.optClean("detail")
            if (detail.startsWith("{")) {
                finishWithResult(detail, "接口")
                onDone(true)
                return@evaluateJavascript
            }

            val videoSrc = payload.optClean("videoSrc")
            if (videoSrc.startsWith("http")) {
                finishWithResult(synthesizeAweme(payload), "页面")
                onDone(true)
                return@evaluateJavascript
            }

            statusView.text = if (payload.optClean("url").isNotBlank()) {
                getString(R.string.douyin_capture_status_waiting)
            } else {
                getString(R.string.douyin_capture_status_loading)
            }
            onDone(false)
        }
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
        private fun injectHook(html: String): String {
            val script = "<script>$HOOK_JS</script>"
            val headIdx = html.indexOf("<head>")
            if (headIdx >= 0) {
                return html.substring(0, headIdx + 6) + script + html.substring(headIdx + 6)
            }
            val htmlIdx = html.indexOf("<html")
            if (htmlIdx >= 0) {
                val gt = html.indexOf('>', htmlIdx)
                if (gt >= 0) return html.substring(0, gt + 1) + script + html.substring(gt + 1)
            }
            return script + html
        }

        private val HOOK_JS = """
            (function(){
              try{
                if(window.__aqHooked){return 'already';}
                window.__aqHooked=true;
                window.__aqDetail=window.__aqDetail||null;
                var isDetail=function(u){return !!u&&u.indexOf('/aweme/v1/web/aweme/detail/')>=0;};
                var oo=XMLHttpRequest.prototype.open, os=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open=function(m,u){try{this.__aqUrl=u;}catch(e){}return oo.apply(this,arguments);};
                XMLHttpRequest.prototype.send=function(){
                  var self=this;
                  this.addEventListener('load',function(){
                    try{
                      if(isDetail(self.__aqUrl)){
                        var t=(self.responseType===''||self.responseType==='text')?self.responseText:null;
                        if(t){window.__aqDetail=t;}
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
                          try{r.clone().text().then(function(t){window.__aqDetail=t;});}catch(e){}
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

        private val READ_JS = """
            (function(){
              try{
                var out={detail:'',videoSrc:'',title:'',cover:'',url:''};
                out.url=(location&&location.href)?location.href:'';
                // 必须用 ||'' 兜底：若写成 out.detail=window.__aqDetail，
                // 未捕获时该字段是 JSON null，经 evaluateJavascript 回传后
                // Kotlin 侧 optString() 会读到字符串 "null"（而非空串），
                // 从而被当成有效作品 JSON 交给解析器，报出难以定位的错误。
                out.detail=(window.__aqDetail||'');
                var v=document.querySelector('video');
                if(v){out.videoSrc=v.currentSrc||v.src||'';var p=v.getAttribute('poster');if(p){out.cover=p;}}
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
