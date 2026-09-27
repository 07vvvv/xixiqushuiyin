package com.aiqu.watermark.data.model

import kotlinx.serialization.Serializable

/** 解析失败时抛出，message 会直接展示给用户，因此必须是「可操作」的中文提示 */
class ParseException(message: String) : Exception(message)

/**
 * 平台枚举。
 *
 * 显式标注 @Serializable：kotlinx.serialization 虽会为枚举自动生成序列化器，
 * 但 [ParsedMedia] 等数据类把它用作字段类型，显式声明可消除任何版本下的生成歧义。
 */
@Serializable
enum class Platform(val key: String, val displayName: String) {
    BILIBILI("bilibili", "哔哩哔哩"),
    XHS("xhs", "小红书"),
    DOUYIN("douyin", "抖音"),
    UNKNOWN("unknown", "未知");

    companion object {
        fun fromKey(key: String?): Platform = entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

@Serializable
data class AuthorInfo(
    val name: String = "",
    val avatar: String = "",
    val id: String = "",
)

/**
 * 一个可保存的媒体资源。
 *
 * url 是首选地址（无水印 / 无损原图），altUrl 与 fallbackUrl 是备用地址，
 * 保存时会按 url → altUrl → fallbackUrl 顺序重试。
 */
@Serializable
data class MediaItem(
    val url: String = "",
    val altUrl: String = "",
    val fallbackUrl: String = "",
    val previewUrl: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val liveUrl: String = "",
)

@Serializable
data class VideoInfo(
    val url: String = "",
    val backupUrls: List<String> = emptyList(),
    val qualityLabel: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0L,
    val durationMs: Long = 0L,
    /** 下载该视频时必须携带的 Referer（B站防盗链） */
    val referer: String = "",
)

@Serializable
data class ParsedMedia(
    val platform: Platform = Platform.UNKNOWN,
    val type: String = "",
    val title: String = "",
    val desc: String = "",
    val author: AuthorInfo = AuthorInfo(),
    val cover: String = "",
    val durationMs: Long = 0L,
    val statsText: String = "",
    val images: List<MediaItem> = emptyList(),
    val video: VideoInfo? = null,
    val sourceUrl: String = "",
    val costMs: Long = 0L,
    /** 命中的解析通道，便于排查：native / browser / search / provider */
    val channel: String = "",
    /** debug 信息（不持久化） */
    val debug: List<String> = emptyList(),
) {
    val hasVideo: Boolean get() = video != null && video.url.isNotBlank()
    val isImages: Boolean get() = images.isNotEmpty()
}

@Serializable
data class HistoryEntry(
    val id: String,
    val sourceUrl: String = "",
    val platformKey: String = Platform.UNKNOWN.key,
    val title: String = "",
    val author: String = "",
    val cover: String = "",
    val hasVideo: Boolean = false,
    val imageCount: Int = 0,
    val createdAt: Long = 0L,
)
