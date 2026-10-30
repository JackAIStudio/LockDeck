package com.jack.lockscreenweather.overlay

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.jack.lockscreenweather.R
import com.jack.lockscreenweather.data.Store
import com.jack.lockscreenweather.data.Weather

/**
 * 锁屏天气浮层。
 *
 * 为什么用 AccessibilityService：
 * 普通通知会被 MIUI 的 miui.enableKeyguard=false 挡在锁屏外，
 * 而 TYPE_ACCESSIBILITY_OVERLAY 是 Android 专门允许覆盖锁屏的窗口类型，
 * 不需要 root（LockscreenWidgets 也是这么做的）。
 *
 * 关键点：
 * - 只在「锁屏 + 亮屏」时显示
 * - 解锁后必须立刻移除，否则会盖住系统中的任何界面
 * - FLAG_NOT_FOCUSABLE 保证不抢输入焦点（不能盖住 PIN 键盘）
 *
 * 已知限制（HyperOS 3 / Redmi Turbo 5 实测）：
 * 锁屏状态下 SystemUI 的 NotificationShade 会独占输入焦点（dumpsys input 里
 * FocusedWindows 一直是 NotificationShade），第三方浮层虽然显示在最上层，
 * 但收不到任何触摸事件 —— 加 FLAG_TOUCHABLE_WHEN_WAKING 也没用。
 * 所以这张卡片是「只读展示」：看得到天气，点不动。
 * 要真正一键直达，用通知栏按钮或控制中心磁贴。
 */
class LockScreenOverlayService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val main = Handler(Looper.getMainLooper())

    private var receiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        Store.setA11yConnected(this, true)
        registerScreenReceiver()
        startWatchdog()
        evaluate()
    }

    /**
     * 兜底轮询：无障碍事件偶尔会漏（锁屏切换、系统吞事件），
     * 每 2 秒校一次状态，避免出现"该显示却没显示"。
     */
    private var lastBump = 0L

    /** 本次亮屏后是否已经解锁过 */
    @Volatile private var unlockedSinceScreenOn = false

    /** 是否已经解锁过（跨亮屏周期保留，灭屏时重置） */
    @Volatile private var hasUserPresent = false

    /** 最近一次 evaluate 的时间，用于判断轮询是否还活着 */
    @Volatile private var lastHeartbeat = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            // 整个循环包在 try/catch 里，任何异常都不能让轮询断掉。
            // 之前 evaluate() 抛异常会把 postDelayed 一起跳过，轮询就永久停止，
            // 表现就是"卡片突然消失，必须重开 App 才恢复"。
            try {
                val bump = Store.getOverlayBump(this@LockScreenOverlayService)
                if (bump != lastBump) {
                    lastBump = bump
                    if (overlayView != null) {
                        runCatching { reposition(force = true) }
                        runCatching { updateContent() }
                    }
                }
                evaluate()
            } catch (t: Throwable) {
                android.util.Log.e("LSW", "watchdog 异常，异常已吞掉继续轮询", t)
            } finally {
                // 无论如何都要排下一次
                main.removeCallbacks(this)
                main.postDelayed(this, 1500)
            }
        }
    }
    private fun startWatchdog() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 2000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 锁屏/解锁、亮屏/灭屏都会触发窗口变化，据此决定显隐
        // 每次事件都先自检：服务实例可能被系统重建过，windowManager 会失效
        if (windowManager == null) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            Store.setError(this, "自愈: 重新获取 windowManager")
        }
        // 窗口被系统回收时 overlayView 会变成"幽灵"，重建一次
        if (overlayView != null && overlayView!!.parent == null) {
            android.util.Log.w("LSW", "overlay 已脱离，重建")
            overlayView = null
        }
        startWatchdog()
        evaluate()
    }

    override fun onInterrupt() {
        hide()
    }

    override fun onDestroy() {
        super.onDestroy()
        Store.setA11yConnected(this, false)
        main.removeCallbacks(watchdog)
        hide()
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
    }

    private fun registerScreenReceiver() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        // 解锁成功：此后下拉通知栏/控制中心都算已解锁
                        unlockedSinceScreenOn = true
                        hasUserPresent = true
                        android.util.Log.i("LSW", "USER_PRESENT -> unlocked")
                        evaluate()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        // 灭屏：下一次亮屏重新从锁定状态开始
                        unlockedSinceScreenOn = false
                        hasUserPresent = false
                        android.util.Log.i("LSW", "SCREEN_OFF -> reset lock state")
                        evaluate()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        // 亮屏时还没解锁
                        unlockedSinceScreenOn = false
                        android.util.Log.i("LSW", "SCREEN_ON")
                        evaluate()
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, f)
        }
    }

    /** 根据「是否锁屏 + 是否亮屏 + 用户是否开启」决定显示还是隐藏 */
    /**
     * 决定显示还是隐藏。
     *
     * 判断依据只用 KeyguardManager.isKeyguardLocked。
     * 它是唯一语义明确的信号；"前台包名是不是 systemui" 不可用，
     * 因为主屏幕、计算器这些界面同样可能被系统标记成 systemui 相关，
     * 会导致卡片在桌面和别的 App 上一直显示。
     */
    private fun evaluate() {
        main.post {
          lastHeartbeat = System.currentTimeMillis()
          runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            // isInteractive 在 AOD（息屏显示）下仍是 true，卡片会浮在黑屏上。
            // 用"屏幕是否处于全亮"再兜一层。
            val interactive = pm?.isInteractive == true
            val awake = interactive && !isAodShowing()
            val enabled = Store.isOverlayEnabled(this)
            val locked = isKeyguardLockedNow()
            android.util.Log.i("LSW", "evaluate locked=$locked awake=$awake enabled=$enabled")

            // 必须同时满足：开关开、屏幕亮、确实在锁屏。
            // awake 里额外排掉 AOD —— isInteractive 在息屏显示下仍为 true，
            // 只判它会让卡片浮在黑屏上。
            if (enabled && awake && locked) {
                runCatching { show() }
                    .onFailure { Store.setError(this, "show异常: ${it.javaClass.simpleName}") }
                runCatching { reposition() }
                    .onFailure { android.util.Log.w("LSW", "reposition failed", it) }
                if (overlayView == null) runCatching { show() }
            } else {
                hide()
            }
          }.onFailure {
              android.util.Log.e("LSW", "evaluate 异常", it)
          }
        }
    }

    /**
     * 是否处于 AOD（息屏显示）。
     *
     * HyperOS 的 AOD 由 com.miui.aod 绘制，此时 PowerManager.isInteractive 仍为 true，
     * 但用户看到的是黑底时钟，不该叠我们的卡片。
     */
    private fun isAodShowing(): Boolean = runCatching {
        val ws = windows ?: return@runCatching false
        ws.any { w ->
            w.root?.packageName?.toString() == "com.miui.aod" && w.isActive
        }
    }.getOrDefault(false)

    /**
     * 读取锁屏状态。
     *
     * HyperOS 3 上 KeyguardManager 偶发返回 false（系统层却是 true），
     * 这里做一次"设备是否真的锁着"的互补判断：
     * 只有当 KeyguardManager 说未锁、且当前确实处于某个应用界面时，
     * 才认定已解锁。这样不会因为偶发 false 而误隐藏。
     */
    /**
     * 是否处于锁屏状态。
     *
     * 实测结论（HyperOS 3 / Redmi Turbo 5）：
     *  - KeyguardManager.isKeyguardLocked：解锁后下拉通知栏仍返回 true，不可靠
     *  - ACTION_USER_PRESENT：服务重建后收不到，不可靠
     *  - 前台包名：桌面是 com.miui.home；锁屏和下拉都是 com.android.systemui，
     *    无法用包名区分锁屏和下拉
     *
     * 可行的判据：看 systemui 窗口里到底有没有锁屏内容。
     *  - 有锁屏时钟/bouncer/锁屏快捷方式 => 真锁屏，显示
     *  - 只有快捷开关磁贴/通知列表       => 已解锁后下拉，隐藏
     */
    private fun isKeyguardLockedNow(): Boolean {
        return runCatching {
            val ws = windows
            if (ws.isNullOrEmpty()) {
                // 拿不到窗口，按系统说法走
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                return@runCatching km?.isKeyguardLocked == true
            }

            // 若当前正在用具体 App（非 systemui），肯定已解锁
            val focusedPkg = ws.firstOrNull { it.isFocused }
                ?.root?.packageName?.toString().orEmpty()
            if (focusedPkg.isNotEmpty() &&
                focusedPkg != "com.android.systemui" &&
                focusedPkg != "com.miui.aod"
            ) {
                return@runCatching false
            }

            // 处在 systemui 窗口：检查是不是真正的锁屏
            for (w in ws) {
                val root = w.root ?: continue
                if (hasKeyguardContent(root)) return@runCatching true
            }
            false
        }.getOrDefault(false)
    }

    /** 窗口里是否有锁屏专属元素 */
    private fun hasKeyguardContent(root: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 250) {
            val n = queue.removeFirst()
            val id = n.viewIdResourceName.orEmpty()
            if (id.contains("keyguard", true) ||
                id.contains("bouncer", true) ||
                id.contains("lock_screen", true)
            ) {
                return true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    private fun show() {
        val wm = windowManager
        if (wm == null) {
            Store.setError(this, "windowManager is null")
            return
        }
        if (overlayView != null) {
            updateContent()
            return
        }
        val themed = ContextThemeWrapper(this, R.style.Theme_LockOverlay)
        // 显式传 parent=null 但手动设 LayoutParams，避免高度被系统忽略
        val v = LayoutInflater.from(themed).inflate(R.layout.overlay_weather, null)
        // 固定高度：实测 WRAP_CONTENT 在 accessibility overlay 下会被拉伸到整屏
        val density = resources.displayMetrics.density
        val cardHeight = (112 * density).toInt()
        v.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            cardHeight,
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            cardHeight,
            // 关键：这个类型允许盖在锁屏之上
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                // 跟 NotificationShade 用同一个标志，让窗口在锁屏亮屏期间也算"可交互"。
                // 实测（HyperOS 3）这并不能把触摸从 SystemUI 手里抢回来，
                // 但加上它不影响显示，留着给可能支持该行为的机型。
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 放在时钟下方，避免遮挡时间和日期
            y = Store.getOverlayY(this@LockScreenOverlayService)  // 通知区下方，避免和锁屏通知重叠
        }

        bindClick(v)
        enableDrag(v, wm)
        runCatching { wm.addView(v, params) }
            .onSuccess {
                overlayView = v
                android.util.Log.i("LSW", "overlay added OK")
                updateContent()
            }
            .onFailure {
                android.util.Log.e("LSW", "overlay addView FAILED", it)
                Store.setError(this, "addView失败: ${it.javaClass.simpleName}: ${it.message}")
            }
    }

    /**
     * 避开锁屏通知：读取通知区域的底边，把卡片挪到它下面。
     * 通知会随消息增多而变高，所以每次状态变化都重新计算。
     */
    private fun reposition(force: Boolean = false) {
        val v = overlayView ?: return
        val wm = windowManager ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return

        val minTop = Store.getOverlayY(this)   // 基准位置（时钟下方）

        // 只有 App 里显式设置（force）时才用固定值；平时一律自动避让
        if (force) {
            if (lp.y != minTop) {
                lp.y = minTop
                runCatching { wm.updateViewLayout(v, lp) }
            }
            return
        }

        val notifBottom = lockScreenNotificationBottom()
        val targetY = if (notifBottom > 0) maxOf(minTop, notifBottom + dp(12)) else minTop

        if (lp.y != targetY) {
            lp.y = targetY
            runCatching { wm.updateViewLayout(v, lp) }
            android.util.Log.i("LSW", "reposition y=$targetY (notifBottom=$notifBottom)")
        }
    }

    /**
     * 锁屏通知区的底边（屏幕坐标）。
     *
     * 用小米锁屏通知的稳定资源 id 精确定位：
     *   notification_title / notification_text  -> 普通通知
     *   zen_title / zen_quit                    -> 勿扰提示
     *   header_title                            -> 媒体播放器标题
     * 这比遍历所有文本猜高度可靠得多。
     */
    private fun lockScreenNotificationBottom(): Int {
        val root = rootInActiveWindow ?: return 0
        var maxBottom = 0
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        val screenH = screenHeight()

        while (queue.isNotEmpty() && guard++ < 800) {
            val n = queue.removeFirst()
            val id = n.viewIdResourceName.orEmpty()
            val known = id.endsWith("notification_title") ||
                id.endsWith("notification_text") ||
                id.endsWith("zen_title") ||
                id.endsWith("zen_quit") ||
                id.endsWith("header_title") ||
                id.endsWith("notification_icon") ||
                id.endsWith("notification_time")

            if (known) {
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                if (r.top > screenH * 0.08 && r.bottom < screenH * 0.72 && r.height() > 0) {
                    if (r.bottom > maxBottom) maxBottom = r.bottom
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return maxBottom
    }

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun hide() {
        val wm = windowManager ?: return
        overlayView?.let { v -> runCatching { wm.removeView(v) } }
        overlayView = null
    }

    private fun updateContent() {
        val root = overlayView ?: return
        val w = Store.loadWeather(this)

        // ---- 第一行：图标 + 当前温度 + 天气 ----
        root.findViewById<TextView>(R.id.ov_icon)?.text = w?.icon ?: "…"
        root.findViewById<TextView>(R.id.ov_temp)?.text = w?.let { "${it.tempNow}°" } ?: "--°"
        root.findViewById<TextView>(R.id.ov_desc)?.text =
            w?.let { it.description } ?: "获取中"

        // ---- 第二行：最高最低 + 城市 + 更新时间（合并成一行，不重复） ----
        val rangeView = root.findViewById<TextView>(R.id.ov_range)
        if (rangeView != null) {
            rangeView.text = if (w == null) {
                "正在获取天气"
            } else {
                val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                val parts = mutableListOf<String>()
                parts.add("${w.tempMin}°~${w.tempMax}°")
                if (w.city.isNotBlank()) parts.add(w.city)
                parts.add(fmt.format(java.util.Date(w.updatedAt)))
                parts.joinToString(" · ")
            }
        }

        // ---- 降雨提示 ----
        val rainView = root.findViewById<TextView>(R.id.ov_rain)
        val hint = w?.takeIf { Store.isRainHintEnabled(this) }?.umbrellaHint
        if (rainView != null) {
            if (hint.isNullOrBlank()) {
                rainView.visibility = View.GONE
            } else {
                rainView.visibility = View.VISIBLE
                rainView.text = "☂ " + hint
            }
        }

        // ---- 两个快捷按钮：显示目标动作的短名字 ----
        bindAppButton(root, R.id.ov_app1_name, R.id.ov_app1_icon, "app1", DEFAULT_ACTION1)
        bindAppButton(root, R.id.ov_app2_name, R.id.ov_app2_icon, "app2", DEFAULT_ACTION2)
    }

    /** 把一个按钮的文案换成目标动作的短名字 */
    private fun bindAppButton(
        root: View,
        nameId: Int,
        iconId: Int,
        zone: String,
        fallback: String,
    ) {
        val id = Store.getTarget(this, zone) ?: fallback
        val qa = com.jack.lockscreenweather.action.QuickAction
        root.findViewById<TextView>(nameId)?.text = qa.shortLabel(this, id)
        root.findViewById<TextView>(iconId)?.text = qa.glyph(this, id)
    }

    /** 长按卡片进入拖动模式，松手后记住位置 */
    private fun enableDrag(v: View, wm: WindowManager) {
        var downY = 0f
        var startWin = 0
        var dragging = false

        v.setOnLongClickListener {
            dragging = true
            startWin = (v.layoutParams as WindowManager.LayoutParams).y
            true
        }

        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
                        val ny = startWin + (ev.rawY - downY).toInt()
                        lp.y = ny.coerceIn(0, screenHeight() - dp(140))
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        val lp = v.layoutParams as? WindowManager.LayoutParams
                        if (lp != null) {
                            Store.setOverlayY(this, lp.y)
                            Store.markCustomY(this)
                            android.util.Log.i("LSW", "拖动后记住 y=${lp.y}")
                        }
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    /**
     * 三个点击区，各自绑一个动作（见 QuickAction）：
     *   天气区 -> 默认小米天气
     *   按钮1  -> 默认支付宝扫一扫
     *   按钮2  -> 默认微信扫一扫
     * 三者的目标都可以在 App 里改。
     */
    private fun bindClick(v: View) {
        v.findViewById<View>(R.id.ov_zone_weather)?.setOnClickListener {
            android.util.Log.i("LSW", "tap WEATHER")
            runAction("main", "app:" + WEATHER_PKG)
        }
        v.findViewById<View>(R.id.ov_zone_app1)?.setOnClickListener {
            android.util.Log.i("LSW", "tap APP1")
            runAction("app1", DEFAULT_ACTION1)
        }
        v.findViewById<View>(R.id.ov_zone_app2)?.setOnClickListener {
            android.util.Log.i("LSW", "tap APP2")
            runAction("app2", DEFAULT_ACTION2)
        }
    }

    /** 用户配了就用用户配的，没配就落到默认动作 */
    private fun runAction(zone: String, fallback: String) {
        val id = Store.getTarget(this, zone) ?: fallback
        android.util.Log.i("LSW", "zone $zone -> $id")
        com.jack.lockscreenweather.action.ActionRunner.run(this, zone, id)
    }

    private companion object {
        const val WEATHER_PKG = com.jack.lockscreenweather.action.QuickAction.PKG_WEATHER
        const val DEFAULT_ACTION1 = com.jack.lockscreenweather.action.QuickAction.ALIPAY_SCAN
        const val DEFAULT_ACTION2 = com.jack.lockscreenweather.action.QuickAction.WECHAT_SCAN
    }

}
