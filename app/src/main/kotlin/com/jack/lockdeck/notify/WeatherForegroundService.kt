package com.jack.lockdeck.notify

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.jack.lockdeck.data.Store
import com.jack.lockdeck.data.WeatherRepo
import com.jack.lockdeck.tile.WeatherTileInUse

/**
 * 常驻前台服务：既挂通知，也**自己负责定时刷新**。
 *
 * ## 为什么要自己计时，而不是交给 WorkManager
 *
 * 原来刷新调度全交给 WorkManager。在 HyperOS 3 上实测**根本不执行**：
 * 任务确实在队列里（`state=0`），但 `run_attempt_count` 永远是 0。
 * 用 `cmd jobscheduler run -f` 强制跑，Job 显示 `Running job [FORCED]`，
 * Worker 却依然没跑起来。表现就是：下拉看到的温度一直停在开启那一刻，
 * 磁贴和通知都不会自己更新。
 *
 * 而前台服务在这台机器上是**站得住的**（`isForeground=true`，通知常驻，
 * 属于系统认可的 specialUse 类型，不会被当成普通后台任务挂起）。
 * 既然它本来就一直在跑，让它顺手把刷新做了，比把时间交给一个
 * 会被压掉的调度器靠谱得多。
 *
 * WorkManager 保留为**兜底**：万一服务被系统杀掉又没能自愈，
 * 那个周期性任务多少还能补救一次。
 *
 * ## 为什么不直接用 AlarmManager
 *
 * 精确闹钟要 `SCHEDULE_EXACT_ALARM`，Android 12+ 默认不给，
 * 而且系统会在省电模式下把它降级成不精确闹钟。为了「每 10 分钟看一次天气」
 * 去申请闹钟权限不值当。前台服务里的 Handler 循环不占额外权限，
 * 代价只是进程必须活着——而常驻通知本来就要求它活着。
 */
class WeatherForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    /**
     * 正在刷新中。定时刷和「立即刷新」可能撞在一起
     * （用户刚好在定时器触发的那一刻点刷新），两次请求同时跑没有意义，
     * 还会让后写的那份覆盖先写的。用这个原子标记挡掉并发的第二次。
     */
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 一次定时刷新的动作。 */
    private val tick = object : Runnable {
        override fun run() {
            if (!Store.isEnabled(this@WeatherForegroundService)) {
                handler.removeCallbacks(this)
                return
            }
            triggerRefresh()
            // 无论这次成不成功，都排下一次——失败了更要多试几次
            scheduleNext()
        }
    }

    /** 起一个后台线程做刷新；已有刷新在跑就直接跳过。 */
    private fun triggerRefresh() {
        if (!refreshing.compareAndSet(false, true)) return
        Thread {
            try {
                refreshOnce()
            } finally {
                refreshing.set(false)
            }
        }.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 开关关掉时别再把自己挂回来（START_STICKY 之后系统可能重拉一次）
        if (!Store.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = WeatherNotifier.buildOnChannel(this, CHANNEL_ID, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }

        // 用户点名要立刻刷一次（点了「立即刷新」/ 磁贴刷新）
        if (intent?.action == ACTION_REFRESH_NOW) {
            handler.removeCallbacks(tick)
            triggerRefresh()
            scheduleNext()
            return START_STICKY
        }

        // 用户改了刷新间隔：重排下一轮，别等旧的那一轮走完才生效
        if (intent?.action == ACTION_RESCHEDULE) {
            if (started) scheduleNext() else startLoop()
            return START_STICKY
        }

        startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        started = false
        super.onDestroy()
    }

    /**
     * 启动定时循环。
     *
     * 幂等：`onStartCommand` 每次 refresh() 都会被调用一次，
     * 这里用 [started] 防止越叠越多（每调一次就多一条循环，很快会变成
     * 每分钟刷十次）。
     *
     * 起来时**不**立刻刷新：服务经常因为「重写通知」被 start 一下
     * （每次数据变化都会走 refresh → start），如果这里也刷一次，
     * 会变成「刷新→写通知→再刷新」的循环请求。只在缓存太旧时补一次。
     */
    private fun startLoop() {
        if (started) return
        started = true

        val ageMs = System.currentTimeMillis() - (Store.loadWeather(this)?.updatedAt ?: 0L)
        val stale = ageMs > STALE_MS
        if (stale) {
            handler.post(tick)
        } else {
            // 数据还新：只把下一轮排上，不立刻发请求
            scheduleNext()
        }
    }

    /**
     * 排下一次刷新：对齐到「整 N 分钟 + 一点偏移」，不是从此刻起数 N 分钟。
     *
     * ## 为什么要对齐
     *
     * 小米那两个接口是按整 5 分钟推进的（实测 pubTime 依次是 15:25、15:34…）。
     * 如果我们的轮询落在它更新**之前**（比如 15:24:50），拿到的是上一批数据，
     * 而且下一次要再等满 5 分钟才有机会纠正——用户看到的就是「比官方慢一拍」。
     *
     * 对齐之后每次都在整点后 [ALIGN_OFFSET_MS] 才发请求，稳稳在接口更新之后，
     * 不会撞在窗口边界上。代价是最多要多等一个间隔的零头，可以接受。
     */
    private fun scheduleNext() {
        handler.removeCallbacks(tick)
        val minutes = Store.getRefreshMinutes(this).coerceAtLeast(5)
        val intervalMs = minutes * 60_000L
        val now = System.currentTimeMillis()

        // 下一个「整间隔 + 偏移」的时间点
        val slot = (now / intervalMs + 1) * intervalMs + ALIGN_OFFSET_MS
        // 刚过偏移点时算出来的可能是过去的时间（比如正好卡在边界），保险起见兜一下
        val delay = (slot - now).takeIf { it > 0 } ?: intervalMs
        handler.postDelayed(tick, delay)
    }

    /** 在后台线程做一次完整刷新（定位 → 选站 → 抓天气 → 重写通知）。 */
    private fun refreshOnce() {
        // 拿个短暂的 wake lock：屏幕黑着的时候别让 CPU 在请求中途睡下去，
        // 否则容易半路断掉、缓存写不完全。用完立刻释放。
        var lock: PowerManager.WakeLock? = null
        try {
            runCatching {
                val pm = getSystemService(PowerManager::class.java)
                lock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "lockdeck:weather-refresh"
                )?.apply { acquire(30_000L) }
            }

            when (val r = WeatherRepo.refresh(this)) {
                is WeatherRepo.Result.Ok -> {
                    WeatherNotifier.show(this, r.weather)
                    // 磁贴读的是缓存，数据变了要主动让它重画一次
                    WeatherTileInUse.requestUpdate(this)
                }
                // 没权限：重试也没用，等用户去设置里给。通知上会把原因写出来。
                WeatherRepo.Result.NoPermission ->
                    WeatherNotifier.show(this, Store.loadWeather(this))
                // 没位置 / 接口失败：保留上一次的数值，别把好的数据擦掉
                else -> Unit
            }
        } catch (_: Throwable) {
            // 定时任务里绝不能把异常抛出去，否则循环会断，之后再也不会刷新
        } finally {
            runCatching { lock?.takeIf { it.isHeld }?.release() }
        }
    }

    companion object {
        const val CHANNEL_ID = WeatherNotifier.CHANNEL_ID
        const val NOTIF_ID = 1002

        /**
         * 缓存超过这么久就算「需要补一次」。
         *
         * 10 分钟：和默认刷新间隔一致。服务重建时如果数据比这还旧，
         * 说明中间断过，值得立刻补一次；否则只排下一轮。
         */
        private const val STALE_MS = 10 * 60_000L

        /**
         * 对齐整间隔后多等的这段时间。
         *
         * 20 秒：接口在整点那一刻发布，留出一点服务端写入和 CDN 生效的余量。
         * 太长会让「打开 App 正是该刷新的时候」变成干等。
         */
        private const val ALIGN_OFFSET_MS = 20_000L

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, WeatherForegroundService::class.java)
                ctx.startForegroundService(i)
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, WeatherForegroundService::class.java)) }
        }

        /** 刷新通知内容（服务没起来就先拉起来）。 */
        fun refresh(ctx: Context, w: com.jack.lockdeck.data.Weather?) {
            if (!Store.isEnabled(ctx)) return
            // 先把服务拉起来：服务在跑时 startForeground 会自己更新这条通知；
            // 没跑时这一下也只是把它提前台，不额外发第二条。
            start(ctx)
            runCatching {
                val n = WeatherNotifier.buildOnChannel(ctx, CHANNEL_ID, ongoing = true, w = w)
                ctx.getSystemService(android.app.NotificationManager::class.java)
                    .notify(NOTIF_ID, n)
            }
        }

        /**
         * 让服务立刻做一次刷新（用户点了「立即刷新」时用）。
         *
         * 走 startService + 一个 action，而不是在这里新开线程——
         * 这样刷新统一由服务里的那条循环来做，不会出现「用户手动刷一次、
         * 定时器又刷一次」撞在一起的情况。
         */
        fun refreshNow(ctx: Context) {
            if (!Store.isEnabled(ctx)) return
            runCatching {
                val i = Intent(ctx, WeatherForegroundService::class.java)
                    .setAction(ACTION_REFRESH_NOW)
                ctx.startForegroundService(i)
            }
        }

        const val ACTION_REFRESH_NOW = "com.jack.lockdeck.REFRESH_NOW"

        /** 刷新间隔改了（或换了数据源，其默认间隔不同），重排下一轮 */
        const val ACTION_RESCHEDULE = "com.jack.lockdeck.RESCHEDULE"

        /** 让服务按当前设置重排定时（改完刷新间隔后调用）。 */
        fun reschedule(ctx: Context) {
            if (!Store.isEnabled(ctx)) return
            runCatching {
                ctx.startForegroundService(
                    Intent(ctx, WeatherForegroundService::class.java).setAction(ACTION_RESCHEDULE)
                )
            }
        }
    }
}
