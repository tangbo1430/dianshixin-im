# 店时信

店时信 Android 应用源码，包含记账模块、聊天模块和相关服务文档。

## 构建

项目使用 Android Gradle Plugin 9.4.1、Gradle 9.6.0 和 Java 17。构建前请从腾讯官方 TUIKit 下载页面获取与项目匹配的 Android 源码，并将模块放在本仓库根目录的 `TUIKit` 文件夹下。第三方 TUIKit 源码不随本仓库分发。

Windows PowerShell：

```powershell
cd qch
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug --console=plain
```

应用签名配置请在本机单独创建 `qch/signing.properties`；本地签名材料和构建产物不会提交到仓库。
