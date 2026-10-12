package io.github.bqj6666.ohsync.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 导出数据的本地副本：按天存好的那一份「成品」。
 *
 * 为什么要它：给 WebDAV 的必须是**全量**，但如果每次都把整个窗口从 Health Connect
 * 重读一遍，代价随窗口线性增长（实测跟随「全部」同步窗口时会读 10 年、几分钟，
 * 还卡住过一次）。这里把已经整理好的按天结果落盘，之后每次只重读最近几天、
 * 与缓存合并 —— 传出去的仍是全量，读的量却只有几天。
 *
 * 为什么以 Health Connect 为准、而不是在应用里自己累加：
 *   增量推送会反复送来同一批记录（首轮全量、回填切片、增量有 2 分钟重叠窗口），
 *   自己累加就必须另建一套去重表，还可能和 HC 越走越远。而 HC 是按
 *   clientRecordId 做 upsert 的，**它本身就是权威且幂等的** —— 重读它得到的天值
 *   永远不会重复计数。所以缓存只承担「省掉重复读」，不承担「记账」。
 *
 * 失效条件：窗口天数、时区偏移、是否纳入其他来源，任一变化就整份丢弃重建
 * （这几种变化都会让旧的天值不再正确）。
 */
object ExportCache {

    private const val TAG = "OHSyncExport"
    private const val FILE_NAME = "export_days.json"
    private const val VERSION = 1

    /** 缓存里的元信息 + 天数据。 */
    class Snapshot(
        val days: Map<String, JSONObject>,
        val windowDays: Int,
        val offsetSec: Int,
        val otherSources: Boolean,
    )

    fun load(context: Context, windowDays: Int, offsetSec: Int, otherSources: Boolean): Snapshot {
        val empty = Snapshot(emptyMap(), windowDays, offsetSec, otherSources)
        val f = file(context)
        if (!f.exists()) return empty
        return runCatching {
            val root = JSONObject(f.readText())
            val ok = root.optInt("v") == VERSION &&
                root.optInt("window_days") == windowDays &&
                root.optInt("offset_sec") == offsetSec &&
                root.optBoolean("other_sources") == otherSources
            if (!ok) {
                Log.i(TAG, "导出缓存的参数变了（窗口/时区/来源），整份重建")
                return@runCatching empty
            }
            val daysObj = root.optJSONObject("days") ?: return@runCatching empty
            val map = HashMap<String, JSONObject>(daysObj.length())
            daysObj.keys().forEach { k -> daysObj.optJSONObject(k)?.let { map[k] = it } }
            Log.i(TAG, "载入导出缓存：${map.size} 天")
            Snapshot(map, windowDays, offsetSec, otherSources)
        }.getOrElse {
            Log.w(TAG, "导出缓存读取失败，按空处理", it)
            empty
        }
    }

    fun save(context: Context, days: Map<String, JSONObject>, windowDays: Int, offsetSec: Int, otherSources: Boolean) {
        runCatching {
            val daysObj = JSONObject()
            days.forEach { (k, v) -> daysObj.put(k, v) }
            val root = JSONObject()
                .put("v", VERSION)
                .put("window_days", windowDays)
                .put("offset_sec", offsetSec)
                .put("other_sources", otherSources)
                .put("days_count", days.size)
                .put("days", daysObj)
            // 先写临时文件再改名：中途被杀不会留下一份半截的缓存
            val tmp = File(file(context).parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            // 大小要在 rename 之前取：改名之后这个 File 对象就指向不存在的路径了，
            // 取 length() 会得到 0（日志里写 0 KB 会让人以为没存进去）
            val kb = tmp.length() / 1024
            tmp.renameTo(file(context))
            Log.i(TAG, "导出缓存已存：${days.size} 天，$kb KB")
        }.onFailure { Log.w(TAG, "导出缓存写入失败", it) }
    }

    /** 参数变了就整份丢弃。 */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
