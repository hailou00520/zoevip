# 适配：Afusekt

## 标识

| 项 | 值 |
|----|----|
| 显示名 | Afusekt |
| 包名 | `com.attempt.afusekt` |
| 设置页 | `AfusektSettingsActivity` |
| Hook 入口 | `hook.AfusektHooks` + `hook.WebDavSyncHooks` |
| 反编译参考 | `afusekt/decompiled/` |

## 能力

1. **PRO/VIP 本地解锁**
   - `RoleValue` a/c/d/e → true，b → false
   - `AesCryptNative` 相关布尔 / 校验放行
   - `MyAppConfig.e` / `q` 强制 PRO
   - `BaseActivity.X` 拦截本地 PRO 弹窗文案

2. **WebDAV 资源库同步**（替代官方云）
   - 上传入口：`VideoLibraryFragment.l0` → 拦截后走 WebDAV PUT
   - 拉取入口：`VideoLibraryFragment.i` → 拦截后走 WebDAV GET
   - 内层官方 API：`OrderUserTools$Companion.c`、`getVideoSource` 仅阻断
   - 同步开关：`SpUtil.d(..., "同步资源库", ...)` 在已配置 WebDAV 时强制 true
   - 同步期间屏蔽官方 toast（成功上传 / 无权限 / 操作成功 等）

3. **刮削随资源库备份**
   - 表：`MovieData`、`TvData`、`VideoInfoTable`
   - 网盘/本地/SMB/Alist 等（`ResourceType.Companion.a`）必备份
   - 媒体服仅 `isScan=true` 时备份
   - 读写经 Room 混淆接口：`RoomDatabase.i()` → OpenHelper.`g2()` → `FrameworkSQLiteDatabase.a`

## 配置通道（模块 → 目标进程）

因 Android 包可见性，XSharedPreferences / ContentProvider 在目标进程常读不到。当前方案：

1. 模块保存 → `WebDavConfigPush` 广播 `com.zoevip.lsp.WEBDAV_CONFIG`
2. 目标进程 `WebDavConfigReceiver` 写入私有缓存文件
3. Afusekt 启动时若无缓存 → 广播 `WEBDAV_CONFIG_REQUEST` 向模块拉配置
4. Hook 读配置优先：缓存 → Provider → 外部文件 → xprefs

相关常量：

- 模块包名：`com.zoevip.lsp`
- Provider：`content://com.zoevip.lsp.webdav`
- Prefs 名：`webdav_sync`

## WebDAV JSON 结构（摘要）

```json
{
  "userAccount": "...",
  "videoSources": [ { "sourceId", "name", "account", "pass", "sourceType", ... } ],
  "scrapeTables": {
    "MovieData": [ ... ],
    "TvData": [ ... ],
    "VideoInfoTable": [ ... ],
    "sourceIds": [ "..." ]
  },
  "scrapeVersion": 2,
  "scrapeMovieCount": 0,
  "scrapeTvCount": 0
}
```

默认远端文件名：`afusekt-library-sync.json`

## UI 文案约定

测试连接不要显示 HTTP 码：

- 成功 →「测试连接成功，已连接」
- 404 → 已连接，同步文件上传后自动创建
- 401/403 → 账号或密码不正确

## 验证

```powershell
adb logcat -s ZoeVIP
```

期望：`Loading hooks for ...` / `WebDAV sync hooks installed` / `intercept l0 upload` / `PUT` / `scrape dump ...`

## 注意

- 删资源库会清本地刮削；旧同步文件若无 `scrapeTables`，拉回后需重新刮削再上传
- 包名从旧 `com.afusekt.lsp` 迁到 `com.zoevip.lsp` 后，WebDAV 配置需在 ZoeVIP 里重填
