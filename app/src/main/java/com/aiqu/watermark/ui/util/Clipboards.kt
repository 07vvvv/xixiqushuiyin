package com.aiqu.watermark.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 剪贴板读写。
 *
 * 刻意使用系统 ClipboardManager，而不是 Compose 的 LocalClipboardManager：
 * 后者在新版 Compose UI 中已废弃（替代品 LocalClipboard 是 suspend API），
 * 而这里只需要同步读写一小段文本。用系统 API 可避免随 Compose 版本升级而失效，
 * 也省掉一个额外的 API 依赖面。
 *
 * 注意：Android 10+ 限制应用在**没有焦点**时读取剪贴板。本工具所有读取动作
 * 都由用户在前台点击触发，因此不受该限制影响。
 */
object Clipboards {

    private fun service(context: Context): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /** @return 是否写入成功 */
    fun copy(context: Context, text: String): Boolean {
        val cm = service(context) ?: return false
        return try {
            cm.setPrimaryClip(ClipData.newPlainText("aq_watermark", text))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 读取剪贴板纯文本，失败或为空时返回空串 */
    fun read(context: Context): String {
        val cm = service(context) ?: return ""
        return try {
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""
            clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
