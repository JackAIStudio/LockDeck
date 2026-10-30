package com.jack.lockdeck.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 应用更新后把通知和定时任务重新拉起来。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> {
                if (com.jack.lockdeck.data.Store.isOverlayEnabled(ctx) || com.jack.lockdeck.data.Store.isEnabled(ctx)) {
                    WeatherWorker.schedule(ctx)
                    WeatherWorker.enqueueNow(ctx)
                }
            }
        }
    }
}
