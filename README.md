# ZoeVIP

多应用 LSPosed 解锁模块（`com.zoevip.lsp`）。

模块 UI 已全面升级：跟随系统深浅色（Material 3 质感，纯代码设计系统 `ui/UiKit.java`），详见 [`PROGRESS.md`](PROGRESS.md) §4.1。

## 换电脑 / 换 AI 续作

**先读 [`PROGRESS.md`](PROGRESS.md)**（进度冻结在 **3.8.0**，含保证说明与交接规则）。

## 安装

| 用途 | 路径 |
|------|------|
| 最新包 | `dist/latest/ZoeVIP-3.8.0.apk` |
| 快捷入口 | `D:\文档\apk\ZoeVIP-Release` |
| 适配说明 | `info/adapters/INDEX.md` |

当前权威版本：**3.8.0**（versionCode **99**）  
SHA256：`DD0A0017160F0FEB765B22C8C38E9379E5ACE215EDA8FDC36D055FB71E7FC73C`  
（与微信正式包 **字节一比一**）

## 目录

```
zoevip/
├── PROGRESS.md     # 进度交接（必读）
├── dist/           # 发行：最新包 + 历史/基包归档
├── artifacts/      # 仅恢复溯源说明
├── docs/release/   # 发布校验笔记
├── app/            # 源码
├── info/           # 适配文档
└── tools/          # 工程脚本
```

## 构建

```powershell
.\gradlew.bat :app:assembleRelease
```

重新编译不会与 3.8.0 字节一比一；日常安装请用 `dist/latest`。
