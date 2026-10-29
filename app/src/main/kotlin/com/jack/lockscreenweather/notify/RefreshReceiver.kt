package com.jack.lockscreenweather.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jack.lockscreenweather.data.Store

/**
 * 刷新 / 重发广播：锁屏和通知栏上直接触发，不需要解锁。
 */
class RefreshReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REFRESH = "com.jack.lockscreenweather.REFRESH"

        /** 通知被划掉后补发自己 */
        const val ACTION_REPOST = "com.jack.lockscreenweather.REPOST"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> {
                WeatherWorker.enqueueNow(ctx)
                // 先给一个"刷新中"的反馈，避免用户以为没反应
                WeatherNotifier.show(ctx, Store.loadWeather(ctx))
            }

            ACTION_REPOST -> {
                // 被左滑划掉了：用户明确想让它消失一会儿的话，
                // 这里的立即重发会显得很顽固，所以只重发一次，不做循环重试。
                WeatherNotifier.show(ctx, Store.loadWeather(ctx))
            }
        }
    }
}
