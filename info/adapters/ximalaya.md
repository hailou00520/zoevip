# 喜马拉雅

| 项 | 值 |
|----|----|
| 显示名 | 喜马拉雅 |
| 包名 | `com.ximalaya.ting.android` |
| 模块版本 | ZoeVIP 3.8.1+ |
| 入口 | `XimalayaHooks`（legacy） / **`LibXimalayaHooks`（libxposed，必走）** |

## 能力

- 看广告领时长 / 领金币 / 福利页激励视频：**跳过广告，直接触发领奖回调**
- 服务端返回失败时强制 `IncentiveRewardResponse.isSuccess()` 为 true

## Hook 点（9.5.1.3 核对）

| 类 | 方法 | 作用 |
|----|------|------|
| `adsdk.AdSDK` | `loadRewardVideoAd` | 不加载真实广告，立即回调 onReward(true) |
| `adsdk.external.IRewardVideoAdListener` | `onReward` | 强制 realFinishTask=true |
| `host.data.model.ad.AdGoldCoinResponseData` | `success` / `retry` 字段 | 发金币/领时长接口（关键） |
| `Gson.fromJson` | 解析后 patch | 服务端返回失败时强制成功 |
| `opensdk.model.advertis.IncentiveResponse$Data` | `isSuccess` | 旧版激励响应 |
| `host.model.ad.VideoUnLockResult` | `isSuccess` | 看广告解锁试听 |

## 使用

1. LSPosed 启用 ZoeVIP，作用域勾选 `com.ximalaya.ting.android`
2. 强制停止喜马拉雅后重开
3. 进入「我的 → 免费听 / 福利中心」等看广告领时长入口，点击后直接领奖（无广告）

## 注意

- 需登录账号；部分奖励仍依赖服务端，极端情况可能提示已领完
- 适配基于 **9.5.1.3**，大版本升级后若失效需重新核对类名
