# 股票涨跌监控 · 原生 Android App（锁屏绝不漏报）

一个**原生 Android 应用**：后台用系统级「前台服务」持续拉取行情，越阈值直接弹
**高优先级 + 锁屏可见**的系统通知（声音 + 震动），不再受浏览器定时器被冻结的限制。
界面与网页版（PWA）同一套，支持按**名称/代码**搜索添加（含**港股**）、**≥20 支**同时监控、
**分组 / 批量设阈值**、**涨跌幅 + 主力资金极速流入/流出**报警、红涨绿跌。
报警逻辑：**突破阈值后持续报警**，点「停止报警」静音、每再同向变动 1% 续报，点「解除」彻底复位；
**盘中重大利好/利空消息**弹窗+通知提醒。表头固定显示**上证/深证/北证50 + 道指/纳指/标普500**实时涨跌幅与**北京时间**。

> 网页版（PWA）：https://58360ba84ccf43d0a65b08044447e00b.app.codebuddy.work

## 目录结构
```
stock-monitor-android/
├── app/src/main/
│   ├── java/com/stockmon/app/
│   │   ├── MainActivity.kt      # WebView 加载 www + 原生桥接
│   │   ├── WebAppInterface.kt   # JS<->原生 桥接（saveConfig/start/stop/电池设置）
│   │   ├── MonitorService.kt    # 前台服务：后台轮询 + 锁屏报警通知
│   │   └── BootReceiver.kt      # 开机/更新后自启
│   ├── res/                     # 图标、主题、布局
│   └── assets/www/              # 网页版界面（index.html 等）
├── build.gradle / settings.gradle / gradle.properties
└── .github/workflows/build.yml  # 云端一键出包
```

> **关于 Gradle Wrapper**：工程已含 `gradlew` / `gradlew.bat` / `gradle-wrapper.properties`。
> 首次用 Android Studio 打开工程会自动补全 `gradle-wrapper.jar`，无需手动处理；
> GitHub Actions 云端构建自带 Gradle 8.6，也不依赖 wrapper 文件。

## 两种出包方式

### 方式 A：云端一键出包（推荐，无需本地 Android SDK）
1. 把本目录推到 GitHub 仓库。
2. 仓库 **Settings → Secrets and variables → Actions** 添加（可选，用于签名正式包）：
   - `KEYSTORE_BASE64`：把你的签名 keystore 用 `base64 -w0 release.keystore` 生成后填入
   - `KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`
   - 不填则用 debug keystore 出包（可正常安装，只是非正式签名）。
3. **Actions → Build Release APK → Run workflow**。
4. 跑完在 Artifacts 下载 `stock-monitor-apk`（含 `app-release.apk`）。

### 方式 B：本地 Android Studio 编译
1. 用 Android Studio 打开本目录（含 `settings.gradle` 即为工程）。
2. 等待 Gradle 同步完成（自动下载 AGP/Kotlin 依赖，需联网）。
3. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
4. 产物在 `app/build/outputs/apk/release/app-release.apk`。

## 安装到荣耀 Magic7
1. 把 `app-release.apk` 传到手机，点击安装（首次需允许「未知来源」）。
2. 打开 App → 顶部「开始监控」→ 它会自动启动前台服务（状态栏出现「🐂 股票监控运行中」常驻通知，这是正常的）。
3. 点「🤖 后台设置」→「跳转系统电池设置」或在系统里：
   - **电池 → 不受限制**（最关键，否则服务会被冻结）
   - **应用启动管理 → 允许自启动 / 关联启动 / 后台活动**
   - **通知管理 → 允许通知 + 锁屏通知 + 横幅 + 设为重要**
4. 想重启手机也自动盯盘：手机管家 → 自启动管理 → 开启本应用。

## 工作原理（为什么这次锁屏不漏报）
- 之前网页版把轮询放进 Web Worker，而浏览器（尤其 QQ 浏览器）在后台会冻结/不跑 Worker，所以不更新。
- 原生版把轮询放进 **Android 前台服务（Foreground Service）**：这是系统级后台任务，
  配合「忽略电池优化」可长期存活，每次越阈值弹 **PRIORITY_MAX + VISIBILITY_PUBLIC** 通知，
  锁屏也能看到、能响铃震动。网页界面只负责交互与展示，真正的报警由原生服务兜底。

## 数据来源
- 行情：东方财富实时接口（`push2delay.eastmoney.com`）。A 股（沪/深/京）、**港股**（`116.` 前缀）、
  美股三大指数（道指 `100.DJIA`、纳指 `100.NDX`、标普500 `100.SPX`）均支持。
- 重大新闻：新浪滚动财经（`feed.mix.sina.com.cn`，JSONP），仅在交易时段（9:30-11:30、13:00-15:00）扫描利好/利空关键词。
- 原生服务用 HttpURLConnection 直接请求，无需跨域；网页端用 JSONP，任何浏览器都能用。

## 说明
- 个股默认**不报警**（需手动在股票设置或分组批量里设涨/跌阈值）；**美股三大指数**保留涨跌幅 ≥0.8% 报警。
- 步进报警步长固定 **1%**（停止后每再跌/涨 1% 续报）；资金急速流入/流出为**自动检测**（占比 ≥2% 且两次刷新变动 ≥1.5 个百分点，盘内判定），无需手填阈值。
- `foregroundServiceType="dataSync"`，在 Android 14+ 合法长期后台运行（仍需「忽略电池优化」）。
