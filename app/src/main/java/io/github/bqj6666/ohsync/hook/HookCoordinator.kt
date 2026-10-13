package io.github.bqj6666.ohsync.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Hook 侧总编排。
 *
 * 两个阶段，对应 LSPosed 的两个回调：
 *   onPackageLoaded —— App 创建**之前**。只装 hook，不碰数据库（此时还没有 Context）。
 *   onPackageReady  —— App 创建**之后**。拿 Context、兜底取库、出报告、推送。
 *
 * 之所以分两段：OPPO 健康在 Application 初始化时就打开数据库，若等 onPackageReady
 * 再挂钩，首次开库已经过去了，拿不到实例。
 */
class HookCoordinator(private val xposed: XposedInterface) {

    private val reader = TableReader()
    private val dbAccess = DbAccess(xposed)

    @Volatile private var reported = false

    /**
     * 上次「下发推送」的时刻。
     *
     * 两个用途：① 挡住 1.5 秒内的重复下发；② 作为同步间隔的计时基准之一
     * （另一个是主进程持久化的 lastSyncAt，见 [syncWanted]）。
     *
     * 必须是成员变量而不是调度线程的局部变量 —— 局部变量会随进程重启归零，
     * 那正是「宿主每次重启都立刻多推一次、用户设的间隔被绕过」的成因。
     */
    @Volatile private var lastPushAt = 0L

    /** 阶段一：App 创建之前。 */
    fun onPackageLoaded(cl: ClassLoader) {
        runCatching { dbAccess.installEarlyHooks(cl) }
            .onFailure { Log.e(TAG, "提前挂钩失败", it) }
    }

    /** 阶段二：App 创建之后。 */
    fun onPackageReady(cl: ClassLoader) {
        AppContextHolder.init()

        // 事件通道**先注册**：它只依赖 Context，不依赖数据库。
        //
        // 早期版本把它放在「拿到数据库之后」，于是 OPPO 健康启动完却没开库时，
        // 用户点「立即同步」的广播根本没人接 —— 界面就一直停在「已通知读取端」，
        // 用户只能自己去打开一次 OPPO 健康。这是本轮修的主症状。
        startSyncScheduler()

        dbAccess.onDatabaseReady = { db ->
            reader.attach(db)
            reportTables()
            reportedNotReady = false
            // 拿到库时若正好到点（或用户要求过）就顺手推一次；没到点就不推 ——
            // 「OPPO 健康重启了」本身不构成同步的理由，用户设的是 1 小时。
            // 调度线程那边的「等库」状态由它自己在下一轮清零（库已就绪时必然走到分支外）。
            val cfg = RemoteConfig.fetch()
            if (syncWanted(cfg, System.currentTimeMillis())) {
                pushNow(withProbe = cfg?.backfillRequested == true, incremental = true)
            }
        }

        // 兜底：路 A 错过首次开库时，反复重试从 AppDatabase.INSTANCE 反射取
        dbAccess.startFallbackLoop(cl)
    }

    /** 已经回报过「库还没就绪」。每次拿到库后复位，避免反复回报。 */
    @Volatile private var reportedNotReady = false

    /**
     * 真实表结构报告：表名取自 sqlite_master，是权威来源。
     * 认不出语义的表照实列出来（进未识别清单），不猜。
     */
    private fun reportTables() {
        if (reported) return
        reported = true
        val tables = reader.tableNames()
        Log.i(TAG, "数据库共 ${tables.size} 张表")
        var known = 0
        for (t in tables) {
            val type = TableRules.resolve(t.lowercase()) ?: continue
            known++
            val mark = if (type.hcRecord == null) "（无 HC 对应，废弃）" else ""
            Log.i(TAG, "  [表] $t -> ${type.label}$mark")
        }
        val unknown = tables.filter { TableRules.resolve(it.lowercase()) == null }
        Log.i(TAG, "可识别 $known / 共 ${tables.size}")
        Log.i(TAG, "未识别：$unknown")
    }

    /**
     * 同步调度：事件驱动 + 本地计时，不做轮询。
     *
     *   - 用户点「立即同步」/ 改设置 → 主进程发广播 → 立即唤醒
     *   - 自动同步 → 本地按用户设的间隔计时
     *
     * 平时只在本地 wait，不产生任何跨进程调用。这一点很重要：早期版本每 5 秒
     * 查一次配置，等于把 OHSync 主进程永久钉在内存里（实测常驻 PSS 约 55 MB）。
     *
     * 另留一个低频兜底刷新（[CONFIG_REFRESH_MS]），防止广播被省电策略丢弃。
     */
    private fun startSyncScheduler() {
        if (schedulerStarted) return
        schedulerStarted = true

        Thread {
            var cfg: HookConfig? = null
            var cfgAt = 0L
            var lastFetchTry = 0L
            // 「想同步却拿不到库」从何时开始；0 = 当前没有这个诉求。
            var dbWaitSince = 0L
            var restarts = 0
            while (true) {
                try {
                    // 事件通道尽早注册。放在循环里反复试是因为 Application 挂上的
                    // 时机可能略晚于 onPackageReady，一次失败就再也不注册的话，
                    // 「立即同步」会重新变成没人接（正是本轮要修的毛病）。
                    tryRegisterTrigger()

                    val now = System.currentTimeMillis()
                    // 取配置失败也要节流：通道没注册时循环是 1 秒一轮，
                    // 不加这层就会退化成每秒一次的跨进程轮询（早期版本的毛病）。
                    val fetchDue = configDirty || cfg == null || now - cfgAt >= CONFIG_REFRESH_MS
                    if (fetchDue && now - lastFetchTry >= FETCH_MIN_GAP_MS) {
                        lastFetchTry = now
                        configDirty = false
                        RemoteConfig.fetch(force = true)?.let {
                            cfg = it
                            cfgAt = now
                        }
                    }

                    val manual = manualRequest
                    if (syncWanted(cfg, now)) {
                        if (reader.isReady()) {
                            // 手动与周期都是**增量**：整窗口只该在「第一次」干一次
                            // （QueryPlan.computeSince 发现 lastSyncAt=0 时会自动退化成
                            // 整个窗口），之后每次点「立即同步」不该把八万多条重推一遍。
                            // 更早的历史由下面的回填游标一轮轮往回铺，与增量互不干扰。
                            pushNow(
                                withProbe = manual || cfg?.backfillRequested == true,
                                incremental = true,
                            )
                            manualRequest = false
                            dbWaitSince = 0L
                        } else {
                            if (dbWaitSince == 0L) dbWaitSince = now
                            // 回报一次「我收到了，但库还没开」：主进程据此知道读方还
                            // 活着，不必去重启 OPPO 健康（那是没人响应时才该做的）。
                            // 只报一次 —— 反复报会把看门狗一直喂饱，反而掩盖问题。
                            if (manual && !reportedNotReady) {
                                reportedNotReady = true
                                RemoteConfig.reportReaderState(false)
                            }
                            // 确实想同步、却一直拿不到库 —— 只有这时才值得请主进程重启 OPPO 健康。
                            //
                            // 早期版本是**每个拿不到库的进程无条件每 20 分钟求一次**（一轮 60 秒
                            // × 20 轮），而 OPPO 健康有 5 个进程、其中只有主进程可能拿到库，
                            // 于是永远在重启宿主：实测每 20 分钟一次，每次重启又让同步计时器
                            // 归零、立刻多推一次，用户设的 1 小时就是这样被顶成「20 分钟一份」
                            // 的（夜间 02:00→08:50 那串整齐的 21 分钟间隔就是它）。
                            // 现在有两道闸：必须真的「想同步」，且每个进程一生最多求
                            // [MAX_RESTARTS_PER_PROCESS] 次。
                            if (now - dbWaitSince >= DB_WAIT_BEFORE_RESTART_MS &&
                                restarts < MAX_RESTARTS_PER_PROCESS
                            ) {
                                restarts++
                                dbWaitSince = now
                                Log.w(
                                    TAG,
                                    "想同步但 ${DB_WAIT_BEFORE_RESTART_MS / 1000} 秒拿不到数据库，" +
                                        "第 $restarts 次请求重启 OPPO 健康",
                                )
                                RemoteConfig.requestHealthRestart()
                            }
                        }
                    } else {
                        dbWaitSince = 0L
                    }
                    waitForEvent(if (triggerRegistered) TICK_MS else REGISTER_RETRY_MS)
                } catch (t: Throwable) {
                    Log.e(TAG, "同步调度异常", t)
                    waitForEvent(60_000L)
                }
            }
        }.apply { isDaemon = true }.start()
        Log.i(TAG, "同步调度已启动（事件驱动，本地计时）")
    }

    @Volatile private var triggerRegistered = false

    /**
     * 现在该不该推？
     *
     * 三个理由，任一成立即可：
     *   - 用户点了「立即同步」（广播设的 [manualRequest]）；
     *   - 主进程下发的 `backfillRequested` —— 用户点过同步、半路却碰上宿主重启时，
     *     这个标志还留在配置里，不至于白点一次；
     *   - 到了用户设的间隔。
     *
     * 「到点」的基准取 `max([lastPushAt], 主进程持久化的 lastSyncAt)`。**必须带上后者**：
     * 本进程的计时器会随进程重启归零，只看它的话宿主每次重启都会立刻多推一次，
     * 用户设的 1 小时就废了（实测表现是每 20 分钟上传一份）。
     * lastSyncAt 由主进程在每次推送真正结束后持久化，跨进程、跨重启都有效。
     */
    private fun syncWanted(cfg: HookConfig?, now: Long): Boolean {
        if (manualRequest) return true
        if (cfg == null) return false          // 配置取不到时保守处理：不推
        if (cfg.backfillRequested) return true
        val minutes = cfg.intervalMinutes
        if (minutes <= 0) return false         // 「仅手动」
        val anchor = maxOf(lastPushAt, cfg.lastSyncAt)
        return anchor <= 0L || now - anchor >= minutes * 60_000L
    }

    /** 注册事件通道。只需成功一次；Context 还没拿到时下一轮再试。 */
    private fun tryRegisterTrigger() {
        if (triggerRegistered) return
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context
        if (ctx == null) {
            Log.w(TAG, "还没有 Context，事件通道下一轮再试")
            return
        }
        SyncTrigger.register(
            ctx,
            onSyncNow = { manualRequest = true; configDirty = true; wake() },
            onConfigChanged = { configDirty = true; wake() },
            onPing = { RemoteConfig.ping() },
        )
        triggerRegistered = true
    }

    private var schedulerStarted = false

    /** 用户在应用里点了「立即同步」。 */
    @Volatile private var manualRequest = false

    /** 配置有变化，下一轮重新取。 */
    @Volatile private var configDirty = false

    private fun wake() = synchronized(wakeLock) { wakeLock.notifyAll() }

    private fun waitForEvent(ms: Long) {
        synchronized(wakeLock) {
            try {
                wakeLock.wait(ms)
            } catch (_: InterruptedException) {
            }
        }
    }

    private val wakeLock = Object()

    /**
     * 下发一次推送（异步，立刻返回）。
     *
     * 调用方必须先过 [syncWanted]：这里只管「1.5 秒内不重复下发」，
     * 不管「该不该同步」。
     *
     * @param withProbe   顺带跑一次证据探针（输出很啰嗦，只在用户明确要求时跑）
     * @param incremental 只取上次同步之后的新数据；整窗口只在「从没同步过」时自动发生
     *                    （见 QueryPlan.computeSince）
     */
    private fun pushNow(withProbe: Boolean, incremental: Boolean = false) {
        if (!reader.isReady()) {
            Log.w(TAG, "数据库未就绪，跳过推送")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastPushAt < 1500) return
        // 立刻记时间：它同时是同步间隔的计时基准，所以「尝试过」就要算数，
        // 不能等推送线程跑完（那要几十秒，期间调度线程会反复重新发起）。
        lastPushAt = now
        Thread {
            runCatching {
                if (withProbe) Probe.run(reader)
                Pusher.push(reader, incremental)
            }.onFailure { Log.e(TAG, "推送失败", it) }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "OHSyncHook"

        /** 本地计时粒度。只 sleep，不做任何 IPC。 */
        private const val TICK_MS = 30_000L

        /** 事件通道还没注册成功时的重试粒度，要快，用户可能马上就会点同步。 */
        private const val REGISTER_RETRY_MS = 1_000L

        /** 两次跨进程取配置之间的最小间隔。 */
        private const val FETCH_MIN_GAP_MS = 30_000L

        /** 兜底刷新配置的间隔（正常情况下由事件驱动）。 */
        private const val CONFIG_REFRESH_MS = 3_600_000L

        /** 想同步却一直拿不到数据库，等这么久才去请主进程重启 OPPO 健康。 */
        private const val DB_WAIT_BEFORE_RESTART_MS = 2 * 60_000L

        /**
         * 每个进程一生最多请求几次重启宿主。
         *
         * 用来掐断「拿不到库 → 重启 → 新进程仍拿不到 → 再重启」的无限循环。
         * 主进程那边还有 10 分钟节流兜着，两道闸叠起来，最坏也不会变成重启风暴。
         */
        private const val MAX_RESTARTS_PER_PROCESS = 3
    }
}
