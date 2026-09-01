# Meitu Xiuxiu (`com.mt.mtxx.mtxx`)

## Target

- Package: `com.mt.mtxx.mtxx`
- Tested: **12.17.0** (versionCode 121700)

## Strategy

Hook central VIP gate APIs and member info getters:

- `ModuleVipApiImpl.isVip()` / `getVipType()` / copy & preview gates
- `MaterialKitApiImpl.isVip()` / `isPassedByVipUserData()`
- `UserMemberInfo.getIsVip()`
- `VipInfoBean.isVip()`, `UserInfoBean.isVip()` / `isSvip()`
- `RightsCompBean.isVipValid()` / `isSvipValid()`
- Internal VIP module: `com.meitu.vip.module.{o,s,e$q}.isVip()`
- `XXVipUtil` — all static no-arg boolean getters (obfuscated Kotlin object)

## Files

- libxposed: `LibMtxxHooks.java`
- legacy: `MtxxHooks.java`

## Notes

- Meitu has native signature / hook detection (`librelease_sig.so`). ZoeVIP global `LibAntiDetect` runs for this scope; enable LSPosed **hide module** for mtxx.
- Cloud/AIGC features may still require server-side credits even when VIP UI gates pass locally.

## Logcat

```sh
logcat -d | grep -iE "ZoeVIP:Mtxx|ZoeVIP.*mtxx"
```
