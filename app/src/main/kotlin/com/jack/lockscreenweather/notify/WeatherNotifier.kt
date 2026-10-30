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
import com.jack.lockscreenweather.action.QuickAction
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
            "锁屏卡片",
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
        return buildOnChannel(ctx, CHANNEL_ID, ongoing = true, w = w)
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

        val mainAction = Store.getTarget(ctx, "main") ?: QuickAction.openApp(WEATHER)
        val contentIntent = actionPendingIntent(ctx, mainAction, RC_MAIN)

        // 按钮 1 / 按钮 2 —— 各自跑一个动作，用户可在 App 里改
        val app1Action = Store.getTarget(ctx, "app1") ?: QuickAction.ALIPAY_SCAN
        val app2Action = Store.getTarget(ctx, "app2") ?: QuickAction.WECHAT_SCAN
        val app1Label = QuickAction.shortLabel(ctx, app1Action)
        val app2Label = QuickAction.shortLabel(ctx, app2Action)

        // 两种通知样式，可在 App 里切换：
        //
        //  A. 系统原生（BigTextStyle）—— 稳定置顶，从不折叠；
        //     代价是 action 按钮要长按通知才露出来。
        //  B. 自定义卡片（RemoteViews）—— 天气和两个按钮并排一行，
        //     左侧下拉一眼就能看到并直接点；代价是 MIUI 有概率把它
        //     折叠进「更多通知」。实测（HyperOS 3 / Redmi Turbo 5）
        //     只用最朴素的 LinearLayout + TextView 时不会被折叠。
        val useCard = Store.isNotifCardEnabled(ctx)

        val builder = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(ongoing)         // 常驻，划不掉
            .setOnlyAlertOnce(true)      // 刷新时不重复打扰
            // 不用 setSilent：MIUI 会把 SILENT 的通知统一收进「更多通知」，
            // 左侧下拉第一屏看不到。静音靠 channel 的 sound=null 实现，效果一样。
            .setSilent(false)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            // 被左滑划掉时立刻重发，做到「划掉也会自己回来」（对齐 Clash Meta 的手感）
            .setDeleteIntent(repostPendingIntent(ctx))

        if (useCard) {
            builder.setCustomContentView(buildCard(ctx, w, app1Action, app2Action, app1Label, app2Label))
            builder.setCustomBigContentView(buildCard(ctx, w, app1Action, app2Action, app1Label, app2Label))
        } else {
            builder.addAction(
                R.drawable.ic_stat_weather,
                app1Label,
                actionPendingIntent(ctx, app1Action, RC_APP1),
            )
            builder.addAction(
                R.drawable.ic_stat_weather,
                app2Label,
                actionPendingIntent(ctx, app2Action, RC_APP2),
            )
        }
        return builder
            .build()
    }

    /** 自定义通知卡片的 RemoteViews */
    private fun buildCard(
        ctx: Context,
        w: Weather?,
        app1Action: String,
        app2Action: String,
        app1Label: String,
        app2Label: String,
    ): android.widget.RemoteViews {
        val rv = android.widget.RemoteViews(ctx.packageName, R.layout.notif_weather_card)
        rv.setTextViewText(R.id.n_icon, w?.icon ?: "…")
        rv.setTextViewText(R.id.n_temp, w?.let { "${it.tempNow}°" } ?: "--°")
        rv.setTextViewText(R.id.n_desc, w?.description ?: "获取中")
        rv.setTextViewText(
            R.id.n_sub,
            if (w == null) {
                "点开可手动刷新"
            } else {
                val parts = mutableListOf<String>()
                parts.add("${w.tempMin}°~${w.tempMax}°")
                if (w.rainChance >= 0) parts.add("降雨 ${w.rainChance}%")
                if (w.city.isNotBlank()) parts.add(w.city)
                parts.joinToString(" · ")
            },
        )
        rv.setTextViewText(R.id.n_btn1, app1Label)
        rv.setTextViewText(R.id.n_btn2, app2Label)
        rv.setOnClickPendingIntent(R.id.n_btn1, actionPendingIntent(ctx, app1Action, RC_APP1))
        rv.setOnClickPendingIntent(R.id.n_btn2, actionPendingIntent(ctx, app2Action, RC_APP2))
        return rv
    }

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

    /**
     * 把动作包成 PendingIntent。
     *
     * 注意：这里必须走 LaunchTrampolineActivity，不能直接把 Intent 塞进
     * PendingIntent.getActivity —— 从锁屏点通知时系统会挂起这个启动，
     * 用户得自己上滑，看起来就像"点了没反应"。
     */
    private fun actionPendingIntent(ctx: Context, actionId: String, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, com.jack.lockscreenweather.overlay.LaunchTrampolineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(com.jack.lockscreenweather.overlay.LaunchTrampolineActivity.EXTRA_ACTION, actionId)
        }
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
