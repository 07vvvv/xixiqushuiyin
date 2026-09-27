package com.aiqu.watermark.media

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.aiqu.watermark.data.model.MediaItem
import com.aiqu.watermark.data.model.ParseException
import com.aiqu.watermark.data.model.VideoInfo
import com.aiqu.watermark.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载并写入相册
 *
 * 与原小程序版的关键差异：Android 可以带 Referer / Cookie 直连平台 CDN，
 * 因此**不需要云函数中转、不需要云存储**，本地落盘后插入 MediaStore 即可。
 *
 * 分工：
 *  - API 29+ ：MediaStore 插入（自己创建的媒体无需任何存储权限）
 *  - API 26-28：写入公共目录并触发 MediaScanner（需要 WRITE_EXTERNAL_STORAGE）
 */
object MediaSaver {

    private const val ALBUM = "爱去水印"
    private val VIDEO_SUBDIR get() = "${Environment.DIRECTORY_MOVIES}/$ALBUM"
    private val IMAGE_SUBDIR get() = "${Environment.DIRECTORY_PICTURES}/$ALBUM"

    /** API 28 及以下需要申请存储权限；29+ 不需要 */
    fun needsLegacyStoragePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun hasLegacyStoragePermission(context: Context): Boolean {
        if (!needsLegacyStoragePermission()) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    data class BatchResult(val saved: Int, val failed: Int, val errors: List<String>)

    suspend fun saveVideo(
        context: Context,
        video: VideoInfo,
        baseName: String,
        cookie: String = "",
        ua: String = Http.PC_UA,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val candidates = (listOf(video.url) + video.backupUrls).filter { it.isNotBlank() }
        if (candidates.isEmpty()) throw ParseException("没有可下载的视频地址")

        var lastErr: Exception? = null
        for (url in candidates) {
            try {
                val tmp = downloadToCache(context, url, video.referer, cookie, "video", ua, onProgress)
                val mime = detectMime(tmp)
                val name = withExt(baseName, mime)
                return@withContext publish(context, tmp, mime, name, isVideo = true)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw ParseException("视频保存失败：${lastErr?.message ?: "未知错误"}")
    }

    suspend fun saveImage(
        context: Context,
        item: MediaItem,
        baseName: String,
        cookie: String = "",
        index: Int = 0,
        referer: String? = null,
        ua: String = Http.PC_UA,
    ): String = withContext(Dispatchers.IO) {
        val candidates = listOf(item.url, item.altUrl, item.fallbackUrl).filter { it.isNotBlank() }
        if (candidates.isEmpty()) throw ParseException("没有可下载的图片地址")

        var lastErr: Exception? = null
        for (url in candidates) {
            try {
                val tmp = downloadToCache(context, url, referer, cookie, "image", ua)
                val mime = detectMime(tmp)
                val name = withExt(if (index > 0) "${baseName}_$index" else baseName, mime)
                return@withContext publish(context, tmp, mime, name, isVideo = false)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw ParseException("图片保存失败：${lastErr?.message ?: "未知错误"}")
    }

    /** 批量保存（图集），串行执行避免并发写入相册冲突 */
    suspend fun saveImages(
        context: Context,
        items: List<MediaItem>,
        baseName: String,
        cookie: String = "",
        referer: String? = null,
        ua: String = Http.PC_UA,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BatchResult {
        var saved = 0
        val errors = mutableListOf<String>()
        items.forEachIndexed { i, item ->
            try {
                saveImage(
                    context = context,
                    item = item,
                    baseName = baseName,
                    cookie = cookie,
                    index = if (items.size > 1) i + 1 else 0,
                    referer = referer,
                    ua = ua,
                )
                saved++
            } catch (e: Exception) {
                errors += (e.message ?: "未知错误")
            }
            onProgress(i + 1, items.size)
        }
        return BatchResult(saved, errors.size, errors)
    }

    // ── 内部实现 ────────────────────────────────────────────────

    private fun downloadToCache(
        context: Context,
        url: String,
        referer: String?,
        cookie: String,
        prefix: String,
        ua: String = Http.PC_UA,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val dir = File(context.cacheDir, "download").apply { mkdirs() }
        val tmp = File(dir, "$prefix-${System.currentTimeMillis()}-${(0..9999).random()}.tmp")
        Http.download(
            url = url,
            dest = tmp,
            referer = referer?.takeIf { it.isNotBlank() },
            cookie = cookie.takeIf { it.isNotBlank() },
            ua = ua,
            onProgress = onProgress,
        )
        return tmp
    }

    /** 按魔数识别真实类型 —— 小红书的原图是 PNG，但地址里可能带 jpg 字样 */
    private fun detectMime(file: File): String {
        val head = ByteArray(16)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (e: Exception) {
            -1
        }
        if (read >= 4) {
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            val b2 = head[2].toInt() and 0xFF
            val b3 = head[3].toInt() and 0xFF
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "image/png"
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "image/jpeg"
            if (read >= 12 &&
                head[0].toInt() == 0x52 && head[1].toInt() == 0x49 &&
                head[8].toInt() == 0x57 && head[9].toInt() == 0x45
            ) {
                return "image/webp"
            }
            if (read >= 12 && head[4].toInt() == 0x66 && head[5].toInt() == 0x74 &&
                head[6].toInt() == 0x79 && head[7].toInt() == 0x70
            ) {
                return "video/mp4"
            }
        }
        return "application/octet-stream"
    }

    private fun withExt(baseName: String, mime: String): String {
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("jpeg") -> "jpg"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            mime.contains("mp4") -> "mp4"
            else -> "bin"
        }
        val clean = baseName.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(60).ifBlank { "aq_media" }
        return "$clean.$ext"
    }

    private fun publish(
        context: Context,
        src: File,
        mime: String,
        fileName: String,
        isVideo: Boolean,
    ): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(context, src, mime, fileName, isVideo)
            } else {
                if (!hasLegacyStoragePermission(context)) {
                    throw ParseException("需要存储权限才能保存到相册，请在系统设置中开启")
                }
                publishToPublicDir(context, src, mime, fileName, isVideo)
            }
        } finally {
            src.delete()
        }
    }

    private fun publishViaMediaStore(
        context: Context,
        src: File,
        mime: String,
        fileName: String,
        isVideo: Boolean,
    ): String {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val relative = if (isVideo) VIDEO_SUBDIR else IMAGE_SUBDIR

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw ParseException("无法写入相册（MediaStore 插入失败）")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out) }
            } ?: throw ParseException("无法打开相册写入流")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw ParseException("写入相册失败：${e.message ?: "未知错误"}")
        }

        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)

        return "$relative/$fileName"
    }

    private fun publishToPublicDir(
        context: Context,
        src: File,
        mime: String,
        fileName: String,
        isVideo: Boolean,
    ): String {
        val base = Environment.getExternalStoragePublicDirectory(
            if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
        )
        val dir = File(base, ALBUM).apply { mkdirs() }
        val dest = File(dir, fileName)
        src.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        return dest.absolutePath
    }
}
