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

        // 关键：不要用 RemoteViews / setCustomContentView。
        // MIUI 把「自定义布局、系统读不出文案」的通知判为低价值，会折叠进"更多通知"。
        // JackVoiceIME 的录音通知用的就是系统原生 BigTextStyle，实测能稳定置顶。
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
            .addAction(
                R.drawable.ic_stat_weather,
                "刷新",
                refreshPendingIntent(ctx, RC_REFRESH),
            )
            .build()
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
