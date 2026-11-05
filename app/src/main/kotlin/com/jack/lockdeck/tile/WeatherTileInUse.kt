package com.jack.lockdeck.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.jack.lockdeck.data.Store

/**
 * 天气相关的按钮是否还被加在控制中心里。
 *
 * 为什么需要这个标记：天气按钮显示的温度和其它按钮一样，读的是 [Store] 里的
 * 天气缓存，本身不联网。通知栏关掉之后，只有「还有人控制中心里看天气」
 * 才值得继续定时抓取，否则就是白白耗电耗流量。
 *
 * ## 现在有两个按钮要记账
 *
 * 天气相关的按钮有两个：[WeatherTileService]（当前温度）和 [RangeTileService]
 * （今日高低温）。它们看的是同一份缓存、同一条刷新链路，所以「还要不要刷」
 * 应该看**这两个里还有没有至少一个在**。
 *
 * 早先只有天气一个按钮时，这里只有一个布尔值就够了。加了第二个按钮之后，
 * 一个布尔值会立刻出错：用户把高低温拿掉、天气还留着，`clear()` 一写
 * 就把「有人在看」抹掉了，定时刷新随之停掉——而天气按钮还明晃晃地在屏幕上。
 *
 * 所以改成**按按钮分别记账**，两个都空了才算真的没人用。
 *
 * TileService 没提供「我现在被加进去了吗」的公共 API，只能在 onStartListening
 * （系统把它挂到控制中心、即将显示时）打个标记。一旦用户把它移出控制中心，
 * 就只是不再刷新这个标记——我们宁可多抓一阵子，也不要在用户看得见按钮时
 * 却停在「暂无数据」。
 */
object WeatherTileInUse {

    private const val KEY_WEATHER = "tileWeatherInUse"
    private const val KEY_RANGE = "tileRangeInUse"

    /** 当前温度按钮：还在控制中心里。 */
    fun mark(ctx: Context) = set(ctx, KEY_WEATHER, true)

    /** 高低温按钮：还在控制中心里。 */
    fun markRange(ctx: Context) = set(ctx, KEY_RANGE, true)

    /** 当前温度按钮被移出控制中心。 */
    fun clear(ctx: Context) = set(ctx, KEY_WEATHER, false)

    /** 高低温按钮被移出控制中心。 */
    fun clearRange(ctx: Context) = set(ctx, KEY_RANGE, false)

    /** 还有没有任何一个天气按钮在控制中心里。 */
    fun marked(ctx: Context): Boolean {
        val p = Store.prefs(ctx)
        return p.getBoolean(KEY_WEATHER, false) || p.getBoolean(KEY_RANGE, false)
    }

    private fun set(ctx: Context, key: String, value: Boolean) {
        val p = Store.prefs(ctx)
        if (p.getBoolean(key, false) == value) return
        p.edit().putBoolean(key, value).apply()
    }

    /**
     * 请系统让两个按钮各自重新走一遍 [TileService.onStartListening]。
     *
     * 后台刷新完温度变了，但按钮上还是旧的——因为 TileService 平时根本不会被调用，
     * 只有系统要显示控制中心时才会拉它起来。这个 API 就是「提前通知它一下」，
     * 让下次下拉时拿到的是最新值。
     *
     * 两个都要通知：只通知一个的话，另一个会一直停在旧温度上。
     * 失败无所谓（比如那个按钮根本没加进控制中心），吞掉即可。
     */
    fun requestUpdate(ctx: Context) {
        for (cls in listOf(WeatherTileService::class.java, RangeTileService::class.java)) {
            runCatching {
                TileService.requestListeningState(ctx, ComponentName(ctx, cls))
            }
        }
    }
}
