package com.aiqu.watermark.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aiqu.watermark.data.model.HistoryEntry
import com.aiqu.watermark.data.model.Platform
import com.aiqu.watermark.ui.AppViewModel
import com.aiqu.watermark.ui.components.AqEmptyState
import com.aiqu.watermark.ui.components.AqHero
import com.aiqu.watermark.ui.components.AqPlatformPill
import com.aiqu.watermark.ui.components.AqTag
import com.aiqu.watermark.ui.components.AqTextAction
import com.aiqu.watermark.ui.theme.AqRadius
import com.aiqu.watermark.ui.theme.AqTheme
import com.aiqu.watermark.ui.util.Clipboards
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录。
 *
 * 交互上做了两处与「模板」不同的选择：
 *  · 点按一条记录弹出**底部动作表**（bottom sheet），而不是系统 AlertDialog ——
 *    后者会把标题和三个按钮挤在一张小卡片里，且打断感很强
 *  · 整行本身就是可点区域，没有额外的「更多」按钮，减少一次点击
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: AppViewModel, onOpenParse: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var confirmClear by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<HistoryEntry?>(null) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(Unit) { vm.refreshHistory() }

    val list = vm.historyList

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            AqHero(
                title = "历史记录",
                subtitle = if (list.isEmpty()) {
                    "解析过的内容会自动记录，仅保存在这台设备上"
                } else {
                    "共 ${list.size} 条 · 仅保存在本机"
                },
                actions = {
                    if (list.isNotEmpty()) {
                        AqTextAction("清空", { confirmClear = true }, color = Color.White)
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
        }

        if (list.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AqEmptyState(
                        title = "还没有解析记录",
                        subtitle = "解析成功后会自动记在这里，方便随时找回。",
                    )
                }
            }
        } else {
            items(list, key = { it.id }) { entry ->
                HistoryRow(
                    entry = entry,
                    onClick = { actionTarget = entry },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空历史") },
            text = { Text("将删除全部本地历史记录，操作不可恢复。已保存到相册的素材不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    actionTarget?.let { entry ->
        // 先播完收起动画再销毁，否则 bottom sheet 会「啪」地消失
        val dismiss = {
            scope.launch { sheetState.hide() }.invokeOnCompletion { actionTarget = null }
        }

        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            ) {
                Text(
                    entry.title.ifBlank { "（无标题）" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(9.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AqPlatformPill(Platform.fromKey(entry.platformKey), compact = true)
                    AqTag(formatTime(entry.createdAt))
                    if (entry.hasVideo) AqTag("视频")
                    if (entry.imageCount > 0) AqTag("${entry.imageCount} 张图")
                }

                Spacer(Modifier.height(16.dp))

                SheetAction(
                    title = "立即解析",
                    subtitle = "重新走一遍解析流程，拿到最新地址",
                    onClick = {
                        dismiss()
                        vm.loadIntoInput(entry.sourceUrl)
                        onOpenParse()
                    },
                )
                SheetAction(
                    title = "复制分享链接",
                    subtitle = entry.sourceUrl.take(60),
                    onClick = {
                        val ok = Clipboards.copy(context, entry.sourceUrl)
                        vm.showToast(if (ok) "已复制分享链接" else "复制失败")
                        dismiss()
                    },
                )
                SheetAction(
                    title = "删除这条记录",
                    subtitle = "只删除记录，不影响已保存的素材",
                    dangerous = true,
                    onClick = {
                        vm.removeHistory(entry.id)
                        dismiss()
                    },
                )
            }
        }
    }
}

/** 底部动作表里的一行 */
@Composable
private fun SheetAction(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    dangerous: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val titleColor = if (dangerous) scheme.error else scheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = titleColor,
            fontWeight = FontWeight.Medium,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = AqTheme.extras.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AqRadius.card)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, AqTheme.extras.hairline, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(AqRadius.thumb))
                .background(scheme.surfaceVariant),
        ) {
            if (entry.cover.isNotBlank()) {
                AsyncImage(
                    model = entry.cover,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AqPlatformPill(Platform.fromKey(entry.platformKey), compact = true)
                Spacer(Modifier.weight(1f))
                Text(
                    formatTime(entry.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = AqTheme.extras.faint,
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                entry.title.ifBlank { "（无标题）" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                buildString {
                    if (entry.author.isNotBlank()) append(entry.author)
                    val kind = buildList {
                        if (entry.hasVideo) add("视频")
                        if (entry.imageCount > 0) add("${entry.imageCount} 张图")
                    }.joinToString(" + ")
                    if (kind.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(kind)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatTime(ts: Long): String =
    if (ts <= 0L) "" else timeFormat.format(Date(ts))
