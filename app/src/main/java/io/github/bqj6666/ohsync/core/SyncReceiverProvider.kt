package io.github.bqj6666.ohsync.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * Hook 进程 -> 主进程的数据入口。
 *
 * export + token 校验，不用自定义权限（见 spec 4.2）。
 * 写入路径：call("ping") 做连通性与 token 自检；insert() 收记录。
 */
class SyncReceiverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun ctx(): Context = requireNotNull(context)

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val token = values?.getAsString(COL_TOKEN)
        val expected = TokenStore.getOrCreate(ctx())
        if (token == null || !TokenStore.constantTimeEquals(token, expected)) {
            Log.w(TAG, "rejected: bad token")
            return null
        }
        val type = values?.getAsString(COL_TYPE) ?: return null
        val payload = values?.getAsString(COL_PAYLOAD) ?: return null
        return try {
            val records = PayloadCodec.decode(type, payload)
            val engine = SyncEngine.get(ctx())
            engine.noteActivity()
            engine.submit(SyncBatch(records, isBackfill = false))
            Log.i(TAG, "accepted ${records.size} records of $type")
            Uri.withAppendedPath(uri, "ok")
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed", t)
            null
        }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val token = selectionArgs?.firstOrNull()
        val expected = TokenStore.getOrCreate(ctx())
        val ok = token != null && TokenStore.constantTimeEquals(token, expected)
        val engine = SyncEngine.get(ctx())
        val matrix = android.database.MatrixCursor(
            arrayOf(COL_HOOK_CONNECTED, COL_PENDING_BACKFILL, COL_SYNCED_COUNT)
        )
        matrix.addRow(arrayOf(if (ok) 1 else 0, if (engine.pendingBackfill()) 1 else 0, engine.syncedCount()))
        return matrix
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/vnd.io.github.bqj6666.ohsync"

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0

    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        val expected = TokenStore.getOrCreate(ctx())
        val engine = SyncEngine.get(ctx())
        return android.os.Bundle().apply {
            when (method) {
                // 读取端在线确认：口令对就记下，界面据此显示「读取端正常」
                "ping" -> {
                    val ok = arg != null && TokenStore.constantTimeEquals(arg, expected)
                    putBoolean("ok", ok)
                    if (ok) engine.noteHookSeen()
                }
                // Hook 侧取配置。
                //
                // 为什么让读方来取，而不是让用户手抄：Hook 代码跑在 OPPO 健康进程里，
                // 那里没有我们自己的 UI，用户没有任何途径把口令粘进去。
                // 同步间隔与时间窗口同理，都由主进程持有并在每次轮询时下发。
                "config" -> {
                    val c = requireNotNull(context)
                    putString("token", expected)
                    putInt("intervalMinutes", Settings.intervalMinutes(c))
                    putInt("windowDays", Settings.windowDays(c))
                    putBoolean("backfillRequested", engine.pendingBackfill())
                    putLong("lastSyncAt", Settings.lastSyncAt(c))
                    // 回填切片终点。首次下发时固定成「现在」并落盘，之后每回填完一段
                    // 由 backfillSliceDone 往前挪 7 天；铺满窗口后置 -1，读取端据此停止。
                    var before = Settings.backfillBefore(c)
                    if (before == 0L) {
                        before = System.currentTimeMillis()
                        Settings.setBackfillBefore(c, before)
                    }
                    putLong("backfillBefore", if (before > 0L) before else 0L)
                }
                // Hook 侧完成一次手动同步后回报，避免重复触发
                "backfillDone" -> engine.clearBackfillRequest()
                // 读取端报告「这一段回填完了」→ 存下它算好的新游标。
                // 游标不由这里推算：只有读取端知道本段实际取到了哪条最早的数据，
                // 主进程按固定跨度去减的话，稠密类型撞上单次条数上限时会把中间
                // 没取到的那几天永久跳过（早期实现的问题）。
                "backfillSliceDone" -> {
                    val c = requireNotNull(context)
                    extras?.getLong("nextBefore")?.let { next ->
                        val windowStart = System.currentTimeMillis() -
                            Settings.windowDays(c) * 86_400_000L
                        Settings.setBackfillBefore(c, if (next <= windowStart) -1L else next)
                    }
                }
                // 读取端太久没拿到数据库（OPPO 健康起来了却没开库）→ 重启它重新走一遍启动
                "restartHealth" -> HealthRestarter.restartIfDue(requireNotNull(context))
                // 读取端收到「立即同步」后的回执：它活着，并告知数据库此刻是否就绪。
                // 有这条回执，主进程看门狗就知道该不该出手重启 OPPO 健康。
                "readerState" -> {
                    engine.noteReaderState(extras?.getBoolean("dbReady", false) == true)
                }
                // Hook 侧一次推送真正结束后回报：记下时间点（下次增量只取这之后的数据），
                // 同时清掉「已请求回填」标志 —— UI 以此判断手动同步已完成。
                "syncDone" -> {
                    extras?.getLong("at")?.let {
                        Settings.setLastSyncAt(requireNotNull(context), it)
                    }
                    engine.clearBackfillRequest()
                    // 一次推送真正结束 —— 这是触发 WebDAV 导出的时机（按最小间隔节流）。
                    // 放在这里而不是每批数据之后：一次同步有十几批，只有全部写完
                    // 导出的 JSON 才是完整的。
                    ExportRunner.maybeAuto(requireNotNull(context))
                }
                "requestBackfill" -> engine.requestBackfill()
                else -> Unit
            }
        }
    }

    companion object {
        private const val TAG = "OHSyncProvider"
        const val COL_TOKEN = "token"
        const val COL_TYPE = "type"
        const val COL_PAYLOAD = "payload"
        const val COL_HOOK_CONNECTED = "hook_connected"
        const val COL_PENDING_BACKFILL = "pending_backfill"
        const val COL_SYNCED_COUNT = "synced_count"

        fun authority(context: Context): String = "${context.packageName}.sync"
        fun uri(context: Context): Uri = Uri.parse("content://${authority(context)}/records")
    }
}
