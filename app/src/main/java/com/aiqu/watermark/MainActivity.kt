package com.aiqu.watermark

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aiqu.watermark.ui.AppViewModel
import com.aiqu.watermark.ui.history.HistoryScreen
import com.aiqu.watermark.ui.home.HomeScreen
import com.aiqu.watermark.ui.settings.SettingsScreen
import com.aiqu.watermark.ui.theme.AqTheme
import com.aiqu.watermark.ui.theme.AqWatermarkTheme

/**
 * 应用唯一入口。
 *
 * 结构上的两个决定：
 *  1. **底部导航**取代了「每个页面各带一个返回箭头的 AppBar」—— 只有三个平级目的地，
 *     导航栏比层层返回更符合直觉，也避免了三套 AppBar 的观感重复。
 *  2. **Toast 与免责声明提到这一层** —— 提到根节点后，任意页面触发的提示都能立刻弹出；
 *     之前的写法把它们放在首页，导致在历史页触发的提示要等切回首页才出现。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AqWatermarkTheme {
                val vm: AppViewModel = viewModel()
                val nav = rememberNavController()
                val context = LocalContext.current

                val backStackEntry by nav.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route ?: ROUTE_PARSE

                LaunchedEffect(Unit) { vm.refreshHistory() }

                // 全局提示：任意页面设置 toastMessage 都立即生效
                LaunchedEffect(vm.toastMessage) {
                    if (vm.toastMessage.isNotBlank()) {
                        Toast.makeText(context, vm.toastMessage, Toast.LENGTH_SHORT).show()
                        vm.consumeToast()
                    }
                }

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                    containerColor = MaterialTheme.colorScheme.background,
                    // 顶部交给各页面的 hero 自行处理状态栏内边距（渐变要延伸到状态栏下），
                    // 因此这里清零，避免出现「状态栏高度被算两次」的空白。
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        AqBottomBar(current = currentRoute, onSelect = { route ->
                            if (route != currentRoute) nav.switchTab(route)
                        })
                    },
                ) { padding ->
                    NavHost(
                        navController = nav,
                        startDestination = ROUTE_PARSE,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        composable(ROUTE_PARSE) {
                            HomeScreen(vm = vm)
                        }
                        composable(ROUTE_HISTORY) {
                            HistoryScreen(vm = vm, onOpenParse = { nav.switchTab(ROUTE_PARSE) })
                        }
                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(vm = vm)
                        }
                    }
                }

                if (!vm.disclaimerAccepted) {
                    AlertDialog(
                        onDismissRequest = { vm.acceptDisclaimer() },
                        title = { Text("使用前请知悉") },
                        text = {
                            Text(
                                "本工具仅用于个人学习与收藏：请勿将保存的素材用于商业用途，" +
                                    "也不要二次公开发布。素材版权归原作者所有，请尊重原创。\n\n" +
                                    "解析出的是平台公开可访问的媒体地址，应用不修改、不伪造任何内容。"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { vm.acceptDisclaimer() }) { Text("我知道了") }
                        },
                    )
                }
            }
        }
    }
}

// ── 底部导航 ───────────────────────────────────────────────────

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val NAV_ITEMS = listOf(
    NavItem(ROUTE_PARSE, "解析", Icons.Filled.Search),
    NavItem(ROUTE_HISTORY, "历史", Icons.Filled.History),
    NavItem(ROUTE_SETTINGS, "设置", Icons.Filled.Settings),
)

private const val ROUTE_PARSE = "parse"
private const val ROUTE_HISTORY = "history"
private const val ROUTE_SETTINGS = "settings"

/**
 * 切换标签页。
 *
 * 用 `popUpTo(起始路由)` 而不是 `popUpTo(nav.graph.startDestinationId)`：
 * 后者依赖 NavGraph 已初始化，且在导航库升级时行为偶有变化；
 * 起始路由是常量，直接写死最稳。
 */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(ROUTE_PARSE) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 自绘底栏：选中项是一枚渐变胶囊，而不是默认的圆形 indicator + 小圆点文字。
 */
@Composable
private fun AqBottomBar(current: String, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extras = AqTheme.extras

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(extras.hairline)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NAV_ITEMS.forEach { item ->
                val selected = item.route == current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .then(if (selected) Modifier.background(AqTheme.heroBrush) else Modifier)
                        .clickable { onSelect(item.route) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) Color.White else scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            item.label,
                            color = if (selected) Color.White else scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
