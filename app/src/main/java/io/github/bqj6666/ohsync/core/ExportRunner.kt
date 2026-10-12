package io.github.bqj6666.ohsync.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 导出编排：从 Health Connect 生成 JSON → 传到 WebDAV。
 *
 * 这一套原本是应用外面的一条链：healthsync 内核模块用 root 读 HC 的明文库 +
 * hc-export.py 解析 + 传坚果云。现在整条挪进应用：**不需要 root、不需要内核模块、
 * 也不需要那个脚本**，只用应用自己的 Health Connect 读权限。
 *
 * 触发方式有两种：同步完成后自动（受最小间隔节流），以及界面上的「立即上传」。
 * 生成的 JSON 结构见 [SyncExport]。
 */
object ExportRunner {

    private const val TAG = "OHSyncExport"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var running = false

    /**
     * 一次同步结束后调用。
     *
     * 三道闸：总开关、凭据是否配齐、距上次上传是否够久。任何一道没过就静静跳过 ——
     * 自动导出失败不该打扰用户，失败原因留在 [Settings.lastExportResult] 里。
     */
    fun maybeAuto(context: Context) {
        val c = context.applicationContext
        if (!Settings.exportEnabled(c)) return
        if (!Settings.webDavReady(c)) {
            Log.i(TAG, "WebDAV 没配齐，跳过自动导出")
            return
        }
        val gapMs = Settings.exportMinGapMinutes(c) * 60_000L
        val last = Settings.lastExportAt(c)
        if (last > 0L && System.currentTimeMillis() - last < gapMs) {
            Log.i(TAG, "距上次导出不足 ${gapMs / 60_000} 分钟，跳过")
            return
        }
        run(c)
    }

    /** 界面上的「立即上传」，跳过所有节流。 */
    fun runNow(context: Context) {
        val c = context.applicationContext
        if (!Settings.webDavReady(c)) {
            SyncEngine.noteExportResult("WebDAV 还没配好（地址 / 账号 / 密码）")
            return
        }
        run(c)
    }

    private fun run(c: Context) {
        if (running) {
            Log.i(TAG, "上一次导出还没结束，跳过")
            return
        }
        running = true
        SyncEngine.noteExportStart()
        scope.launch {
            val result = try {
                doExport(c)
            } catch (t: Throwable) {
                Log.e(TAG, "导出失败", t)
                "导出失败：${t.message ?: t.javaClass.simpleName}"
            }
            running = false
            if (result.startsWith("已上传")) Settings.setLastExportAt(c, System.currentTimeMillis())
            Settings.setLastExportResult(c, result)
            SyncEngine.noteExportResult(result)
            Log.i(TAG, result)
        }
    }

    private suspend fun doExport(c: Context): String {
        val hc = HcClient(c)
        if (hc.client == null) return "导出失败：Health Connect 不可用"

        val window = Settings.windowDays(c)
        val exporter = SyncExport(c, hc)
        val json = exporter.buildJson(window, Settings.exportOtherSources(c))

        val cfg = WebDavClient.Config(
            url = Settings.webdavUrl(c),
            user = Settings.webdavUser(c),
            pass = Settings.webdavPass(c),
            path = Settings.webdavPath(c),
        )
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(OffsetDateTime.now())
        val bytes = WebDavClient.bytes(json)
        val err = WebDavClient.upload(cfg, linkedMapOf(
            // latest.json 是固定名，下游按它取；带时间戳的那份用于回溯
            "latest.json" to bytes,
            "health-$stamp.json" to bytes,
        ))
        if (err != null) return "上传失败：$err"

        val note = exporter.readFailures
            .takeIf { it.isNotEmpty() }
            ?.let { "；${it.size} 类读不到（缺读权限）：${it.take(3).joinToString("、")}" }
            ?: ""
        return "已上传 ${bytes.size / 1024} KB（窗口 $window 天）$note"
    }
}
