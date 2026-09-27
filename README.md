# 爱去水印 · Android 版

粘贴抖音 / 小红书 / B站 的分享链接 → 解析出无水印原片与无损原图 → 直接下载到相册。

**无需服务器、无需云服务、无需账号、零月费。**

---

## 为什么换成 Android —— 它直接消除了原来的三个死结

| 原小程序版的问题 | Android 版如何解决 |
|---|---|
| 抖音要求 TLS 指纹匹配 Chrome，Node/云函数做不到 → 必须租云托管（月费） | **Android 系统 WebView 本身就是 Chromium**。由它加载抖音页面，**抖音自己的 SDK** 生成合法请求，应用只读取结果 → **零成本**，且不伪造任何签名、不内置第三方专有代码 |
| 小程序 `downloadFile` 强制域名白名单 → 文件必须经云函数→云存储中转 | 应用可带 Referer / Cookie 直连平台 CDN，下载后插入 MediaStore → **整个中转层消失** |
| 必须注册小程序 appid、开通云开发、部署 3 个云函数 | **什么都不需要**，装上就能用 |

代价：分发给他人需自行装 APK；以及下面「构建后需要你确认的几点」。

---

## 工程结构

```
aq-watermark-android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradlew / gradlew.bat / .gitignore
├── .github/workflows/build-apk.yml   CI：产出 debug + release APK
├── gradle/
│   ├── libs.versions.toml            版本目录（AGP 8.7.3 / Kotlin 2.1.0 / Compose BOM 2025.01.00）
│   └── wrapper/                      gradle-wrapper.jar + properties（Gradle 8.9）
└── app/
    ├── build.gradle.kts              无 KSP、无 Room，依赖最少
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml       权限：INTERNET；WRITE_EXTERNAL_STORAGE 仅限 API≤28
        ├── res/                      主题、配色、自适应图标、网络安全配置
        └── java/com/aiqu/watermark/
            ├── AqWatermarkApp.kt
            ├── MainActivity.kt       Compose + Navigation
            ├── data/model/Models.kt  统一数据结构与错误类型
            ├── net/
            │   ├── Http.kt           OkHttp 封装（含流式下载）
            │   ├── Platforms.kt      平台识别 + 地址可用性校验
            │   └── platform/
            │       ├── BilibiliParser.kt   自研
            │       ├── XhsParser.kt        自研
            │       └── DouyinParser.kt     详情映射 + 可选第三方通道
            ├── media/MediaSaver.kt   下载 → 魔数识别 → MediaStore 写入相册
            ├── store/
            │   ├── SettingsStore.kt  SharedPreferences（Cookie 只存本机）
            │   └── HistoryStore.kt   本地 JSON 历史
            └── ui/
                ├── AppViewModel.kt   全应用统一状态
                ├── components/Aq.kt  ★ 自有视觉组件集（hero / 面板 / 标签 / 输入 / 媒体卡）
                ├── util/Clipboards.kt 系统剪贴板封装
                ├── theme/Theme.kt    配色、扩展色、圆角、平台品牌色
                ├── home/HomeScreen.kt
                ├── history/HistoryScreen.kt
                ├── settings/SettingsScreen.kt
                └── douyin/           ★ 抖音真实浏览器抓取
                    ├── DouyinCaptureActivity.kt
                    └── DouyinCaptureHolder.kt
```

---

## 界面设计

### 为什么自建组件集（`ui/components/Aq.kt`）

直接用 Material3 的 `TopAppBar + Card + OutlinedTextField` 会得到一套「模板观感」：
灰底 AppBar、带阴影的卡片、四条边一个颜色的输入框。这不是审美问题，而是**信息层级被抹平**——
用户看不出哪个是入口动作、哪个是次要信息。因此改用一套自有的视觉语言：

| 决策 | 替代做法 | 目的 |
|---|---|---|
| **hero 渐变头图**，渐变延伸到状态栏下 | 顶部一条浅色 AppBar | 首屏立刻有品牌识别度；标题与「输入」同处一个色块，视线不被打断 |
| **输入区是虚线框 + 实时平台识别** | 普通实线输入框 | 虚线传达「把东西丢进来」；识别出平台后立刻显示品牌色标签，用户马上知道「它认出来了」 |
| **主按钮在 hero 上反白**（白底品牌字） | 渐变按钮 | 渐变按钮叠在渐变背景上会融掉，必须反白 |
| **卡片无阴影，靠 1px 发丝线分层** | Material 的 tonal elevation | 减少视觉噪声，多层卡片叠加时不会发灰 |
| **平台标签用各平台自己的品牌色** | 统一的灰底 Chip | 一眼分辨平台；也让界面摆脱「一套灰底走天下」 |
| **结果页用封面 hero 卡**（图上叠标题/作者/清晰度） | 字段一行行往下排 | 内容类 App 的呈现方式，信息密度更高且更直观 |
| **底部导航用渐变胶囊** | 默认圆形 indicator | 选中态更明确，且与 hero 呼应 |
| **历史项操作走底部动作表** | 系统 AlertDialog | 动作带副标题说明，打断感更弱 |

所有色值与圆角集中在 `ui/theme/Theme.kt`（`AqExtras` + `AqRadius`），改一处全局生效。

### 配色

| 用途 | 浅色 | 深色 |
|---|---|---|
| 品牌主色 | `#FF2E63` | `#FF5C86` |
| hero 渐变 | `#FF3D6E → #FF7A4D` | `#FF4D7A → #FF8A5B` |
| 页面底色 | `#F5F6FA` | `#0D0F14` |
| 卡片 | `#FFFFFF` + `#ECEEF4` 描边 | `#15181F` + `#272C35` 描边 |
| 次要强调（备用通道/引导） | `#7C5CFF` | `#9E8AFF` |
| 平台色 | 抖音 `#16181D` · 小红书 `#FF2442` · B站 `#0AA6DC` | 同 |

深色模式通过 `isSystemInDarkTheme()` 自动切换，两套 `AqExtras` 分别承载渐变、发丝线、弱化文字。

### 版面结构（首页）

```
hero 渐变（通栏，含状态栏）
  ├ 标题 + 一句价值说明
  ├ 虚线输入区（实时平台标签 / 字数 / 从剪贴板粘贴）
  └ 反白主按钮「开始解析」
────────── 以下为页面底色 ──────────
解析中 → 自定义往复进度条 + 当前阶段提示
出错   → 错误面板（原因 + 可执行建议）
有结果 → 封面 hero 卡（图上叠平台/清晰度/标题/作者/互动数）
        元信息标签（解析通道 · 耗时）
        视频面板（渐变小主按钮 + 尺寸体积）
        图集面板（全部保存 + 三列缩略图，左上角序号）
        复制文案 / 复制直链（描边次按钮）
无结果 → 三步引导卡
底栏   → 渐变胶囊导航（解析 / 历史 / 设置）
```

### 抓取页

`DouyinCaptureActivity` 用经典 View 绘制（与 WebView 混用时 Compose 的 `AndroidView` 会引入额外的
生命周期与尺寸同步复杂度，而这一页只有「进度 + 浏览器 + 两个按钮」，收益为负），
但改用同一套渐变色与圆角按钮语言，因此不像「另一个应用」。

---

## 构建步骤

### 先上传到 GitHub

本地仓库已初始化并完成 2 次提交（分支 `main`），远端也已指向
`https://github.com/07vvvv/xixiqushuiyin.git`。**只剩推送这一步**，且必须在
**你自己的终端**里执行（沙箱环境下 Git Credential Manager 无法弹出浏览器完成登录）。

**第 1 步 · 推送**

打开一个终端（Windows Terminal / Git Bash / 资源管理器地址栏输入 `cmd` 后回车），执行：

```bash
cd /d D:\work-run\aq-watermark-android
git push -u origin main
```

首次推送会弹出 **Git Credential Manager** 的浏览器登录窗口（本机已安装），
登录一次即可，之后不再需要输密码。

**第 2 步 · 若它改问用户名密码**

GitHub 已不接受账户密码。到
Settings → Developer settings → **Personal access tokens → Tokens (classic)** 生成一个，
权限勾 **`repo`**，并且**必须**勾 **`workflow`** —— 仓库里有 `.github/workflows/` 文件，
少了 `workflow` 权限会被直接拒绝推送。生成后把它当作密码粘贴即可。

> 若提示 `rejected — fetch first`，说明远端不是空仓库（建仓库时勾了 README 之类）。
> 解决：`git push -u origin main --force`（本地是你唯一需要的版本，覆盖即可）。

**仓库已经建好了吗？** 未验证到远端内容，但 `git ls-remote` 返回 200 且无任何引用，
说明 `07vvvv/xixiqushuiyin` 存在且为空 —— 可以直接推，不会冲突。

**第 3 步 · 取 APK**

推送完成后打开仓库的 **Actions** 页，会看到 `Build APK` 正在运行（也可在 Actions 页点
`Run workflow` 手动触发）。构建结束、出现绿色对勾后，进入该次运行，
在页面底部的 **Artifacts** 里下载 `aiqu-watermark-apk`，内含：

- `aiqu-watermark-release.apk` ← 日常安装用这个
- `aiqu-watermark-debug.apk` ← 排查问题用

失败时同一位置会有 `build-reports` 工件，里面的编译日志可以直接发给我。

**关于工程根目录**：仓库根目录必须正好是 `settings.gradle.kts` 所在这一层。
本目录已经就是这个结构，直接推即可。另外 `.gitattributes` 已把 `gradlew` 锁定为 LF 换行，
避免 Linux runner 上报 `bad interpreter: /bin/sh^M`。

### 方式 A：GitHub Actions（推荐，无需本机装 Android SDK）

工程已包含 Gradle Wrapper 与工作流 `.github/workflows/build-apk.yml`。

1. 把 **本目录的内容**（`settings.gradle.kts`、`app/`、`gradle/`、`gradlew` 等）作为**仓库根目录**上传
2. 推送到 `main` / `master`，或在 Actions 页面点 `Run workflow`
3. 构建完成后在 **Artifacts** 里下载 `aiqu-watermark-apk`，内含：
   - `aiqu-watermark-release.apk` ← 日常安装用这个
   - `aiqu-watermark-debug.apk` ← 排查问题用
4. 若构建失败，同一页可下载 `build-reports` 工件，里面有编译日志

工作流要点：JDK 17 + Gradle 8.9；`release` 复用 debug 签名（`app/build.gradle.kts` 已配置），
因此**不需要在仓库里放任何签名密钥**；同时显式创建 debug keystore，避免不同镜像的行为差异。

> `local.properties` 不要上传（`.gitignore` 已排除）：它记录的是 Windows 本机 SDK 绝对路径，
> 一旦提交会让 CI 找不到 Android SDK。

### 方式 B：本机 Android Studio

- 打开本目录，等待 Gradle Sync 完成（首次会下载依赖）
- `Run → Run 'app'`，或 `./gradlew assembleDebug`
- 目标设备：Android 8.0（API 26）及以上

### 命令行等价写法

```bash
./gradlew assembleDebug assembleRelease --stacktrace
```

---

## 各平台通道

| 平台 | 通道 | 需要配置 | 质量 |
|---|---|---|---|
| **哔哩哔哩** | 自研 · 纯 REST | 无 | 免登录 720P；填 SESSDATA 可 1080P |
| **小红书** | 自研 · 页面解析 | 无 | 无损原图（源 PNG，实测 1206×2622） |
| **抖音** | **本机真实浏览器** | 建议填 Cookie | 取 `bit_rate` 最高档无水印地址；备选第三方接口 |

### 抖音是怎么工作的

点「开始解析」后会打开一个内置浏览器页（`DouyinCaptureActivity`），它做三件事：

1. **拦截主文档并注入钩子** —— 在页面自身脚本执行之前安装 XHR/fetch 监听，
   从而稳定捕获抖音自己发出的 `/aweme/v1/web/aweme/detail/` 响应（这是最可靠的手段）
2. 页面开始加载时再注入一次，应对不走主文档的场景
3. 轮询页面 `video.currentSrc` / `og:image` / title 作为兜底

拿到详情后按 `bit_rate` 取最高码率档 —— 即无水印地址。

若出现验证码或需要点击播放，**页面是可交互的，手动完成即可**，脚本会自动继续。

---

## 设置项说明

| 项 | 作用 | 获取方式 |
|---|---|---|
| B站 `SESSDATA` | 提升到 1080P | 浏览器登录 B站 → F12 → Application → Cookies |
| 抖音 `Cookie` | 提升浏览器通道成功率 | 浏览器打开 douyin.com → F12 → Network → 任一请求 → 复制 Cookie |
| 小红书 `Cookie` | 一般不需要 | 仅当解析失败时再填 |
| 抖音第三方接口 | 备用通道 | 你自备接口地址；留空则只用浏览器通道 |

Cookie 只写入本机 SharedPreferences，不外传。

---

## 静态排查记录（本轮已修）

沙箱内无 JDK/SDK，代码仍未编译验证；但本轮已做完整静态核对，并修复了下列**确定存在问题**：

| # | 位置 | 问题 | 严重度 |
|---|---|---|---|
| 1 | `HomeScreen.kt` | `r.images.firstOrNull()?.width ?: 0 > 0` —— Kotlin 中 `>` 优先级高于 `?:`，实际被解析为 `x ?: (0 > 0)`，条件类型推断为 `Any` | **编译失败** |
| 2 | `HistoryStore.kt` | `encodeToString` / `decodeFromString` 是 `kotlinx.serialization` 包下的**扩展函数**，缺少 import 会「Unresolved reference」。已改为显式 `ListSerializer` | **编译失败** |
| 3 | `DouyinParser.kt` | 正则里写了 `\u{1F000}`（JS/ICU 语法）。`java.util.regex` 只支持 `\uXXXX` 与 `\x{...}`，一旦解析抖音文案就抛 `PatternSyntaxException` | **运行时崩溃** |
| 4 | `DouyinCaptureActivity.kt` | READ_JS 把 `detail` 初始化为 JSON `null`，而 `org.json` 的 `optString` 对 JSON null 返回字符串 `"null"` —— 会被当作有效作品 JSON 传给解析器 | **运行时崩溃** |
| 5 | `AppViewModel.kt` | 抖音路径把**整段分享文案**（含口令）当作 URL 交给 `WebView.loadUrl`，页面根本不会打开该作品 | **功能失效** |
| 6 | `Platforms.kt` | `EMPTY_PATH_RE` 含 `(\?|$)`，会把合法的 `.../play/?video_id=v0200f...` 一并判为无效地址（抖音第三方通道返回的正是这种形态） | **功能失效** |
| 7 | `AndroidManifest.xml` | MainActivity 用了 `Theme.AqWatermark`，而 `installSplashScreen()` 读的是**当前 Activity 的 theme**，闪屏图标在 API 31+ 与兼容实现下均不生效 | 功能失效 |
| 8 | 全局 | `LocalClipboardManager` 在新版 Compose UI 中已废弃。已替换为系统 `ClipboardManager`（新增 `ui/util/Clipboards.kt`） | 兼容风险 |
| 9 | `MediaSaver.kt` | 图片下载不带 Referer/UA，B站与小红书图片存在 403 风险。已补 `referer` / `ua` 参数 | 资源失败 |
| 10 | 全局 | 抖音 CDN 大量返回 `http://`，而 Android 9+ 默认禁止明文流量。已改为「https 优先、原始 http 降级为备用」，并扩充白名单 | 资源失败 |

### 第二轮排查（界面重构同批）

| # | 位置 | 问题 | 严重度 |
|---|---|---|---|
| 11 | `AppViewModel` / `HomeScreen` | `settings.disclaimerAccepted` 读的是 SharedPreferences，**不是 Compose 状态** —— 点「我知道了」只写了磁盘不触发重组，免责声明弹窗会一直挂在屏幕上，直到用户碰巧改动其它 state 才消失。已改为 ViewModel 中的 `mutableStateOf` | **功能失效** |
| 12 | `MainActivity` | `toastMessage` 的监听只挂在首页，在历史页/设置页触发的提示要等切回首页才显示。已提到根节点统一处理 | **功能失效** |
| 13 | `AppViewModel` | `costMs` 从未被赋值，结果卡永远显示 `0 ms`。已在 `onParsed` 用解析起止时间计算（浏览器通道因含人工操作时长，不参与计时） | 信息错误 |
| 14 | `DouyinParser` | 第三方通道构造的 `VideoInfo` 缺 `referer`，抖音 CDN 防盗链会 403 | 资源失败 |
| 15 | `HomeScreen` | 拉起浏览器前未清空 `DouyinCaptureHolder`，上一次的残余结果可能被复用 | 竞态 |
| 16 | `DouyinCaptureActivity` | `private const val COLOR_BRAND_A = 0xFFFF3D6E.toInt()` —— `0xFFFF3D6E` 超出 Int 范围被推断为 Long，`.toInt()` 不是编译期常量，`const val` 会编译失败 | **编译失败** |
| 17 | `DouyinCaptureActivity` | `LinearLayout.addView(view)` 使用默认布局参数（宽度 WRAP_CONTENT），渐变头会缩成文字宽度而非通栏 | 布局错误 |

### 第三轮排查（上传前终检）

| # | 位置 | 问题 | 严重度 |
|---|---|---|---|
| 18 | `Aq.kt` `AqCoverHero` | `Brush.verticalGradient(listOf(0f to X, 1f to Y))` —— `Brush` **没有**接受 `List<Pair<Float, Color>>` 的重载（色标只能用 vararg 或 Array 传，`List` 只能传 `List<Color>`），找不到匹配重载 | **编译失败** |
| 19 | `Aq.kt` `AqCoverHero` | 封面为空时（纯文字笔记）底色是浅色 `surfaceVariant`，而卡上标题恒为白字 → 浅色模式下「白字白底」看不清。已改为无封面时用深色底 | 可读性缺陷 |
| 20 | `Aq.kt` `AqLoadingStrip` | 动画进度用 `by` 委托在 composable 体内读取 → **每帧触发重组**。已改为在 `Canvas` 的绘制 lambda 里读 `.value`，只触发重绘 | 性能 |

终检同时确认了以下高频出错点均无问题：
所有 `Modifier.weight` 都在 `Row`/`Column` 作用域内；不存在 `?:` 与比较运算符的优先级误用；
所有 `Brush.xxxGradient` 传的都是 `List<Color>`；无 composable 调用落在非 inline lambda 中；
`R.string.*` 与 `strings.xml` 一一对应；清单里的主题/颜色/图标引用全部存在。

### 第四轮：对照真实库源码逐一对签名（本地无 JDK/SDK 的替代验证）

本机没有 JDK 与 Android SDK，无法本地编译。改为把工程**实际依赖版本**的 `-sources.jar`
从 `dl.google.com` Maven 拉下来，直接核对每个被调用 API 的原始声明：

- Compose BOM 2025.01.00 → material3 **1.3.1** / ui、foundation、runtime、animation-core **1.7.6**
- 逐个核对 47 个 API（Scaffold、ModalBottomSheet、BasicTextField、Brush、WindowInsets、
  `Modifier.*`、LazyColumn、动画 API…）与 OkHttp / kotlinx-serialization / Coil / Navigation / activity 的调用

**查出 1 个真实编译错误：**

| # | 位置 | 问题 | 严重度 |
|---|---|---|---|
| 21 | `net/Http.kt` | `OkHttp` 的 `HttpUrl.newBuilder(link: String)` 返回的是**可空** `Builder?`（链接非法时返回 null），而代码写成 `newBuilder(loc).build()` —— 在可空接收者上直接调用。已改为 `newBuilder(loc)?.build()` | **编译失败** |

> 佐证（源码原文）：`okhttp-4.12.0-sources.jar :: HttpUrl.kt:735`
> `fun newBuilder(link: String): Builder? { return try { Builder().parse(this, link) } catch (_: IllegalArgumentException) { null } }`
> 同时确认 `OkHttpClient.newBuilder(): Builder` 与 `HttpUrl.newBuilder(): Builder` 均为非空，只有这一个重载是可空的。

**其余全部通过**（含此前几轮我怀疑过的点，现已排除）：
`Scaffold` 确含 `contentWindowInsets`；`Brush.verticalGradient` 只有 `List<Color>` 与 `vararg Pair` 两个重载
（**证实第一轮我把 `List<Pair<Float,Color>>` 传进去确实无法编译**，第三轮的修正必要且正确）；
`BasicTextField` 的 String 重载确含 `cursorBrush` / `singleLine` / `maxLines` / `decorationBox`；
`Modifier.padding` 的 4 个重载、`statusBarsPadding` / `navigationBarsPadding` / `imePadding`、
`InfiniteTransition.animateFloat`、`Navigation` 的 `popUpTo(route){saveState}`、`installSplashScreen` 等均存在。

**这套验证仍未覆盖的部分**（诚实边界）：我自己的类型推断与逻辑错误、AGP/Gradle 版本兼容性、
XML/资源解析、以及真机运行行为。这些只能靠 CI 与真机。

其余仍需**真机确认**的（无法通过静态检查消除）：

1. **抖音 WebView 抓取效果** —— 抖音页面策略随时变化，需真机验证注入钩子是否命中
2. **小红书视频笔记路径** —— 图文笔记已验证；视频笔记按 `note.video.media.stream.{h264,h265,av1}` 与
   `consumer.originVideoKey` 实现，尚未实测
3. **Compose API 版本差异** —— 若个别组件报「未解析」，按 IDE 提示切换当前版本的重载即可

---

## 常见问题

| 现象 | 处理 |
|---|---|
| 抖音一直停在「正在等待播放地址」 | 在页面里点一下视频播放；或在设置里填抖音 Cookie |
| 保存失败提示存储权限 | Android 9 及以下需在系统设置里授予存储权限 |
| B站只能取到 720P | 设置里填 `SESSDATA=xxx` |
| 小红书提示令牌失效 | 在 App 内重新「分享 → 复制链接」 |
| 图片保存后是 PNG 而非 JPG | 正常。小红书源图就是无损 PNG，应用按魔数识别真实格式 |

---

## 维护指引

平台改版是常态，改动范围很小：

| 平台 | 文件 | 关键点 |
|---|---|---|
| B站 | `BilibiliParser.kt` | `playurl` 的 `qn` 与 `platform` 组合；媒体直链必须带 Referer |
| 小红书 | `XhsParser.kt` | **必须移动端 UA**；数据在 `__INITIAL_STATE__.noteData.data.noteData`；原图 = `sns-img-qc.xhscdn.com/{fileId}` |
| 抖音 | `DouyinCaptureActivity.kt` / `DouyinParser.kt` | 页面结构变化一般不影响（不实现签名算法，始终依赖页面自身 SDK）；抓不到时先看注入钩子是否生效 |

---

## 使用须知

本工具仅用于个人学习与收藏，请勿将保存的素材用于商业用途或二次公开发布。
素材版权归原作者所有，请尊重原创。
