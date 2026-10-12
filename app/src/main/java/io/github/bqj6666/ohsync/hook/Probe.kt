package io.github.bqj6666.ohsync.hook

import android.util.Log

/**
 * 证据探针：把还没把握的字段的**真实取值**打到日志里。
 *
 * 为什么需要它：单位与枚举含义猜错会让 Health Connect 里出现脏数据，比缺数据更难排查。
 * 与其猜，不如把原始样本打出来，看过之后再决定映射。
 *
 * 只读，不改任何东西。
 */
object Probe {

    private const val TAG = "OHSyncProbe"

    fun run(reader: TableReader) {
        sleepPieceTypes(reader)
        sleepNightTotals(reader)
        sleepSessions(reader)
        sportSamples(reader)
        bodySamples(reader)
        sportRecordTables(reader)
    }

    /**
     * 睡眠分段：DBSleepPiece.type 是 OPPO 的私有枚举，全量反编译后追完所有引用链
     * （SleepPieceDao / SleepPieceStore / 同步层）都找不到常量定义或分支语义，
     * 只当作不透明 int 搬运。所以先看真实分布。
     */
    private fun sleepPieceTypes(reader: TableReader) {
        val table = "DBSleepPiece"
        if (reader.tableNames().none { it == table }) return
        val rows = reader.query(
            "SELECT type, COUNT(*) AS cnt, MIN(start_timestamp) AS first_ts, " +
                "MAX(end_timestamp) AS last_ts, " +
                "AVG(end_timestamp - start_timestamp) AS avg_len " +
                "FROM $table GROUP BY type ORDER BY type"
        )
        if (rows.isEmpty()) {
            Log.w(TAG, "$table 无数据")
            return
        }
        Log.i(TAG, "$table.type 取值分布（${rows.size} 种）：")
        for (r in rows) {
            Log.i(
                TAG,
                "  type=${r["type"]} 条数=${r["cnt"]} 平均段长=" +
                    "${(r["avg_len"]?.toDoubleOrNull()?.div(60000.0))?.toInt()}分钟 " +
                    "范围=${fmt(r["first_ts"])}~${fmt(r["last_ts"])}",
            )
        }
    }

    /** 步数/距离/卡路里的原始值：用于确认距离与卡路里的单位，确认前不写入 HC。 */
    private fun sportSamples(reader: TableReader) {
        for (table in reader.tableNames()) {
            val cols = reader.columns(table)
            if ("total_steps" !in cols && "steps" !in cols) continue
            val picked = listOf(
                "_id", "start_time", "start_timestamp", "end_time", "end_timestamp",
                "total_steps", "steps", "total_distance", "distance",
                "total_calories", "calories", "timezone",
            ).filter { it in cols }
            if (picked.isEmpty()) continue
            val rows = reader.query(
                "SELECT ${picked.joinToString()} FROM $table " +
                    "ORDER BY 1 DESC LIMIT 3"
            )
            Log.i(TAG, "$table 样本（列：${picked.joinToString()}）：")
            rows.forEachIndexed { i, r ->
                Log.i(TAG, "  [$i] " + picked.joinToString { "$it=${r[it]}" })
            }
        }
    }

    /** 体重/体脂表的样本，确认 measurement_timestamp 单位与数值量级。 */
    private fun bodySamples(reader: TableReader) {
        for (table in reader.tableNames()) {
            val cols = reader.columns(table)
            if ("weight" !in cols || "measurement_timestamp" !in cols) continue
            val picked = listOf(
                "weight_id", "measurement_timestamp", "weight", "body_fat_rate", "height",
            ).filter { it in cols }
            val rows = reader.query(
                "SELECT ${picked.joinToString()} FROM $table ORDER BY measurement_timestamp DESC LIMIT 3"
            )
            Log.i(TAG, "$table 样本：")
            rows.forEachIndexed { i, r ->
                Log.i(TAG, "  [$i] " + picked.joinToString { "$it=${r[it]}" })
            }
        }
    }

    /**
     * 运动会话表的体检。
     *
     * 背景：1.1.0 之前完全没有同步运动记录，所以先确认「运动落在哪张表、有多少条、
     * sport_mode 都取了哪些值」，再决定读哪张、怎么映射。
     *
     * 表名取自 sqlite_master，列名取自 PRAGMA table_info —— 都是运行时真实结构。只读。
     */
    private fun sportRecordTables(reader: TableReader) {
        val present = reader.tableNames().toSet()
        for (table in SPORT_TABLES) {
            if (table !in present) {
                Log.i(TAG, "[运动表] $table 不存在")
                continue
            }
            val cols = reader.columns(table)
            val count = reader.query("SELECT COUNT(*) AS c FROM $table")
                .firstOrNull()?.get("c")
            Log.i(TAG, "[运动表] $table 共 $count 行")
            Log.i(TAG, "  列: ${cols.sorted().joinToString()}")

            if ("sport_mode" in cols) {
                val dist = reader.query(
                    "SELECT sport_mode, COUNT(*) AS c FROM $table " +
                        "GROUP BY sport_mode ORDER BY c DESC"
                )
                Log.i(TAG, "  sport_mode 分布: " +
                    dist.joinToString { "mode=${it["sport_mode"]}×${it["c"]}" })
            }
            for (col in listOf("display", "sync_status")) {
                if (col !in cols) continue
                val dist = reader.query(
                    "SELECT $col, COUNT(*) AS c FROM $table GROUP BY $col ORDER BY c DESC"
                )
                Log.i(TAG, "  $col 分布: " +
                    dist.joinToString { "${it[col]}×${it["c"]}" })
            }

            val order = listOf("start_time", "start_timestamp").firstOrNull { it in cols } ?: "1"
            val picked = listOf(
                "_id", "client_data_id", "start_time", "end_time",
                "start_timestamp", "end_timestamp", "sport_mode", "sport_name",
                "total_duration", "total_distance", "total_calories", "total_steps",
                "display", "timezone",
            ).filter { it in cols }
            val rows = reader.query(
                "SELECT ${picked.joinToString()} FROM $table ORDER BY $order DESC LIMIT 5"
            )
            Log.i(TAG, "  最近 5 条:")
            rows.forEachIndexed { i, r ->
                Log.i(TAG, "    [$i] " + picked.joinToString { "$it=${r[it]}" })
            }
        }
    }

    /** 运动会话的候选表，按可信度排序。 */
    private val SPORT_TABLES = listOf(
        "DBOneTimeSport",     // 单次运动主表：start_time / end_time / sport_mode
        "DBTrackMetadata",    // 轨迹类运动汇总：另有 sport_name / 距离 / 卡路里
        "DBOneTimeSportStat", // 运动统计视图
    )

    private fun fmt(millis: String?): String {
        val v = millis?.toLongOrNull() ?: return "-"
        return if (v <= 0) "-" else java.time.Instant.ofEpochMilli(v).toString()
    }

    /**
     * 按「日期 + type」汇总分段时长。
     *
     * 用途：拿它和 OPPO 健康界面上显示的睡眠阶段对账，确定 type 的语义。
     * 例：界面显示某晚 深睡68分/浅睡262分/快速眼动106分/清醒2分，
     * 那么当晚四种 type 的分钟数就能一一对应上，映射不再是猜。
     */
    private fun sleepNightTotals(reader: TableReader) {
        if (reader.tableNames().none { it == "DBSleepPiece" }) return
        val rows = reader.query(
            "SELECT date(start_timestamp/1000, 'unixepoch', '+8 hours') AS d, type, " +
                "COUNT(*) AS cnt, SUM(end_timestamp - start_timestamp)/60000 AS mins " +
                "FROM DBSleepPiece GROUP BY d, type ORDER BY d DESC, type LIMIT 24"
        )
        Log.i(TAG, "DBSleepPiece 按夜汇总（日期 / type / 段数 / 分钟）：")
        for (r in rows) {
            Log.i(TAG, "  ${r["d"]}  type=${r["type"]}  段数=${r["cnt"]}  共 ${r["mins"]} 分钟")
        }
    }

    /** DBSleepTable 的睡眠段（判断夜间睡眠与小睡如何区分）。 */
    private fun sleepSessions(reader: TableReader) {
        if (reader.tableNames().none { it == "DBSleepTable" }) return
        val cols = reader.columns("DBSleepTable")
        val picked = listOf(
            "start_time", "end_time", "sleep_type", "sleep_state", "device_type",
        ).filter { it in cols }
        if (picked.isEmpty()) return
        val rows = reader.query(
            "SELECT ${picked.joinToString()} FROM DBSleepTable ORDER BY start_time DESC LIMIT 6"
        )
        Log.i(TAG, "DBSleepTable 最近 6 段（列：${picked.joinToString()}）：")
        for (r in rows) {
            Log.i(TAG, "  " + picked.joinToString { "$it=${r[it]}" })
        }
    }
}
