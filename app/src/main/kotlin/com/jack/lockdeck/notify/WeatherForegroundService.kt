package com.jack.lockdeck.notify

import android.app.Notification
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
 * 为什么非要它：普通通知在 HyperOS 上会被降级、限流，甚至被判定为
 * 「不重要的 App 发的」直接折叠；只有前台服务那条通知能稳稳待在
 * 左侧下拉第一屏，锁屏上也看得见。JackVoice 的常驻入口也是这个套路
 * （`VoiceResidentService`，specialUse 型）。
 *
 * 服务本身不干活，天气抓取在 [WeatherWorker] 里，调度交给 WorkManager。
 *
 * Android 14+ 要求声明 foregroundServiceType，这里用 specialUse + 对应权限，
 * 避免被系统拒绝启动。
 */
class WeatherForegroundService : Service() {

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
        return START_STICKY
    }

    companion object {
        const val CHANNEL_ID = WeatherNotifier.CHANNEL_ID
        const val NOTIF_ID = 1002

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
    }
}
