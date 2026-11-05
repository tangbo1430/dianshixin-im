# 店时信 + 腾讯 IM 合包验证工程

版本 `0.2.0` / code `2`，包名 `com.dianadianxinb.app`，最低 Android 9（API 28）。
本工程为独立的内部测试工程；不是屋六信正式发布工程，也不是原店时信 2.4.0 正式包的签名升级版。

## 两个启动面

- A：店时信记账，`:dianshixin` library，API `https://www.xmjcmy.com:18445`。
- B：屋六信交接版腾讯 IM，`:app` + TUIKit，业务 API `https://admin.awhaha.com`。
- 唯一桌面入口：`FaceEntryActivity`。统一配置 `https://config.xmjcmy.com/dianshixin/tiuibipl.txt`。
- 上传工程根目录 `cos/dianshixin/tiuibipl.txt` 至对应 COS key。字段 `CHAOXXMAI_V3_PROPERTY_A_ENABLED:1` 表示 A，`0` 表示 B。
- 两面均包含在 APK 中，没有双入口、厂商判断、审核判断或隐藏手势。不要修改屋六信旧 `wuliuxin4.0/tiuibipl.txt`。
- 启动展示复用应用 Logo 和名称，不显示内部配置加载文案。配置读取最多 5 秒、16 KiB；失败时显示“网络连接异常，请稍后重试。”并使用有效缓存，没有缓存则进入 A，具体原因保留在诊断日志中。隐私告知及统一路由规则不变。初始文件为 A；上传与 CDN 刷新由配置发布者执行。

## 构建和验证

Windows PowerShell，在本目录执行；本机已验证使用 Android Studio JBR、Gradle 9.6.0、AGP 9.4.1：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :dianshixin:testDebugUnitTest --max-workers=2 --console=plain --no-daemon
```

输出 `app/build/outputs/apk/debug/app-debug.apk`，仅为 debug 签名验证包。正式签名、腾讯新包登记及厂商离线推送未配置，`ENABLE_TIM_PUSH=false`，不应以旧应用凭据替代。
AGP 9 兼容旧 TUIKit 构建脚本暂使用 `android.builtInKotlin=false` / `android.newDsl=false`，Java/Kotlin target 均为 17；没有跳过 AAR metadata 校验。

## 法律与验收边界

记账原版本化协议门禁保留，服务端已通过正常管理员和 MFA 流程发布内部测试稿；首次加载全文、主动同意及进入主界面已在交付 APK 上实测。聊天首屏明确内部测试、只用合成数据，关联 `https://www.xmjcmy.com/legal/dianshixin-ab/` 的测试稿（源码在工程根目录 `legal/`），不沿用屋六信的正式政策身份。
主体、有效联系方式、完整 SDK 清单、数据地域及出境安排未落实，不具备直接上架条件。
合包构建、单测、设备启动与组件回归不等于已验收真实聊天账号、双端消息、通话、离线推送或云端 A/B 切换。具体结果见 [PROJECT_NOTES.md](PROJECT_NOTES.md)。
