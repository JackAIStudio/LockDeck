package com.jack.lockdeck.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jack.lockdeck.R
import com.jack.lockdeck.action.QuickAction
import com.jack.lockdeck.data.Store
import com.jack.lockdeck.data.Weather
import com.jack.lockdeck.launch.LaunchTrampolineActivity

/**
 * 天气磁贴：控制中心里直接看温度。
 *
 * ## 为什么温度要画成图标
 *
 * HyperOS 的控制中心磁贴格子**只渲染图标，不渲染 label / subtitle**
 * （实测：label 设成 "32°" 什么都不显示，subtitle 也一样）。
 * 所以想在那里看到温度，唯一的办法是把数字**画进图标位图**：
 * `Icon.createWithBitmap()` + 自己 Canvas 画字。
 *
 * 这一点在 AOSP 原生控制中心上不一样（那边会显示 label），
 * 所以这里两种都设：文字画进图标（HyperOS 看得到），
 * label/subtitle 也照常填（原生 ROM 上看得到）。
 *
 * ## 颜色会被系统统一染色
 *
 * 实测 HyperOS 会把第三方磁贴图标整体染成纯白：画一个红绿蓝三色的图，
 * 控制中心里显示出来是全白。所以这里不要指望颜色，靠**形状和字形**区分。
 * 这也解释了为什么原来那几个扫一扫磁贴只能做成纯白剪影。
 *
 * 天气数据不在这里抓：onStartListening 会被系统高频调用，抓一次就是一次网络请求。
 * 数据由 [com.jack.lockdeck.notify.WeatherForegroundService] 定时写进缓存，这里只读取。
 *
 * ## 点一下是「刷新」，不再跳走
 *
 * 老版本点一下就跳天气 App。问题很实际：**下拉控制中心是为了看一眼，不是为了切应用**。
 * 点一下把整个面板收掉、跳走，看完还得退回来，反而是多了一步。
 *
 * 现在点一下 = 立刻刷一次天气，人就站在控制中心里，数字自己会变。
 *
 * 代价是刷新那 1~3 秒里数字不动，看起来像没反应。所以点下去先把磁贴切成
 * `STATE_ACTIVE`（图标变亮），刷完再落回 `STATE_INACTIVE`——这点视觉反馈
 * 是这个交互能不能被理解的关键，不能省。
 *
 * 想进天气 App 有两条现成的路：点通知栏那条天气（整条通知绑的就是打开 App），
 * 或者长按（只在原生 Android 上有效，HyperOS 不上报长按，见 [onStartCommand]）。
 */
class WeatherTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // 系统把它挂到控制中心、即将显示时必然走这里——借此确认「磁贴还在用」，
        // 通知栏关掉后由 Worker 继续刷新它。
        WeatherTileInUse.mark(this)
        refreshTile()
    }

    /**
     * 用户把它拖进控制中心。这是**确切**的「被添加」信号。
     */
    override fun onTileAdded() {
        super.onTileAdded()
        WeatherTileInUse.mark(this)
        refreshTile()
    }

    /**
     * 用户把它从控制中心移除。
     *
     * ## 为什么必须实现
     *
     * 老版本只靠 onStartListening 打「还在用」的标记，一旦置 true 就再也
     * 变不回 false。用户把天气按钮拖走之后，App 仍然以为有人在看，
     * 于是继续每 5 分钟联网定位一次——纯粹白耗电。
     *
     * 这个回调是系统明确告诉我们「已经移除了」的唯一时机，必须接住。
     * 注意别在这里直接 stopService：通知栏可能还开着，那条刷新链路要留着。
     * 只清标记，由 [com.jack.lockdeck.notify.WeatherWorker] 那边按需决定。
     */
    override fun onTileRemoved() {
        super.onTileRemoved()
        WeatherTileInUse.clear(this)
    }

    override fun onClick() {
        super.onClick()
        // 先给视觉反馈：点下去这一下必须看得见，否则用户会以为没反应、再点一次。
        qsTile?.let {
            it.state = Tile.STATE_ACTIVE
            it.updateTile()
        }

        // 真正干活的是前台服务里那条刷新链路，和通知里的「立即刷新」是同一条路。
        // 这里自己开线程也能刷，但那样会出现「磁贴刷一次、定时器又刷一次」，
        // 两次请求互相覆盖缓存。
        runCatching { com.jack.lockdeck.notify.WeatherForegroundService.refreshNow(this) }

        // 刷新是网络请求，1~3 秒。等它写完缓存再重画，别立刻读旧值。
        mainHandler.postDelayed({ refreshTile() }, TILE_REFRESH_GRACE_MS)
    }

    /**
     * 长按磁贴 → 打开天气 App。
     *
     * ## 这条路在 HyperOS 上不通（真机验证过）
     *
     * `TileService` **没有** `onLongClick()`——编译直接报 "overrides nothing"，
     * 翻 `android.jar` 的方法表也确实只有 onClick / onStartListening /
     * onStopListening / onTileAdded / onTileRemoved。
     *
     * 官方给的长按出口是 `QS_TILE_PREFERENCES` 广播，已经按文档注册了
     * （见 AndroidManifest 里这个 service 的第二个 intent-filter，
     * `dumpsys package` 能查到确实注册上了）。但 HyperOS 3 实测**根本不发**：
     * 长按第三方磁贴，系统直接弹它自己的「应用信息」页
     * （`com.miui.securitycenter/ApplicationsDetailsActivity`），
     * 那条广播一次都没出现。手动 `am startservice -a ...QS_TILE_PREFERENCES`
     * 能进下面这个分支，说明代码没问题，是系统不走这条路。
     *
     * 所以这个入口留着（原生 Android 上有效），但**不能指望它**：
     * 想看 15 天预报就点通知栏那条天气、或者直接开 App。
     * 天气按钮只负责一件事——点一下刷新，数字当场变。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QS_TILE_PREFERENCES) {
            openWeatherApp()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** 跳天气 App。锁屏上直接 startActivity 会被拦，必须走中间页。 */
    private fun openWeatherApp() {
        val trampoline = Intent(this, LaunchTrampolineActivity::class.java).apply {
            putExtra(LaunchTrampolineActivity.EXTRA_ACTION, QuickAction.openApp(QuickAction.PKG_WEATHER))
            putExtra(LaunchTrampolineActivity.EXTRA_ZONE, "weatherTile")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 2001, trampoline,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 点了之后等多久再重画磁贴。
     *
     * 2.5 秒覆盖一次正常刷新（定位 + 天气 + 降水三个请求）。等不到也没关系：
     * 刷新完成时 `WeatherForegroundService` 会调 `WeatherTileInUse.requestUpdate`，
     * 那次才是准的。这里只是保证「通常能立刻看到数字变」。
     */
    private val TILE_REFRESH_GRACE_MS = 2_500L

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val w = Store.loadWeather(this)

        if (w == null) {
            tile.label = "天气"
            tile.state = Tile.STATE_UNAVAILABLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 把真实原因说出来：没有定位权限 / 还没定位，别让用户猜
                tile.subtitle = Store.getError(this) ?: "暂无数据"
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_weather)
        } else {
            val rain = w.nextRain
            tile.label = if (rain != null) "${w.tempNow}° ${rain.headline}"
            else "${w.tempNow}° ${w.description}"
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // subtitle 在 HyperOS 上不显示，但原生 Android 和 MIUI 编辑器里能看到，
                // 所以照填——这里放的是比「温度」更值得占用一行的那句话。
                // 高低温不写在这里：它已经是隔壁那个按钮的全部内容（RangeTileService）。
                // 两个地方都说同一件事，用户会以为看到了两条重复信息。
                tile.subtitle = if (w.place.isNotBlank()) w.description + " · " + w.place
                else w.description
            }
            // 关键：内容画进位图。HyperOS 只认图标这一层（见类注释）。
            tile.icon = Icon.createWithBitmap(contentBitmap(w))
        }
        tile.updateTile()
    }

    /**
     * 把「当前温度」画成图标位图——只画这一个数。
     *
     * ## 为什么把高低温挪走了
     *
     * 原来这里塞了三样：当前温度、今日最低、今日最高。在 96px 的框里，
     * 高低温只能压到 **11px 高**，一瞥根本读不出来——那行字等于白占地方。
     *
     * 格子的面积是系统定死的（见下面那张实测表），想更大只能**多占一格**。
     * 所以高低温独立成了 [RangeTileService]：这边只留当前温度，字号顶到
     * 满高的 80%；那边两行数字各占半格，字号一样大。代价是控制中心多一格——
     * 这是权衡过的取舍，不是实现不了。
     *
     * ## 尺寸是实测的，三个数字定死了版式
     *
     * 用「涂满一块、看系统画出来多大」的校准图量过（HyperOS 3 / Redmi Turbo 5）：
     *
     * | 事实 | 实测 |
     * |---|---|
     * | 图标可用区 | **96×96px** 的正方形，居中在 220px 的磁贴圆圈里 |
     * | 会不会裁 | 不裁，四角完整 |
     * | 非正方形位图 | **被强行压成正方形**（384×192 的图渲染成 96×96），不是等比缩放，也不是按宽度铺满 |
     * | 颜色 | 纯红渲染成纯白——整个位图被染成单色，彩色没有意义 |
     * | alpha | **保留**。128 的半透明白渲染成浅灰 |
     *
     * 前三条合起来是硬上限：**能用的就这 96×96**，做不成参考图那种宽卡片
     * （要不要宽卡片得看 MIUI 给不给第三方开双格大磁贴，客户端决定不了）。
     * 最后一条是白捡的：正因为 alpha 活着，想画「浅一档」的东西才画得出来。
     *
     * 版式因此是**按 96px 反推**的，不是照抄设计稿比例。
     */
    private fun contentBitmap(w: Weather): Bitmap {
        // 直接按系统的 96px 画，再交给它 1:1 缩放。画大再缩没有额外收益
        // （实测 192 和 384 出来都是 96），反而多一次重采样把边缘磨糊。
        val size = TILE_ICON_PX
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size * 0.5f
        val cy = size * 0.5f

        // ---- 当前温度：只有数字 ----
        //
        // 不画「°」：控制中心这一格本来就知道是温度，那个小圈除了占宽度
        // 没有别的用处——去掉之后宽度约束整个消失，字号只受格子高度限制，
        // 能一直顶到满格。
        //
        // 用**字形紧边界**算尺寸和居中（getTextBounds），不是 fontMetrics：
        // fontMetrics 给的是整行行距，里面一大截是留白，拿它限制高度会让
        // 数字白白小一圈（高低温那格实测就掉到 24px，改紧边界后翻倍）。
        val nowText = w.tempNow.toString()
        val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = size * 1.10f
        }
        val box = android.graphics.Rect()
        fun measure(p: Paint, t: String): Pair<Float, Float> {
            p.getTextBounds(t, 0, t.length, box)
            return p.measureText(t) to box.height().toFloat()
        }
        // 只受两条上限约束：宽度 96% 内、高度 86% 内。谁先到顶听谁的。
        val roomW = size * 0.96f
        val roomH = size * 0.86f
        val (wNow, hNow) = measure(nowPaint, nowText)
        if (wNow > roomW) nowPaint.textSize *= roomW / wNow
        val hAfterW = measure(nowPaint, nowText).second
        if (hAfterW > roomH) nowPaint.textSize *= roomH / hAfterW

        // 基线按紧边界算：让字形本身落在格子正中，而不是让行距框居中。
        nowPaint.getTextBounds(nowText, 0, nowText.length, box)
        canvas.drawText(
            nowText,
            cx,
            cy - (box.top + box.bottom) / 2f,
            nowPaint,
        )

        return bmp
    }

    companion object {
        /**
         * 系统给图标的方框，实测 96px。
         *
         * 不是 dp、也不能靠密度换算——它就钉死在这个像素数上，
         * 所以位图直接按 96 画，别画大再缩。
         *
         * 公开给 [RangeTileService] 用：两个按钮的图标框是同一个大小，
         * 各写一个 96 迟早会分叉。
         */
        const val TILE_ICON_PX = 96
    }
}
