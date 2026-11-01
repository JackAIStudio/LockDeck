package com.jack.lockdeck.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jack.lockdeck.R
import com.jack.lockdeck.action.QuickAction
import com.jack.lockdeck.data.Store
import com.jack.lockdeck.data.Weather
import com.jack.lockdeck.data.WeatherRepo
import com.jack.lockdeck.launch.LaunchTrampolineActivity

/**
 * 通知栏天气信息。
 *
 * ## 定位：**只做信息，不做入口**
 *
 * 扫一扫、收付款这些「点一下要发生点什么」的动作归控制中心磁贴；
 * 通知栏只回答「现在几度、今天什么天」。所以它没有自定义布局、
 * 没有并排按钮——就是系统自己画的那种一行通知，和 JackVoice 的
 * 常驻通知长得一样，系统怎么排版它就怎么显示。
 *
 * ## 这些参数不能随便动（每一条都是踩出来的）
 *
 * 参照本机跑通的 JackVoice 常驻通知（`jackvoice_resident`）：
 *
 * - **绝不用 setCustomContentView**：用过 RemoteViews 的通知，
 *   extras 里会带 `android.contains.customView=true`。MIUI 读不懂自定义
 *   布局里的文案，就把它当「低价值通知」折叠进「更多通知」。
 *   实测这是折叠的主因，比 importance 影响更大。
 * - **importance 用 LOW**：LOW 不进「静默通知」折叠区，也不响不震。
 *   之前为了「显眼」提到 DEFAULT，反而更吵、还更容易被当垃圾。
 *   代价是 **LOW 的通知不在状态栏显示图标**（Android 原生行为，不是 MIUI
 *   特有：下拉栏和通知卡里照常有，只是收起后左上角那里不占位）。
 *   实测把通道换成 IMPORTANCE_DEFAULT 后，同一个 setSmallIcon 立刻会在
 *   时间右边出现；但如果想要「安静但常驻」，保持 LOW 才是对的。
 * - **setSilent(true)**：和 LOW 配套，刷新时不吭声。
 * - **前台服务**：普通通知在 HyperOS 上会被降级／限流，只有带
 *   FOREGROUND_SERVICE 标记的常驻通知能稳稳待在左边下拉第一屏。
 *   真正调 startForeground 的是 [WeatherForegroundService]。
 *
 * ## 一条实测结论：这条通知**上不了锁屏**
 *
 * HyperOS 3（Redmi Turbo 5 / OS3.0 / Android 16）的锁屏通知由 SystemUI 的
 * `KeyguardCoordinator` + `MiuiNotifCoordinator` 两个过滤器决定，
 * 第三方应用默认被判成 `keyguard=false`，只进下拉栏、不进锁屏。
 *
 * **下列变量已逐一排除，全都不是原因**（2026-11 真机复测）：
 * - 勿扰模式：关掉勿扰后普通第三方通知照样上不去
 * - importance：LOW / DEFAULT / HIGH 三档结果一致
 * - 静音：`setSilent(true)` / `false` 结果一致
 * - 系统「锁屏通知」档位：改成「显示无声通知」后仍然上不去
 * - 焦点通知 / 超级岛：带 `miui.focus.param` 能拿到 `focusType=PARAMS`，
 *   但锁屏依旧被 `KeyguardCoordinator` 过滤；本机真实样本「九号出行」
 *   的电池岛同样上不了锁屏
 *
 * 唯一决定因素是 MIUI 按应用下发的锁屏权限，App 侧无法用参数绕过。
 * 详见 [LockScreenCheck]（那里有可复现的自检入口）。
 *
 * 早期版本为了在锁屏上显示天气，用无障碍服务画了一层 TYPE_ACCESSIBILITY_OVERLAY
 * 浮层。那玩意儿会盖在别人通知上、还要每 1.5 秒轮询一次无障碍节点树，
 * 又丑又卡，已经整个删掉。**不要再往那个方向走。**
 */
object WeatherNotifier {

    /**
     * 天气信息通道。v5：从自定义卡片改回纯原生（见类注释）。
     *
     * 通道 ID 里的版本号不是随便加的：importance、响铃这些通道级属性
     * Android 不允许原地改（用户手动改过的还会被锁定），只能换新 ID 重建。
     */
    const val CHANNEL_ID = "lsw_weather_v5"

    /** 点击整条通知：打开天气 App */
    private const val RC_MAIN = 100
    private const val RC_REPOST = 106

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "天气",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "在通知栏与锁屏常驻显示当前天气"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    fun build(ctx: Context, w: Weather?): Notification {
        ensureChannel(ctx)
        return buildOnChannel(ctx, CHANNEL_ID, ongoing = true, w = w)
    }

    /**
     * 用指定 channel 构建通知。
     *
     * 前台服务通知必须与 startForeground 传入的 Notification 使用同一个 channel，
     * 否则 Android 会直接抛异常。
     */
    fun buildOnChannel(
        ctx: Context,
        channelId: String,
        ongoing: Boolean,
        w: Weather? = Store.loadWeather(ctx),
    ): Notification {
        // 必须在 startForeground 之前把通道建好，否则系统会甩
        // CannotPostForegroundServiceNotificationException: Bad notification。
        ensureChannel(ctx)
        // 标题写天气，正文一行放下「今日温度区间 + 城市」。
        // 不塞降雨概率：带上它就折成两行，跟 JackVoice 那种
        // 「标题一行 + 正文一行」的干净样式就不一样了。
        // 想看降雨就点开通知进天气 App。
        //
        // 唯一的例外是**马上要下雨**：那是「现在得决定带不带伞」的信息，
        // 比温度区间重要，所以压过温度区间顶到正文第一行，标题也换成它。
        // 不下雨的时候（99% 的时间）还是原来那两行，不占地方。
        val rainSoon = w?.minutely?.takeIf { it.hasRain }
        val title = when {
            rainSoon != null -> rainSoon.summary
            w != null -> "${w.tempNow}° ${w.description}"
            else -> "正在获取天气"
        }
        val detail = if (w == null) {
            "点开可手动刷新"
        } else {
            buildString {
                if (rainSoon == null) {
                    append("今日 ${w.tempMin}°~${w.tempMax}°")
                } else {
                    append("${w.tempNow}° ${w.description} · 今日 ${w.tempMin}°~${w.tempMax}°")
                }
                if (w.place.isNotBlank()) append(" · ").append(w.place)
            }
        }

        // 整条通知点开 = 天气；扫一扫那些去控制中心磁贴。
        val mainAction = Store.getTarget(ctx, "main") ?: QuickAction.openApp(QuickAction.PKG_WEATHER)

        return NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_stat_lockdeck)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(ongoing)         // 常驻，点不掉 X
            .setOnlyAlertOnce(true)      // 刷新不重复打扰
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(actionPendingIntent(ctx, mainAction, RC_MAIN))
            // OnGoing 只是不给 X，MIUI 上左滑照样能划掉；划掉立刻补回来。
            .setDeleteIntent(repostPendingIntent(ctx))
            .build()
    }

    fun show(ctx: Context, w: Weather?) {
        if (!Store.isEnabled(ctx)) return
        // 只保留一条：统一走前台服务那条（id=1002）。
        WeatherForegroundService.refresh(ctx, w)
    }

    fun cancel(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(WeatherForegroundService.NOTIF_ID) }
    }

    // ---------------------------------------------------------------- intents

    /**
     * 把动作包成 PendingIntent。
     *
     * 必须走 LaunchTrampolineActivity：锁屏上直接把目标 Intent 塞进
     * PendingIntent.getActivity，系统会挂起这次启动，用户得自己上滑，
     * 看起来就是「点了没反应」。
     */
    private fun actionPendingIntent(ctx: Context, actionId: String, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, LaunchTrampolineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(LaunchTrampolineActivity.EXTRA_ACTION, actionId)
        }
        return PendingIntent.getActivity(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 通知被左滑划掉后，立刻把常驻通知重新挂上去。
     *
     * OnGoing 只是不让点 X，左滑在 MIUI 上仍然能划掉。
     * 只补一次、不做循环重试——用户真要它消失就去设置里关开关。
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

    const val WEATHER = QuickAction.PKG_WEATHER

    /** 供 UI 预览：立刻刷一次并返回（同步，仅用于 Activity 内部）。 */
    fun refreshNow(ctx: Context): Weather? {
        return when (val r = WeatherRepo.refresh(ctx)) {
            is WeatherRepo.Result.Ok -> r.weather
            // 拿不到就返回最后一份成功的快照（可能是 null），绝不补一个假位置
            else -> Store.loadWeather(ctx)
        }
    }
}
