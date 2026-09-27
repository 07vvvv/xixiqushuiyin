package com.aiqu.watermark.store

import android.content.Context
import com.aiqu.watermark.data.model.Platform

/**
 * 设置存储（SharedPreferences）
 *
 * 刻意不用 DataStore：这里都是同步读取的少量键值，SharedPreferences 零异步复杂度、
 * 零额外依赖，更适合个人工具。
 *
 * Cookie 只保存在本机，不外传。
 */
class SettingsStore(context: Context) {

    private val sp = context.getSharedPreferences("aq_settings", Context.MODE_PRIVATE)

    var bilibiliCookie: String
        get() = sp.getString(KEY_BILI, "") ?: ""
        set(value) = sp.edit().putString(KEY_BILI, value.trim()).apply()

    var douyinCookie: String
        get() = sp.getString(KEY_DOUYIN, "") ?: ""
        set(value) = sp.edit().putString(KEY_DOUYIN, value.trim()).apply()

    var xhsCookie: String
        get() = sp.getString(KEY_XHS, "") ?: ""
        set(value) = sp.edit().putString(KEY_XHS, value.trim()).apply()

    /** 抖音备用通道：第三方接口（留空则只用真实浏览器通道） */
    var douyinProviderUrl: String
        get() = sp.getString(KEY_PROVIDER_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_PROVIDER_URL, value.trim()).apply()

    var douyinProviderToken: String
        get() = sp.getString(KEY_PROVIDER_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_PROVIDER_TOKEN, value.trim()).apply()

    var disclaimerAccepted: Boolean
        get() = sp.getBoolean(KEY_DISCLAIMER, false)
        set(value) = sp.edit().putBoolean(KEY_DISCLAIMER, value).apply()

    /** B站是否填了 SESSDATA（决定能否取 1080P） */
    val hasBilibiliLogin: Boolean get() = bilibiliCookie.contains("SESSDATA=")

    fun cookieFor(platform: Platform): String = when (platform) {
        Platform.BILIBILI -> bilibiliCookie
        Platform.XHS -> xhsCookie
        Platform.DOUYIN -> douyinCookie
        Platform.UNKNOWN -> ""
    }

    fun setCookieFor(platform: Platform, value: String) {
        when (platform) {
            Platform.BILIBILI -> bilibiliCookie = value
            Platform.XHS -> xhsCookie = value
            Platform.DOUYIN -> douyinCookie = value
            Platform.UNKNOWN -> Unit
        }
    }

    companion object {
        private const val KEY_BILI = "cookie_bilibili"
        private const val KEY_DOUYIN = "cookie_douyin"
        private const val KEY_XHS = "cookie_xhs"
        private const val KEY_PROVIDER_URL = "douyin_provider_url"
        private const val KEY_PROVIDER_TOKEN = "douyin_provider_token"
        private const val KEY_DISCLAIMER = "disclaimer_accepted"
    }
}
