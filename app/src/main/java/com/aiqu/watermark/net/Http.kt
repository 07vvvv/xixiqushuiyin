package com.aiqu.watermark.net

import com.aiqu.watermark.data.model.ParseException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 统一网络层（OkHttp）
 *
 * 与原小程序版的关键差异：Android 可以直接带自定义 Referer / Cookie 请求任意 CDN，
 * 因此**不需要云函数中转**，下载完写入相册即可。
 */
object Http {

    const val PC_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val noRedirectClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .build()

    data class TextResult(val code: Int, val body: String, val finalUrl: String)

    private fun buildRequest(
        url: String,
        ua: String,
        referer: String?,
        cookie: String?,
    ): Request.Builder {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (!referer.isNullOrBlank()) b.header("Referer", referer)
        if (!cookie.isNullOrBlank()) b.header("Cookie", cookie)
        return b
    }

    /** GET 文本，不因 4xx 抛异常（调用方自行判断状态码） */
    fun getText(
        url: String,
        ua: String = PC_UA,
        referer: String? = null,
        cookie: String? = null,
        noRedirect: Boolean = false,
    ): TextResult {
        val c = if (noRedirect) noRedirectClient else client
        try {
            c.newCall(buildRequest(url, ua, referer, cookie).build()).execute().use { r ->
                return TextResult(r.code, r.body?.string() ?: "", r.request.url.toString())
            }
        } catch (e: Exception) {
            throw ParseException("网络请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** GET 文本，4xx/5xx 抛可读异常 */
    fun getTextOrThrow(
        url: String,
        ua: String = PC_UA,
        referer: String? = null,
        cookie: String? = null,
    ): TextResult {
        val r = getText(url, ua, referer, cookie)
        if (r.code >= 400) throw ParseException("接口返回 HTTP ${r.code}（${url.take(80)}…）")
        return r
    }

    fun getJson(
        url: String,
        ua: String = PC_UA,
        referer: String? = null,
        cookie: String? = null,
    ): JSONObject {
        val text = getTextOrThrow(url, ua, referer, cookie).body
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ParseException("接口返回不是合法 JSON：${text.take(120)}")
        }
    }

    fun postJson(
        url: String,
        jsonBody: String,
        ua: String = PC_UA,
        referer: String? = null,
        cookie: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): TextResult {
        val b = buildRequest(url, ua, referer, cookie)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        try {
            client.newCall(b.build()).execute().use { r ->
                return TextResult(r.code, r.body?.string() ?: "", r.request.url.toString())
            }
        } catch (e: Exception) {
            throw ParseException("网络请求失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 还原短链，**自动跟随多跳**（默认最多 5 跳）。
     *
     * 为什么必须多跳：平台短链常是「短链 → 短链 → 详情页」的多级跳转
     * （小红书 `xhslink.com/a/xxx` 就可能先跳到 `xhslink.com/m/xxx`）。
     * 只取一跳会拿到仍是短链的中间地址，导致后续提取不到笔记 ID / 作品 ID，
     * 表现为「未能识别小红书笔记 ID」这类误导性报错。
     *
     * 每一步都不跟随重定向（自身循环），以便**保留中间地址的查询参数**
     * （小红书的 `xsec_token` 就在查询串里，丢了就拿不到数据）。
     */
    fun resolveRedirect(
        url: String,
        ua: String = MOBILE_UA,
        referer: String? = null,
        maxHops: Int = 5,
    ): String {
        var current = url
        for (hop in 0 until maxHops) {
            val (finalUrl, nextUrl) = resolveOneHop(current, ua, referer)
            if (nextUrl.isBlank() || nextUrl == current) return finalUrl
            current = nextUrl
        }
        return current
    }

    /**
     * 单跳：不跟随重定向，返回 (本次请求的最终地址, Location 解析出的下一跳)。
     *
     * 刻意用「返回值」而不是在 lambda 里改外部变量 —— 被 lambda 捕获的局部变量
     * 即使是 `String?` 且已判空，Kotlin 也不会对它做智能转换，直接赋值给 `String`
     * 会编译失败。返回 Pair 可以彻底避开这类问题。
     */
    private fun resolveOneHop(url: String, ua: String, referer: String?): Pair<String, String> {
        return try {
            noRedirectClient.newCall(buildRequest(url, ua, referer, null).build()).execute().use { r ->
                val finalUrl = r.request.url.toString()
                val nextUrl = if (r.code in 300..399) {
                    r.header("Location")?.let { loc ->
                        // HttpUrl.newBuilder(link: String) 返回**可空** Builder（链接非法时为 null），
                        // 必须用安全调用，不能直接 .build()
                        r.request.url.newBuilder(loc)?.build()?.toString()
                    } ?: ""
                } else {
                    ""
                }
                finalUrl to nextUrl
            }
        } catch (e: Exception) {
            // 还原失败就退回原地址，由调用方给出「无法识别 ID」并附带该地址，便于定位
            url to ""
        }
    }

    /**
     * 流式下载到本地文件（不缓冲整个文件，避免大视频 OOM）
     * @return 实际写入字节数
     */
    fun download(
        url: String,
        dest: File,
        referer: String? = null,
        cookie: String? = null,
        ua: String = PC_UA,
        onProgress: ((written: Long, total: Long) -> Unit)? = null,
    ): Long {
        try {
            client.newCall(buildRequest(url, ua, referer, cookie).build()).execute().use { r ->
                if (r.code >= 400) throw ParseException("下载失败，HTTP ${r.code}")
                val body = r.body ?: throw ParseException("下载失败：响应体为空")
                val total = body.contentLength()
                dest.parentFile?.mkdirs()
                var written = 0L
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            written += n
                            onProgress?.invoke(written, total)
                        }
                        output.flush()
                    }
                }
                if (written == 0L) throw ParseException("下载到 0 字节，地址可能已过期")
                return written
            }
        } catch (e: ParseException) {
            throw e
        } catch (e: Exception) {
            throw ParseException("下载失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
