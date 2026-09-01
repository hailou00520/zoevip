# 已适配应用清单

| ID | 显示名 | 包名 | 说明文件 | 能力摘要 |
|----|--------|------|----------|----------|
| afusekt | Afusekt | `com.attempt.afusekt` | [afusekt.md](afusekt.md) | PRO/VIP 本地解锁；WebDAV 资源库同步（含刮削） |
| capyplayer | CapyPlayer | `com.feifeiduck.capyplayer` | [capyplayer.md](capyplayer.md) | Lifetime Pro 解锁 · 无限资源库 · 全部 Pro 功能 |
| vidhub | VidHub / Media Hub | `com.oumi.utility.media.hub` | — | VIP 解锁；NIS 检测绕过 |
| vtools | Scene / VTools | `com.omarea.vtools` | [vtools.md](vtools.md) | 专业版永久激活 |
| ximalaya | 喜马拉雅 | `com.ximalaya.ting.android` | [ximalaya.md](ximalaya.md) | 看广告领时长 · 跳过广告直接领奖 |
| mtxx | 美图秀秀 | `com.mt.mtxx.mtxx` | [mtxx.md](mtxx.md) | VIP/SVIP 会员功能解锁 |
| fanqie | 番茄畅听 | `com.xs.fm` | — | VIP 解锁 · 免广告 · 激励领奖 bypass |
| fanqie_novel | 番茄免费小说 | `com.dragon.read` | — | VIP 解锁 · 免广告 · 付费章节 |

## 作用域（LSPosed）

当前作用域（两处需同步）：

- `res/values/arrays.xml` → `xposed_scope`
- `resources/META-INF/xposed/scope.list` → **LSPosed「推荐应用」标签来源**

- `com.attempt.afusekt`
- `com.feifeiduck.capyplayer`
- `com.oumi.utility.media.hub`
- `com.omarea.vtools`
- `com.ximalaya.ting.android`
- `com.mt.mtxx.mtxx`
- `com.xs.fm`
- `com.dragon.read`
