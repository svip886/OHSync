package io.github.bqj6666.ohsync.core

import android.util.Base64
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 极简 WebDAV 客户端：建目录 + PUT 文件。
 *
 * 为什么不用现成的库：本应用只做「把一份 JSON 传上去」这一件事，引一个 WebDAV
 * 客户端依赖（还要拖来 OkHttp、xml 解析）不划算；PUT + Basic 认证就是全部需求。
 *
 * 只做 HTTPS：WebDAV 的凭据是明文 Basic，走 http 等于把账号密码发在公网上。
 */
object WebDavClient {

    private const val TAG = "OHSyncDav"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /** @param url 服务地址（含 WebDAV 根路径），@param path 远端存放目录 */
    data class Config(val url: String, val user: String, val pass: String, val path: String) {
        val remoteDir: String
            get() = url.trim().trimEnd('/') + "/" + path.trim().trim('/').ifBlank { "health" }
    }

    /**
     * 上传一组文件。
     *
     * @return null 表示全部成功；否则是给人看的失败原因（会直接显示在界面上）。
     */
    fun upload(cfg: Config, files: Map<String, ByteArray>): String? {
        if (!cfg.url.trim().startsWith("https://")) {
            return "服务地址必须以 https:// 开头（Basic 认证走 http 等于裸奔）"
        }
        val auth = "Basic " + Base64.encodeToString(
            "${cfg.user}:${cfg.pass}".toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        // 先建目录。已存在时服务器按规范返回 405，属正常情况，忽略。
        runCatching { mkcol(cfg.remoteDir, auth) }
            .onFailure { Log.w(TAG, "MKCOL 失败（目录可能已存在）", it) }

        for ((name, body) in files) {
            val err = put("${cfg.remoteDir}/$name", auth, body)
            if (err != null) return "$name 上传失败：$err"
        }
        return null
    }

    private fun mkcol(url: String, auth: String): String? =
        runCatching {
            val c = open(url, auth, "MKCOL")
            c.doOutput = true
            c.outputStream.use { }
            val code = c.responseCode
            c.disconnect()
            if (code in 200..299 || code == 405) null else "HTTP $code"
        }.getOrElse { it.message ?: it.javaClass.simpleName }

    private fun put(url: String, auth: String, body: ByteArray): String? =
        runCatching {
            val c = open(url, auth, "PUT")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val detail = if (code in 200..299) null else readBrief(c)
            c.disconnect()
            if (code in 200..299) null else "HTTP $code${if (detail.isNullOrBlank()) "" else " $detail"}"
        }.getOrElse { t -> t.message ?: t.javaClass.simpleName }

    private fun open(url: String, auth: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.instanceFollowRedirects = true
        c.setRequestProperty("Authorization", auth)
        c.setRequestProperty("User-Agent", "OHSync")
        return c
    }

    /** 失败时把服务端返回的正文截一小段带上，否则只有个状态码难排查。 */
    private fun readBrief(c: HttpURLConnection): String? = runCatching {
        val stream = c.errorStream ?: return@runCatching null
        val text = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        text.replace(Regex("\\s+"), " ").trim().take(160)
    }.getOrNull()

    /** 供调试：可直接把 JSON 文本写成字节。 */
    fun bytes(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)
}
