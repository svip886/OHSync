package io.github.bqj6666.ohsync.hook

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.github.bqj6666.ohsync.core.Ids
import io.github.bqj6666.ohsync.core.PayloadCodec
import io.github.bqj6666.ohsync.core.RecordType
import io.github.bqj6666.ohsync.core.SyncRecord

/**
 * 把 OPPO 的表读出来 -> 规范化成 SyncRecord -> 推给主进程。
 *
 * 「读什么」交给 QueryPlan 决定，这里只负责取数、编码、递送。
 *
 * 主进程可能不在（本机不会因为数据接口被访问就去启动一个没在跑的应用），
 * 所以每次推送前先发唤醒广播、等数据接口可用再发。
 */
object Pusher {

    private const val TAG = "OHSyncPush"
    private const val OHSYNC_PACKAGE = Ids.APP_ID
    private const val PROVIDER_SUFFIX = Ids.PROVIDER_SUFFIX

    /** 唤醒广播，与主进程侧 WakeReceiver 约定一致。 */
    private const val ACTION_WAKE = Ids.ACTION_WAKE

    /** 等主进程就绪的最长时间。 */
    private const val WAIT_READY_MS = 12_000L

    fun push(reader: TableReader, incremental: Boolean = false) {
        val specs = QueryPlan.forTables(reader, incremental)
        val sleep = SleepBuilder.build(reader)
        if (specs.isEmpty() && sleep.isEmpty()) {
            Log.w(TAG, "没有找到可同步的表（结构可能已变）")
            return
        }
        if (!ensureHostReady()) {
            Log.e(TAG, "主进程未就绪，本次推送放弃（下轮会重试）")
            return
        }

        var total = 0
        if (sleep.isNotEmpty() && send(RecordType.SLEEP_SESSION.id, sleep)) {
            total += sleep.size
        }
        for (spec in specs) {
            val rows = reader.query(spec.sql)
            val records = rows.mapNotNull(spec.toRecord)
            if (records.isEmpty()) continue
            if (send(spec.type.id, records)) {
                total += records.size
                Log.i(TAG, "${spec.type.label}：${records.size} 条")
            }
        }

        // 历史回填。单次推送有 3000 条上限，而查询固定按时间倒序取「最近 N 条」，
        // 于是每轮拿到的都是同一批数据，更早的记录永远进不来。这里每轮再补一段
        // 切片（7 天），并把切片终点回报给主进程往前挪，若干轮后即铺满整个窗口。
        val sliceEnd = RemoteConfig.fetch()?.backfillBefore ?: 0L
        if (sliceEnd > 0L) {
            val sliceSpecs = QueryPlan.forBackfillSlice(reader, sliceEnd)
            var sliceTotal = 0
            for (spec in sliceSpecs) {
                val rows = reader.query(spec.sql)
                val records = rows.mapNotNull(spec.toRecord)
                if (records.isEmpty()) continue
                if (send(spec.type.id, records)) sliceTotal += records.size
            }
            Log.i(TAG, "回填切片 <$sliceEnd 共 $sliceTotal 条")
            total += sliceTotal
            // 无论这一段有没有数据都要回报，否则终点不会往前挪
            RemoteConfig.reportBackfillSliceDone(sliceEnd)
        }

        Log.i(TAG, "推送完成，共 $total 条${if (incremental) "（增量）" else "（全窗口）"}")
        RemoteConfig.reportSyncDone(System.currentTimeMillis())
    }

    /**
     * 确保主进程的数据接口可用。
     *
     * 先发唤醒广播（事件驱动，正常情况主进程收到后立刻启动服务），再轮询试探接口。
     * 唤醒后系统还需要一点时间把进程和服务拉起来，所以留了等待窗口。
     */
    private fun ensureHostReady(): Boolean {
        if (AppContextHolder.context == null) AppContextHolder.init()
        val ctx = AppContextHolder.context ?: return false
        if (RemoteConfig.fetch(force = true) != null) return true

        val token = TokenHolder.token
        if (token.isEmpty()) return false

        Log.i(TAG, "主进程不在，发唤醒广播")
        runCatching {
            ctx.sendBroadcast(
                Intent(ACTION_WAKE).setPackage(OHSYNC_PACKAGE).putExtra("token", token)
            )
        }.onFailure { Log.w(TAG, "发唤醒广播失败", it) }

        val deadline = System.currentTimeMillis() + WAIT_READY_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(700L)
            } catch (_: InterruptedException) {
                return false
            }
            if (RemoteConfig.fetch(force = true) != null) {
                Log.i(TAG, "主进程已就绪")
                return true
            }
        }
        return false
    }

    private fun send(type: String, records: List<SyncRecord>): Boolean {
        if (RemoteConfig.fetch() == null || TokenHolder.token.isEmpty()) return false
        val ctx = AppContextHolder.context ?: return false
        return try {
            val values = ContentValues().apply {
                put("token", TokenHolder.token)
                put("type", type)
                put("payload", PayloadCodec.encode(records))
            }
            ctx.contentResolver.insert(
                Uri.parse("content://$OHSYNC_PACKAGE$PROVIDER_SUFFIX/records"),
                values,
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "推送 $type 失败", t)
            false
        }
    }
}
