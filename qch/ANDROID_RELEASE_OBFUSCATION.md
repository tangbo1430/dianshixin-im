# Android Release 混淆构建

## 验收包

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:assembleQaRelease
```

`qaRelease` 启用与正式包相同的 R8 和资源裁剪规则，使用 Android 调试签名，产物可直接安装测试。Debug 构建保持不混淆。

## 正式包

复制 `signing.properties.example` 为本地 `signing.properties`，填写以下配置：

```properties
storeFile=key.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

然后执行：

```powershell
.\gradlew.bat clean :app:assembleRelease
```

`signing.properties` 和密钥文件已被 Git 忽略。未提供签名配置时，Release 只生成未签名产物。

## Mapping

混淆映射位于：

```text
app/build/outputs/mapping/qaRelease/mapping.txt
app/build/outputs/mapping/release/mapping.txt
```

每个对外 APK 必须保存对应的 `mapping.txt`。映射文件不得打入 APK或提交到公开仓库，不能用其他版本的 mapping 还原崩溃栈。
