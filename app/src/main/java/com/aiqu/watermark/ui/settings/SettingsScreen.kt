package com.aiqu.watermark.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiqu.watermark.BuildConfig
import com.aiqu.watermark.data.model.Platform
import com.aiqu.watermark.ui.AppViewModel
import com.aiqu.watermark.ui.components.AqField
import com.aiqu.watermark.ui.components.AqGhostButton
import com.aiqu.watermark.ui.components.AqHero
import com.aiqu.watermark.ui.components.AqInfoRow
import com.aiqu.watermark.ui.components.AqPanel
import com.aiqu.watermark.ui.components.AqPlatformPill
import com.aiqu.watermark.ui.components.AqPrimaryButton
import com.aiqu.watermark.ui.components.AqSectionHeader
import com.aiqu.watermark.ui.theme.AqTheme

/**
 * 设置。
 *
 * 布局上把原来「一张长卡片里塞三个输入框 + 一个保存按钮」拆成四组独立面板：
 * 登录态 / 备用通道 / 数据 / 关于。每组有自己的标题、说明与保存动作，
 * 避免所有控件糊成一坨 —— 这是模板式设置页最典型的问题。
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    var bili by remember { mutableStateOf(vm.settings.bilibiliCookie) }
    var xhs by remember { mutableStateOf(vm.settings.xhsCookie) }
    var douyin by remember { mutableStateOf(vm.settings.douyinCookie) }
    var providerUrl by remember { mutableStateOf(vm.settings.douyinProviderUrl) }
    var providerToken by remember { mutableStateOf(vm.settings.douyinProviderToken) }

    fun persist() {
        vm.settings.bilibiliCookie = bili
        vm.settings.xhsCookie = xhs
        vm.settings.douyinCookie = douyin
        vm.settings.douyinProviderUrl = providerUrl
        vm.settings.douyinProviderToken = providerToken
        vm.showToast("已保存")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        AqHero(
            title = "设置",
            subtitle = "所有凭据只写在本机，不会上传到任何服务器",
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))

            // ── 平台登录态 ──
            AqPanel {
                AqSectionHeader("平台登录态") {
                    Text(
                        "可选",
                        style = MaterialTheme.typography.labelSmall,
                        color = AqTheme.extras.faint,
                    )
                }
                Spacer(Modifier.height(7.dp))
                AqHint("不填也能解析公开内容。填写只会提升清晰度或成功率，不改变解析方式。")

                Spacer(Modifier.height(18.dp))
                AqPlatformHead(Platform.BILIBILI, "用于取 1080P")
                Spacer(Modifier.height(10.dp))
                AqField(
                    label = "SESSDATA",
                    value = bili,
                    onValueChange = { bili = it },
                    placeholder = "只填值即可，例如 7f2c……",
                    hint = "浏览器登录 B站 → F12 → Application → Cookies → 复制 SESSDATA 的值",
                    multiline = true,
                )

                Spacer(Modifier.height(18.dp))
                AqPlatformHead(Platform.DOUYIN, "建议填写")
                Spacer(Modifier.height(10.dp))
                AqField(
                    label = "登录 Cookie 原文",
                    value = douyin,
                    onValueChange = { douyin = it },
                    placeholder = "整段 Cookie 直接粘贴",
                    hint = "抖音详情接口有浏览器级门禁，带登录态可显著提升本机浏览器通道的成功率",
                    multiline = true,
                )

                Spacer(Modifier.height(18.dp))
                AqPlatformHead(Platform.XHS, "一般不需要")
                Spacer(Modifier.height(10.dp))
                AqField(
                    label = "Cookie",
                    value = xhs,
                    onValueChange = { xhs = it },
                    placeholder = "留空即可，仅当解析失败时再填",
                    multiline = true,
                )

                Spacer(Modifier.height(20.dp))
                AqPrimaryButton(
                    text = "保存",
                    onClick = { persist() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 抖音备用通道 ──
            AqPanel {
                AqSectionHeader("抖音备用通道")
                Spacer(Modifier.height(7.dp))
                AqHint(
                    "默认使用本机浏览器解析，无需任何配置。若你另有第三方解析接口，" +
                        "填在这里可作为失败时的备用；留空则完全不走外部服务。"
                )
                Spacer(Modifier.height(16.dp))
                AqField(
                    label = "接口地址",
                    value = providerUrl,
                    onValueChange = { providerUrl = it },
                    placeholder = "https://example.com/api/parse",
                )
                Spacer(Modifier.height(14.dp))
                AqField(
                    label = "令牌",
                    value = providerToken,
                    onValueChange = { providerToken = it },
                    placeholder = "可选，将以 Authorization: Bearer 发送",
                )
                Spacer(Modifier.height(18.dp))
                AqGhostButton(
                    text = "保存备用通道",
                    onClick = { persist() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 数据 ──
            AqPanel {
                AqSectionHeader("数据")
                Spacer(Modifier.height(14.dp))
                AqGhostButton(
                    text = "清空历史记录（${vm.historyList.size} 条）",
                    onClick = {
                        vm.clearHistory()
                        vm.showToast("已清空历史记录")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                AqGhostButton(
                    text = "重新显示免责声明",
                    onClick = {
                        vm.resetDisclaimer()
                        vm.showToast("下次进入解析页时会重新显示")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                AqHint(
                    "下载过程中会在应用缓存目录生成临时文件，保存成功后立即删除；" +
                        "也可在系统设置里清除应用缓存。"
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 关于 ──
            AqPanel {
                AqSectionHeader("关于")
                Spacer(Modifier.height(10.dp))
                AqInfoRow("应用", "爱去水印")
                HorizontalDivider(color = AqTheme.extras.hairline)
                AqInfoRow("版本", BuildConfig.VERSION_NAME)
                HorizontalDivider(color = AqTheme.extras.hairline)
                AqInfoRow("哔哩哔哩", "自研解析")
                HorizontalDivider(color = AqTheme.extras.hairline)
                AqInfoRow("小红书", "自研解析")
                HorizontalDivider(color = AqTheme.extras.hairline)
                AqInfoRow("抖音", "本机浏览器")
                Spacer(Modifier.height(14.dp))
                AqHint(
                    "本工具仅用于个人学习与收藏，请勿将保存的素材用于商业用途或二次公开发布。" +
                        "素材版权归原作者所有。"
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 平台小节标题：品牌色标签 + 一句用途说明 */
@Composable
private fun AqPlatformHead(platform: Platform, note: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AqPlatformPill(platform, compact = true)
        Spacer(Modifier.width(8.dp))
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = AqTheme.extras.faint,
        )
    }
}

/** 面板内的辅助说明文字 */
@Composable
private fun AqHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 17.sp,
    )
}
