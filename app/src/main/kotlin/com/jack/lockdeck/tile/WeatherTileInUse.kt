package com.jack.lockdeck.tile

import android.content.Context
import com.jack.lockdeck.data.Store

/**
 * 天气磁贴是否被加进了控制中心。
 *
 * 为什么需要这个标记：天气磁贴显示的温度和其它磁贴一样，读的是 [Store] 里的
 * 天气缓存，本身不联网。通知栏关掉之后，只有「磁贴还在用」才值得继续定时抓取，
 * 否则就是白白耗电耗流量。
 *
 * TileService 没提供「我现在被加进去了吗」的公共 API，只能在 onStartListening
 * （系统把它挂到控制中心、即将显示时）打个标记。一旦用户把它移出控制中心，
 * 就只是不再刷新这个标记——我们宁可多抓一阵子，也不要在用户看得见磁贴时
 * 却停在「暂无数据」。
 */
object WeatherTileInUse {

    private const val KEY = "tileWeatherInUse"

    fun mark(ctx: Context) {
        val p = Store.prefs(ctx)
        if (p.getBoolean(KEY, false)) return
        p.edit().putBoolean(KEY, true).apply()
    }

    fun marked(ctx: Context): Boolean = Store.prefs(ctx).getBoolean(KEY, false)
}
