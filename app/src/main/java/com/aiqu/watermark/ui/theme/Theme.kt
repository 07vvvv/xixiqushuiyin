package com.aiqu.watermark.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aiqu.watermark.data.model.Platform

// ── 品牌与平台色 ────────────────────────────────────────────────

/** 品牌主色：洋红。用于主按钮、选中态、强调数字 */
val BrandCoral = Color(0xFFFF2E63)

/** 品牌辅色：落日橙。与主色构成 hero 渐变 */
val BrandSunset = Color(0xFFFF8A5B)

/** 次要强调色：紫。用于「历史 / 设置」这类非主路径的强调 */
val BrandViolet = Color(0xFF7C5CFF)

/**
 * 平台标识色。
 *
 * 刻意使用各平台**自己的品牌色**，而不是统一的灰色标签：
 * 一眼就能认出这是抖音还是小红书，也让界面摆脱「一套灰底 Chip 走天下」的模板感。
 */
val PlatformDouyin = Color(0xFF16181D)
val PlatformXhs = Color(0xFFFF2442)
val PlatformBili = Color(0xFF0AA6DC)
val PlatformNone = Color(0xFF8A90A0)

fun platformColor(platform: Platform): Color = when (platform) {
    Platform.DOUYIN -> PlatformDouyin
    Platform.XHS -> PlatformXhs
    Platform.BILIBILI -> PlatformBili
    Platform.UNKNOWN -> PlatformNone
}

/** 把解析通道的内部代号翻成用户能读懂的词 */
fun channelLabel(channel: String): String = when (channel) {
    "native" -> "自研解析"
    "browser" -> "本机浏览器"
    "provider" -> "接口通道"
    else -> channel
}

// ── ColorScheme ────────────────────────────────────────────────

private val LightScheme = lightColorScheme(
    primary = BrandCoral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3EB),
    onPrimaryContainer = Color(0xFF5A0024),
    secondary = BrandViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FF),
    onSecondaryContainer = Color(0xFF271B60),
    tertiary = BrandSunset,
    onTertiary = Color.White,
    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF12141A),
    surface = Color.White,
    onSurface = Color(0xFF12141A),
    surfaceVariant = Color(0xFFF1F2F7),
    onSurfaceVariant = Color(0xFF6C7284),
    outline = Color(0xFFE3E6EE),
    outlineVariant = Color(0xFFEFF1F6),
    error = Color(0xFFE5484D),
    onError = Color.White,
    errorContainer = Color(0xFFFFE9E9),
    onErrorContainer = Color(0xFF7A1114),
    scrim = Color(0x99000000),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF5C86),
    onPrimary = Color(0xFF3D0018),
    primaryContainer = Color(0xFF5E0A2A),
    onPrimaryContainer = Color(0xFFFFE3EB),
    secondary = Color(0xFF9E8AFF),
    onSecondary = Color(0xFF1B1340),
    secondaryContainer = Color(0xFF2C2359),
    onSecondaryContainer = Color(0xFFEDE9FF),
    tertiary = BrandSunset,
    onTertiary = Color(0xFF3A1700),
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF15181F),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF1F232C),
    onSurfaceVariant = Color(0xFF9CA2B4),
    outline = Color(0xFF2C313C),
    outlineVariant = Color(0xFF23272F),
    error = Color(0xFFFF7B7F),
    onError = Color(0xFF3A0003),
    errorContainer = Color(0xFF4A1416),
    onErrorContainer = Color(0xFFFFDADB),
    scrim = Color(0xCC000000),
)

// ── Material3 之外的扩展色 ──────────────────────────────────────
//
// Material3 的 ColorScheme 槽位有限（没有 hero 渐变、发丝线、极弱化文字这些概念），
// 又要区分明暗两套，所以用 CompositionLocal 单独承载。

@Immutable
data class AqExtras(
    /** 卡片描边：比 outline 更淡，用于「无阴影、靠描边分层」的视觉体系 */
    val hairline: Color,
    /** 极弱化的正文（辅助说明、版权信息） */
    val faint: Color,
    /** hero 区渐变 */
    val heroGradient: List<Color>,
    /** 中性 Chip 底色 */
    val chip: Color,
    /** 「无损 / 已完成」等正向标识色 */
    val positive: Color,
)

private val LightExtras = AqExtras(
    hairline = Color(0xFFECEEF4),
    faint = Color(0xFF9BA1B0),
    heroGradient = listOf(Color(0xFFFF3D6E), Color(0xFFFF7A4D)),
    chip = Color(0xFFF1F2F7),
    positive = Color(0xFF12A150),
)

private val DarkExtras = AqExtras(
    hairline = Color(0xFF272C35),
    faint = Color(0xFF767D8E),
    heroGradient = listOf(Color(0xFFFF4D7A), Color(0xFFFF8A5B)),
    chip = Color(0xFF222630),
    positive = Color(0xFF3DD68C),
)

private val LocalAqExtras = staticCompositionLocalOf { LightExtras }

/** 扩展色的访问入口，用法与 `MaterialTheme.colorScheme` 一致 */
object AqTheme {
    val extras: AqExtras
        @Composable
        @ReadOnlyComposable
        get() = LocalAqExtras.current

    /** hero 渐变笔刷 */
    val heroBrush: Brush
        @Composable
        @ReadOnlyComposable
        get() = Brush.linearGradient(LocalAqExtras.current.heroGradient)
}

// ── 形状 ───────────────────────────────────────────────────────
//
// 整体偏大圆角（20-30dp），比 Material 默认的 12/16 更有「卡片感」，
// 也更贴近国内主流内容类 App 的观感。

val AqShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** 常用的一次性圆角，集中在此便于统一调整 */
object AqRadius {
    val hero = 36.dp
    val card = 22.dp
    val button = 16.dp
    val thumb = 14.dp
}

@Composable
fun AqWatermarkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extras = if (darkTheme) DarkExtras else LightExtras
    CompositionLocalProvider(LocalAqExtras provides extras) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            shapes = AqShapes,
            content = content,
        )
    }
}
