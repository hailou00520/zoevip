# 适配：CapyPlayer

## 标识

| 项 | 值 |
|----|----|
| 显示名 | CapyPlayer |
| 包名 | `com.feifeiduck.capyplayer` |
| Hook 入口 | `hook.CapyPlayerHooks` |
| 反编译参考 | `capyplayer/base.apk` / `capyplayer/extracted/libapp.so` |

## 能力

1. **Lifetime Pro 全解锁**
   - 注入 `flutter.subscription_state` / `desktop_subscription_state` 为 lifetime
   - 拦截服务端同步写入，阻止降级为 free
   - `proFeatureEntitlementProvider` / `subscriptionEntitlementReadyProvider` 相关 prefs 强制 ready
   - Google Play Billing 购买态伪造（`capyplayer.pro.lifetime`）

2. **无限资源库**
   - `add_resource_quota` → `Integer.MAX_VALUE`
   - `resource_entitlement.dart` / `PaywallGuard` 依赖订阅 tier，Pro 解锁后不再拦截

3. **Pro 体验功能**（随订阅 tier 一并解锁）
   - PaywallGuard 拦截的 Pro 功能（如 MDK 内核、字幕搜索等）
   - 隐藏订阅卡片相关限制

## 架构

| 层级 | 说明 |
|------|------|
| 应用 | Flutter，逻辑在 `libapp.so` |
| 订阅 API | `https://api-capyplayer.feifeiduck.cn/api/v1/subscriptions/status` 等 |
| 本地缓存 | `FlutterSharedPreferences` + 可能 DataStore |
| 付费检查 | `PaywallGuard.ensureProFeature` / `proFeatureEntitlementProvider` |

## 使用

1. LSPosed 启用 ZoeVIP，作用域勾选 `com.feifeiduck.capyplayer`
2. 强制停止 CapyPlayer 后重开
3. 设置里应显示 Lifetime / Pro 状态；Pro 功能不再弹 paywall

## 验证

```powershell
adb logcat -s ZoeVIP
```

期望：`seeded lifetime Pro prefs` / `forced subscription write` / `blocked subscription downgrade`

## 注意

- 主 API 走 Dart `HttpClient`，OkHttp hook 仅覆盖少量 Java 路径；核心靠 prefs 注入 + 写拦截
- App 大版本更新后若 JSON 字段变化，需对照 `subscription_models.dart` 调整
