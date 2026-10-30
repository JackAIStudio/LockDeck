package com.jack.lockdeck.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.jack.lockdeck.data.Store

/**
 * 常驻前台服务，只为了拿到 FOREGROUND_SERVICE 类型的通知。
 *
 * 动机：实测发现普通通知在 HyperOS 3 的锁屏上不展示，
 * 而 Clash Meta 的「前台服务通知」能正常常驻锁屏并实时刷新。
 * 两者的差别就在 flags 里的 FOREGROUND_SERVICE 和对应的 channel/importance。
 *
 * 注意：Android 14+ 要求声明 foregroundServiceType，
 * 这里用 specialUse + 对应权限，避免被系统拒绝启动。
 */
class WeatherForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "lsw_fg_v4"
        const val NOTIF_ID = 1002

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            // 用 DEFAULT(3)：LOW(2) 会被 MIUI 统一收进「更多通知」，
            // 左侧下拉第一屏根本看不到。DEFAULT + setSilent(true) 同样不响不震。
            if (existing != null && existing.importance == NotificationManager.IMPORTANCE_DEFAULT) return
            existing?.let { nm.deleteNotificationChannel(CHANNEL_ID) }
            val ch = NotificationChannel(
                CHANNEL_ID,
                "锁屏卡片（常驻）",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "在锁屏与通知栏常驻显示当前天气"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }

        fun buildNotification(ctx: Context): Notification =
            WeatherNotifier.buildOnChannel(ctx, CHANNEL_ID, ongoing = true)

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, WeatherForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, WeatherForegroundService::class.java)) }
        }

        fun refresh(ctx: Context, w: com.jack.lockdeck.data.Weather?) {
            if (!Store.isEnabled(ctx)) return
            // 渠道必须先建好，否则 notify 会报 "No Channel found" 静默失败
            ensureChannel(ctx)
            // 关键：先把服务拉起来（服务没起来时 notify 出来的只是普通通知，
            // 没有 FOREGROUND_SERVICE 标志，HyperOS 锁屏不会展示）。
            start(ctx)
            runCatching {
                val n = WeatherNotifier.buildOnChannel(
                    ctx, CHANNEL_ID, ongoing = true, w = w
                )
                ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
            }
        }
    }
}
