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

    /**
     * 单次导出的时间上限。
     *
     * 给得很宽是因为：**首次**构建缓存要把整个窗口从 HC 读一遍，而 HC 的分页速度
     * 波动极大（实测同一个查询，快时 8 毫秒/页、慢时 3 秒/页，像是被限流）。
     * 十年的窗口页数有四五百页，最坏情况要十几分钟。缓存建成之后就只读最近几天了。
     */
    private const val TIMEOUT_MS = 30 * 60_000L

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
        // 自动导出走增量读 + 合并缓存；全量重建只留给用户手动触发
        run(c, full = false)
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

        // 导出窗口默认跟随「回填范围」—— 用户把回填设成「全部」，给出去的也应该是全部
        val window = Settings.effectiveExportWindowDays(c)
        val otherSources = Settings.exportOtherSources(c)
        val offsetSec = SyncExport.localOffsetSeconds

        // 本地那份「成品」缓存。给出去的是全量，但只有最近几天需要重读 HC。
        val snap = ExportCache.load(c, window, offsetSec, otherSources)
        val readFrom = if (full || snap.days.isEmpty()) {
            Log.i(
                TAG,
                if (full) "全量重建：整窗口重读（HC 分页在数据量大时会明显变慢，可能要几分钟）"
                else "没有可用缓存，本轮全量读整个窗口",
            )
            null
        } else {
            // 往前多留 4 天：数据是陆续到的，而且同步侧的增量窗口固定向过去回看 48 小时
            // （手表晚到的数据要靠它补），所以最近几天的天值一直在变，得反复重读。
            // 起点必须落在某天 00:00，否则那天的天值会残缺。
            LocalDate.now().minusDays(4).atStartOfDay(ZoneOffset.ofTotalSeconds(offsetSec))
                .toInstant().toEpochMilli()
                .also { Log.i(TAG, "增量读：只重读 ${Instant.ofEpochMilli(it)} 之后的记录，其余用缓存") }
        }
        // 全量重建时不复用缓存里的天；但**也不提前删掉旧缓存** —— 万一这次读到一半失败，
        // 旧缓存还在，远端那份文件至少还是上一次的正确版本（早先的写法是先把缓存清了，
        // 失败就两头空）。
        val reuse = if (full) emptyMap() else snap.days

        val exporter = SyncExport(c, hc)
        val text = exporter.buildJson(window, otherSources, readFrom, reuse)

        // 有类型读失败就**不发**这一轮。
        //
        // 为什么不能照发：增量读时重新读的那几天是**按类型整份重建**的，某个类型没读到
        // （超时或没权限），那几天的这个类型就会变成空 —— 覆盖上去等于把已有的正确数据
        // 删掉。宁可这一轮不出新文件（远端保持上一版），也不要发布一份缺数据的版本。
        val failed = exporter.readFailures
        if (failed.isNotEmpty()) {
            return "本轮跳过上传：${failed.size} 类没读到（${failed.take(4).joinToString("、")}）" +
                "—— 缓存与远端保持上一版不变，下一轮会自动重试；若一直如此，" +
                "请在下方「授权读取健康数据」重新授权。"
        }

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
            // 固定名，下游按它取
            "latest.json.gz" to gz,
            // 带时间戳的那份只留归档，同样压上
            "health-$stamp.json.gz" to gz,
        ))
        if (err != null) return "上传失败：$err"

        return "已上传 gz ${gz.size / 1024} KB（未压缩 ${plain.size / 1024} KB，" +
            "窗口 $window 天，全量 ${exporter.builtDays.size} 天）"
    }
}
