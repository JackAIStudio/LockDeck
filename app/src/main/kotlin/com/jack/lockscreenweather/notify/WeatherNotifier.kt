package com.jack.lockscreenweather.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jack.lockscreenweather.R
import com.jack.lockscreenweather.data.LocationHelper
import com.jack.lockscreenweather.data.Store
import com.jack.lockscreenweather.data.Weather
import com.jack.lockscreenweather.data.WeatherApi

/**
 * 锁屏天气卡片。
 *
 * 设计要点（都是为了验证可行性）：
 * 1. 用 RemoteViews 自定义布局 —— 验证小米会不会把它降级成系统模板
 * 2. 三个独立点击区，各自一个 PendingIntent —— 验证多点位是否互不干扰
 * 3. PendingIntent 必须 requestCode 唯一 + FLAG_IMMUTABLE，否则会互相覆盖
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

        val views = RemoteViews(ctx.packageName, R.layout.notif_weather).apply {
            if (w != null) {
                setTextViewText(R.id.tv_temp, "${w.tempNow}°")
                setTextViewText(R.id.tv_icon, w.icon)
                setTextViewText(R.id.tv_range, "今日 ${w.tempMin}° ~ ${w.tempMax}°")
                setTextViewText(R.id.tv_desc, buildString {
                    append(w.description)
                    if (w.city.isNotBlank()) append(" · ").append(w.city)
                })
            } else {
                setTextViewText(R.id.tv_temp, "--°")
                setTextViewText(R.id.tv_icon, "…")
                setTextViewText(R.id.tv_range, "今日 --° ~ --°")
                setTextViewText(R.id.tv_desc, "正在获取天气")
            }

            // ---- 三个独立点击区 ----
            // 主区域：打开用户指定的 App（默认天气 App）
            setOnClickPendingIntent(
                R.id.zone_main,
                targetPendingIntent(ctx, Store.getTarget(ctx, "main") ?: WEATHER, RC_MAIN),
            )
            // 左侧：刷新（不经解锁，直接广播触发后台刷新）
            setOnClickPendingIntent(R.id.zone_left, refreshPendingIntent(ctx, RC_REFRESH))
            // 右侧：用户指定的 App（默认闹钟）
            setOnClickPendingIntent(
                R.id.zone_right,
                targetPendingIntent(ctx, Store.getTarget(ctx, "right") ?: CLOCK, RC_RIGHT),
            )
        }

        val contentIntent = targetPendingIntent(ctx, Store.getTarget(ctx, "main") ?: WEATHER, RC_MAIN)

        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setCustomContentView(views)
            // 展开态也用同一套布局，避免用户下拉时看到空壳
            .setCustomBigContentView(views)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)            // 常驻，划不掉
            .setOnlyAlertOnce(true)      // 刷新时不重复打扰
            .setSilent(true)             // 静音：不响不震，即使通道是 DEFAULT
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 锁屏显示全文
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    fun show(ctx: Context, w: Weather?) {
        if (!Store.isEnabled(ctx)) return
        runCatching {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, build(ctx, w))
        }
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
