package com.jack.lockscreenweather.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jack.lockscreenweather.R
import com.jack.lockscreenweather.data.LocationHelper
import com.jack.lockscreenweather.data.Store
import com.jack.lockscreenweather.data.Weather
import com.jack.lockscreenweather.data.WeatherApi

/**
 * 通知栏天气卡片。
 *
 * 设计要点：
 * 1. 只用系统原生布局（BigTextStyle）。RemoteViews 自定义布局会被 MIUI 折叠，
 *    详见 buildOnChannel() 里的实测记录。
 * 2. 两个 App 快捷按钮各绑一个 PendingIntent。
 * 3. PendingIntent 必须 requestCode 唯一 + FLAG_IMMUTABLE，否则会互相覆盖。
 */
object WeatherNotifier {

    // v2：通道 ID 变更，绕开旧通道被系统锁定为 LOW 的问题
    private const val CHANNEL_ID = "lsw_weather_v2"
    private const val NOTIF_ID = 1001

    // 每个点击区的唯一 requestCode
    private const val RC_MAIN = 100
    private const val RC_LEFT = 101
    private const val RC_RIGHT = 102
    private const val RC_REFRESH = 103
    private const val RC_APP1 = 104
    private const val RC_APP2 = 105
    private const val RC_REPOST = 106

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        // 旧版本用的 LOW 会被小米折叠进"静默通知"，锁屏看不到，这里统一升级为 DEFAULT
        if (existing != null && existing.importance >= NotificationManager.IMPORTANCE_DEFAULT) return
        existing?.let { nm.deleteNotificationChannel(CHANNEL_ID) }
        val ch = NotificationChannel(
            CHANNEL_ID,
            "锁屏天气",
            // DEFAULT：能进入锁屏主区域；配合 setSilent(true) 保证不响不震
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "在锁屏显示当前温度与今日最高最低"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * 构建通知。
     * @param w 天气数据；为 null 时显示占位文案，保证卡片始终存在。
     */
    fun build(ctx: Context, w: Weather?): Notification {
        ensureChannel(ctx)
        return buildOnChannel(ctx, CHANNEL_ID, ongoing = true, silent = true, w = w)
    }

    /**
     * 用指定 channel 构建通知。
     *
     * 前台服务通知必须与 startForeground 传入的 Notification 保持一致的 channel，
     * 否则 Android 会直接抛异常。
     */
    fun buildOnChannel(
        ctx: Context,
        channelId: String,
        ongoing: Boolean,
        silent: Boolean,
        w: Weather? = Store.loadWeather(ctx),
    ): Notification {
        val title = w?.let { "${it.tempNow}° ${it.description}" } ?: "正在获取天气"
        val detail = buildString {
            if (w != null) {
                append("今日 ${w.tempMin}° ~ ${w.tempMax}°")
                if (w.rainChance >= 0) append(" · 降雨 ${w.rainChance}%")
                if (w.city.isNotBlank()) append(" · ").append(w.city)
            } else {
                append("点开可手动刷新")
            }
        }

        val contentIntent = targetPendingIntent(ctx, Store.getTarget(ctx, "main") ?: WEATHER, RC_MAIN)

        // 按钮 1 / 按钮 2 —— 各自打开不同 App，用户可在 App 里改
        val app1Pkg = Store.getTarget(ctx, "app1") ?: "com.tencent.mm"
        val app2Pkg = Store.getTarget(ctx, "app2") ?: "com.eg.android.AlipayGphone"
        val app1Label = labelOf(ctx, app1Pkg, "微信")
        val app2Label = labelOf(ctx, app2Pkg, "支付宝")

        // 只用系统原生布局。
        //
        // 实测结论（HyperOS 3 / Redmi Turbo 5）：
        //   - 系统原生 BigTextStyle  → 稳定置顶，从不折叠；副作用是 action 按钮要点「长按」才出
        //   - 任意 RemoteViews 自定义 → 一律折叠进「更多通知」，连天气本身都看不见
        //                              （试过补 setContentTitle/Text 试图救回来，无效；
        //                                也试过自定义布局 + addAction，照样折叠）
        // 两者不可兼得。选了"天气一定看得见"，按钮就让用户长按调出。
        return NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(ongoing)         // 常驻，划不掉
            .setOnlyAlertOnce(true)      // 刷新时不重复打扰
            .setSilent(silent)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            // 被左滑划掉时立刻重发，做到「划掉也会自己回来」（对齐 Clash Meta 的手感）
            .setDeleteIntent(repostPendingIntent(ctx))
            .addAction(
                R.drawable.ic_stat_weather,
                app1Label,
                targetPendingIntent(ctx, app1Pkg, RC_APP1),
            )
            .addAction(
                R.drawable.ic_stat_weather,
                app2Label,
                targetPendingIntent(ctx, app2Pkg, RC_APP2),
            )
            .build()
    }

    /** 取 App 显示名，拿不到就用兜底文案 */
    private fun labelOf(ctx: Context, pkg: String, fallback: String): String =
        runCatching {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(ai).toString()
        }.getOrNull() ?: fallback

    fun show(ctx: Context, w: Weather?) {
        if (!Store.isEnabled(ctx)) return
        // 只保留一条：统一走前台服务那条（id=1002）。
        // 以前这里会再发一条 id=1001，导致通知栏出现两条一模一样的天气。
        WeatherForegroundService.refresh(ctx, w)
    }

    fun cancel(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) }
    }

    // ---------------------------------------------------------------- intents

    /** 打开某个包；包不存在就回落到天气 App；都不存在则取消。 */
    private fun targetPendingIntent(ctx: Context, pkg: String, requestCode: Int): PendingIntent {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: ctx.packageManager.getLaunchIntentForPackage(WEATHER)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.mi.com"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 刷新用广播，锁屏下不需要解锁即可生效。 */
    private fun refreshPendingIntent(ctx: Context, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, RefreshReceiver::class.java).apply {
            action = RefreshReceiver.ACTION_REFRESH
        }
        return PendingIntent.getBroadcast(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 通知被左滑划掉后，立刻把常驻通知重新挂上去。
     *
     * 为什么需要：OnGoing 只是不让用户「点 X」，左滑在 MIUI 上仍然能划掉。
     * Clash Meta 那种「划掉马上回来」的手感，就是因为它每秒都在重发通知；
     * 我们 10 分钟才更新一次，只能靠 deleteIntent 主动补发。
     */
    private fun repostPendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, RefreshReceiver::class.java).apply {
            action = RefreshReceiver.ACTION_REPOST
        }
        return PendingIntent.getBroadcast(
            ctx, RC_REPOST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    const val WEATHER = "com.miui.weather2"
    const val CLOCK = "com.android.deskclock"

    /** 供 UI 预览：拿到最新数据并返回（同步，仅用于 Activity 内部）。 */
    fun refreshNow(ctx: Context): Weather? {
        val fix = LocationHelper.lastKnown(ctx) ?: LocationHelper.FALLBACK
        val res = WeatherApi.fetch(fix.lat, fix.lon)
        val w = res.getOrElse { e ->
            Store.setError(ctx, "天气请求失败: ${e.javaClass.simpleName}: ${e.message}")
            return Store.loadWeather(ctx)
        }
        Store.setError(ctx, null)
        val city = Store.loadWeather(ctx)?.city.orEmpty().ifBlank {
            WeatherApi.reverseCity(fix.lat, fix.lon)
        }
        val merged = w.copy(city = city)
        Store.saveWeather(ctx, merged)
        return merged
    }
}
