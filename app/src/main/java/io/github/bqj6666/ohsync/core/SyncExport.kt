package io.github.bqj6666.ohsync.core

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

/**
 * 把 Health Connect 的数据渲染成导出用的那份 JSON。
 *
 * 这份格式原本由手机上的 hc-export.py（配合 healthsync 内核模块读 HC 明文库）产出，
 * 现在整个搬进应用：**不再需要 root、不需要额外的内核模块，也不需要那个脚本**。
 * 键名与结构保持原样（对方的下游脚本已按这套键解析），只做了这些补充：
 *   · 时间戳带时区偏移（`2026-10-11T23:30:41+08:00`），日期按当地日归属；
 *   · 每个口径都带来源分层（`steps_sources` / `metrics.<表>.sources`），可自证没混源；
 *   · `exercise[]` 补上该次运动的消耗/心率/距离。
 *
 * 与脚本版的差别：脚本是遍历 HC 库里所有非空的 `*_record_table` 自动发现，
 * 应用里只能按类型逐个读，所以这里覆盖的是一张固定的类型表（OHSync 会写的全部
 * 类型 + 对方点名要的那些）。要加类型就加到 [extraMetric]。
 */
class SyncExport(private val context: Context, private val hc: HcClient) {

    // ---- 内部累积结构 -------------------------------------------------------

    private class Stat {
        var sum = 0.0
        var n = 0
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        fun add(v: Double) {
            sum += v; n++
            if (v < min) min = v
            if (v > max) max = v
        }
        fun json(round: Int = 2): JSONObject = JSONObject()
            .put("sum", roundTo(sum, round))
            .put("n", n)
            .put("min", roundTo(min, round))
            .put("max", roundTo(max, round))
            .put("avg", roundTo(if (n == 0) 0.0 else sum / n, round))
    }

    private class Metric {
        val cols = LinkedHashMap<String, Stat>()
        /** 来源 → (该表主列的合计, 条数, 各列合计) */
        val sources = LinkedHashMap<String, JSONObject>()

        fun addSource(pkg: String, mainCol: String, values: Map<String, Double>) {
            val e = sources.getOrPut(pkg) {
                JSONObject().put("sum", 0.0).put("n", 0).put("cols", JSONObject())
            }
            e.put("n", e.getInt("n") + 1)
            val cols = e.getJSONObject("cols")
            values.forEach { (c, v) -> cols.put(c, roundTo(cols.optDouble(c, 0.0) + v, 2)) }
            mainCol.let { mc ->
                values[mc]?.let { e.put("sum", roundTo(cols.optDouble(mc, 0.0), 2)) }
            }
        }
    }

    private class Day {
        var steps = 0L
        val stepsSources = LinkedHashMap<String, LongArray>()   // pkg -> [sum, n]
        val hr = Stat()
        var spo2: Stat? = null
        var restingHr: Long? = null
        val sleep = ArrayList<JSONObject>()
        val metrics = LinkedHashMap<String, Metric>()
    }

    private class Exercise(
        val start: Instant, val end: Instant, val startZo: ZoneOffset?, val endZo: ZoneOffset?,
        val typeName: String, val typeId: Int, val pkg: String,
    )

    // ---- 读取时的临时状态 ---------------------------------------------------

    private val days = HashMap<String, Day>()
    private val exercises = ArrayList<Exercise>()
    /** 运动窗口聚合用的序列：(时间毫秒, 值)，读完统一排序。 */
    private val calSeries = ArrayList<Pair<Long, Double>>()
    private val distSeries = ArrayList<Pair<Long, Double>>()
    private val hrSeries = ArrayList<Pair<Long, Double>>()
    private val seenOrigins = LinkedHashSet<String>()
    private val failedTypes = ArrayList<String>()

    /** 上一次 [buildJson] 里读不到的类型（通常是没授那一项的读权限）。 */
    val readFailures: List<String> get() = failedTypes

    /** 设备当前时区。HC 里老记录存的偏移是 0（当年写的时候没带），回落用这个。 */
    private val localOffset: ZoneOffset = OffsetDateTime.now().offset

    private fun offOf(zo: ZoneOffset?): ZoneOffset =
        if (zo == null || zo.totalSeconds == 0) localOffset else zo

    private fun dayOf(t: Instant, zo: ZoneOffset?): String = t.atOffset(offOf(zo)).toLocalDate().toString()

    private fun isoOf(t: Instant, zo: ZoneOffset?): String = STAMP.format(t.atOffset(offOf(zo)))

    private fun day(key: String) = days.getOrPut(key) { Day() }

    // ---- 主流程 -------------------------------------------------------------

    /**
     * @param windowDays 导出窗口（天）
     * @param otherSources true 表示把别的应用写进 HC 的记录也算进当日值
     *        （默认 false：只算本应用写的，避免步数这类被多来源叠加）
     * @return JSON 文本
     */
    suspend fun buildJson(windowDays: Int, otherSources: Boolean): String {
        val c = hc.client ?: throw IllegalStateException("Health Connect 不可用")
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(windowDays.toLong()))
        val filter = TimeRangeFilter.between(start, end)

        for (type in EXPORT_TYPES) {
            val kclass = hcRecordKClass(type) ?: continue
            runCatching {
                var token: String? = null
                var pages = 0
                do {
                    val resp = c.readRecords(
                        ReadRecordsRequest(
                            recordType = kclass,
                            timeRangeFilter = filter,
                            pageSize = PAGE_SIZE,
                            pageToken = token,
                        )
                    )
                    for (r in resp.records) onRecord(r, otherSources)
                    token = resp.pageToken
                    pages++
                } while (token != null && pages < MAX_PAGES)
            }.onFailure {
                // 最常见的原因是没授这一项的「读」权限 —— 记下来，界面能提示
                failedTypes += type.label
                Log.w(TAG, "读取 ${type.label} 失败", it)
            }
        }

        calSeries.sortBy { it.first }
        distSeries.sortBy { it.first }
        hrSeries.sortBy { it.first }
        return render(windowDays, start, end)
    }

    @Suppress("UNCHECKED_CAST")
    private fun hcRecordKClass(type: RecordType): KClass<Record>? =
        (HcClient.hcRecordKClass(type) as KClass<Record>?)

    private fun onRecord(r: Record, otherSources: Boolean) {
        val pkg = r.metadata.dataOrigin.packageName
        seenOrigins += pkg
        val mine = pkg == Ids.APP_ID
        // 来源分层永远覆盖所有来源（这正是它的用途：证明没有混源）；
        // 但当日值默认只采纳本应用写的。
        val countInDay = mine || otherSources

        when (r) {
            is StepsRecord -> {
                val d = dayOf(r.endTime, r.endZoneOffset)
                val s = day(d).stepsSources.getOrPut(pkg) { LongArray(2) }
                s[0] += r.count; s[1] += 1
                if (countInDay) day(d).steps += r.count
            }

            is DistanceRecord -> {
                val d = dayOf(r.endTime, r.endZoneOffset)
                val meters = r.distance.inMeters
                if (countInDay) metric(d, "distance_record_table", mapOf("distance" to meters))
                metricSource(d, "distance_record_table", "distance", pkg, mapOf("distance" to meters))
                distSeries += r.startTime.toEpochMilli() to meters
            }

            is ActiveCaloriesBurnedRecord -> {
                val d = dayOf(r.endTime, r.endZoneOffset)
                // HC 里 energy 的原始列就是 cal（1000 cal = 1 kcal），与原脚本口径一致
                val cal = r.energy.inCalories
                if (countInDay) metric(d, "active_calories_burned_record_table", mapOf("energy" to cal))
                metricSource(d, "active_calories_burned_record_table", "energy", pkg, mapOf("energy" to cal))
                calSeries += r.startTime.toEpochMilli() to cal / 1000.0
            }

            is HeartRateRecord -> for (s in r.samples) {
                val d = dayOf(s.time, r.endZoneOffset)
                val bpm = s.beatsPerMinute.toDouble()
                if (countInDay) day(d).hr.add(bpm)
                hrSeries += s.time.toEpochMilli() to bpm
            }

            is OxygenSaturationRecord -> {
                val d = dayOf(r.time, r.zoneOffset)
                if (countInDay) {
                    val stat = day(d).spo2 ?: Stat().also { day(d).spo2 = it }
                    stat.add(r.percentage.value)
                }
            }

            is RestingHeartRateRecord -> {
                val d = dayOf(r.time, r.zoneOffset)
                if (countInDay) day(d).restingHr = r.beatsPerMinute
            }

            is SleepSessionRecord -> {
                // 睡眠按「醒来时刻」所在日（跨零点的那一晚算到起床那天）
                val d = dayOf(r.endTime, r.endZoneOffset)
                val st = JSONObject()
                    .put("awake_min", 0).put("light_min", 0).put("deep_min", 0)
                    .put("rem_min", 0).put("sleeping_min", 0)
                    .put("out_of_bed_min", 0).put("unknown_min", 0)
                for (stage in r.stages) {
                    val key = STAGE_NAMES[stage.stage] ?: "unknown"
                    val minutes = maxOf(0L, Duration.between(stage.startTime, stage.endTime).toMinutes())
                    st.put(key + "_min", st.getInt(key + "_min") + minutes)
                }
                day(d).sleep += JSONObject()
                    .put("start", isoOf(r.startTime, r.startZoneOffset))
                    .put("end", isoOf(r.endTime, r.endZoneOffset))
                    .put("duration_min", maxOf(0L, Duration.between(r.startTime, r.endTime).toMinutes()))
                    .put("stages", st)
                    .put("source", pkg)
            }

            is ExerciseSessionRecord -> {
                // 运动按「开始时刻」所在日
                exercises += Exercise(
                    r.startTime, r.endTime, r.startZoneOffset, r.endZoneOffset,
                    typeNameOf(r), r.exerciseType, pkg,
                )
            }

            // 其余类型统一进 metrics。这里刻意不依赖 IntervalRecord /
            // InstantaneousRecord —— 这两个接口在 Kotlin 层是 internal，外部模块
            // 引用不了（编译期直接报 "it is internal in file"），只能各类型自己给出时刻。
            else -> extraMetric(r)?.let { e ->
                val d = dayOf(e.time, e.zone)
                if (countInDay) metric(d, e.table, e.values)
                metricSource(d, e.table, e.values.keys.first(), pkg, e.values)
            }
        }
    }

    /**
     * 除手工处理的几类之外，其余类型统一进 metrics。表名/列名沿用 HC 自己的命名。
     *
     * 返回值里带上「时刻 + 时区偏移」，而不是靠 IntervalRecord / InstantaneousRecord
     * 这两个接口统一取 —— 它们在 Kotlin 层是 internal，外部模块引用不了。
     */
    private class Extra(
        val table: String,
        val values: Map<String, Double>,
        val time: Instant,
        val zone: ZoneOffset?,
    )

    private fun extraMetric(r: Record): Extra? = when (r) {
        is TotalCaloriesBurnedRecord -> Extra(
            "total_calories_burned_record_table", mapOf("energy" to r.energy.inCalories),
            r.endTime, r.endZoneOffset,
        )
        is FloorsClimbedRecord -> Extra(
            "floors_climbed_record_table", mapOf("floors" to r.floors),
            r.endTime, r.endZoneOffset,
        )
        is BasalMetabolicRateRecord -> Extra(
            "basal_metabolic_rate_record_table",
            mapOf("basal_metabolic_rate" to r.basalMetabolicRate.inKilocaloriesPerDay),
            r.time, r.zoneOffset,
        )
        is HeartRateVariabilityRmssdRecord -> Extra(
            "heart_rate_variability_rmssd_record_table",
            mapOf("heart_rate_variability_millis" to r.heartRateVariabilityMillis),
            r.time, r.zoneOffset,
        )
        is RespiratoryRateRecord -> Extra(
            "respiratory_rate_record_table", mapOf("rate" to r.rate), r.time, r.zoneOffset,
        )
        is Vo2MaxRecord -> Extra(
            "vo2_max_record_table", mapOf("vo2" to r.vo2MillilitersPerMinuteKilogram),
            r.time, r.zoneOffset,
        )
        is WeightRecord -> Extra(
            "weight_record_table", mapOf("weight" to r.weight.inGrams), r.time, r.zoneOffset,
        )
        is BodyFatRecord -> Extra(
            "body_fat_record_table", mapOf("body_fat_percentage" to r.percentage.value),
            r.time, r.zoneOffset,
        )
        is LeanBodyMassRecord -> Extra(
            "lean_body_mass_record_table", mapOf("lean_body_mass" to r.mass.inGrams),
            r.time, r.zoneOffset,
        )
        is BodyWaterMassRecord -> Extra(
            "body_water_mass_record_table", mapOf("body_water_mass" to r.mass.inGrams),
            r.time, r.zoneOffset,
        )
        is BoneMassRecord -> Extra(
            "bone_mass_record_table", mapOf("bone_mass" to r.mass.inGrams),
            r.time, r.zoneOffset,
        )
        is HeightRecord -> Extra(
            "height_record_table", mapOf("height" to r.height.inMeters), r.time, r.zoneOffset,
        )
        is BloodPressureRecord -> Extra(
            "blood_pressure_record_table",
            mapOf(
                "systolic" to r.systolic.inMillimetersOfMercury,
                "diastolic" to r.diastolic.inMillimetersOfMercury,
            ),
            r.time, r.zoneOffset,
        )
        is BloodGlucoseRecord -> Extra(
            "blood_glucose_record_table", mapOf("level" to r.level.inMilligramsPerDeciliter),
            r.time, r.zoneOffset,
        )
        is BodyTemperatureRecord -> Extra(
            "body_temperature_record_table", mapOf("temperature" to r.temperature.inCelsius),
            r.time, r.zoneOffset,
        )
        // 皮温记的是「相对基线的偏差」，只有 baseline 是同量纲的绝对值
        is SkinTemperatureRecord -> Extra(
            "skin_temperature_record_table",
            mapOf("temperature" to (r.baseline?.inCelsius ?: 0.0)),
            r.endTime, r.endZoneOffset,
        )
        is HydrationRecord -> Extra(
            "hydration_record_table", mapOf("volume" to r.volume.inLiters),
            r.endTime, r.endZoneOffset,
        )
        is ElevationGainedRecord -> Extra(
            "elevation_gained_record_table", mapOf("elevation" to r.elevation.inMeters),
            r.endTime, r.endZoneOffset,
        )
        else -> null
    }

    private fun metric(dayKey: String, table: String, values: Map<String, Double>) {
        val m = day(dayKey).metrics.getOrPut(table) { Metric() }
        values.forEach { (col, v) -> m.cols.getOrPut(col) { Stat() }.add(v) }
    }

    private fun metricSource(
        dayKey: String, table: String, mainCol: String, pkg: String, values: Map<String, Double>,
    ) {
        day(dayKey).metrics.getOrPut(table) { Metric() }.addSource(pkg, mainCol, values)
    }

    private fun typeNameOf(r: ExerciseSessionRecord): String =
        r.title?.takeIf { it.isNotBlank() } ?: ExerciseTypeMap.nameOfHc(r.exerciseType)

    // ---- 渲染 ---------------------------------------------------------------

    private fun render(windowDays: Int, start: Instant, end: Instant): String {
        // 先把运动按日分好，避免在外层按天循环里把 658 条重复扫 366 遍
        val exByDay = HashMap<String, ArrayList<Exercise>>()
        for (e in exercises) exByDay.getOrPut(dayOf(e.start, e.startZo)) { ArrayList() } += e

        val daysJson = JSONArray()
        for (key in days.keys.sortedDescending()) {
            val d = days.getValue(key)
            val o = JSONObject()
            o.put("date", key)
            o.put("steps", d.steps)

            val ss = JSONObject()
            d.stepsSources.forEach { (pkg, v) ->
                ss.put(pkg, JSONObject().put("sum", v[0]).put("n", v[1]))
            }
            o.put("steps_sources", ss)

            o.put("sleep", JSONArray(d.sleep))
            o.put(
                "heart_rate",
                if (d.hr.n == 0) JSONObject()
                else JSONObject()
                    .put("min", roundTo(d.hr.min, 1))
                    .put("max", roundTo(d.hr.max, 1))
                    .put("avg", roundTo(d.hr.sum / d.hr.n, 1)),
            )
            o.put(
                "spo2",
                d.spo2?.let {
                    JSONObject()
                        .put("avg", roundTo(it.sum / it.n, 1))
                        .put("min", roundTo(it.min, 1))
                        .put("max", roundTo(it.max, 1))
                } ?: JSONObject.NULL,
            )
            o.put("resting_hr", d.restingHr ?: JSONObject.NULL)

            val ex = JSONArray()
            for (e in exByDay[key].orEmpty()) {
                val lo = e.start.toEpochMilli()
                val hi = maxOf(e.end.toEpochMilli(), lo + 1)
                val (kcal, kcalN) = windowSum(calSeries, lo, hi)
                val (distM, distN) = windowSum(distSeries, lo, hi)
                val (hrSum, hrN) = windowSum(hrSeries, lo, hi)
                ex.put(
                    JSONObject()
                        .put("start", isoOf(e.start, e.startZo))
                        .put("end", isoOf(e.end, e.endZo))
                        .put("type", e.typeName)
                        .put("type_id", e.typeId)
                        .put("source", e.pkg)
                        .put("calories_kcal", if (kcalN > 0) roundTo(kcal, 1) else JSONObject.NULL)
                        .put("avg_hr", if (hrN > 0) roundTo(hrSum / hrN, 1) else JSONObject.NULL)
                        .put("max_hr", windowMax(hrSeries, lo, hi)?.let { roundTo(it, 0) } ?: JSONObject.NULL)
                        .put("distance_km", if (distN > 0) roundTo(distM / 1000.0, 3) else JSONObject.NULL),
                )
            }
            o.put("exercise", ex)

            val metrics = JSONObject()
            d.metrics.forEach { (table, m) ->
                val t = JSONObject()
                m.cols.forEach { (col, stat) -> t.put(col, stat.json()) }
                if (m.sources.isNotEmpty()) {
                    val s = JSONObject()
                    m.sources.forEach { (pkg, v) -> s.put(pkg, v) }
                    t.put("sources", s)
                }
                metrics.put(table, t)
            }
            o.put("metrics", metrics)
            daysJson.put(o)
        }

        val sources = JSONObject()
        seenOrigins.forEach { sources.put(it, appLabel(it)) }

        return JSONObject()
            .put("generated_at", STAMP.format(OffsetDateTime.now()))
            .put("window_days", windowDays)
            .put("local_offset_sec", localOffset.totalSeconds)
            .put("source_filter", Ids.APP_ID)
            .put("range_start", isoOf(start, null))
            .put("range_end", isoOf(end, null))
            .put("read_failed_types", JSONArray(failedTypes))
            .put("days", daysJson)
            .put("sources", sources)
            .toString(1)
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ---- 小工具 -------------------------------------------------------------

    /** [lo, hi) 区间内的 (合计, 样本数)。序列必须已按时间升序。 */
    private fun windowSum(s: List<Pair<Long, Double>>, lo: Long, hi: Long): Pair<Double, Int> {
        val i = lowerBound(s, lo)
        val j = lowerBound(s, hi)
        var sum = 0.0
        for (k in i until j) sum += s[k].second
        return sum to (j - i)
    }

    private fun windowMax(s: List<Pair<Long, Double>>, lo: Long, hi: Long): Double? {
        val i = lowerBound(s, lo)
        val j = lowerBound(s, hi)
        var m: Double? = null
        for (k in i until j) m = if (m == null || s[k].second > m) s[k].second else m
        return m
    }

    private fun lowerBound(s: List<Pair<Long, Double>>, t: Long): Int {
        var lo = 0
        var hi = s.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (s[mid].first < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val TAG = "OHSyncExport"

        /**
         * 参与导出的类型。
         *
         * 这既是读取循环的范围，也是要申请的「读」权限清单 —— 两个地方必须一致，
         * 所以清单（AndroidManifest 的 READ_*）也要跟着这里改。
         * 比原脚本的「自动发现所有非空表」窄：应用里只能按类型读，没法枚举 HC 的表。
         *
         * 没纳入的是两类：**序列型**（StepsCadence / Speed / Power / PedalingCadence，
         * 它们的值在 samples 列表里，得另写聚合）和与减重分析无关的
         * （月经 / 宫颈黏液 / 性活动 / 营养摄入 / 正念）。OPPO 侧这些本来也没有数据 ——
         * 实测 HC 里非空的只有 OHSync 写的那几类，所以不漏。
         */
        val EXPORT_TYPES: List<RecordType> = listOf(
            RecordType.STEPS, RecordType.DISTANCE,
            RecordType.ACTIVE_CALORIES, RecordType.TOTAL_CALORIES,
            RecordType.HEART_RATE, RecordType.RESTING_HEART_RATE, RecordType.HRV,
            RecordType.BLOOD_OXYGEN,
            RecordType.SLEEP_SESSION, RecordType.EXERCISE_SESSION,
            RecordType.WEIGHT, RecordType.BODY_FAT, RecordType.LEAN_BODY_MASS,
            RecordType.BODY_WATER, RecordType.BONE_MASS, RecordType.HEIGHT,
            RecordType.BLOOD_PRESSURE, RecordType.BLOOD_GLUCOSE, RecordType.BODY_TEMPERATURE,
            RecordType.SKIN_TEMPERATURE, RecordType.RESPIRATORY_RATE,
            RecordType.BASAL_METABOLIC_RATE, RecordType.VO2_MAX,
            RecordType.FLOORS_CLIMBED, RecordType.ELEVATION_GAINED, RecordType.HYDRATION,
        )

        /** 本地 ISO8601，带 +08:00 这种偏移。 */
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        private const val PAGE_SIZE = 1000

        /** 单类型最多翻这么多页。防呆：真出现异常多的数据也不至于把进程拖死。 */
        private const val MAX_PAGES = 500

        /** HC 的 stage 取值 → 原脚本用的键名。7（AWAKE_IN_BED）原脚本也归到 unknown。 */
        private val STAGE_NAMES = mapOf(
            SleepSessionRecord.STAGE_TYPE_UNKNOWN to "unknown",
            SleepSessionRecord.STAGE_TYPE_AWAKE to "awake",
            SleepSessionRecord.STAGE_TYPE_SLEEPING to "sleeping",
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to "out_of_bed",
            SleepSessionRecord.STAGE_TYPE_LIGHT to "light",
            SleepSessionRecord.STAGE_TYPE_DEEP to "deep",
            SleepSessionRecord.STAGE_TYPE_REM to "rem",
        )

    }
}

/**
 * 四舍五入到指定小数位。
 *
 * 放成文件级函数而不是 companion 成员：内部嵌套类（Stat/Day/Metric）也要用，
 * 跨嵌套层访问 companion 成员容易踩到解析规则，不值得冒这个险。
 */
private fun roundTo(v: Double, digits: Int): Double {
    if (v.isNaN() || v.isInfinite()) return 0.0
    var factor = 1.0
    repeat(digits) { factor *= 10.0 }
    return Math.round(v * factor) / factor
}
