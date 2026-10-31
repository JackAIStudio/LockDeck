package com.jack.lockdeck.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.jack.lockdeck.R

/**
 * 锁屏可见性自检。
 *
 * ## 为什么需要它
 *
 * HyperOS 3（Redmi Turbo 5 实测）把「哪条通知能上锁屏」交给 SystemUI 的
 * `KeyguardCoordinator` + `MiuiNotifCoordinator` 两个过滤器判定，第三方应用
 * 默认被判成 `keyguard=false`，通知只进下拉栏、不进锁屏。
 *
 * 实测排除了这些常见误解（都不是原因）：
 * - **勿扰**：关掉勿扰也一样上不去（勿扰只会额外再挡一层）
 * - **importance**：LOW / DEFAULT / HIGH 三档都试过，结果一致
 * - **静音**：`setSilent(true)` 与 `false` 都试过，结果一致
 * - **焦点通知 / 超级岛**：即使成功带上 `miui.focus.param`（系统已认，
 *   `focusType=PARAMS`），锁屏依然被 `KeyguardCoordinator` 过滤。
 *   本机真实样本：九号出行的电池岛同样上不了锁屏。
 *
 * 唯一决定因素是 MIUI 按应用下发的锁屏权限，App 侧无法用参数绕过。
 * 所以这里不做「猜」，而是**直接读系统设置**把真实状态告诉用户。
 *
 * ## 锁屏通知开关的三种取值
 *
 * `Settings.Secure.lock_screen_show_silent_notifications`：
 * - `1` 显示对话、默认通知和无声通知（最宽松）
 * - `0` 隐藏静音对话和通知（默认，本机出厂就是这个）
 * - key 不存在 = 不显示任何通知
 *
 * 注意：把它改成 1 之后，本机第三方通知**依然上不了锁屏**（实测），
 * 说明真正的闸门在应用粒度，不在这个全局开关上。这里读出它只是为了
 * 别让用户以为「改了这个就行」。
 */
object LockScreenCheck {

    /** 系统设置里「锁屏通知」的当前档位。 */
    enum class Mode(val label: String) {
        SHOW_ALL("显示对话、默认通知和无声通知"),
        HIDE_SILENT("隐藏静音对话和通知"),
        NONE("不显示任何通知");

        companion object {
            fun read(ctx: Context): Mode {
                val v = runCatching {
                    Settings.Secure.getInt(ctx.contentResolver, KEY)
                }.getOrNull()
                return when (v) {
                    1 -> SHOW_ALL
                    0 -> HIDE_SILENT
                    else -> NONE
                }
            }
        }
    }

    private const val KEY = "lock_screen_show_silent_notifications"

    /** 焦点通知权限查询的 provider（小米私有，失败属正常）。 */
    private const val FOCUS_URI = "content://miui.statusbar.notification.public"

    /**
     * 本机是否放行第三方通知到锁屏。
     *
     * 判据：SystemUI 给我们的通知打的是 `keyguard=T` 还是 `F`。
     * 这里没有 binder 接口可读，只能取「实测结论 + 设置档位」的保守判断：
     * 第三方应用在 HyperOS 3 上默认不放行，因此恒为 false，
     * 除非用户能把本应用加进系统的锁屏白名单（目前 MIUI 设置里没有这个入口）。
     */
    fun thirdPartyAllowedOnLockScreen(): Boolean = false

    /** 当前焦点通知协议版本：1=OS1 2=OS2 3=OS3 超级岛；0=不支持 */
    fun focusProtocolVersion(ctx: Context): Int = runCatching {
        Settings.System.getInt(ctx.contentResolver, "notification_focus_protocol", 0)
    }.getOrDefault(0)

    /** 本应用是否拿到了焦点通知权限（需小米后台开通，查不到就当没有）。 */
    fun hasFocusPermission(ctx: Context): Boolean = runCatching {
        val b = ctx.contentResolver.call(
            android.net.Uri.parse(FOCUS_URI),
            "canShowFocus",
            null,
            android.os.Bundle().apply { putString("package", ctx.packageName) },
        )
        b?.getBoolean("canShowFocus", false) ?: false
    }.getOrDefault(false)

    /**
     * 发一条自检通知。
     *
     * 用途：让用户在锁屏上亲眼看一次「到底显不显示」，比任何说明都直接。
     * 故意用 DEFAULT 重要性 + 非静音，把「静音被挡」这个变量排除掉——
     * 如果这样都上不去，就只剩应用粒度这一条命门。
     */
    fun postProbe(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val chId = "lsw_lock_probe_v1"
        if (nm.getNotificationChannel(chId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "锁屏自检", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "检查锁屏是否放行本应用的通知"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
            )
        }
        val n = NotificationCompat.Builder(ctx, chId)
            .setSmallIcon(R.drawable.ic_stat_weather)
            .setContentTitle("锁屏自检")
            .setContentText("看到这条就说明锁屏放行了本应用")
            .setStyle(NotificationCompat.BigTextStyle().bigText("看到这条就说明锁屏放行了本应用"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(false)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        nm.notify(PROBE_ID, n)
    }

    const val PROBE_ID = 9200

    fun summary(ctx: Context): String {
        val mode = Mode.read(ctx)
        val proto = focusProtocolVersion(ctx)
        return buildString {
            append("锁屏通知档位：").append(mode.label)
            append("\n焦点通知协议：")
            append(
                when (proto) {
                    3 -> "OS3（超级岛可用）"
                    2 -> "OS2"
                    1 -> "OS1"
                    else -> "不支持"
                },
            )
            append("\n第三方通知上锁屏：")
            append(if (thirdPartyAllowedOnLockScreen()) "放行" else "被系统挡下")
        }
    }
}
