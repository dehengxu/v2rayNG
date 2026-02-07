# v2rayNG 构建环境配置指南

本文档旨在解决构建 v2rayNG 时遇到的 Go 原生库编译障碍。

## 核心问题

v2rayNG 是一个混合项目（Kotlin + Go），依赖 `AndroidLibXrayLite` 子模块中的 Go 代码。
构建 APK 前，必须先将 Go 代码编译为 Android AAR 库。

**当前障碍**:
1. `gomobile` 工具未正确初始化
2. NDK 环境配置缺失
3. Go 模块依赖未下载

## 解决方案步骤

### 1. 环境准备

确保已安装以下工具：
- Go 1.21+ (推荐 1.22)
- Android SDK
- Android NDK (推荐 r26c 或 r25c)
- JDK 17+

### 2. 配置 gomobile 工具链

```bash
# 1. 安装 gomobile
go install golang.org/x/mobile/cmd/gomobile@latest

# 2. 将 go bin 添加到 PATH
export PATH=$PATH:$(go env GOPATH)/bin

# 3. 初始化 gomobile (需要 NDK)
# 注意：必须指定 NDK 路径，否则会报错
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
gomobile init
```

### 3. 编译原生库 (AndroidLibXrayLite)

这是最关键的一步，必须在构建 APK 之前完成。

```bash
# 1. 进入子模块目录
cd AndroidLibXrayLite

# 2. 下载 Go 依赖 (配置代理)
export GOPROXY=https://goproxy.cn,direct
go mod tidy -v

# 3. 编译生成 AAR (耗时约 5-10 分钟)
# -target=android 指定目标平台
# -o libv2ray.aar 指定输出文件名
gomobile bind -v -target=android -androidapi 19 -ldflags='-s -w' ./
```

> **注意**: 编译成功后会生成 `libv2ray.aar` 和 `libv2ray-sources.jar`

### 4. 修复 Gradle 配置

确保 `app/build.gradle.kts` 能找到生成的 AAR 文件。通常需要将 `.aar` 文件复制到 `app/libs` 目录。

```bash
# 创建 libs 目录 (如果不存在)
mkdir -p ../V2rayNG/app/libs

# 复制生成的 aar
cp libv2ray.aar ../V2rayNG/app/libs/
```

### 5. 构建 APK

```bash
cd ../V2rayNG
./gradlew assembleRelease
```

## 常见问题排查

### Q1: `gomobile: ndk-bundle not found`
**解决**: 确保设置了 `ANDROID_NDK_HOME` 环境变量指向具体的 NDK 版本目录。

### Q2: `go: module not found`
**解决**: 检查网络代理，确保能访问 Google/GitHub 服务，或使用 `GOPROXY`。

### Q3: `compileSdkVersion` 不匹配
**解决**: 确保 `build.gradle` 中的 `compileSdkVersion` 与 NDK 支持的 API 级别兼容。

---
**自动化构建脚本**:

```bash
#!/bin/bash
set -e

# 配置环境
export ANDROID_HOME=/Users/dehengxu/dev/Android/sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
export PATH=$PATH:$(go env GOPATH)/bin
export GOPROXY=https://goproxy.cn,direct

echo "=== 1. 初始化子模块 ==="
git submodule update --init --recursive

echo "=== 2. 编译 Go 原生库 ==="
cd AndroidLibXrayLite
go mod tidy
gomobile bind -v -target=android -androidapi 21 -ldflags='-s -w' -o libv2ray.aar ./

echo "=== 3. 复制库文件 ==="
mkdir -p ../V2rayNG/app/libs
cp libv2ray.aar ../V2rayNG/app/libs/

echo "=== 4. 构建 APK ==="
cd ../V2rayNG
./gradlew assembleRelease

echo "=== 构建完成！==="
```
