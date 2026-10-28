package com.jack.lockscreenweather.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jack.lockscreenweather.data.Store

/**
 * 刷新广播：锁屏上点「刷新」直接走这里，不需要解锁。
 * 注意这里只做轻量动作，重活交给 WorkManager。
 */
class RefreshReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REFRESH = "com.jack.lockscreenweather.REFRESH"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH) return
        WeatherWorker.enqueueNow(ctx)
        // 先给一个"刷新中"的反馈，避免用户以为没反应
        val cur = Store.loadWeather(ctx)
        WeatherNotifier.show(ctx, cur)
    }
}
