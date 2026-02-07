package com.v2ray.ang.handler

import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.text.Charsets

object WebDavManager {
    private var cfg: WebDavConfig? = null
    private var client: OkHttpClient? = null

    /**
     * Initialize the WebDAV manager with a configuration and build an OkHttp client.
     *
     * @param config WebDavConfig containing baseUrl, credentials, remoteBasePath and timeoutSeconds.
     */
    fun init(config: WebDavConfig) {
        cfg = config
        client = OkHttpClient.Builder()
            .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .authenticator { _, response ->
                // If we already have an Authorization header, check if it was rejected or if we can retry with a different scheme
                if (response.request.header("Authorization") != null) {
                    // Check if we exhausted attempts (limit to 3 to prevent loops)
                    if (responseCount(response) >= 3) {
                         Log.w(AppConfig.TAG, "WebDAV auth failed: credentials rejected by server (max retries reached)")
                         return@authenticator null
                    }
                }

                val username = cfg?.username
                val password = cfg?.password

                if (!username.isNullOrEmpty()) {
                    val challenges = response.challenges()
                    Log.d(AppConfig.TAG, "WebDAV server challenges: $challenges")

                    // 1. Try Digest Auth if available
                    val digestChallenge = challenges.find { it.scheme.equals("Digest", ignoreCase = true) }
                    if (digestChallenge != null) {
                        try {
                            val authHeader = calcDigestAuth(digestChallenge.realm, digestChallenge.authParams, username, password ?: "", response.request.method, response.request.url.encodedPath)
                            if (authHeader != null) {
                                Log.d(AppConfig.TAG, "WebDAV authenticator - responding with Digest Auth")
                                return@authenticator response.request.newBuilder()
                                    .header("Authorization", authHeader)
                                    .build()
                            }
                        } catch (e: Exception) {
                            Log.e(AppConfig.TAG, "WebDAV Digest calculation failed", e)
                        }
                    }

                    // 2. Fallback to Basic Auth
                    // Use UTF-8 charset for Basic Auth to support non-ASCII characters
                    val credential = Credentials.basic(username, password ?: "", Charsets.UTF_8)
                    Log.d(AppConfig.TAG, "WebDAV authenticator - responding with Basic Auth (UTF-8)")

                    return@authenticator response.request.newBuilder()
                        .header("Authorization", credential)
                        .build()
                }
                null
            }
            .build()
    }

    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++
            prior = prior.priorResponse
        }
        return result
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun calcDigestAuth(realm: String?, authParams: Map<String?, String>?, username: String, password: String, method: String, uri: String): String? {
        if (realm == null || authParams == null) return null

        val nonce = authParams["nonce"] ?: return null
        val qop = authParams["qop"]
        val algorithm = authParams["algorithm"] ?: "MD5"
        val opaque = authParams["opaque"]

        // HA1 = MD5(username:realm:password)
        val ha1 = md5("$username:$realm:$password")

        // HA2 = MD5(method:digestURI)
        val ha2 = md5("$method:$uri")

        val response: String
        val cnonce = String.format("%08x", (Math.random() * 100000000).toLong())
        val nc = "00000001" // Nonce count, simple implementation assuming 1 request per nonce

        if (qop != null && (qop == "auth" || qop.contains("auth"))) {
            // response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
            response = md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            // response = MD5(HA1:nonce:HA2) (Legacy RFC 2069)
            response = md5("$ha1:$nonce:$ha2")
        }

        val sb = StringBuilder()
        sb.append("Digest ")
        sb.append("username=\"$username\", ")
        sb.append("realm=\"$realm\", ")
        sb.append("nonce=\"$nonce\", ")
        sb.append("uri=\"$uri\", ")
        sb.append("response=\"$response\"")

        if (opaque != null) {
            sb.append(", opaque=\"$opaque\"")
        }
        if (algorithm != "MD5") {
            sb.append(", algorithm=\"$algorithm\"")
        }
        if (qop != null) {
            sb.append(", qop=\"auth\"")
            sb.append(", nc=$nc")
            sb.append(", cnonce=\"$cnonce\"")
        }

        return sb.toString()
    }


    /**
     * Upload a local file to a remote file name under the configured remoteBasePath.
     * The provided `remoteFileName` should be a file name (e.g. "backup_ng.zip").
     * The method will attempt to create parent directories via MKCOL before PUT.
     *
     * @param localFile File to upload.
     * @param remoteFileName Remote file name relative to configured remoteBasePath.
     * @return true if upload succeeded (HTTP 2xx), false otherwise.
     */
    suspend fun uploadFile(localFile: File, remoteFileName: String): Boolean = withContext(Dispatchers.IO) {
        val remote = buildRemoteUrl(remoteFileName)
        try {
            val cl = client ?: return@withContext false

            // Ensure parent directories exist
            val dirPath = remote.substringBeforeLast('/')
            if (dirPath != remote) {
                ensureRemoteDirs(dirPath)
            }

            // Determine content type based on file extension
            val mediaType = when (localFile.extension.lowercase()) {
                "zip" -> "application/zip"
                "json" -> "application/json"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }.toMediaTypeOrNull()

            val body = localFile.asRequestBody(mediaType)
            val req = Request.Builder().url(remote).put(body).build()
            cl.newCall(req).execute().use { resp ->
                val success = resp.isSuccessful
                if (success) {
                    Log.i(AppConfig.TAG, "WebDAV upload success: $remote")
                } else {
                    Log.e(AppConfig.TAG, "WebDAV upload failed: $remote (HTTP ${resp.code})")
                }
                return@withContext success
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "WebDAV upload exception: $remote", e)
            return@withContext false
        }
    }

    /**
     * Download a remote file (relative to configured remoteBasePath) into a local file.
     *
     * @param remoteFileName Remote file name relative to configured remoteBasePath.
     * @param destFile Local destination file to write to.
     * @return true if download and write succeeded, false otherwise.
     */
    suspend fun downloadFile(remoteFileName: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        val remote = buildRemoteUrl(remoteFileName)
        try {
            val cl = client ?: return@withContext false
            val req = Request.Builder().url(remote).get().build()
            cl.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(AppConfig.TAG, "WebDAV download failed: $remote (HTTP ${resp.code})")
                    return@withContext false
                }

                resp.body.byteStream().use { input ->
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { fos ->
                        input.copyTo(fos)
                    }
                }

                Log.i(AppConfig.TAG, "WebDAV download success: $remote")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "WebDAV download exception: $remote", e)
            return@withContext false
        }
    }

    /**
     * Build a full remote URL by combining the configured base URL, the configured
     * remote base path and a file name provided by the caller.
     *
     * Example: baseUrl="https://example.com/remote.php/dav", remoteBasePath="backups",
     * remoteFileName="backup_ng.zip" => "https://example.com/remote.php/dav/backups/backup_ng.zip"
     *
     * @param remoteFileName A file name relative to the configured remoteBasePath (no leading slash required).
     * @return Full URL string used for HTTP operations.
     */
    private fun buildRemoteUrl(remoteFileName: String): String {
        // Clean baseUrl: remove userInfo if present (user:pass@host -> host)
        var base = cfg?.baseUrl?.trimEnd('/') ?: ""
        try {
            val url = URL(base)
            if (url.userInfo != null) {
                Log.w(AppConfig.TAG, "WebDAV buildRemoteUrl - Removing userInfo from baseUrl")
                base = URL(url.protocol, url.host, url.port, url.path).toString().trimEnd('/')
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "WebDAV buildRemoteUrl - Failed to parse baseUrl: $base", e)
        }

        // Use configured remoteBasePath when not empty; otherwise fallback to AppConfig.WEBDAV_BACKUP_DIR
        val basePathConfigured = cfg?.remoteBasePath?.trim('/')?.takeIf { it.isNotEmpty() }
        val basePath = basePathConfigured ?: AppConfig.WEBDAV_BACKUP_DIR
        val rel = remoteFileName.trimStart('/')
        val result = if (basePath.isEmpty()) "$base/$rel" else "$base/$basePath/$rel"
        Log.d(AppConfig.TAG, "WebDAV buildRemoteUrl - base: '$base', basePath: '$basePath', file: '$rel' -> '$result'")
        return result
    }

    /**
     * Ensure that each directory segment in the given directory URL exists on the
     * WebDAV server. This issues MKCOL requests for each segment in a best-effort
     * manner and ignores errors for segments that already exist.
     *
     * @param dirUrl Absolute URL to the directory that should exist (e.g. https://.../backups)
     */
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
                    val req = Request.Builder().url(mkUrl).method("MKCOL", null).build()
                    cl.newCall(req).execute().use { resp ->
                        // 201 Created or 405 Method Not Allowed (already exists) are acceptable
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
}