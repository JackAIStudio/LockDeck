package com.jack.lockdeck.tile

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
import com.jack.lockdeck.data.Store
import com.jack.lockdeck.data.Weather

/**
 * 高低温按钮：控制中心里专门显示「今日最高 / 最低」的那一格。
 *
 * ## 为什么把它和天气按钮拆开
 *
 * 原来一个按钮里塞了三样东西：当前温度、今日最低、今日最高。结果是在
 * 96px 的图标框里，高低温只能压到 11px 高——一瞥根本读不出来。
 *
 * 系统的硬上限是**一格 96×96px**（见 [WeatherTileService] 里的实测表），
 * 一个格子的面积不会因为你想塞多少东西而变大。所以唯一的出路是**多占一格**：
 * 当前温度独占一格保持大号，高低温另起一格，于是每行数字都能拿到半格高度，
 * 字号直接翻倍。这是一个纯粹的取舍——用一格控制中心位置，换「看得清」。
 *
 * ## 版式：只有数字
 *
 * ```
 *   31     <- 今日最高（纯白）
 *   18     <- 今日最低（暗一档）
 * ```
 *
 * 试过 `↑ 31 / ↓ 18`。箭头确实能说明谁高谁低，但它吃掉横向三分之一的位置，
 * 而这格最缺的就是宽度——删掉之后数字高度从 30px 涨到 41px。控制中心是「扫一眼」
 * 的场景，**大就是清楚**，符号只会跟数字抢注意力。
 *
 * 去掉箭头后靠两件事区分高低：
 *
 * - **位置**：上高下低，符合「先高后低」的读数习惯
 * - **亮度**：最高温纯白、最低温暗一档。系统会把整张位图染成单色，但
 *   **alpha 是保留的**（见 [WeatherTileService] 的实测表），所以这条画得出来
 *
 * 数字按**两行里更宽的那一行**统一收字号，保证零下（-15）和两位数（31）
 * 都不会把字顶出边框。
 */
class RangeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        WeatherTileInUse.markRange(this)
        refreshTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        WeatherTileInUse.markRange(this)
        refreshTile()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        // 只清**自己**这一本账。天气按钮可能还在屏幕上，
        // 清共享标记会把它的定时刷新一起停掉（见 [WeatherTileInUse] 的说明）。
        WeatherTileInUse.clearRange(this)
    }

    /**
     * 点一下：刷新天气，数字自己会变。
     *
     * 和天气按钮同一个动作——两个按钮看的是同一份缓存，刷新自然也该是同一次。
     * 这里不复用 [WeatherTileService] 的实现，是因为 refreshTile 要读自己的位图；
     * 但「怎么刷」走的是同一条前台服务链路，不会重复发请求。
     */
    override fun onClick() {
        super.onClick()
        qsTile?.let {
            it.state = Tile.STATE_ACTIVE
            it.updateTile()
        }
        runCatching { com.jack.lockdeck.notify.WeatherForegroundService.refreshNow(this) }
        mainHandler.postDelayed({ refreshTile() }, TILE_REFRESH_GRACE_MS)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 与天气按钮一致：等一次正常刷新（定位 + 天气 + 降水）的时间。 */
    private val TILE_REFRESH_GRACE_MS = 2_500L

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val w = Store.loadWeather(this)

        if (w == null) {
            tile.label = "高低温"
            tile.state = Tile.STATE_UNAVAILABLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = Store.getError(this) ?: "暂无数据"
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_range)
        } else {
            tile.label = "${w.tempMax}° / ${w.tempMin}°"
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 原生 Android 上 subtitle 是可见的，写成人话；
                // HyperOS 不显示它，但填了也不碍事。
                tile.subtitle = buildString {
                    append("今日最高 ").append(w.tempMax).append("° · 最低 ").append(w.tempMin).append("°")
                    if (w.place.isNotBlank()) append(" · ").append(w.place)
                }
            }
            tile.icon = Icon.createWithBitmap(contentBitmap(w))
        }
        tile.updateTile()
    }

    /**
     * 把「最高 / 最低」画成图标位图——只有两行数字。
     *
     * 版式与取舍见类注释；尺寸依据见 [WeatherTileService] 的实测表。
     * 具体的坑都写在函数体里对应那几行旁边。
     */
    private fun contentBitmap(w: Weather): Bitmap {
        val size = WeatherTileService.TILE_ICON_PX
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val maxText = w.tempMax.toString()
        val minText = w.tempMin.toString()

        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = size * 0.55f
        }
        // 关键：量高度要用**字形紧边界**（getTextBounds），不是 fontMetrics。
        //
        // fontMetrics 带的是整行行距（ascent 到 descent），里面有一大截是留白；
        // 拿它当高度上限，字号会被算小一大半——实测就是这么掉到 24px 高的
        // 。数字才是这格的内容，留白不该占它的预算。
        val tight = android.graphics.Rect()
        fun tightHeight(paint: Paint, s: String): Float {
            paint.getTextBounds(s, 0, s.length, tight)
            return tight.height().toFloat()
        }

        val roomWidth = size * 0.94f
        val roomHeight = size * 0.41f     // 两行各占一半，单行上限（留出中间的空隙）
        val widest = maxOf(numberPaint.measureText(maxText), numberPaint.measureText(minText))
        if (widest > roomWidth) numberPaint.textSize *= roomWidth / widest
        val tallest = maxOf(tightHeight(numberPaint, maxText), tightHeight(numberPaint, minText))
        if (tallest > roomHeight) numberPaint.textSize *= roomHeight / tallest

        // 两行的垂直中心：23.5% / 76.5%。
        //
        // 用紧边界算基线（不是 fontMetrics），让字形本身落在中心——
        // 按行距框居中会整体偏下，因为行距留白上下不对称。
        //
        // 取 23.5/76.5 而不是 26/74：数字放大到 41px 之后，原来的间距只剩 5px，
        // 两行几乎粘在一起，得再拉开一点。
        for ((text, centerY, alpha) in listOf(
            Triple(maxText, size * 0.235f, 255),
            Triple(minText, size * 0.765f, MIN_TEMP_ALPHA),
        )) {
            numberPaint.alpha = alpha
            numberPaint.getTextBounds(text, 0, text.length, tight)
            canvas.drawText(
                text,
                size / 2f,
                centerY - (tight.top + tight.bottom) / 2f,
                numberPaint,
            )
        }

        return bmp
    }

    private companion object {
        /**
         * 最低温暗一档，最高温纯白。
         *
         * 这是**唯一**还能区分高低的信息（箭头已经删了），所以不能调太暗：
         * 180 实测染色后看着像「被禁用」，改成 225 —— 亮暗差还在，
         * 但不会让人以为下面那个数坏掉了。
         */
        const val MIN_TEMP_ALPHA = 225
    }
}
