package com.jack.lockscreenweather.action

import android.content.Context
import android.content.Intent
import com.jack.lockscreenweather.overlay.LaunchTrampolineActivity

/**
 * 所有入口（锁屏卡片、控制中心磁贴、通知按钮）的统一出口。
 *
 * 为什么统一走 LaunchTrampolineActivity：
 * 锁屏上直接 startActivity 会被系统挂起，用户必须自己上滑解锁才看得到，
 * 表现就是"点了没反应"。中间页会主动请求解锁，解锁完成后自动续跳。
 * 已经解锁时它什么都不弹，直接跳走，用户感知不到。
 */
object ActionRunner {

    /** 执行一个动作。refresh 走广播，其余走中间页跳转。 */
    fun run(ctx: Context, zone: String, id: String) {
        if (id == QuickAction.REFRESH) {
            ctx.sendBroadcast(
                Intent(ctx, com.jack.lockscreenweather.notify.RefreshReceiver::class.java).apply {
                    action = com.jack.lockscreenweather.notify.RefreshReceiver.ACTION_REFRESH
                }
            )
            return
        }
        if (!QuickAction.isAvailable(ctx, id)) {
            android.util.Log.w("LSW", "action $id 的目标 App 未安装")
            return
        }
        val i = Intent(ctx, LaunchTrampolineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(LaunchTrampolineActivity.EXTRA_ZONE, zone)
            putExtra(LaunchTrampolineActivity.EXTRA_ACTION, id)
        }
        runCatching { ctx.startActivity(i) }
            .onFailure { android.util.Log.e("LSW", "start trampoline failed", it) }
    }
}
