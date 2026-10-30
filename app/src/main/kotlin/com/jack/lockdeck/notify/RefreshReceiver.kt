package com.jack.lockdeck.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jack.lockdeck.data.Store

/**
 * 刷新 / 重发广播：锁屏和通知栏上直接触发，不需要解锁。
 */
class RefreshReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REFRESH = "com.jack.lockdeck.REFRESH"

        /** 通知被划掉后补发自己 */
        const val ACTION_REPOST = "com.jack.lockdeck.REPOST"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> {
                WeatherWorker.enqueueNow(ctx)
                // 先给一个"刷新中"的反馈，避免用户以为没反应
                WeatherNotifier.show(ctx, Store.loadWeather(ctx))
            }

            ACTION_REPOST -> {
                // 被左滑划掉了：只补发一次，不做循环重试。
                //
                // 不能走 WeatherNotifier.show()：它内部还会再判一次开关，
                // 而且当时是为了「常驻通知」这条链路写的。
                // 这里直接叫前台服务重挂，开关由调用方判一次就够。
                if (Store.isEnabled(ctx)) {
                    WeatherForegroundService.refresh(ctx, Store.loadWeather(ctx))
                }
            }
        }
    }
}
