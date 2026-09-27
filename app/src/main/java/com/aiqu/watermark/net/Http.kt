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

    /** 只取一次重定向目标，不跟随（用于还原短链并保留其查询参数） */
    fun resolveRedirect(url: String, ua: String = MOBILE_UA, referer: String? = null): String {
        try {
            noRedirectClient.newCall(buildRequest(url, ua, referer, null).build()).execute().use { r ->
                val loc = r.header("Location")
                if (!loc.isNullOrBlank()) {
                    // 注意：OkHttp 的 HttpUrl.newBuilder(link: String) 返回的是**可空** Builder
                    // （链接非法时返回 null），因此必须用安全调用，不能直接 .build()。
                    val resolved = r.request.url.newBuilder(loc)?.build()
                    if (resolved != null) return resolved.toString()
                }
                return r.request.url.toString()
            }
        } catch (e: Exception) {
            return url
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
