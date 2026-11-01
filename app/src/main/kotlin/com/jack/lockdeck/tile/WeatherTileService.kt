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
 * 数据由 [com.jack.lockdeck.notify.WeatherWorker] 定时写进缓存，这里只读取。
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
            tile.label = "${w.tempNow}° ${w.description}"
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = buildString {
                    append(w.tempMin).append("°~").append(w.tempMax).append("°")
                    if (w.place.isNotBlank()) append(" · ").append(w.place)
                }
            }
            // 关键：温度画进位图。HyperOS 只认图标这一层。
            tile.icon = Icon.createWithBitmap(tempBitmap("${w.tempNow}°"))
        }
        tile.updateTile()
    }

    /**
     * 把一段文字画成图标位图。
     *
     * 尺寸 192px：磁贴实际显示约 24~48dp，2x/3x 屏放上去还有余量，
     * 再大只是浪费内存（Bitmap 走 Binder 传给 SystemUI）。
     *
     * 纯白字：反正会被系统染色，自己画白的最稳。
     */
    private fun tempBitmap(text: String): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            // 「-12°」这种最长的情况要能塞下，所以按字数调字号
            textSize = when {
                text.length <= 2 -> size * 0.56f
                text.length == 3 -> size * 0.50f
                else -> size * 0.40f
            }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        // 垂直居中：用 fontMetrics 算基线，不然数字会偏下
        val fm = paint.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, size / 2f, baseline, paint)
        return bmp
    }
}
