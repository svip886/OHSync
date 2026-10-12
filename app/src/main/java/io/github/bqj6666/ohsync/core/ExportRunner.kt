package io.github.bqj6666.ohsync.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
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

    /** 单次导出的时间上限。实测有过一次「读到一半没动静」，必须有兜底。 */
    private const val TIMEOUT_MS = 5 * 60_000L

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

    /** 界面上的「立即上传」：增量读 + 与缓存合并，然后上传全量。 */
    fun runNow(context: Context) {
        val c = context.applicationContext
        if (!Settings.webDavReady(c)) {
            SyncEngine.noteExportResult("WebDAV 还没配好（地址 / 账号 / 密码）")
            return
        }
        run(c, full = false)
    }

    /** 界面上的「全量重建」：丢掉缓存，把整个窗口重读一遍。 */
    fun runFullRebuild(context: Context) {
        val c = context.applicationContext
        if (!Settings.webDavReady(c)) {
            SyncEngine.noteExportResult("WebDAV 还没配好（地址 / 账号 / 密码）")
            return
        }
        run(c, full = true)
    }

    private fun run(c: Context, full: Boolean) {
        if (running) {
            Log.i(TAG, "上一次导出还没结束，跳过")
            return
        }
        running = true
        SyncEngine.noteExportStart()
        scope.launch {
            val result = try {
                withTimeout(TIMEOUT_MS) { doExport(c, full) }
            } catch (t: TimeoutCancellationException) {
                // 实测有过一次：窗口 10 年时读 HC 读到一半就没动静了。
                // 光靠日志看不出卡在哪，所以既加了每类型的耗时日志，也加了这道兜底。
                Log.w(TAG, "导出超时", t)
                "导出超时（超过 ${TIMEOUT_MS / 60_000} 分钟），已放弃。把导出窗口调小一些会更稳。"
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

    private suspend fun doExport(c: Context, full: Boolean): String {
        val hc = HcClient(c)
        if (hc.client == null) return "导出失败：Health Connect 不可用"

        // 用**导出窗口**而不是同步窗口：同步可以把「全部」都补进 HC，导出不必跟着跑那么远
        val window = Settings.exportWindowDays(c)
        val otherSources = Settings.exportOtherSources(c)
        val offsetSec = SyncExport.localOffsetSeconds

        // 本地那份「成品」缓存。给出去的是全量，但只有最近几天需要重读 HC。
        val snap = if (full) {
            ExportCache.clear(c)
            ExportCache.load(c, window, offsetSec, otherSources)
        } else {
            ExportCache.load(c, window, offsetSec, otherSources)
        }
        val readFrom = if (snap.days.isEmpty()) {
            Log.i(TAG, "没有可用缓存，本轮全量读整个窗口")
            null
        } else {
            // 往前多留 2 天：数据是陆续到的，昨天/前天的天值可能还在变。
            // 起点必须落在某天 00:00，否则那天的天值会残缺。
            LocalDate.now().minusDays(2).atStartOfDay(ZoneOffset.ofTotalSeconds(offsetSec))
                .toInstant().toEpochMilli()
                .also { Log.i(TAG, "增量读：只重读 ${Instant.ofEpochMilli(it)} 之后的记录，其余用缓存") }
        }

        val exporter = SyncExport(c, hc)
        val text = exporter.buildJson(window, otherSources, readFrom, snap.days)
        if (exporter.builtDays.isNotEmpty()) {
            ExportCache.save(c, exporter.builtDays, window, offsetSec, otherSources)
        }
        val plain = WebDavClient.bytes(text)
        val gz = WebDavClient.gzip(text)
        Log.i(TAG, "JSON ${plain.size / 1024} KB，gzip 后 ${gz.size / 1024} KB，共 ${exporter.builtDays.size} 天")

        val cfg = WebDavClient.Config(
            url = Settings.webdavUrl(c),
            user = Settings.webdavUser(c),
            pass = Settings.webdavPass(c),
            path = Settings.webdavPath(c),
        )
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(OffsetDateTime.now())
        val err = WebDavClient.upload(cfg, linkedMapOf(
            // 固定名，下游按它取 —— 用 gz，体积只有原来的十分之一量级
            "latest.json.gz" to gz,
            // 紧凑版一起传：下游切到 .gz 之前，老的读取方与人工核对还能用。
            // 确认不再需要后删掉这一行即可。
            "latest.json" to plain,
            // 带时间戳的那份只留归档，也压上：反正没人会手动读它
            "health-$stamp.json.gz" to gz,
        ))
        if (err != null) return "上传失败：$err"

        val note = exporter.readFailures
            .takeIf { it.isNotEmpty() }
            ?.let { "；${it.size} 类读不到（缺读权限）：${it.take(3).joinToString("、")}" }
            ?: ""
        return "已上传 gz ${gz.size / 1024} KB / 紧凑 json ${plain.size / 1024} KB" +
            "（窗口 $window 天，全量 ${exporter.builtDays.size} 天）$note"
    }
}
