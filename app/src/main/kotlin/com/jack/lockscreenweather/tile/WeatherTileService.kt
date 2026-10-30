package com.jack.lockscreenweather.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jack.lockscreenweather.R
import com.jack.lockscreenweather.action.QuickAction
import com.jack.lockscreenweather.data.Store
import com.jack.lockscreenweather.overlay.LaunchTrampolineActivity

/**
 * 天气磁贴：右侧下拉（控制中心）直接看天气。
 *
 * 磁贴能显示的东西有限——只有 label + subtitle + 图标，
 * 但刚好够放"23°"和"晴 · 温州"。点一下打开小米天气看详情。
 *
 * 为什么不做成"左侧下拉的天气卡片"：
 * MIUI 会把第三方 App 的常驻通知统一折叠进「更多通知」，
 * 无论 importance 调多高都一样（实测 DEFAULT/HIGH 都折叠）。
 * 控制中心磁贴是唯一能稳定出现在下拉第一屏的位置。
 */
class WeatherTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val target = QuickAction.intentFor(this, QuickAction.openApp(QuickAction.PKG_WEATHER))
            ?: return
        val trampoline = Intent(this, LaunchTrampolineActivity::class.java).apply {
            putExtra(LaunchTrampolineActivity.EXTRA_ACTION, QuickAction.openApp(QuickAction.PKG_WEATHER))
            putExtra(LaunchTrampolineActivity.EXTRA_ZONE, "weatherTile")
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
        } else {
            tile.label = "${w.tempNow}°"
            tile.state = Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = w?.let {
                buildString {
                    append(it.description)
                    if (it.city.isNotBlank()) append(" · ").append(it.city)
                }
            } ?: "暂无数据"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_weather)
        tile.updateTile()
    }
}
