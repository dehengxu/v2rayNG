# V2rayNG Android 构建操作手册

本文档记录了 v2rayNG 项目的构建流程、签名配置及产物说明。

## 1. 环境要求

- **JDK**: Java 17
- **Android SDK**: Latest Command-line tools & Platform-tools
- **Gradle**: Wrapper 8.x (项目中已包含 `./gradlew`)

## 2. 签名配置 (Release)

本项目已配置 Release 签名，用于生成可安装的正式包。

### 2.1 密钥库信息
- **文件位置**: `v2rayng-release.keystore` (位于项目根目录)
- **Key Alias**: `v2rayng`
- **Key Password**: `v2rayng123` (开发测试用)
- **Store Password**: `v2rayng123` (开发测试用)

> **⚠️ 安全提示**: 生产环境发布时，请务必更换为私有的强密码，且不要将 Keystore 密码提交到版本控制系统中（建议配置在 `local.properties` 或环境变量中）。

### 2.2 重新生成密钥 (如需)
如果需要重新生成密钥文件，请执行以下命令：

```bash
keytool -genkey -v -keystore v2rayng-release.keystore \
  -alias v2rayng \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass v2rayng123 \
  -keypass v2rayng123 \
  -dname "CN=v2rayNG, OU=Development, O=v2rayNG, L=Unknown, ST=Unknown, C=CN"
```

## 3. 构建流程

### 3.1 清理项目
构建前建议清理旧的构建产物：
```bash
./gradlew clean
```

### 3.2 编译 Release 包
执行以下命令编译所有架构的 Release APK：
```bash
./gradlew assembleRelease
```
或者只编译 Play Store 版本：
```bash
./gradlew assemblePlaystoreRelease
```

## 4. 构建产物

构建成功后，APK 文件位于 `app/build/outputs/apk/playstore/release/` 目录。

| 文件名后缀 | 架构说明 | 适用场景 |
|-----------|---------|---------|
| `_universal.apk` | 通用 | **推荐**。包含所有架构，兼容性最好 |
| `_arm64-v8a.apk` | ARM 64位 | 绝大多数现代 Android 手机 |
| `_armeabi-v7a.apk` | ARM 32位 | 旧款 Android 手机 |
| `_x86_64.apk` | Intel 64位 | 模拟器、Chromebook、平板 |
| `_x86.apk` | Intel 32位 | 旧款模拟器 |

## 5. 验证签名

使用 Android SDK 的 `apksigner` 工具验证签名是否生效（需支持 V2/V3 签名）：

```bash
# 请将 build-tools 版本号替换为您本地安装的版本
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose app/build/outputs/apk/playstore/release/v2rayNG_*_universal.apk
```

预期输出应包含：
```text
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
```
