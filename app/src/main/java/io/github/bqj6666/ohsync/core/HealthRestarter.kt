package io.github.bqj6666.ohsync.core

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * 重启 OPPO 健康进程。
 *
 * 为什么需要它：读取端的广播接收器虽然已经提前注册（见 HookCoordinator），
 * 但 OPPO 健康有时启动完却迟迟不初始化数据库，读取端拿不到库实例，推送就做不了。
 * 这时候把它重启一遍，逼它重新走一次完整启动流程即可 —— 等价于用户手动打开一次。
 *
 * 两条路，按代价从低到高：
 *   1. 有 root：`su -c "am force-stop …; am start …"` 真重启，前台后台都能做。
 *   2. 没 root：只能把 OPPO 健康拉到前台（[bringToFront]）。效果一样，但要求
 *      本应用此刻是可见的，否则后台启动别的界面会被系统拦住。
 *
 * ⚠️ 强制停止别的应用只有 root/shell 能做，所以第 1 条路需要把本应用加进
 * KernelSU/Magisk 的授权白名单。**没授权时必须让界面明确提示用户**，
 * 不能像早期版本那样静默失败（实测就是「点了立即同步一直没反应」）。
 */
object HealthRestarter {

    private const val TAG = "OHSyncRestart"

    /** 两次重启之间的默认最小间隔，防止来回重启把手机拖垮。 */
    const val DEFAULT_MIN_INTERVAL_MS = 10 * 60_000L

    /** 看门狗自救时的最小间隔。比默认短，但仍然挡住连点造成的重复重启。 */
    private const val RESCUE_MIN_INTERVAL_MS = 2 * 60_000L

    /** su 的执行上限。KernelSU 的授权框有倒计时，给足时间但不能无限等。 */
    private const val SU_TIMEOUT_MS = 25_000L

    private const val TARGET = Ids.TARGET_PACKAGE
    private const val MAIN_ACTIVITY = "com.heytap.health/.main.MainActivity"

    /**
     * 到点就重启（按间隔节流）。
     *
     * @param minIntervalMs 本轮允许的最小间隔。读取端自己上报的场景用默认的 10 分钟；
     *        主进程看门狗在用户已明确要求同步时，可以传更短的值以便尽快自救。
     * @return true 表示这次确实下发并成功执行了重启
     */
    fun restartIfDue(context: Context, minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS): Boolean {
        val now = System.currentTimeMillis()
        val last = Settings.lastHealthRestart(context)
        if (now - last < minIntervalMs) {
            Log.i(TAG, "距上次重启不足 ${minIntervalMs / 1000} 秒，跳过")
            return false
        }
        if (!rootGranted(context)) {
            Log.w(TAG, "没有 root 授权，无法重启 $TARGET")
            return false
        }
        val ok = restartWithRoot()
        // 只在真的成功后才占用节流窗口。早期版本先写时间戳再执行，一旦失败
        // （没有 root 时必然失败）后续 10 分钟就再也不会重试，问题还被掩盖了。
        if (ok) {
            Settings.setLastHealthRestart(context, now)
            Log.i(TAG, "已重启 $TARGET")
        } else {
            Log.w(TAG, "重启 $TARGET 失败")
        }
        return ok
    }

    /**
     * 是否有 root。
     *
     * 首次（或 [force]）会真的执行一次 `su -c id` —— 这正是 KernelSU/Magisk
     * 弹授权框的时机。结果落盘缓存：判为「没有」之后不再自动检测，免得每次
     * 打开应用都弹一次框；界面上留了手动重试的按钮。
     */
    fun rootGranted(context: Context, force: Boolean = false): Boolean {
        if (!force) {
            when (Settings.rootState(context)) {
                Settings.ROOT_YES -> return true
                Settings.ROOT_NO -> return false
            }
        }
        val ok = probeRoot()
        Settings.setRootState(context, if (ok) Settings.ROOT_YES else Settings.ROOT_NO)
        Log.i(TAG, if (ok) "root 可用" else "root 不可用（KernelSU 未授权？）")
        return ok
    }

    /** 界面上的「申请 root 授权」：强制探测一次，让系统把授权框弹出来。 */
    fun requestGrant(context: Context): Boolean = rootGranted(context, force = true)

    /**
     * 非 root 兜底：把 OPPO 健康拉到前台。
     *
     * 和「人工打开一次 OPPO 健康」等价 —— OPPO 健康一旦可见就会去开数据库，
     * 读取端随即拿到实例并推送。要求本应用此刻可见（用户刚点过按钮就满足），
     * 否则会被后台启动限制拦下，这里如实记录失败。
     */
    fun bringToFront(context: Context): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(TARGET)
        if (intent == null) {
            Log.w(TAG, "$TARGET 没有可启动的入口")
            false
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "已把 $TARGET 拉到前台（非 root 兜底）")
            true
        }
    }.onFailure { Log.w(TAG, "拉起 $TARGET 失败（后台启动被拦？）", it) }.getOrDefault(false)

    /**
     * 自救入口：先按 root 重启，不成就退化成拉前台。
     *
     * 自带一个短节流：用户连点「立即同步」会起多个看门狗，不能每次都真重启一遍。
     */
    fun rescue(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - Settings.lastHealthRestart(context) < RESCUE_MIN_INTERVAL_MS) {
            Log.i(TAG, "刚重启过，跳过")
            return true
        }
        if (rootGranted(context)) {
            val ok = restartWithRoot()
            if (ok) {
                Settings.setLastHealthRestart(context, now)
                Log.i(TAG, "自救：已重启 $TARGET")
                return true
            }
        }
        return bringToFront(context)
    }

    private fun restartWithRoot(): Boolean = runCatching {
        val cmd = "am force-stop $TARGET; sleep 1; am start -n $MAIN_ACTIVITY"
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val done = p.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val code = if (done) p.exitValue() else -1
        if (!done) {
            Log.w(TAG, "su 执行超时（授权框没人点？）")
            p.destroy()
        }
        done && code == 0
    }.onFailure { Log.w(TAG, "执行 su 失败", it) }.getOrDefault(false)

    /** `su -c id` 输出里带 uid=0 才算真的拿到了 root。 */
    private fun probeRoot(): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        // 必须先读干输出再 waitFor：su 的输出很短，但读干能避免任何管道阻塞
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val done = p.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!done) {
            p.destroy()
            return@runCatching false
        }
        done && p.exitValue() == 0 && out.contains("uid=0")
    }.getOrElse { false }
}
