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

    private val watchdog = object : Runnable {
        override fun run() {
            // App 里改了位置/设置会写 bump，这里立刻响应，不用等下一次锁屏
            val bump = Store.getOverlayBump(this@LockScreenOverlayService)
            if (bump != lastBump) {
                lastBump = bump
                if (overlayView != null) {
                    runCatching { reposition(force = true) }
                    runCatching { updateContent() }
                }
            }
            evaluate()
            main.postDelayed(this, 1500)
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
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF,
                    Intent.ACTION_USER_PRESENT,
                    -> evaluate()
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
    private fun evaluate() {
        main.post {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val locked = km?.isKeyguardLocked == true
            val awake = pm?.isInteractive == true
            val enabled = Store.isOverlayEnabled(this)
            android.util.Log.i("LSW", "evaluate locked=$locked awake=$awake enabled=$enabled")

            if (enabled && locked && awake) {
                runCatching { show() }
                    .onFailure { Store.setError(this, "show异常: ${it.javaClass.simpleName}") }
                runCatching { reposition() }
                    .onFailure { android.util.Log.w("LSW", "reposition failed", it) }
                // 兜底：如果该显示却没有窗口，重建一次
                if (overlayView == null) {
                    runCatching { show() }
                    Store.setError(this, "overlay为空，已尝试重建")
                }
            } else {
                hide()
            }
        }
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
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
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

        root.findViewById<TextView>(R.id.ov_temp)?.text = w?.let { "${it.tempNow}°" } ?: "--°"
        root.findViewById<TextView>(R.id.ov_icon)?.text = w?.icon ?: "…"
        root.findViewById<TextView>(R.id.ov_range)?.text =
            w?.let { "${it.tempMin}° ~ ${it.tempMax}°" } ?: "--° ~ --°"
        root.findViewById<TextView>(R.id.ov_desc)?.text =
            w?.let { wi ->
                wi.description + if (wi.city.isNotBlank()) " · ${wi.city}" else ""
            } ?: "正在获取天气"

        // 降雨提示
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

        // 更新时间戳（小字，在左下）
        val hintView = root.findViewById<TextView>(R.id.ov_hint)
        if (hintView != null && w != null) {
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            hintView.text = "更新 " + fmt.format(java.util.Date(w.updatedAt))
            hintView.visibility = View.VISIBLE
        }
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
     * 左右两个大区，各绑一个 App。
     * 左：用户配置的 main（默认小米天气）
     * 右：用户配置的 right（默认闹钟）
     */
    private fun bindClick(v: View) {
        v.findViewById<View>(R.id.ov_zone_left)?.setOnClickListener {
            android.util.Log.i("LSW", "tap LEFT")
            launch(Store.getTarget(this, "main"), WEATHER_PKG)
        }
        v.findViewById<View>(R.id.ov_zone_right)?.setOnClickListener {
            android.util.Log.i("LSW", "tap RIGHT")
            launch(Store.getTarget(this, "right"), CLOCK_PKG)
        }
    }

    private companion object {
        const val WEATHER_PKG = "com.miui.weather2"
        const val CLOCK_PKG = "com.android.deskclock"
    }

    /**
     * 打开目标 App。优先用用户配置的包名，失败再回落到默认值。
     * 从锁屏启动会先要求解锁，这是系统安全策略。
     */
    private fun launch(configured: String?, fallback: String) {
        val candidates = listOfNotNull(configured, fallback, WEATHER_PKG).distinct()
        val pkg = candidates.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
        if (pkg == null) {
            android.util.Log.e("LSW", "no target app available")
            return
        }
        android.util.Log.i("LSW", "launch via trampoline -> $pkg")
        // 走透明中间页：已解锁直接跳，锁着则请求解锁后自动跳，避免"点了不反应"
        val i = Intent(this, LaunchTrampolineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(LaunchTrampolineActivity.EXTRA_PKG, pkg)
        }
        runCatching { startActivity(i) }
            .onFailure { android.util.Log.e("LSW", "trampoline failed", it) }
    }
}
