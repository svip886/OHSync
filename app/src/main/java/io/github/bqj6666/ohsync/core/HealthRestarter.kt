package io.github.bqj6666.ohsync.core

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * 重启 OPPO 健康进程。
 *
 * 为什么需要它：Hook 侧的广播接收器要等拿到数据库实例之后才注册，而 OPPO 健康有时
 * 启动完却迟迟不初始化数据库 —— 这时候用户点「立即同步」也好、等自动同步也好，
 * 全都毫无反应，只能手动把 OPPO 健康打开一次。这里把这一步自动化：读取端报告
 * 「太久没拿到数据库」时，由主进程把它重启一遍，逼它重新走一次完整的启动流程。
 *
 * 强杀别的应用只有 root/shell 能做，所以走 `su -c`。首次执行时 KernelSU/Magisk
 * 会弹一次授权，之后长期有效。命令拆成两段（先 force-stop，隔 1 秒再 start），
 * 避免刚杀完就被系统当成异常退出而进入冷却。
 */
object HealthRestarter {

    private const val TAG = "OHSyncRestart"

    /** 两次重启之间的最小间隔，防止来回重启把手机拖垮。 */
    private const val MIN_INTERVAL_MS = 10 * 60_000L

    private const val TARGET = "com.heytap.health"
    private const val MAIN_ACTIVITY = "com.heytap.health/.main.MainActivity"

    /**
     * 到点就重启（按间隔节流）。
     * @return true 表示这次确实下发了重启命令
     */
    fun restartIfDue(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val last = Settings.lastHealthRestart(context)
        if (now - last < MIN_INTERVAL_MS) {
            Log.i(TAG, "距上次重启不足 ${MIN_INTERVAL_MS / 60_000} 分钟，跳过")
            return false
        }
        Settings.setLastHealthRestart(context, now)
        return restart()
    }

    private fun restart(): Boolean {
        val cmd = "am force-stop $TARGET; sleep 1; am start -n $MAIN_ACTIVITY"
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val done = p.waitFor(20, TimeUnit.SECONDS)
            val code = if (done) p.exitValue() else -1
            Log.i(TAG, "已重启 $TARGET（exit=$code）")
            done && code == 0
        }.onFailure { Log.w(TAG, "重启失败（没有 root 授权？）", it) }.getOrDefault(false)
    }
}
