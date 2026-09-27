package com.aiqu.watermark.ui.home

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiqu.watermark.data.model.ParsedMedia
import com.aiqu.watermark.data.model.Platform
import com.aiqu.watermark.media.MediaSaver
import com.aiqu.watermark.net.Platforms
import com.aiqu.watermark.ui.AppViewModel
import com.aiqu.watermark.ui.components.AqCoverHero
import com.aiqu.watermark.ui.components.AqErrorPanel
import com.aiqu.watermark.ui.components.AqGhostButton
import com.aiqu.watermark.ui.components.AqHero
import com.aiqu.watermark.ui.components.AqImageThumb
import com.aiqu.watermark.ui.components.AqLinkInput
import com.aiqu.watermark.ui.components.AqPanel
import com.aiqu.watermark.ui.components.AqParsingPanel
import com.aiqu.watermark.ui.components.AqPositiveNote
import com.aiqu.watermark.ui.components.AqPrimaryButton
import com.aiqu.watermark.ui.components.AqSectionHeader
import com.aiqu.watermark.ui.components.AqStepGuide
import com.aiqu.watermark.ui.components.AqTag
import com.aiqu.watermark.ui.components.AqTextAction
import com.aiqu.watermark.ui.components.formatDimension
import com.aiqu.watermark.ui.components.formatSize
import com.aiqu.watermark.ui.douyin.DouyinCaptureActivity
import com.aiqu.watermark.ui.douyin.DouyinCaptureHolder
import com.aiqu.watermark.ui.theme.channelLabel
import com.aiqu.watermark.ui.util.Clipboards

/**
 * 解析首页。
 *
 * 版面顺序刻意是「输入优先」：
 *   hero 渐变（标题 + 价值说明 + 虚线输入区 + 反白主按钮）
 *   → 状态 / 结果
 * 而不是先摆一堆卡片再找输入框。粘链接是这个应用唯一的入口动作，它就该占据首屏视觉重心。
 */
@Composable
fun HomeScreen(vm: AppViewModel) {
    val context = LocalContext.current

    val detected = remember(vm.input) { Platforms.detect(vm.input) }

    val douyinLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val json = DouyinCaptureHolder.lastAwemeJson
            if (json.isNullOrBlank()) vm.onDouyinCancelled() else vm.onDouyinCaptured(json)
        } else {
            vm.onDouyinCancelled()
        }
        DouyinCaptureHolder.clear()
    }

    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) vm.showToast("未授予存储权限，将无法保存到相册")
    }

    // 需要浏览器解析抖音时，拉起抓取页
    LaunchedEffect(vm.pendingDouyinUrl) {
        val url = vm.pendingDouyinUrl
        if (!url.isNullOrBlank()) {
            vm.consumePendingDouyin()
            // 启动前先清空中转站，避免上一次的残余结果被误用
            DouyinCaptureHolder.clear()
            val intent = Intent(context, DouyinCaptureActivity::class.java)
                .putExtra(DouyinCaptureActivity.EXTRA_URL, url)
                .putExtra(DouyinCaptureActivity.EXTRA_COOKIE, vm.settings.douyinCookie)
            douyinLauncher.launch(intent)
        }
    }

    // Android 9 及以下保存相册需要存储权限，进入时先申请
    LaunchedEffect(Unit) {
        if (MediaSaver.needsLegacyStoragePermission() && !MediaSaver.hasLegacyStoragePermission(context)) {
            storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        AqHero(
            title = "爱去水印",
            subtitle = "抖音 / 小红书 / 哔哩哔哩 · 无水印原片直存相册",
        ) {
            Spacer(Modifier.height(18.dp))
            AqLinkInput(
                value = vm.input,
                onValueChange = vm::onInput,
                detected = detected,
                onPaste = {
                    val text = Clipboards.read(context)
                    if (text.isBlank()) vm.showToast("剪贴板是空的") else vm.onInput(text)
                },
                onClear = { vm.onInput("") },
            )
            Spacer(Modifier.height(14.dp))
            AqPrimaryButton(
                text = if (vm.parsing) "解析中…" else "开始解析",
                onClick = { vm.parse() },
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.input.isNotBlank(),
                loading = vm.parsing,
                inverted = true,
            )
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(18.dp))

            when {
                vm.parsing -> AqParsingPanel(hint = hintForParsing(detected))

                vm.error.isNotBlank() -> AqErrorPanel(
                    message = vm.error,
                    hint = vm.errorHint,
                    onDismiss = { vm.consumeError() },
                )

                vm.result != null -> ResultSections(vm, vm.result!!)

                else -> AqStepGuide(
                    title = "三步拿到无水印素材",
                    steps = listOf(
                        "在抖音 / 小红书 / B站 内点「分享 → 复制链接」",
                        "回到这里粘贴，点「开始解析」",
                        "保存到相册；B站可在设置里填 SESSDATA 取 1080P",
                    ),
                    footnote = "抖音会启动本机浏览器解析：由抖音自己的页面生成合法请求，" +
                        "应用只读取结果，不伪造签名、不内置任何第三方代码。" +
                        "B站与小红书无需任何登录即可使用。",
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 解析中的一句话提示：告诉用户「现在在等什么」 */
private fun hintForParsing(platform: Platform): String = when (platform) {
    Platform.DOUYIN -> "正在还原短链"
    Platform.BILIBILI -> "正在获取播放流"
    Platform.XHS -> "正在读取笔记"
    Platform.UNKNOWN -> "正在识别平台"
}

// ── 结果区 ─────────────────────────────────────────────────────

@Composable
private fun ResultSections(vm: AppViewModel, r: ParsedMedia) {
    AqCoverHero(
        cover = r.cover,
        platform = r.platform,
        title = r.title,
        author = r.author.name,
        stats = r.statsText,
        badge = r.video?.qualityLabel
            ?: if (r.images.isNotEmpty()) "${r.images.size} 张原图" else "",
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(12.dp))
    MetaLine(r)

    if (r.hasVideo) {
        Spacer(Modifier.height(12.dp))
        VideoSection(vm, r)
    }

    if (r.images.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        ImagesSection(vm, r)
    }

    Spacer(Modifier.height(12.dp))
    CopyRow(vm, r)
}

/** 元信息行：通道 · 耗时 · 类型 */
@Composable
private fun MetaLine(r: ParsedMedia) {
    val parts = buildList {
        add(channelLabel(r.channel))
        if (r.costMs > 0L) add("${r.costMs} ms")
        if (r.type == "mixed") add("图 + 视频")
    }.filter { it.isNotBlank() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEach { AqTag(it) }
    }
}

@Composable
private fun VideoSection(vm: AppViewModel, r: ParsedMedia) {
    val v = r.video ?: return
    AqPanel {
        AqSectionHeader("视频") {
            Text(
                listOf(
                    formatDimension(v.width, v.height),
                    formatSize(v.size),
                ).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        AqPrimaryButton(
            text = if (vm.saving) vm.savingText.ifBlank { "保存中…" } else "保存视频到相册",
            onClick = { vm.saveVideo() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !vm.saving,
            loading = vm.saving,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "直接从平台 CDN 下载到本机，不经过任何服务器中转，也不上传任何内容。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImagesSection(vm: AppViewModel, r: ParsedMedia) {
    val first = r.images.firstOrNull()

    AqPanel {
        AqSectionHeader("图集 · ${r.images.size} 张") {
            AqTextAction(
                text = if (vm.saving) vm.savingText.ifBlank { "保存中…" } else "全部保存",
                onClick = { vm.saveAllImages() },
            )
        }

        if (first != null && first.width > 0) {
            Spacer(Modifier.height(8.dp))
            AqPositiveNote("无损原图 ${formatDimension(first.width, first.height)}")
        }

        Spacer(Modifier.height(12.dp))

        // 手写网格而不是 LazyVerticalGrid：后者嵌在 verticalScroll 里会因高度无限而崩溃
        r.images.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEachIndexed { i, item ->
                    AqImageThumb(
                        item = item,
                        index = r.images.indexOf(item).takeIf { it >= 0 } ?: i,
                        onClick = { vm.saveImageAt(r.images.indexOf(item)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        if (vm.saveProgress.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                vm.saveProgress,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "点按任意一张保存该张。全部保存为逐张下载，请保持应用在前台。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CopyRow(vm: AppViewModel, r: ParsedMedia) {
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AqGhostButton(
            text = "复制文案",
            onClick = {
                val text = r.desc.ifBlank { r.title }
                if (text.isBlank()) {
                    vm.showToast("没有可复制的文案")
                } else {
                    vm.showToast(if (Clipboards.copy(context, text)) "已复制文案" else "复制失败")
                }
            },
            modifier = Modifier.weight(1f),
        )
        AqGhostButton(
            text = "复制直链",
            onClick = {
                val link = r.video?.url ?: r.images.firstOrNull()?.url ?: ""
                if (link.isBlank()) {
                    vm.showToast("没有可复制的直链")
                } else {
                    vm.showToast(if (Clipboards.copy(context, link)) "已复制直链" else "复制失败")
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}
