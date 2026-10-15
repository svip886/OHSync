package io.github.bqj6666.ohsync.hook

import android.util.Log
import io.github.bqj6666.ohsync.core.Ids
import io.github.bqj6666.ohsync.core.RecordMapper
import io.github.bqj6666.ohsync.core.RecordType
import io.github.bqj6666.ohsync.core.SyncRecord

/**
 * 从 OPPO 数据库的真实结构推导「读什么、怎么翻译」。
 *
 * 表名与列名都取自**运行中的数据库**（sqlite_master + PRAGMA table_info），
 * 不硬编码、不靠 dex 推断。
 *
 * 单位全部来自实测样本（见 Probe 的输出），不靠猜：
 *   total_distance / distance —— 米
 *   total_calories / calories —— 卡，除以 1000 才是千卡
 *   weight —— 克，除以 1000 才是千克
 *   heart_rate_value —— bpm；systolic/diastolic —— mmHg；血氧 —— 百分比
 *
 * 没被证据支持的字段一律不写进 Health Connect。
 */
internal class TableSpec(
    val type: RecordType,
    val sql: String,
    val toRecord: (Map<String, String?>) -> SyncRecord?,
)

internal object QueryPlan {

    private const val TAG = "OHSyncPlan"

    /** 时间窗口由用户在 OHSync 里设置，这里取主进程下发的值。 */
    private val windowDays: Long
        get() = (RemoteConfig.fetch()?.windowDays ?: 90).toLong()

    /**
     * 每种记录单次推送的上限。
     *
     * 实测一次全量是 8 万多条（血氧/心率这类连续测量每天几百条），
     * 一次性写进 Health Connect 又慢又容易触发它的限流，所以取最近 N 条。
     *
     * 注意：只取「最近 N 条」意味着**永远覆盖不到更早的历史**，所以除了增量，
     * 每次周期同步还会额外回填一段历史切片（见 [forBackfillSlice]），逐步铺满窗口。
     */
    private const val PER_SPEC_LIMIT = 3000

    /**
     * 单次回填切片的时间跨度。
     *
     * 取值要在「别撞上 [PER_SPEC_LIMIT]」和「别回填得太慢」之间平衡：步数这类
     * 一天百来条，两周也就一千多条，还在上限内；心率这类一天几百条本来就会
     * 撞上限，切片再大也没用（靠 [Pusher] 一次多做几段来补）。
     *
     * 放在 core/Ids 是因为主进程也要用它来挪游标，两边必须是同一个值。
     */
    private val BACKFILL_SLICE_MS: Long get() = Ids.BACKFILL_SLICE_MS

    /** 由 [buildSpecs] 每次调用时设置：本次要读的时间下界（含）与上界（不含）。 */
    @Volatile private var lowerBound: Long = 0L
    @Volatile private var upperBound: Long = Long.MAX_VALUE

    /**
     * 增量窗口向过去回看多少。
     *
     * 为什么不能像早期那样只留 2 分钟边界重叠：**手表 → 手机端的数据是晚到的**。
     * 昨晚 22:13 结束的运动，可能当晚 23 点后才从手表同步进 OPPO 健康的库，而增量的
     * 游标（lastSyncAt）早已越过 22:13 —— 按「业务时间 >= 游标」去查，这条记录一辈子
     * 都进不来。实测丢过的数据（2026-10-13/14）：
     *   - 10-13 22:13 的自由训练：OPPO 库里有（DBOneTimeSport _id=660），HC 里整条没有；
     *   - 10-14 22:26 爬楼机：会话行赶上了游标，但它的卡路里/心率/距离明细全部缺失；
     *   - 10-13 的静息心率。
     *
     * 所以增量固定向过去多看 48 小时。重复推送由 Health Connect 按 clientRecordId
     * upsert 幂等吸收，代价是每次同步多写一两千条（实测完全可接受），换来的是
     * 「晚到多少都补得回来」。睡眠不受此影响 —— 它本来就按整个窗口重查。
     */
    const val PERIODIC_OVERLAP_MS = 48 * 3_600_000L

    /** 手动「立即同步」时回看得更久，覆盖手表离线几天才连上手机的场景。 */
    const val MANUAL_OVERLAP_MS = 7 * 86_400_000L

    private fun computeSince(overlapMs: Long): Long {
        val windowStart = System.currentTimeMillis() - windowDays * 86_400_000L
        val last = RemoteConfig.fetch()?.lastSyncAt ?: 0L
        // 上次同步点无效（首次运行）时退化为完整窗口
        if (last <= 0L) return windowStart
        return maxOf(windowStart, last - overlapMs)
    }

    private val since: Long get() = lowerBound

    /** 本次查询的时间上界（不含）。增量同步是 MAX，回填切片是切片终点。 */
    private val upper: Long get() = upperBound

    /**
     * @param overlapMs 增量窗口向过去回看多少：周期同步用 [PERIODIC_OVERLAP_MS]，
     *        手动「立即同步」用 [MANUAL_OVERLAP_MS]（见重叠常量上的说明）。
     *        从没同步过时（lastSyncAt=0）[computeSince] 自动退化成整个时间窗口，
     *        「首次拉全量、之后增量」这条规则在这里兜住，调用方不用判断。
     */
    fun forTables(reader: TableReader, overlapMs: Long = PERIODIC_OVERLAP_MS): List<TableSpec> =
        buildSpecs(reader, computeSince(overlapMs), Long.MAX_VALUE)

    /**
     * 回填切片：取 [before - 一段, before) 这一段的历史。
     *
     * 为什么需要它：单次推送有 PER_SPEC_LIMIT 上限，且查询固定按时间倒序取「最近 N 条」，
     * 于是每次拿到的都是同一批数据，更早的记录一辈子进不来 —— 表现就是步数只覆盖 15 天、
     * 心率 5 天、血氧 10 天、活动卡路里 8 天（睡眠因为总量小没触上限，所以齐全）。
     *
     * 切片终点由主进程下发；**下一段从哪开始由读取端按实际取到的数据决定**
     * （见 [sliceLower] 与 Pusher 的说明），主进程不再机械地减去一个固定跨度。
     */
    fun forBackfillSlice(reader: TableReader, before: Long): List<TableSpec> {
        if (before <= 0L) return emptyList()
        val lower = sliceLower(before)
        if (lower >= before) return emptyList()
        return buildSpecs(reader, lower, before)
    }

    /**
     * 一段切片的时间下界（含），同时受时间窗口约束。
     *
     * 单独暴露出来是因为「本段没有取到任何数据」时，读取端要把游标直接挪到这里，
     * 别按固定跨度瞎跳。
     */
    fun sliceLower(before: Long): Long {
        val windowStart = System.currentTimeMillis() - windowDays * 86_400_000L
        return maxOf(windowStart, before - BACKFILL_SLICE_MS)
    }

    private fun buildSpecs(reader: TableReader, lower: Long, upperExclusive: Long): List<TableSpec> {
        lowerBound = lower
        upperBound = upperExclusive
        val out = ArrayList<TableSpec>()
        for (table in reader.tableNames()) {
            val cols = reader.columns(table)
            if (cols.isEmpty()) continue
            val key = table.lowercase()
            when {
                key.contains("sportdatadetail") -> out += segmentSpecs(table, cols)
                key == "dbweightbodyfattable" -> weightSpec(table, cols)?.let { out += it }
                key == "dbheartrate" -> heartRateSpec(table, cols)?.let { out += it }
                key == "dbbloodpressure" -> bloodPressureSpec(table, cols)?.let { out += it }
                key == "dbbloodoxygensaturation" -> bloodOxygenSpec(table, cols)?.let { out += it }
                // 单次运动会话（跑步/骑行/游泳…）。DBOneTimeSportStat 的名字里同样含
                // "onetimesport"，所以这里必须精确匹配表名，否则两张表会同时命中。
                key == "dbonetimesport" -> oneTimeSportSpec(table, cols)?.let { out += it }
                // 心率日统计表：从中取 rest_hr 作为静息心率
                key == "dbheartratedatastattable" -> restingHrSpec(table, cols)?.let { out += it }
                // 睡眠不在这里：它是多行拼成一条会话，见 SleepBuilder
            }
        }
        Log.i(TAG, "生成 ${out.size} 条同步方案：${out.map { it.type.label }}")
        return out
    }

    /**
     * 分段明细表：一段一行，含 steps / distance / calories。
     * 一张表出三条记录（步数、距离、活动卡路里），都以 start_time 为主键素材。
     */
    private fun segmentSpecs(table: String, cols: Set<String>): List<TableSpec> {
        val out = ArrayList<TableSpec>()
        val startCol = timeCol(cols, "start") ?: return out
        val endCol = timeCol(cols, "end")
        val selectEnd = if (endCol != null) ", $endCol" else ""

        fun spec(type: RecordType, column: String, valueKey: String, convert: (Double) -> Double) {
            if (column !in cols) return
            val sql = "SELECT $startCol$selectEnd, $column FROM $table " +
                "WHERE $column > 0 AND $startCol >= $since AND $startCol < $upper " +
                "ORDER BY $startCol DESC LIMIT $PER_SPEC_LIMIT"
            out += TableSpec(type, sql) { r ->
                val start = r[startCol]?.toLongOrNull()
                val value = r[column]?.toDoubleOrNull()
                if (start == null || start <= 0L || value == null || value <= 0.0) {
                    null
                } else {
                    val end = endCol?.let { r[it]?.toLongOrNull() }
                    SyncRecord(
                        type = type.id,
                        // 该表 _id 恒为 0，不能当主键；用起始时间毫秒定位一段
                        sourceKey = "$table:$start",
                        startTime = start,
                        endTime = maxOf(end ?: start, start),
                        values = mapOf(valueKey to convert(value)),
                    )
                }
            }
        }

        spec(RecordType.STEPS, "steps", RecordMapper.V_COUNT) { it }
        spec(RecordType.DISTANCE, "distance", RecordMapper.V_DISTANCE_M) { it }  // 已是米
        spec(RecordType.ACTIVE_CALORIES, "calories", RecordMapper.V_CALORIES) { it / 1000.0 }
        return out
    }

    /** DBWeightBodyFatTable：weight 单位为克。 */
    private fun weightSpec(table: String, cols: Set<String>): TableSpec? {
        if ("weight" !in cols || "measurement_timestamp" !in cols) return null
        val sql = "SELECT weight_id, measurement_timestamp, weight, body_fat_rate FROM $table " +
            "WHERE weight > 0 AND measurement_timestamp < $upper " +
            "ORDER BY measurement_timestamp DESC LIMIT $PER_SPEC_LIMIT"
        return TableSpec(RecordType.WEIGHT, sql) { r ->
            val ts = r["measurement_timestamp"]?.toLongOrNull()
            val grams = r["weight"]?.toDoubleOrNull()
            if (ts == null || ts <= 0L || grams == null || grams <= 0.0) {
                null
            } else {
                SyncRecord(
                    type = RecordType.WEIGHT.id,
                    sourceKey = r["weight_id"] ?: "w:$ts",
                    startTime = ts,
                    endTime = ts,
                    values = mapOf(RecordMapper.V_MASS_KG to grams / 1000.0),
                )
            }
        }
    }

    /** DBHeartRate：一次测量一行，值即 bpm。 */
    private fun heartRateSpec(table: String, cols: Set<String>): TableSpec? {
        if ("heart_rate_value" !in cols || "data_created_timestamp" !in cols) return null
        val hasType = "heart_rate_type" in cols
        val typeCol = if (hasType) ", heart_rate_type" else ""
        val hasDev = "device_unique_id" in cols
        val devCol = if (hasDev) ", device_unique_id" else ""
        val sql = "SELECT data_created_timestamp, heart_rate_value$typeCol$devCol FROM $table " +
            "WHERE heart_rate_value > 0 AND data_created_timestamp >= $since " +
            "AND data_created_timestamp < $upper " +
            "ORDER BY data_created_timestamp DESC LIMIT $PER_SPEC_LIMIT"
        return TableSpec(RecordType.HEART_RATE, sql) { r ->
            val ts = r["data_created_timestamp"]?.toLongOrNull() ?: return@TableSpec null
            val bpm = r["heart_rate_value"]?.toDoubleOrNull() ?: return@TableSpec null
            // 不能用 _id：实测这张表里大量行的 _id 为 0，会全部挤到同一条记录上。
            // 主键是 (ssoid, device_unique_id, data_created_timestamp, heart_rate_type)，
            // 所以用时间戳 + 类型唯一标识一次测量。
            val hrType = r["heart_rate_type"] ?: "0"
            val dev = r["device_unique_id"] ?: "-"
            if (ts <= 0L || bpm <= 0.0) {
                null
            } else {
                SyncRecord(
                    type = RecordType.HEART_RATE.id,
                    sourceKey = "$table:$ts:$hrType:$dev",
                    startTime = ts,
                    endTime = ts,
                    values = mapOf(
                        RecordMapper.V_BPM to bpm,
                        "sample_0_bpm" to bpm,
                        "sample_0_at" to ts.toDouble(),
                    ),
                )
            }
        }
    }

    /** DBBloodPressure：measure_timestamp / systolic / diastolic，单位 mmHg。 */
    private fun bloodPressureSpec(table: String, cols: Set<String>): TableSpec? {
        if (!cols.containsAll(listOf("measure_timestamp", "systolic", "diastolic"))) return null
        val sql = "SELECT measure_timestamp, systolic, diastolic FROM $table " +
            "WHERE systolic > 0 AND diastolic > 0 AND measure_timestamp >= $since " +
            "AND measure_timestamp < $upper " +
            "ORDER BY measure_timestamp DESC LIMIT $PER_SPEC_LIMIT"
        return TableSpec(RecordType.BLOOD_PRESSURE, sql) { r ->
            val ts = r["measure_timestamp"]?.toLongOrNull() ?: return@TableSpec null
            val sys = r["systolic"]?.toDoubleOrNull() ?: return@TableSpec null
            val dia = r["diastolic"]?.toDoubleOrNull() ?: return@TableSpec null
            if (ts <= 0L) {
                null
            } else {
                SyncRecord(
                    type = RecordType.BLOOD_PRESSURE.id,
                    sourceKey = "$table:${ts}_${sys.toInt()}_${dia.toInt()}",
                    startTime = ts,
                    endTime = ts,
                    values = mapOf(
                        RecordMapper.V_PRESSURE_SYS to sys,
                        RecordMapper.V_PRESSURE_DIA to dia,
                    ),
                )
            }
        }
    }

    /** DBBloodOxygenSaturation：值为百分比。 */
    private fun bloodOxygenSpec(table: String, cols: Set<String>): TableSpec? {
        val valueCol = "blood_oxygen_saturation_value"
        if (valueCol !in cols || "data_created_timestamp" !in cols) return null
        val hasType = "blood_oxygen_saturation_type" in cols
        val typeCol = if (hasType) ", blood_oxygen_saturation_type" else ""
        val hasDev = "device_unique_id" in cols
        val devCol = if (hasDev) ", device_unique_id" else ""
        val sql = "SELECT data_created_timestamp, $valueCol$typeCol$devCol FROM $table " +
            "WHERE $valueCol > 0 AND data_created_timestamp >= $since " +
            "AND data_created_timestamp < $upper " +
            "ORDER BY data_created_timestamp DESC LIMIT $PER_SPEC_LIMIT"
        return TableSpec(RecordType.BLOOD_OXYGEN, sql) { r ->
            val ts = r["data_created_timestamp"]?.toLongOrNull() ?: return@TableSpec null
            val v = r[valueCol]?.toDoubleOrNull() ?: return@TableSpec null
            // 读数越界的直接丢弃，不往 HC 里写脏数据
            if (ts <= 0L || v <= 0.0 || v > 100.0) {
                null
            } else {
                SyncRecord(
                    type = RecordType.BLOOD_OXYGEN.id,
                    // 同心率：_id 不可靠，用时间戳 + 类型
                    sourceKey = "$table:$ts:${r["blood_oxygen_saturation_type"] ?: "0"}:${r["device_unique_id"] ?: "-"}",
                    startTime = ts,
                    endTime = ts,
                    values = mapOf(RecordMapper.V_PERCENT to v),
                )
            }
        }
    }

    /**
     * DBOneTimeSport：单次运动主表。
     *
     * 列实测为 _id / start_time / end_time / sport_mode（见 Probe 的 [运动表] 输出）。
     * 这张表没有距离/卡路里/时长列 —— 那些在 DBOneTimeSportStat 与 DBTrackMetadata 里，
     * 而 Health Connect 的 ExerciseSessionRecord 本身也只承载「会话 + 类型」，
     * 所以这里只取区间与类型，够用。
     *
     * _id 是 INTEGER PRIMARY KEY AUTOINCREMENT，可靠，直接当 sourceKey。
     */
    private fun oneTimeSportSpec(table: String, cols: Set<String>): TableSpec? {
        if (!cols.containsAll(listOf("start_time", "end_time", "sport_mode"))) return null
        val sql = "SELECT _id, start_time, end_time, sport_mode FROM $table " +
            "WHERE end_time > start_time AND start_time >= $since AND start_time < $upper " +
            "ORDER BY start_time DESC LIMIT $PER_SPEC_LIMIT"
        return TableSpec(RecordType.EXERCISE_SESSION, sql) { r ->
            val id = r["_id"]?.toLongOrNull()
            val start = r["start_time"]?.toLongOrNull()
            val end = r["end_time"]?.toLongOrNull()
            if (id == null || start == null || end == null || start <= 0L || end <= start) {
                null
            } else {
                SyncRecord(
                    type = RecordType.EXERCISE_SESSION.id,
                    sourceKey = "$table:$id",
                    startTime = start,
                    endTime = end,
                    // sport_mode 原始值照搬，由主进程翻译成 HC 的 exerciseType（两端分工）
                    metadata = mapOf(
                        "sportMode" to (r["sport_mode"]?.toIntOrNull() ?: -1).toString()
                    ),
                )
            }
        }
    }

    /**
     * DBHeartRateDataStatTable：心率日统计，其中 rest_hr 就是静息心率（一天一条）。
     *
     * 这张表另有 max/min/average_hr（整天统计）与 sleep_base_hr，目前只取 rest_hr ——
     * Health Connect 的 RestingHeartRateRecord 正好是「一天一个静息值」。
     */
    private fun restingHrSpec(table: String, cols: Set<String>): TableSpec? {
        if (!cols.containsAll(listOf("date", "rest_hr"))) return null
        // date 列实测是 yyyymmdd 形式的整数（形如 20261012），不是毫秒时间戳，
        // 没法拿毫秒去和它在 SQL 里比 —— 时间窗口过滤挪到 Kotlin 侧（这张表很小，全取无妨）。
        val sql = "SELECT _id, date, rest_hr FROM $table " +
            "WHERE rest_hr > 0 ORDER BY date DESC LIMIT $PER_SPEC_LIMIT"
        return TableSpec(RecordType.RESTING_HEART_RATE, sql) { r ->
            val ms = epochMillis(r["date"]?.toLongOrNull()) ?: return@TableSpec null
            val bpm = r["rest_hr"]?.toDoubleOrNull() ?: return@TableSpec null
            if (bpm <= 0.0 || ms < since || ms >= upper) {
                null
            } else {
                SyncRecord(
                    type = RecordType.RESTING_HEART_RATE.id,
                    sourceKey = "$table:${r["_id"] ?: ms.toString()}",
                    startTime = ms,
                    endTime = ms,
                    values = mapOf(RecordMapper.V_BPM to bpm),
                )
            }
        }
    }

    /**
     * 把日期列的值规整成毫秒时间戳。
     * DBHeartRateDataStatTable.date 实测是毫秒时间戳；顺手兜住秒时间戳与 yyyymmdd
     * 两种形态，避免格式判断错导致整批记录被丢。
     */
    private fun epochMillis(v: Long?): Long? = when {
        v == null || v <= 0L -> null
        v > 100_000_000_000L -> v          // 毫秒时间戳
        v > 1_000_000_000L -> v * 1000L    // 秒时间戳
        v > 10_000_000L -> runCatching {   // yyyymmdd
            java.time.LocalDate.of((v / 10000).toInt(), ((v / 100) % 100).toInt(), (v % 100).toInt())
                .atStartOfDay(java.time.ZoneOffset.ofHours(8)).toInstant().toEpochMilli()
        }.getOrNull()
        else -> null
    }

    /** 时间列名在不同表里不一致：汇总表是 start_time，明细表是 start_timestamp。 */
    private fun timeCol(cols: Set<String>, prefix: String): String? = when {
        "${prefix}_time" in cols -> "${prefix}_time"
        "${prefix}_timestamp" in cols -> "${prefix}_timestamp"
        else -> null
    }
}
