package io.github.bqj6666.ohsync.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 主进程的同步引擎：串行消费 Hook 推来的批次，写 Health Connect。
 *
 * 用单消费者 + Mutex 而不是并行写：HC 的写入有配额，并行只会更快触发限流，
 * 而且失败时难以判断是哪一批出错。
 */
object SyncEngine {

    private const val TAG = "OHSyncEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** 看门狗检查间隔。 */
    private const val WATCHDOG_TICK_MS = 10_000L

    /** 通知后多久还没动静就去自救（重启 OPPO 健康）。 */
    private const val RESCUE_AFTER_MS = 60_000L

    /** 重启后每隔多久补发一次「立即同步」，直到超时放弃。 */
    private const val RENOTIFY_MS = 40_000L

    /** 自救之后多久仍然没动静就放弃，并明确报错。 */
    private const val GIVE_UP_AFTER_MS = 150_000L

    private val _status = MutableStateFlow(SyncStatus())

    /** 用持久化的值初始化「上次同步时间」，让界面重开后仍显示真实信息。 */
    fun restoreStatus(context: Context) {
        val at = Settings.lastSyncAt(context)
        if (at > 0) {
            _status.value = _status.value.copy(lastSyncAtMillis = at)
        }
    }
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /**
     * 是否已请求手动同步、但还没看到推送结果。
     *
     * 初始 false：UI 一进来不该显示「正在同步」。首次拿库时的历史回填由 Hook 侧
     * 独立判断（它不读这个标志），所以这里不需要为它置位。
     */
    /** 最后一次同步活动的时间，服务据此判断能否空闲退出。 */
    @Volatile private var lastActivity = System.currentTimeMillis()

    fun lastActivityAt(): Long = lastActivity

    /** 数据到达时刷新活动时间。 */
    fun noteActivity() {
        lastActivity = System.currentTimeMillis()
    }

    private val _backfillRequested = MutableStateFlow(false)
    private var hc: HcClient? = null

    private var appContext: Context? = null

    fun get(context: Context): SyncEngine {
        synchronized(this) {
            appContext = context.applicationContext
            if (hc == null) hc = HcClient(context.applicationContext)
            return this
        }
    }

    fun submit(batch: SyncBatch) {
        scope.launch { consume(batch) }
    }

    private suspend fun consume(batch: SyncBatch) = mutex.withLock {
        noteActivity()
        val client = hc ?: run { log("Health Connect 不可用，丢弃 ${batch.records.size} 条"); return@withLock }
        var ok = 0L; var skipped = 0L; var failed = 0L
        val mapped = ArrayList<androidx.health.connect.client.records.Record>(batch.records.size)
        for (r in batch.records) {
            try {
                if (r.recordType.hcRecord == null) { skipped++; continue }
                mapped += RecordMapper.map(r)
            } catch (t: Throwable) {
                failed++
                Log.e(TAG, "map failed for ${r.recordType.id}/${r.sourceKey}", t)
                log("翻译失败 ${r.recordType.id}: ${t.message}")
            }
        }
        try {
            client.writeAll(mapped)
            ok = mapped.size.toLong()
        } catch (t: Throwable) {
            failed += mapped.size
            Log.e(TAG, "批量写入失败（${mapped.size} 条）", t)
            log("批量写入失败：${t.message}")
        }
        _status.value = _status.value.copy(
            hookConnected = true,
            lastSyncAtMillis = System.currentTimeMillis(),
            syncedCount = _status.value.syncedCount + ok,
            skippedCount = _status.value.skippedCount + skipped,
            failedCount = _status.value.failedCount + failed,
            // 注意：这里**不**清 pendingBackfill。一次手动同步往往是多个批次，
            // 首批刚到就把「正在同步」抹掉会让界面闪一下又变回可点状态。
            // 真正的结束信号是读取端回报的 syncDone。
            rescueStage = 0,
        )
        noteActivity()
        log("批次完成：写入 $ok / 跳过 $skipped / 失败 $failed" +
            if (batch.isBackfill) "（历史回填）" else "（实时）")
    }

    /** 清除本应用写入 Health Connect 的全部记录（早期版本有过重复插入）。 */
    fun clearAll() {
        scope.launch {
            val c = hc ?: return@launch
            val n = runCatching { c.deleteAllMine() }
                .onFailure { Log.e(TAG, "清除失败", it) }
                .getOrDefault(0)
            _status.value = _status.value.copy(syncedCount = 0)
            log("已清除 $n 类记录，下次推送会重新写入")
        }
    }

    fun requestBackfill() {
        requestedAt = System.currentTimeMillis()
        _backfillRequested.value = true
        _status.value = _status.value.copy(
            pendingBackfill = true,
            rescueStage = 0,
            lastError = null,
        )
        // 事件驱动：直接唤醒读取端，不必等它下一轮计时
        appContext?.let { TriggerSender.syncNow(it) }
        log("已通知读取端开始同步")
        startWatchdog()
    }

    // ---- 看门狗 -------------------------------------------------------------

    /** 本次手动同步的发起时刻，看门狗据此计时。 */
    @Volatile private var requestedAt = 0L
    private var watchdog: Job? = null

    /**
     * 看门狗：通知读取端之后如果一直没人响应，就替用户把 OPPO 健康重启一遍。
     *
     * 为什么必须有：读取端跑在 OPPO 健康进程里。OPPO 健康若起来却没打开数据库，
     * 推送就完全做不了；更早的版本只指望读取端自己上报，而它可能压根没被注入，
     * 于是用户看到的永远是「已通知读取端」，最后只能自己去开一次 OPPO 健康。
     *
     * 判据是「有没有来自读取端的动静」：数据批次到达、或它主动回报库状态，都会
     * 刷新 [lastActivity]。真有动静就不打扰它，读完写完自然收工。
     */
    private fun startWatchdog() {
        val ctx = appContext ?: return
        watchdog?.cancel()
        watchdog = scope.launch {
            var rescuedAt = 0L
            var lastNudge = 0L
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                // 读取端回报 syncDone 会清掉这个标志，那就是正常收工
                if (!_backfillRequested.value) return@launch

                val now = System.currentTimeMillis()
                val idle = now - maxOf(requestedAt, lastActivity)

                if (rescuedAt == 0L && idle >= RESCUE_AFTER_MS) {
                    _status.value = _status.value.copy(rescueStage = 1)
                    log("读取端 ${idle / 1000} 秒无响应，尝试重启 OPPO 健康")
                    val ok = HealthRestarter.rescue(ctx)
                    if (ok) {
                        log("已下发重启，等它重新开库")
                    } else {
                        log("重启失败：需要 root 权限（见上方提示）")
                        _status.value = _status.value.copy(
                            rootGranted = HealthRestarter.rootGranted(ctx)
                        )
                    }
                    rescuedAt = now
                    lastNudge = now
                    continue
                }

                if (rescuedAt > 0L) {
                    // 重启后是全新进程，「手动同步」这个意图丢了 —— 只要还没有任何
                    // 动静就补发一次，免得白重启（用户设成「仅手动」时尤其需要）。
                    // 有动静（数据在流）时不打扰，否则等于让读取端再全量推一遍。
                    if (idle >= RENOTIFY_MS && now - lastNudge >= RENOTIFY_MS) {
                        lastNudge = now
                        TriggerSender.syncNow(ctx)
                        log("已重新通知读取端")
                    }
                    // 放弃同样看「最后一次动静」：推送中途一直在出数据就不算失败
                    if (now - maxOf(rescuedAt, lastActivity) >= GIVE_UP_AFTER_MS) {
                        _status.value = _status.value.copy(
                            rescueStage = 2,
                            lastError = "读取端始终没有响应。请确认 LSPosed 里本模块已启用、" +
                                "作用域勾选了「OPPO 健康」，并允许本应用使用 root。",
                        )
                        log("放弃：读取端无响应")
                        clearBackfillRequest()
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * 读取端回报：它收到了请求，以及数据库此刻是否就绪。
     *
     * 这是一次「读取端还活着」的证据，因此刷新活动时间 —— 它自己在等数据库时
     * 就不必由主进程去重启 OPPO 健康了。
     */
    fun noteReaderState(dbReady: Boolean) {
        noteActivity()
        _status.value = _status.value.copy(hookConnected = true, readerDbReady = dbReady)
    }

    /**
     * 界面打开时核对 root 状态。只在从未检测过时才真的执行 su。
     *
     * 一定要丢到后台线程：`su -c id` 最坏会阻塞到授权框倒计时结束（几十秒），
     * 在界面里同步做就是 ANR。
     */
    fun refreshRootState(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val granted = HealthRestarter.rootGranted(app)
            _status.value = _status.value.copy(rootGranted = granted)
        }
    }

    /** 用户点「申请 root 授权」：强制探测一次，让系统把授权框弹出来。同理走后台线程。 */
    fun requestRootGrant(context: Context) {
        val app = context.applicationContext
        log("正在申请 root 权限…")
        scope.launch {
            val granted = HealthRestarter.requestGrant(app)
            _status.value = _status.value.copy(rootGranted = granted)
            log(if (granted) "已获得 root 权限，可以自动重启 OPPO 健康" else "仍未获得 root 权限")
        }
    }

    /**
     * 问一次读取端是否在线。
     *
     * 只在界面打开时调用（事件驱动）。读取端收到后会回一个 provider 调用，
     * 由 [noteHookSeen] 记录状态。
     */
    fun pingReader() {
        appContext?.let { TriggerSender.ping(it) }
    }

    fun pendingBackfill(): Boolean = _backfillRequested.value

    /** Hook 侧完成手动同步后调用。 */
    fun clearBackfillRequest() {
        _backfillRequested.value = false
        _status.value = _status.value.copy(pendingBackfill = false)
    }

    fun syncedCount(): Long = _status.value.syncedCount

    fun noteHookSeen() {
        _status.value = _status.value.copy(hookConnected = true)
    }

    fun notePermission(granted: Boolean) {
        _status.value = _status.value.copy(hcPermissionGranted = granted)
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        _logs.value = (_logs.value + "• $line").takeLast(200)
    }
}
