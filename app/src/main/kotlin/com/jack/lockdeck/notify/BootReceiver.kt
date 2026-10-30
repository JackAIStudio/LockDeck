package com.jack.lockdeck.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jack.lockdeck.data.Store
import com.jack.lockdeck.tile.WeatherTileInUse

/**
 * 开机 / 应用更新后把常驻通知和定时任务重新拉起来。
 *
 * 前台服务不会自己开机自启，没有谁去 startForegroundService 它就不在——
 * 用户早上开机第一件事往往是下拉看一眼天气，这时候通知栏里空着等于功能不存在。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> {
                // 旧版本的浮层设置在这里顺手清掉，不留死键
                Store.purgeLegacyOverlayPrefs(ctx)

                val notifOn = Store.isEnabled(ctx)
                val tileOn = WeatherTileInUse.marked(ctx)
                if (notifOn || tileOn) {
                    WeatherWorker.schedule(ctx)
                    WeatherWorker.enqueueNow(ctx)
                }
                if (notifOn) WeatherForegroundService.start(ctx)
            }
        }
    }
}
