# ZoeVIP 进度交接（给其他电脑 / 其他 AI）

> **基准日：2026-08-15**  
> **当前开发版本：3.8.4（versionCode 103）** — 喜马拉雅改用 LibXimalayaHooks（修复 XposedBridge 不可用）  
> **冻结发行包：3.8.0（versionCode 99）** 仍在 `dist/latest/ZoeVIP-3.8.0.apk`（字节一比一，勿覆盖）  
> 把整个 `zoevip/` 目录拷走即可续作；先读本文再改代码。

---

## 1. 两句结论（请先读）

1. **安装包已与微信里的正式 3.8.0 字节级一比一**（见下方 SHA256）。换电脑安装、分发，请只用这个文件，不要用本地 `assembleRelease` 产物冒充。
2. **工程进度已对齐到 3.8.0 功能集**（含 Scene/VTools）：作用域、入口、Hooks 均在源码与 APK 中可核对。  
   注意：本地重新编译 **不能** 保证与现网 APK 字节相同（R8/签名时间戳），但 **版本号与能力应保持 3.8.0**。

---

## 2. 权威安装包（必须保留）

| 项 | 值 |
|----|-----|
| 路径 | `dist/latest/ZoeVIP-3.8.0.apk` |
| 包名 | `com.zoevip.lsp` |
| versionName | `3.8.0` |
| versionCode | `99` |
| 大小 | `786356` bytes |
| SHA256 | `DD0A0017160F0FEB765B22C8C38E9379E5ACE215EDA8FDC36D055FB71E7FC73C` |
| 来源 | 微信文件 `base.apk(1).1`（2026-08 聊天记录） |

校验（PowerShell）：

```powershell
Get-FileHash .\dist\latest\ZoeVIP-3.8.0.apk -Algorithm SHA256
# 必须等于 DD0A0017160F0FEB765B22C8C38E9379E5ACE215EDA8FDC36D055FB71E7FC73C
```

快捷入口（本机）：`D:\文档\apk\ZoeVIP-Release` → `dist/`

---

## 3. 3.8.0 功能进度清单（已完成）

| 目标 App | 包名 | 状态 | 关键实现 |
|----------|------|------|----------|
| Afusekt | `com.attempt.afusekt` | 已纳入 | `AfusektHooks` / `LibAfusektHooks` |
| CapyPlayer | `com.feifeiduck.capyplayer` | 已纳入 | `CapyPlayerHooks` + legacy bridge |
| VidHub | `com.oumi.utility.media.hub` | 已纳入 | `VidHubHooks` / `LibVidHubHooks` + NIS |
| Scene / VTools | `com.omarea.vtools` | **3.8.0 新增** | `VToolsHooks` / `LibVToolsHooks` |
| 喜马拉雅 | `com.ximalaya.ting.android` | **3.8.1 新增** | `XimalayaHooks`（免广告领时长/领奖） |

LSPosed 作用域（`app/src/main/res/values/arrays.xml`）：

- `com.attempt.afusekt`
- `com.feifeiduck.capyplayer`
- `com.oumi.utility.media.hub`
- `com.omarea.vtools`
- `com.ximalaya.ting.android`
- `com.mt.mtxx.mtxx`

入口：

- Legacy：`MainHook`（含 VTools / Ximalaya 分支）
- libxposed：`ZoeModule` → `LibVToolsHooks` / `LegacyHookBridge`（CapyPlayer、喜马拉雅）

APK 内已核对存在字符串/类名：`VToolsHooks`、`LibVToolsHooks`、`ZoeModule`、`com.omarea.vtools`、`3.8.0`。

工程版本号：`app/build.gradle.kts` → `versionName = "3.8.0"`，`versionCode = 99`。

适配说明：`info/adapters/INDEX.md`、`info/adapters/vtools.md`

---

## 4. 目录约定（续作时勿打乱）

```
zoevip/
├── PROGRESS.md          ← 本文件（进度真相）
├── dist/latest/         ← 唯一权威安装包
├── dist/archive/        ← 历史版本与基包（不必改）
├── app/                 ← 源码（在此继续开发）
├── info/adapters/      ← 适配文档
├── docs/release/        ← 字节一比一说明
├── tools/               ← adb 等脚本
└── artifacts/recovery/ ← 仅溯源说明
```

---

## 4.1 UI 全面升级（2026-08 开发线）模块自身 UI 已按「跟随系统深浅色 + Material 3 质感」全面重做，**纯代码构建，未引入 appcompat/Material 依赖**：

- **双主题资源**：`res/values/colors.xml`（浅色）+ `res/values-night/colors.xml`（深色），
  `res/values/themes.xml` + `res/values-night/themes.xml` 定义 `Theme.ZoeVIP`（Manifest 已切换），自动跟随系统深浅色。
- **统一设计系统**：`ui/UiKit.java`（卡片阴影/圆角/水波纹、filled/tonal/outlined/text 按钮、输入框、
  徽章、分区标题、状态圆点、真实应用图标或字母头像、状态栏深浅图标自适应）。
- **`UiColors` 扩展**：新增 `isDark` / `surfaceVariant` / `accentContainer` / `onAccentContainer` /
  `onAccent` / `ripple` / `outline`；模块应用内走自身资源自动深浅色，Afusekt 注入场景保持主题内省。
- **改造页面**：`MainActivity`（首页，含 VTools 行 + 版本页脚）、`AfusektSettingsActivity`、
  `WebDavSettingsUi`（模块对话框）、`XimalayaSettingsUi`、`XimalayaBurstPicker`。
- **编译验证**：`gradlew :app:assembleRelease` 通过（构建产物仅供调试，勿覆盖 `dist/latest` 权威包）。

---

## 4.2 Afusekt 3.2.5 新版适配（2026-08）

**目标**：`com.attempt.afusekt` v3.2.5（versionCode 10740）— Compose 全面重构版。

**逆向结论**（`tools/afusekt_v325/`）：
- 旧版类 `RoleValue` / `MyAppConfig` / `BaseActivity` / `BaseFragment` 已删除（R8 + Compose 重写）。
- VIP 判定收敛到混淆类 **`pb6`**（a–f 共 6 个无参 boolean 方法），逻辑为
  `(mk9.a 签名校验 ? AesCryptNative.g() 完整性 : false) && AesCryptNative 原生标志`。
  `mk9.a` 由 `MyApplication` 启动时 `AesCryptNative.k(bytes)`（nativeVerifySignature）设置。
- `AesCryptNative`（native-lib.so + a–h 包装）签名未变，仍被 `pb6` 调用。
- WebDAV 同步从 `VideoLibraryFragment` 迁移到 Coroutine 架构：
  `service/VideoDataSyncService` + `workManager/VideoDataSyncWorker`（**旧 hook 全部失效，需单独重写**）。

**已实现（VIP 解锁，libxposed + legacy 双路径）**：
- **`pb6.a–f` → 强制 true**（核心）：绕过 mk9 签名门控 + native 标志检查，所有消费 pb6 的页面
  （AccountView / MainComposeActivity / HomeStartConfigActivity / MediaSettingView / MpvSettingView 等）直接获得 VIP。
- **`AesCryptNative` g/h/i/k 按新版签名 hook（关键修复）**：
  - 新版 `g()` 无参（旧版 `g(String)`）、`h(String)`（旧版 `h()` 无参）— **参数数量必须匹配，否则 hook 不生效**。
  - 新增 `i(int)[B`（nativeGetKeyMaterial）与 `k(byte[])`（nativeVerifySignature）hook。
  - **`i(int)` 必须返回 32 字节数组**：`h.<clinit>` 对每个 `f` 枚举值调用 `i()` 并校验长度 == 0x20，
    否则抛 `IllegalArgumentException: Invalid key length for DEFAULT. Expected 32 bytes` →
    `ExceptionInInitializerError` → 后续引用 `h` 类报 `NoClassDefFoundError` → 同步任务取消。
  - 原因：同步/登录流程（AccountView / MainComposeActivity / mz3 / h / fk9）直接调 `AesCryptNative.h(String)` / `i(int)`，
    若 hook 参数写错 → 未拦截 → 触发真实 `libnative-lib.so` 加载 → 被 Shield 阻断 → **`UnsatisfiedLinkError`**。
  - ⚠️ **遗留问题**：`i(int)` 伪造 32 字节 key 只能消除崩溃，但 `h` 类用该 key 做 AES-GCM 加解密 —
    全零 key 无法解密官方服务端数据，**官方同步数据不可用**。真实 key 由 so 动态生成（so stripped 无反检测符号）。
    彻底方案需放行 so 并 hook 其反 hook 检测，或完整重写新版 WebDAV 同步（`VideoDataSyncService` Coroutine 架构）。
- 旧类 hook（RoleValue/MyAppConfig/BaseActivity）保留 try-catch 兜底，老版本不受影响。
- Shield（SO 拦截 + System.exit/killProcess 守卫）不变。

**验证状态**：编译通过；已装机（`ZoeVIP-3.9.38-afu-fix.apk`）。
⚠️ 用户环境为 **root + LSPosed（KernelSU，隐藏模式）**，adb 检测不到注入痕迹（无 su / 无管理器包 / maps 干净），
  但 `UnsatisfiedLinkError` 证明模块实际在 Afusekt 进程运行。验证需在 LSPosed 重新勾选 Afusekt 作用域后
  强制停止 Afusekt 重开，并观察 logcat（`ZoeVIP` / `pb6 VIP gate hooked` / `AesCryptNative.h` / `UnsatisfiedLink`）。

### 4.2.1 WebDAV 同步新版重写（3.2.5，2026-08）

**背景**：新版同步从 `VideoLibraryFragment`（旧清晰入口）迁移到 Coroutine Service + WorkManager，
旧 WebDAV 重定向 hook（VideoLibraryFragment/OrderUserTools/SpUtil）全部失效；官方同步服务端权限校验
（PERMISSION_DENIED）无法本地放行。用户要求"模块自己搞 WebDAV 同步"（原始定位）。

**逆向结论**（详见 `tools/afusekt_v325/SYNC_REVERSE_REPORT.md`）：
- 新版同步 = `VideoDataSyncService.o(VideoSource, Continuation)` 按库同步影视数据（读写 AppDatabase，
  表 `MovieData`/`TvData`/`VideoInfoTable` 结构与旧版**完全一致**，SQL 直读可复用）。
- 网络出口 `qo9.f`（协程 HTTP，嵌套 rq9/maa 状态机）；手动入口 `VideoDataSyncService.d` → EventBus(yz1/i57)。

**已实现（`libxposed/LibWebDavSyncV2.java`）**：
- hook `VideoDataSyncService.o(VideoSource, Continuation)`（按方法名+首参类型定位，Continuation 被 R8 混淆）。
- WebDAV 已配置时：拦截 → 单库 JSON（VideoSource + ScrapeTableSync 该库 MovieData/TvData/VideoInfoTable）
  → PUT 到用户 WebDAV；未配置时放行官方流程。
- 已接入 `LibAfusektHooks.installLate`。
- **待验证**：真机点同步看 WebDAV 服务器是否收到数据；`ScrapeTableSync.appendToRoot` 需要
  `List<VideoSource>`，单库模式已适配（Collections.singletonList）。
- **遗留**：下载方向（拉取 WebDAV 恢复数据库）尚未实现；`o()` hook 返回 null 会中断官方流程，
  需确认不影响 UI 状态（正在同步 X 的数据 → 无提示）。

**待办（如需）**：新版 WebDAV 同步适配（重写 `VideoDataSyncService` / `VideoDataSyncWorker` 的上传/拉取 hook）。

---

## 5. 给下一个 AI 的硬性规则

1. **日常安装 / 对照现网**：只用 `dist/latest/ZoeVIP-3.8.0.apk`。
2. **若用户要“字节一比一”**：禁止用新编译 APK 覆盖 `dist/latest`；除非用户明确要求升版。
3. **若继续开发新版本**：应升 `versionName/versionCode`（例如 3.8.1 / 100），产物放到 `dist/latest` 新文件名，并更新本 `PROGRESS.md`。
4. **VTools 已是 3.8.0 的一部分**，不要当成“未做完的待办”重做一遍，除非现场验证失败再修。
5. 历史归档在 `dist/archive/`，不要和 `latest` 混用。

---

## 6. 构建（仅开发用）

```powershell
.\gradlew.bat :app:assembleRelease
```

**3.8.2 开发包**（推荐应用 scope 已补全）：

| 项 | 值 |
|----|-----|
| 路径 | `dist/latest/ZoeVIP-3.8.2.apk` |
| versionName | `3.8.2` |
| versionCode | `101` |

**3.8.1**（含喜马拉雅 Hook）：`dist/latest/ZoeVIP-3.8.1.apk`

产物仅用于调试。发行/备份现网 **3.8.0** 仍以 `dist/latest/ZoeVIP-3.8.0.apk` 的 SHA256 为准。

---

## 7. 一比一保证声明（可对外复述）

- **发行包**：与微信正式 `ZoeVIP 3.8.0` **字节一比一**（SHA256 如上）。  
- **进度**：源码与清单已 **复刻到 3.8.0**（四应用作用域 + VTools Hooks 齐备，版本号 3.8.0 / 99）。  
- **非保证**：任意一次 `gradlew` 重编输出与上述 APK 字节相同。
