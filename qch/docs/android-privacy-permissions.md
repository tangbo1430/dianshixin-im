# 企才汇 Android 隐私权限说明

## 当前结论

当前 Android 端权限逻辑已按“先同意隐私协议、后初始化业务能力、按功能申请权限”的方式整理。

已确认最终 `qaRelease` 合并 Manifest 中不再包含以下无业务必要的高敏感权限：

- `SYSTEM_ALERT_WINDOW`
- `ACCESS_NOTIFICATION_POLICY`
- `MOUNT_UNMOUNT_FILESYSTEMS`
- `CHANGE_NETWORK_STATE`
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`

## 需要弹窗申请的权限

以下权限都应先展示业务说明弹窗，用户点“同意”后再触发 Android 系统权限弹窗。

| 权限 | 触发场景 | 申请时机 |
| --- | --- | --- |
| `CAMERA` | 扫码、拍照发图、视频通话 | 用户点击对应功能时申请 |
| `RECORD_AUDIO` | 语音消息、语音通话、视频通话 | 用户点击录音或通话时申请 |
| `POST_NOTIFICATIONS` | 新消息通知 | Android 13+ 登录进入主界面后申请 |
| `READ_MEDIA_IMAGES` | 选择头像、从相册发图片 | Android 13+ 选择图片时申请 |
| `READ_MEDIA_VIDEO` | 从相册发视频 | Android 13+ 选择视频时申请 |
| `READ_MEDIA_AUDIO` | 选择音频类媒体 | Android 13+ 选择音频时申请 |
| `READ_EXTERNAL_STORAGE` | 低版本系统读取相册/文件 | Android 12 及以下按功能申请 |
| `BLUETOOTH_CONNECT` | 音视频通话切换蓝牙设备 | Android 12+ 通话链路按需申请 |

## 不需要弹窗的权限

这些是普通能力或系统服务能力，声明后不需要运行时弹窗：

- `INTERNET`：网络访问。
- `ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`：判断网络状态。
- `VIBRATE`：消息震动提醒。
- `WAKE_LOCK`：通话或消息场景下保持必要唤醒。
- `MODIFY_AUDIO_SETTINGS`：通话/语音播放音频路由。
- `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CAMERA`、`FOREGROUND_SERVICE_MICROPHONE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`：音视频通话前台服务。
- `BLUETOOTH`、`BLUETOOTH_ADMIN(maxSdkVersion=30)`：老系统蓝牙兼容。
- `MANAGE_OWN_CALLS`：通话 SDK 自管理通话能力。

## 低版本兼容权限

- `WRITE_EXTERNAL_STORAGE(maxSdkVersion=28)`：由图片/文件 SDK 合并进来，仅用于 Android 9 及以下文件兼容；新系统不会生效，也不作为启动权限申请。

## 已实现的关键点

- 首次启动先展示隐私协议，用户同意后才继续登录和初始化业务。
- 头像上传不再申请相机权限，只在选择相册头像时申请图片读取权限。
- 头像上传在系统权限弹窗前，会先展示“上传头像需要获取相册权限”的业务说明弹窗。
- 语音消息、扫码、通话等 TUIKit 入口，会通过 TUIKit 权限说明页先解释用途，再触发系统权限弹窗。
- 通知权限只在 Android 13+ 登录进入主界面后申请。
- 高敏感但无业务必要的依赖权限已从最终包 Manifest 移除。
- Debug/Release 使用同一套 Manifest 权限收敛规则。

## 仍需注意

腾讯 TUIKit 依赖自身带有 `ContentProvider` 初始化入口。当前企才汇自己的 `TIMAppService` 已做隐私同意前延后初始化，但如果后续要上应用市场严审，建议单独做一次“三方 SDK 隐私合规初始化专项检查”，确认腾讯组件、Bugly、推送等都不在用户同意前采集设备信息或发起网络请求。

## 测试清单

- 新安装 App，首次打开只出现隐私协议，不出现系统权限弹窗。
- 不同意隐私协议，App 退出或停留，不进入登录/IM 初始化流程。
- 同意隐私协议后可进入登录流程。
- 点击扫码时才申请相机权限。
- 点击语音消息时才申请麦克风权限。
- 点击更换头像时只申请相册/图片读取权限。
- Android 13+ 登录进入主界面后再申请通知权限。
- 从相册发图片、视频能正常触发对应媒体权限。
- 音视频通话时再触发相机、麦克风、蓝牙相关权限。
