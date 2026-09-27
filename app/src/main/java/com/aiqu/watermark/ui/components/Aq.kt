package com.aiqu.watermark.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aiqu.watermark.data.model.MediaItem
import com.aiqu.watermark.data.model.Platform
import com.aiqu.watermark.ui.theme.AqRadius
import com.aiqu.watermark.ui.theme.AqTheme
import com.aiqu.watermark.ui.theme.platformColor
import java.util.Locale

/**
 * 本应用自有的视觉组件集。
 *
 * 为什么不直接用 Material3 的 Scaffold + TopAppBar + Card + OutlinedTextField：
 * 那套默认观感（灰底 AppBar、带阴影的 Card、四条边一个颜色的输入框）会让工具类 App
 * 看起来像模板工程。这里统一改为：
 *   · hero 渐变头图 + 状态栏沉浸
 *   · 无阴影、靠 1px 发丝线分层
 *   · 平台品牌色驱动的标签体系
 *   · 大圆角 + 自定义虚线输入区
 * 所有色值与圆角都取自 `AqTheme`，改一处即可全局生效。
 */

// ── 头部 ───────────────────────────────────────────────────────

/**
 * 渐变 hero 头部。
 *
 * 渐变会延伸到状态栏下方（[statusBarsPadding] 放在背景之后），
 * 因此顶部是通栏的暖色渐变，而不是「AppBar 上方一条浅色」。这是与模板观感最大的差别。
 */
@Composable
fun AqHero(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 26.dp,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = AqRadius.hero, bottomEnd = AqRadius.hero))
            .background(AqTheme.heroBrush)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = bottomPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            actions()
        }
        content()
    }
}

/** hero 上的半透明圆形图标按钮 */
@Composable
fun AqIconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.2f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(19.dp),
        )
    }
}

// ── 基础容器 ───────────────────────────────────────────────────

/** 无阴影卡片：白底 + 1px 发丝描边 + 大圆角 */
@Composable
fun AqPanel(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(AqRadius.card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, AqTheme.extras.hairline, shape)
            .padding(padding),
        content = content,
    )
}

/**
 * 虚线框：用于「待粘贴」这类需要提示「这里可以放东西」的容器。
 *
 * 底色刻意用**不透明的 surface** 而非半透明：这个容器会被放在 hero 渐变上，
 * 若用半透明底，深色模式下文字（onSurface 为浅色）会落在浅色渐变上而看不清。
 */
@Composable
fun AqDashedBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outline
    val density = LocalDensity.current
    val radius = with(density) { AqRadius.card.toPx() }
    val strokeW = with(density) { 1.6.dp.toPx() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AqRadius.card))
            .background(MaterialTheme.colorScheme.surface)
            .then(
                Modifier.drawDashedRoundRect(
                    color = lineColor,
                    radiusPx = radius,
                    strokePx = strokeW,
                )
            ),
        content = content,
    )
}

/** 画一圈虚线圆角矩形。抽成扩展函数，避免在 Composable 里写大段 drawBehind */
private fun Modifier.drawDashedRoundRect(
    color: Color,
    radiusPx: Float,
    strokePx: Float,
): Modifier = this.drawBehind {
    val half = strokePx / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(half, half),
        size = Size(size.width - strokePx, size.height - strokePx),
        cornerRadius = CornerRadius(radiusPx - half),
        style = Stroke(
            width = strokePx,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 11f), 0f),
        ),
    )
}

// ── 标签 ───────────────────────────────────────────────────────

/** 平台标签：底色与文字都取平台品牌色 */
@Composable
fun AqPlatformPill(
    platform: Platform,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onDark: Boolean = false,
) {
    val c = platformColor(platform)
    val bg = if (onDark) Color.White.copy(alpha = 0.22f) else c.copy(alpha = 0.12f)
    val fg = if (onDark) Color.White else c

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(
                horizontal = if (compact) 9.dp else 11.dp,
                vertical = if (compact) 3.dp else 5.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (onDark) Color.White else c)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            platform.displayName,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 中性信息标签 */
@Composable
fun AqTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = AqTheme.extras.chip,
) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
    )
}

/** 分组标题 + 右侧动作 */
@Composable
fun AqSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

// ── 按钮 ───────────────────────────────────────────────────────

/**
 * 主按钮。
 *
 * 用 Box 手绘而不是 Material3 的 Button：Button 内部的 Surface 会覆盖掉
 * 通过 modifier 施加的渐变背景，无法做出渐变按钮。
 *
 * [inverted] 用于「按钮本身位于 hero 渐变之上」的场景 —— 此时渐变按钮会与背景融掉，
 * 必须反白成白底品牌字。
 */
@Composable
fun AqPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    inverted: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val brush: Brush
    val fg: Color
    when {
        !enabled -> {
            brush = Brush.linearGradient(
                listOf(
                    if (inverted) Color.White.copy(alpha = 0.28f) else scheme.surfaceVariant,
                    if (inverted) Color.White.copy(alpha = 0.28f) else scheme.surfaceVariant,
                )
            )
            fg = if (inverted) Color.White.copy(alpha = 0.8f) else scheme.onSurfaceVariant
        }
        inverted -> {
            brush = Brush.linearGradient(listOf(Color.White, Color.White))
            fg = scheme.primary
        }
        else -> {
            brush = AqTheme.heroBrush
            fg = Color.White
        }
    }

    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(AqRadius.button))
            .background(brush)
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = fg,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text,
                color = fg,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 次要按钮：描边式，用于并列的次级动作 */
@Composable
fun AqGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AqRadius.button)
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, scheme.outline, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 纯文字动作，用于「从剪贴板粘贴 / 清空」这类轻量操作 */
@Composable
fun AqTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
    )
}

// ── 输入 ───────────────────────────────────────────────────────

/**
 * 分享链接输入区。
 *
 * 三个刻意的设计决定：
 *  1. 用虚线框而非实线输入框 —— 明确传达「把东西丢进来」
 *  2. 实时显示识别出的平台（带品牌色）—— 用户粘贴后立刻知道「它认出来了」
 *  3. 不用 Material 的 OutlinedTextField —— 那四条边一个颜色的方框是最强的模板信号
 */
@Composable
fun AqLinkInput(
    value: String,
    onValueChange: (String) -> Unit,
    detected: Platform,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = AqTheme.extras
    val scheme = MaterialTheme.colorScheme

    AqDashedBox(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "分享链接",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                when {
                    detected != Platform.UNKNOWN -> AqPlatformPill(detected, compact = true)
                    value.isNotBlank() -> AqTag("未识别的链接")
                    else -> Unit
                }
            }

            Spacer(Modifier.height(10.dp))

            Box(Modifier.fillMaxWidth()) {
                if (value.isBlank()) {
                    Text(
                        "把链接或整段分享文案粘到这里",
                        color = extras.faint,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 74.dp),
                    textStyle = TextStyle(
                        color = scheme.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    ),
                    cursorBrush = SolidColor(scheme.primary),
                    maxLines = 5,
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                AqTextAction("从剪贴板粘贴", onPaste)
                if (value.isNotBlank()) {
                    AqTextAction("清空", onClear, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${value.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.faint,
                )
            }
        }
    }
}

/**
 * 表单字段。
 *
 * 与 [AqLinkInput] 的区别：这里是「已确定要填的东西」（Cookie / 接口地址），
 * 所以用实线描边的紧凑输入框，而不是虚线大框。
 */
@Composable
fun AqField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    badge: String = "",
    placeholder: String = "",
    hint: String = "",
    multiline: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            if (badge.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                AqTag(badge, color = scheme.primary, background = scheme.primaryContainer)
            }
            Spacer(Modifier.weight(1f))
            if (value.isNotBlank()) {
                AqTextAction("清除", { onValueChange("") }, color = scheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(7.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(scheme.surfaceVariant.copy(alpha = 0.55f))
                .border(1.dp, scheme.outline, shape)
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            if (value.isBlank() && placeholder.isNotBlank()) {
                Text(
                    placeholder,
                    color = AqTheme.extras.faint,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (multiline) 46.dp else 20.dp),
                textStyle = TextStyle(
                    color = scheme.onSurface,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                ),
                cursorBrush = SolidColor(scheme.primary),
                singleLine = !multiline,
                maxLines = if (multiline) 4 else 1,
            )
        }

        if (hint.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = AqTheme.extras.faint,
                lineHeight = 17.sp,
            )
        }
    }
}

/** 「标签 —— 值」信息行，用于关于区 */
@Composable
fun AqInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── 进度与提示 ─────────────────────────────────────────────────

/** 自绘的往复式进度条（比默认的 LinearProgressIndicator 更贴合品牌） */
@Composable
fun AqLoadingStrip(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val transition = rememberInfiniteTransition(label = "aq-strip")
    // 刻意不用 `by` 委托：进度值若在 composable 体内读取，动画每帧都会触发**重组**；
    // 改成在 Canvas 的绘制 lambda 里读 `.value`，则只触发**重绘**（draw phase 快照跟踪）。
    val progressState = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "aq-strip-progress",
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
    ) {
        val progress = progressState.value
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = track, cornerRadius = radius)
        val barWidth = size.width * 0.34f
        val x = (size.width + barWidth) * progress - barWidth
        drawRoundRect(
            color = color,
            topLeft = Offset(x, 0f),
            size = Size(barWidth, size.height),
            cornerRadius = radius,
        )
    }
}

/** 解析中状态面板 */
@Composable
fun AqParsingPanel(hint: String, modifier: Modifier = Modifier) {
    AqPanel(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "正在解析",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        AqLoadingStrip()
    }
}

/** 错误面板：标题 + 原因 + 可执行建议 */
@Composable
fun AqErrorPanel(
    message: String,
    hint: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AqRadius.card))
            .background(scheme.errorContainer)
            .padding(16.dp),
    ) {
        Text(
            "没能解析",
            color = scheme.onErrorContainer,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            message,
            color = scheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 19.sp,
        )
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(9.dp))
            Row {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(scheme.onErrorContainer.copy(alpha = 0.6f))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    hint,
                    color = scheme.onErrorContainer.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                    lineHeight = 18.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        AqTextAction("知道了", onDismiss, color = scheme.onErrorContainer)
    }
}

/** 正向提示条：例如「已取到无损原图 1206×2622」 */
@Composable
fun AqPositiveNote(text: String, modifier: Modifier = Modifier) {
    val positive = AqTheme.extras.positive
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(positive))
        Spacer(Modifier.width(7.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = positive,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── 媒体 ───────────────────────────────────────────────────────

/**
 * 封面 hero 卡。
 *
 * 把封面、平台、清晰度、标题、作者一次性叠在一张图上 —— 这是「内容 App」的呈现方式，
 * 而不是「字段一行一行往下排」的表单方式。
 */
@Composable
fun AqCoverHero(
    cover: String,
    platform: Platform,
    title: String,
    author: String,
    stats: String,
    badge: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val hasCover = cover.isNotBlank()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp, max = 260.dp)
            .clip(RoundedCornerShape(AqRadius.card))
            // 无封面时要换成深色底：卡上的标题恒为白字（因为正常情况是压在图片上），
            // 若沿用 surfaceVariant 这类浅色底，浅色模式下会变成「白字白底」看不清。
            .background(if (hasCover) scheme.surfaceVariant else Color(0xFF2A2D36)),
    ) {
        if (hasCover) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }

        // 底部压暗，保证白字可读。
        // 注意必须用 `colors =` 的 List<Color> 重载：Brush 没有接受
        // List<Pair<Float, Color>> 的重载（色标只能用 vararg 或 Array 传），
        // 写 listOf(0f to X, 1f to Y) 会因找不到匹配重载而编译失败。
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color(0xE6000000),
                        )
                    )
                )
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AqPlatformPill(platform, onDark = true)
            if (badge.isNotBlank()) {
                AqTag(badge, color = Color.White, background = Color.White.copy(alpha = 0.22f))
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                title.ifBlank { "（无标题）" },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                listOf(author.ifBlank { "未知作者" }, stats)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 图集缩略图：左上角标序号，点按即保存该张 */
@Composable
fun AqImageThumb(
    item: MediaItem,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(AqRadius.thumb))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.previewUrl.ifBlank { item.url },
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(
                "${index + 1}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ── 空态 ───────────────────────────────────────────────────────

/** 空态引导：标题 + 编号步骤 */
@Composable
fun AqStepGuide(
    title: String,
    steps: List<String>,
    footnote: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    AqPanel(modifier = modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(14.dp))
        steps.forEachIndexed { index, step ->
            Row(modifier = Modifier.padding(vertical = 5.dp)) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        color = scheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(11.dp))
                Text(
                    step,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                )
            }
        }
        if (footnote.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.secondaryContainer.copy(alpha = 0.55f))
                    .padding(12.dp),
            ) {
                Text(
                    footnote,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSecondaryContainer,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

/** 通用空态 */
@Composable
fun AqEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 工具 ───────────────────────────────────────────────────────

/** 字节数转可读体积 */
fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    return if (bytes >= 1048576L) {
        String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    } else {
        "${bytes / 1024} KB"
    }
}

/** 把「已取到无损原图」之类的尺寸说明拼出来 */
fun formatDimension(width: Int, height: Int): String =
    if (width > 0 && height > 0) "$width×$height" else ""
