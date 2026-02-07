# WebDAV 同步功能实现分析

> 分析日期: 2026-02-07
> 分析人: Claude Code Agent

---

## 一、功能边界与模块结构

### 1.1 功能边界

WebDAV 同步功能属于 **配置备份/恢复** 子系统的一部分，主要用于将 v2rayNG 的配置数据备份到远程 WebDAV 服务器或从服务器恢复配置。

```
配置备份恢复系统
├── 本地备份/恢复 (Local File System)
├── WebDAV 备份/恢复 (Remote Server)  <-- 分析目标
└── 分享备份 (Share via Intent)
```

### 1.2 相关模块

| 模块 | 文件路径 | 职责 |
|------|----------|------|
| 备份 UI | `ui/BackupActivity.kt` | 用户交互界面，选择备份/恢复方式 |
| WebDAV 管理器 | `handler/WebDavManager.kt` | WebDAV 协议通信核心实现 |
| 配置存储 | `handler/MmkvManager.kt` | WebDAV 配置的持久化存储 |
| 数据模型 | `dto/WebDavConfig.kt` | WebDAV 连接配置 DTO |
| ZIP 工具 | `util/ZipUtil.kt` | 备份文件压缩/解压 |
| 应用配置 | `AppConfig.kt` | 常量定义 (备份目录名、文件名) |

---

## 二、WebDAV 协议版本分析

### 2.1 协议版本

**该实现使用的是 WebDAV Class 1 协议** (RFC 4918 基础子集)

特点：
- 仅使用最基本的 HTTP 扩展方法
- 没有使用 Class 2 的锁定机制 (LOCK/UNLOCK)
- 没有使用属性操作 (PROPFIND/PROPPATCH)

### 2.2 使用的 WebDAV/HTTP 方法

| 方法 | 用途 | 代码位置 |
|------|------|----------|
| `PUT` | 上传文件到服务器 | `WebDavManager.kt:66` |
| `GET` | 从服务器下载文件 | `WebDavManager.kt:93` |
| `MKCOL` | 创建远程目录 | `WebDavManager.kt:168` |

### 2.3 核心代码片段

#### 文件上传 (PUT)

**位置**: `WebDavManager.kt:46-80`

```kotlin
suspend fun uploadFile(localFile: File, remoteFileName: String): Boolean = withContext(Dispatchers.IO) {
    val remote = buildRemoteUrl(remoteFileName)
    try {
        val cl = client ?: return@withContext false

        // 确保父目录存在
        val dirPath = remote.substringBeforeLast('/')
        if (dirPath != remote) {
            ensureRemoteDirs(dirPath)
        }

        // 根据文件扩展名确定 Content-Type
        val mediaType = when (localFile.extension.lowercase()) {
            "zip" -> "application/zip"
            "json" -> "application/json"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }.toMediaTypeOrNull()

        val body = localFile.asRequestBody(mediaType)
        val req = applyAuth(Request.Builder().url(remote).put(body)).build()
        cl.newCall(req).execute().use { resp ->
            val success = resp.isSuccessful
            if (!success) {
                Log.e(AppConfig.TAG, "WebDAV upload failed: $remote (HTTP ${resp.code})")
            }
            return@withContext success
        }
    } catch (e: Exception) {
        Log.e(AppConfig.TAG, "WebDAV upload exception: $remote", e)
        return@withContext false
    }
}
```

#### 认证实现 (HTTP Basic Auth)

**位置**: `WebDavManager.kt:142-149`

```kotlin
private fun applyAuth(builder: Request.Builder): Request.Builder {
    val username = cfg?.username
    val password = cfg?.password
    if (!username.isNullOrEmpty()) {
        builder.header("Authorization", Credentials.basic(username, password ?: ""))
    }
    return builder
}
```

#### 目录创建 (MKCOL)

**位置**: `WebDavManager.kt:158-182`

```kotlin
private fun ensureRemoteDirs(dirUrl: String) {
    try {
        val cl = client ?: return
        val url = URL(dirUrl)
        val segments = url.path.split("/").filter { it.isNotEmpty() }
        var accum = ""
        for (seg in segments) {
            accum += "/$seg"
            val mkUrl = URL(url.protocol, url.host, if (url.port == -1) -1 else url.port, accum).toString()
            try {
                val req = applyAuth(Request.Builder().url(mkUrl).method("MKCOL", null)).build()
                cl.newCall(req).execute().use { resp ->
                    // 201 Created 或 405 Method Not Allowed (已存在) 都可接受
                    if (resp.code != 201 && resp.code != 405 && resp.code != 409) {
                        Log.w(AppConfig.TAG, "WebDAV MKCOL $mkUrl returned ${resp.code}")
                    }
                }
            } catch (_: Exception) {
                // best-effort, continue
            }
        }
    } catch (e: Exception) {
        Log.e(AppConfig.TAG, "WebDAV ensureRemoteDirs error", e)
    }
}
```

---

## 三、HTTP 401 错误分析

### 3.1 错误信息

```
webdav upload failed: http://192.168.1.3/backups/backup_ng.zip (HTTP 401)
```

### 3.2 401 状态码含义

HTTP 401 Unauthorized 表示：
- 请求需要用户认证
- 提供的认证信息无效或缺失
- 服务器拒绝了当前的访问凭证

### 3.3 根本原因分析

#### 问题点 1: 认证条件判断

**位置**: `WebDavManager.kt:145`

```kotlin
if (!username.isNullOrEmpty()) {
    builder.header("Authorization", Credentials.basic(username, password ?: ""))
}
```

**问题**: 只有当 `username` 非空时才添加认证头。如果用户配置时未填写用户名，请求将不带任何认证信息。

#### 问题点 2: URL 格式

```
http://192.168.1.3/backups/backup_ng.zip
```

**问题**: 使用的是 `http://` 而非 `https://`。某些 WebDAV 服务器可能：
- 强制要求 HTTPS 进行认证
- 在 HTTP 连接上拒绝认证请求

#### 问题点 3: 仅支持 Basic 认证

当前实现只支持 HTTP Basic Authentication，不支持：
- Digest Authentication
- OAuth
- NTLM (Windows 认证)

### 3.4 可能的 401 错误原因汇总

| 可能原因 | 概率 | 说明 |
|----------|------|------|
| 用户名/密码配置错误 | 高 | 最常见原因 |
| 用户名为空 | 中 | 导致不发送认证头 |
| 服务器需要 Digest 认证 | 中 | 代码只支持 Basic 认证 |
| 路径权限问题 | 低 | `/backups` 目录可能需要特殊权限 |
| HTTPS 强制要求 | 低 | 某些服务器拒绝 HTTP 的认证请求 |

---

## 四、数据流分析

```
用户点击备份
     │
     ▼
BackupActivity.backupViaWebDav()
     │
     ├── MmkvManager.decodeWebDavConfig() → 读取 WebDAV 配置
     │
     ├── backupConfigurationToCache() → 创建本地 ZIP 备份
     │
     ├── WebDavManager.init(config) → 初始化 OkHttp 客户端
     │
     └── WebDavManager.uploadFile()
            │
            ├── buildRemoteUrl() → 构建完整 URL
            ├── ensureRemoteDirs() → MKCOL 创建目录
            ├── applyAuth() → 添加 Basic Auth 头
            └── PUT 请求 → 上传文件
                   │
                   ▼
              HTTP 401 错误 ← 认证失败
```

---

## 五、配置数据模型

### WebDavConfig

**位置**: `dto/WebDavConfig.kt`

```kotlin
data class WebDavConfig(
    val baseUrl: String,              // WebDAV 服务器基础 URL
    val username: String? = null,     // 用户名 (可选)
    val password: String? = null,     // 密码 (可选)
    val remoteBasePath: String = "/", // 远程备份目录
    val timeoutSeconds: Long = 30     // 超时时间 (秒)
)
```

### 相关常量

**位置**: `AppConfig.kt:13-14`

```kotlin
const val WEBDAV_BACKUP_DIR = "backups"           // 默认备份目录名
const val WEBDAV_BACKUP_FILE_NAME = "backup_ng.zip"  // 备份文件名
```

---

## 六、解决 401 错误的建议

### 6.1 用户排查步骤

1. **验证配置**
   - 打开 v2rayNG 的 WebDAV 设置对话框
   - 确认用户名和密码已正确填写且非空
   - 确认 URL 格式正确

2. **测试连接**
   使用 curl 命令验证服务器认证：
   ```bash
   curl -v -u username:password http://192.168.1.3/backups/
   ```

3. **检查服务器日志**
   查看 WebDAV 服务器的访问日志，确认：
   - 是否收到请求
   - 收到的认证信息是否正确

4. **尝试 HTTPS**
   如果服务器支持，将 URL 配置为 `https://` 格式

### 6.2 代码层面的潜在改进点

当前实现的局限性：

1. **认证方式单一**
   - 仅支持 HTTP Basic 认证
   - 不支持 Digest、OAuth、NTLM 等其他认证方式

2. **错误提示不足**
   - 401 错误时没有详细的错误提示给用户
   - 用户无法区分是配置问题还是服务器问题

3. **没有预检机制**
   - 没有在保存配置时验证连接有效性
   - 用户只能在实际备份时才发现配置错误

---

## 七、技术栈总结

| 项目 | 技术/版本 |
|------|-----------|
| WebDAV 协议版本 | Class 1 基础实现 (RFC 4918 子集) |
| 使用的方法 | PUT, GET, MKCOL |
| 认证方式 | HTTP Basic Authentication |
| HTTP 客户端 | OkHttp |
| 异步框架 | Kotlin Coroutines |
| 配置存储 | MMKV (腾讯) |

---

## 八、相关文件列表

| 文件 | 作用 |
|------|------|
| `handler/WebDavManager.kt` | WebDAV 协议通信核心 |
| `ui/BackupActivity.kt` | 备份/恢复 UI 和业务逻辑 |
| `dto/WebDavConfig.kt` | 配置数据模型 |
| `handler/MmkvManager.kt` | 配置持久化 (第 651-661 行) |
| `AppConfig.kt` | 常量定义 (第 13-14 行) |
