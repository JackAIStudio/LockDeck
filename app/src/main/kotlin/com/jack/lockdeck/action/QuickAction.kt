package com.jack.lockdeck.action

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 一个"快捷动作"：控制中心磁贴、通知栏点击共用的定义。
 *
 * 用字符串 id 存储（SharedPreferences 里只存一个 id），
 * 所以以后加新动作不用改存储格式。
 *
 * id 约定：
 *   alipay_scan / wechat_scan / alipay_pay / wechat_pay  → 内置动作
 *   app:<packageName>                                    → 打开任意 App
 *   refresh                                              → 刷新天气（内置）
 */
object QuickAction {

    const val ALIPAY_SCAN = "alipay_scan"
    const val WECHAT_SCAN = "wechat_scan"
    const val ALIPAY_PAY = "alipay_pay"
    const val WECHAT_PAY = "wechat_pay"
    const val REFRESH = "refresh"

    private const val APP_PREFIX = "app:"

    // ---- 包名 ----
    const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
    const val PKG_WECHAT = "com.tencent.mm"
    const val PKG_WEATHER = "com.miui.weather2"

    // ---- 微信 ----
    // weixin://dl/scan 之类的深链在 8.x 上只会打开一个报错的 WebView，
    // 真正能用的是微信自己的动态快捷方式入口（实测已进扫一扫界面）：
    //   component = com.tencent.mm/.ui.LauncherUI
    //   action    = com.tencent.mm.ui.ShortCutDispatchAction
    //   extra     = LauncherUI.Shortcut.LaunchType = launch_type_scan_qrcode
    private const val WX_LAUNCHER = "com.tencent.mm.ui.LauncherUI"
    private const val WX_SHORTCUT_ACTION = "com.tencent.mm.ui.ShortCutDispatchAction"
    private const val WX_SHORTCUT_KEY = "LauncherUI.Shortcut.LaunchType"
    private const val WX_SCAN = "launch_type_scan_qrcode"
    private const val WX_PAY = "launch_type_offline_wallet"

    // ---- 支付宝 ----
    // saId=10000007 是扫一扫，10000003 是首页；两者都走 alipayqr scheme
    private const val ALIPAY_SCHEME = "alipayqr://platformapi/startapp?saId=%s"
    private const val ALIPAY_SA_SCAN = "10000007"

    fun openApp(pkg: String) = APP_PREFIX + pkg

    /** 动作对应的目标 App 包名（用来取图标）；刷新这种没有 App 的返回 null */
    fun packageOf(id: String): String? = when (id) {
        ALIPAY_SCAN, ALIPAY_PAY -> PKG_ALIPAY
        WECHAT_SCAN, WECHAT_PAY -> PKG_WECHAT
        REFRESH -> null
        else -> appPackageOf(id)
    }

    /** 磁贴角标类型：扫码 / 收付款 / 无 */
    fun badgeOf(id: String): String? = when (id) {
        ALIPAY_SCAN, WECHAT_SCAN -> "scan"
        ALIPAY_PAY, WECHAT_PAY -> "pay"
        else -> null
    }

    fun appPackageOf(id: String): String? =
        if (id.startsWith(APP_PREFIX)) id.removePrefix(APP_PREFIX) else null

    /** 所有内置动作，供设置页展示 */
    val presets: List<String> = listOf(
        ALIPAY_SCAN,
        WECHAT_SCAN,
        ALIPAY_PAY,
        WECHAT_PAY,
        REFRESH,
    )

    /** 磁贴上显示的短名字 */
    fun label(ctx: Context, id: String): String = when (id) {
        ALIPAY_SCAN -> "支付宝扫一扫"
        WECHAT_SCAN -> "微信扫一扫"
        ALIPAY_PAY -> "支付宝收付款"
        WECHAT_PAY -> "微信收付款"
        REFRESH -> "刷新天气"
        else -> appPackageOf(id)?.let { pkg -> appLabel(ctx, pkg) } ?: "未设置"
    }

    /** 磁贴副标题用的更短文案（宽度有限） */
    fun shortLabel(ctx: Context, id: String): String = when (id) {
        ALIPAY_SCAN -> "扫一扫"
        WECHAT_SCAN -> "扫一扫"
        ALIPAY_PAY -> "收付款"
        WECHAT_PAY -> "收付款"
        REFRESH -> "刷新"
        else -> appLabel(ctx, appPackageOf(id).orEmpty()).take(6)
    }

    private fun appLabel(ctx: Context, pkg: String): String {
        if (pkg.isBlank()) return "未设置"
        return runCatching {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    /**
     * 把动作翻译成要启动的 Intent。
     *
     * @param ctx 上下文
     * @param id  动作 id
     * @return 拿不到可启动目标时返回 null
     */
    fun intentFor(ctx: Context, id: String): Intent? {
        val pm = ctx.packageManager
        val raw: Intent? = when (id) {
            ALIPAY_SCAN -> alipayIntent(ALIPAY_SA_SCAN)
            ALIPAY_PAY -> alipayIntent(null) // 首页，收付款在首屏
            WECHAT_SCAN -> wechatShortcutIntent(WX_SCAN)
            WECHAT_PAY -> wechatShortcutIntent(WX_PAY)
            else -> appPackageOf(id)?.let { pm.getLaunchIntentForPackage(it) }
        }
        return raw?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
    }

    private fun alipayIntent(saId: String?): Intent? {
        val uri = if (saId == null) {
            "alipayqr://platformapi/startapp"
        } else {
            ALIPAY_SCHEME.format(saId)
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PKG_ALIPAY)
    }

    private fun wechatShortcutIntent(launchType: String): Intent =
        Intent(WX_SHORTCUT_ACTION).apply {
            setClassName(PKG_WECHAT, WX_LAUNCHER)
            putExtra(WX_SHORTCUT_KEY, launchType)
        }

    /** 目标 App 是否装在这台机器上 */
    fun isAvailable(ctx: Context, id: String): Boolean = when (id) {
        ALIPAY_SCAN, ALIPAY_PAY -> isInstalled(ctx, PKG_ALIPAY)
        WECHAT_SCAN, WECHAT_PAY -> isInstalled(ctx, PKG_WECHAT)
        REFRESH -> true
        else -> appPackageOf(id)?.let { isInstalled(ctx, it) } ?: false
    }

    private fun isInstalled(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0)
    }.isSuccess
}
