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
                // 走前台服务那条循环刷新：实测 WorkManager 在这台机器上
                // 不会真正执行（run_attempt_count 一直是 0），靠它刷新等于没刷新。
                WeatherForegroundService.refreshNow(ctx)
                // 先重写一次通知，避免用户以为没反应
                WeatherNotifier.show(ctx, Store.loadWeather(ctx))
            }

            ACTION_REPOST -> {
                // 被左滑划掉了：只补发一次，不做循环重试。
                // 用户铁了心要它消失就去设置里关开关，反复弹回来只会招人烦。
                if (Store.isEnabled(ctx)) {
                    WeatherForegroundService.refresh(ctx, Store.loadWeather(ctx))
                }
            }
        }
    }
}
