package io.github.bqj6666.ohsync.core

import android.content.Context

/**
 * 用户可调设置。
 *
 * 存 SharedPreferences：只有两个值，（interval, windowDays），
 * 用 DataStore 要多引一个依赖和一层协程，不划算。
 */
object Settings {

    private const val PREFS = "ohsync_settings"
    private const val KEY_INTERVAL = "sync_interval_minutes"
    private const val KEY_WINDOW = "window_days"
    private const val KEY_LAST_SYNC = "last_incremental_sync_at"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_BACKFILL_BEFORE = "backfill_before"
    private const val KEY_LAST_RESTART = "last_health_restart_at"
    private const val KEY_ROOT_STATE = "root_state"
    private const val KEY_WEBDAV_URL = "webdav_url"
    private const val KEY_WEBDAV_USER = "webdav_user"
    private const val KEY_WEBDAV_PASS = "webdav_pass"
    private const val KEY_WEBDAV_PATH = "webdav_path"
    private const val KEY_EXPORT_ENABLED = "export_enabled"
    private const val KEY_EXPORT_MIN_GAP = "export_min_gap_min"
    private const val KEY_LAST_EXPORT_AT = "last_export_at"
    private const val KEY_LAST_EXPORT_RESULT = "last_export_result"
    private const val KEY_EXPORT_OTHER_SOURCES = "export_other_sources"
    private const val KEY_EXPORT_WINDOW = "export_window_days"

    /** 同步间隔选项。0 表示只在手动点「立即同步」时才推。 */
    val INTERVAL_OPTIONS: List<Pair<Int, String>> = listOf(
        0 to "仅手动",
        15 to "15 分钟",
        30 to "30 分钟",
        60 to "1 小时",
        180 to "3 小时",
        360 to "6 小时",
        720 to "12 小时",
        1440 to "每天",
    )

    /** 时间窗口选项：决定一次回填多久的历史。 */
    val WINDOW_OPTIONS: List<Pair<Int, String>> = listOf(
        7 to "最近 7 天",
        30 to "最近 30 天",
        90 to "最近 3 个月",
        180 to "最近 6 个月",
        365 to "最近 1 年",
        3650 to "全部",
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun intervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL)

    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL, minutes).apply()
        // 立刻告知读取端，否则要等兜底刷新（最长 1 小时）才生效
        TriggerSender.configChanged(context)
    }

    fun windowDays(context: Context): Int =
        prefs(context).getInt(KEY_WINDOW, DEFAULT_WINDOW)

    fun setWindowDays(context: Context, days: Int) {
        val old = windowDays(context)
        prefs(context).edit().putInt(KEY_WINDOW, days).apply()
        // 只有**窗口变大**才需要重新回填：把游标置 0，读取端下一轮会从「现在」重新
        // 往前铺，一直铺到新的窗口起点。窗口变小不用管 —— 已经回填的比新窗口还久，
        // 多的那些留着也不碍事。
        //
        // 注意这一步不能省：日常推送都是增量的（只取上次同步之后的新数据），
        // 窗口调大变出来的那段历史只能靠回填游标去补，游标不重置就永远补不到。
        if (days > old) setBackfillBefore(context, 0L)
        TriggerSender.configChanged(context)
    }

    fun intervalLabel(context: Context): String =
        INTERVAL_OPTIONS.firstOrNull { it.first == intervalMinutes(context) }?.second
            ?: "${intervalMinutes(context)} 分钟"

    fun windowLabel(context: Context): String =
        WINDOW_OPTIONS.firstOrNull { it.first == windowDays(context) }?.second
            ?: "最近 ${windowDays(context)} 天"

    /**
     * 上次增量同步的时间点。
     *
     * 周期推送只取这个时间之后的数据 —— 否则每小时都要把整个窗口
     * （1.5 万条量级）重读重写一遍，纯属浪费。
     */
    fun lastSyncAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC, 0L)

    fun setLastSyncAt(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, at).apply()
    }

    /**
     * 是否保持常驻（前台服务）。
     *
     * 默认开。关掉可以省一个常驻进程和一条通知，但本机不会因为数据接口访问去
     * 拉起没在跑的应用，后台同步会随之失效 —— 那时只有打开应用才能同步。
     */
    fun keepAlive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_ALIVE, true)

    fun setKeepAlive(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_ALIVE, on).apply()
        if (on) SyncService.start(context) else SyncService.stop(context)
    }

    /**
     * 回填切片终点。0 = 尚未开始（主进程首次下发时固定成「现在」），
     * -1 = 已铺满整个窗口、不用再回填，>0 = 还要继续往前推。
     */
    fun backfillBefore(context: Context): Long =
        prefs(context).getLong(KEY_BACKFILL_BEFORE, 0L)

    fun setBackfillBefore(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_BACKFILL_BEFORE, at).apply()
    }

    /** 上次重启 OPPO 健康的时间（毫秒），用于节流。 */
    fun lastHealthRestart(context: Context): Long =
        prefs(context).getLong(KEY_LAST_RESTART, 0L)

    fun setLastHealthRestart(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_LAST_RESTART, at).apply()
    }

    /**
     * root 授权状态：-1 = 还没检测过，0 = 没有，1 = 有。
     *
     * 落盘是为了「只询问一次」：每次执行 su 都可能让 KernelSU 弹一次授权框，
     * 检测结果为「没有」时就不再自动检测，只留界面上的手动按钮。
     */
    fun rootState(context: Context): Int = prefs(context).getInt(KEY_ROOT_STATE, ROOT_UNKNOWN)

    fun setRootState(context: Context, state: Int) {
        prefs(context).edit().putInt(KEY_ROOT_STATE, state).apply()
    }

    const val ROOT_UNKNOWN = -1
    const val ROOT_NO = 0
    const val ROOT_YES = 1

    // ---- WebDAV 导出 --------------------------------------------------------
    //
    // 凭据只存在本应用的私有 SharedPreferences 里（清单里 allowBackup=false，
    // 不会被备份出去）。**绝不写进代码或仓库**：仓库是公开 fork，
    // 写死就等于把坚果云账号送人。

    fun webdavUrl(context: Context): String = prefs(context).getString(KEY_WEBDAV_URL, "") ?: ""

    fun webdavUser(context: Context): String = prefs(context).getString(KEY_WEBDAV_USER, "") ?: ""

    fun webdavPass(context: Context): String = prefs(context).getString(KEY_WEBDAV_PASS, "") ?: ""

    /** 远端存放目录，拼在 [webdavUrl] 后面。默认 health。 */
    fun webdavPath(context: Context): String =
        prefs(context).getString(KEY_WEBDAV_PATH, "health")?.takeIf { it.isNotBlank() } ?: "health"

    fun setWebDav(context: Context, url: String, user: String, pass: String, path: String) {
        prefs(context).edit()
            .putString(KEY_WEBDAV_URL, url.trim())
            .putString(KEY_WEBDAV_USER, user.trim())
            .putString(KEY_WEBDAV_PASS, pass.trim())
            .putString(KEY_WEBDAV_PATH, path.trim().ifBlank { "health" })
            .apply()
    }

    /** 三项都填了才算配好。 */
    fun webDavReady(context: Context): Boolean =
        webdavUrl(context).isNotBlank() && webdavUser(context).isNotBlank() &&
            webdavPass(context).isNotBlank()

    /** 是否开启导出（关闭后不自动上传，「立即上传」也拦着）。 */
    fun exportEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXPORT_ENABLED, false)

    fun setExportEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXPORT_ENABLED, on).apply()
    }

    /** 两次自动上传之间的最小间隔。同步很频繁时不该反复生成整份 JSON。 */
    fun exportMinGapMinutes(context: Context): Int =
        prefs(context).getInt(KEY_EXPORT_MIN_GAP, DEFAULT_EXPORT_MIN_GAP)

    fun setExportMinGapMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_EXPORT_MIN_GAP, minutes).apply()
    }

    fun lastExportAt(context: Context): Long = prefs(context).getLong(KEY_LAST_EXPORT_AT, 0L)

    fun setLastExportAt(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_LAST_EXPORT_AT, at).apply()
    }

    fun lastExportResult(context: Context): String =
        prefs(context).getString(KEY_LAST_EXPORT_RESULT, "") ?: ""

    fun setLastExportResult(context: Context, text: String) {
        prefs(context).edit().putString(KEY_LAST_EXPORT_RESULT, text).apply()
    }

    /**
     * 导出时是否把**别的应用**写进 Health Connect 的记录也算进当日值。
     *
     * 默认关：步数这类数据一旦混入 Google Play 服务写的记录就会偏高（实测同一
     * 天 1822 vs 1486）。开着的时候来源分层的明细照样给出来，能看出是谁贡献的。
     * 将来华为体脂秤经 Health Sync 进来时，体重要单独看别的源的记录，再开。
     */
    fun exportOtherSources(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXPORT_OTHER_SOURCES, false)

    fun setExportOtherSources(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXPORT_OTHER_SOURCES, on).apply()
    }

    /**
     * 导出窗口（天）。**与回填范围分开，但默认跟随它**。
     *
     * 回填范围决定把多少历史补进 Health Connect；导出窗口决定那份 JSON 覆盖多久。
     * 两者分开是因为「全部」回填可能要读十年，导出跟着跑会又慢又大 —— 但用户选的
     * 是「全部」，默认就该给全部，所以默认值 0 = 「跟随回填范围」。
     * 想收窄再单独选具体天数。
     */
    fun exportWindowDays(context: Context): Int =
        prefs(context).getInt(KEY_EXPORT_WINDOW, EXPORT_FOLLOW_SYNC)

    fun setExportWindowDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_EXPORT_WINDOW, days).apply()
    }

    /** 实际生效的导出窗口天数：选了「跟随」就用回填范围。 */
    fun effectiveExportWindowDays(context: Context): Int =
        exportWindowDays(context).takeIf { it > 0 } ?: windowDays(context)

    /**
     * 导出窗口选项。第一项是「跟随回填范围」（值 0），其余与回填范围同一套 ——
     * 包含「全部」，因为选了全部回填就该给全部导出。
     */
    val EXPORT_WINDOW_OPTIONS: List<Pair<Int, String>> =
        listOf(EXPORT_FOLLOW_SYNC to "跟随回填范围") + WINDOW_OPTIONS

    /** 导出窗口的默认值：跟随回填范围。 */
    const val EXPORT_FOLLOW_SYNC = 0

    /** 两次自动上传之间的最小间隔（分钟）。 */
    const val DEFAULT_EXPORT_MIN_GAP = 30

    /** 默认 1 小时：够及时，也不至于反复扫库。 */
    const val DEFAULT_INTERVAL = 60

    /** 默认回填 3 个月。 */
    const val DEFAULT_WINDOW = 90
}
