# ZoeVIP 模块总览

> 下次改模块 / 加适配时先读本文件，再读 `info/adapters/` 下对应应用说明。

## 基本信息

| 项 | 值 |
|----|----|
| 显示名 | ZoeVIP |
| 包名 | `com.zoevip.lsp` |
| 工程目录 | `afusekt-lsp/` |
| 输出 APK | `afusekt-lsp/output/zoevip-lsp.apk` |
| LSPosed 入口 | `com.afusekt.lsp.MainHook`（`assets/xposed_init`） |
| Log 标签 | `ZoeVIP` |
| 品牌图标源 | `branding/zoevip-icon.png` |

## 目录结构（代码）

```
afusekt-lsp/app/src/main/java/com/afusekt/lsp/
  MainActivity.java              # 桌面入口：已适配列表 + 打开 LSPosed
  ModuleApp.java                 # Application
  MainHook.java                  # Xposed 入口（按包名分发）
  AfusektSettingsActivity.java   # Afusekt 设置页
  hook/                          # 通用 / Afusekt hooks
  sync/                          # Afusekt WebDAV + 刮削同步
```

说明文件（给人 / 下次对话用）：

```
afusekt-lsp/info/
  MODULE.md                      # 本文件
  adapters/INDEX.md              # 适配清单
  adapters/afusekt.md            # Afusekt 详情
branding/zoevip-icon.png         # 桌面图标原图
```

## 构建与安装

```powershell
cd afusekt-lsp
.\gradlew.bat :app:assembleRelease
Copy-Item app\build\outputs\apk\release\app-release.apk output\zoevip-lsp.apk -Force
adb install -r output\zoevip-lsp.apk
```

## LSPosed 使用

1. 启用模块 **ZoeVIP**
2. 作用域勾选目标 App（见 `adapters/INDEX.md`）
3. 强制停止目标 App 后重开
4. 桌面打开 ZoeVIP 可进各应用设置、跳转 LSPosed

## 加新适配时

1. 在 `info/adapters/` 新建 `{app}.md`，并登记到 `INDEX.md`
2. 在 `arrays.xml` 的 `xposed_scope` **以及** `resources/META-INF/xposed/scope.list` 增加包名（后者决定 LSPosed「推荐应用」）
3. 在 `MainHook` / 列表 UI 增加入口
4. hooks 建议单独子包，避免和别的 App 缠在一起
