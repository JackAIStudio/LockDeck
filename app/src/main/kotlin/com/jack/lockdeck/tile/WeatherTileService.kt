package com.jack.lockdeck.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jack.lockdeck.R
import com.jack.lockdeck.action.QuickAction
import com.jack.lockdeck.data.Store
import com.jack.lockdeck.launch.LaunchTrampolineActivity

/**
 * 天气磁贴：右侧下拉（控制中心）直接看天气。
 *
 * 磁贴能显示的东西有限——只有 label + subtitle + 图标，
 * 但刚好够放"23°"和"晴 · 温州"。点一下打开小米天气看详情。
 *
 * 扫一扫、收付款这些「点一下就发生」的动作只留在这里（控制中心），
 * 通知栏那条只负责信息展示——两处不重复，用户不用记两套入口。
 *
 * 天气数据不在这里抓：onStartListening 会被系统高频调用，抓一次就是一次网络请求。
 * 数据由 [com.jack.lockdeck.notify.WeatherWorker] 定时写进缓存，这里只读取。
 * 只要磁贴被摆进控制中心过，就会打上 [WeatherTileInUse] 标记，
 * 通知栏关掉之后 Worker 仍会继续刷新它。
 */
class WeatherTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // 系统把它挂到控制中心、即将显示时必然走这里——借此确认「磁贴还在用」，
        // 通知栏关掉后由 Worker 继续刷新它。
        WeatherTileInUse.mark(this)
        refreshTile()
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
