package com.jack.lockdeck.tile

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.Log
import com.jack.lockdeck.R

/**
 * 控制中心里那三个按钮。
 *
 * ## 先把名字说清楚
 *
 * 安卓官方把控制中心（下拉那两排圆角方块）里的每个格子叫 **Quick Settings tile**，
 * 中文 ROM 里常被译成「磁贴」。这个词对用户几乎没有信息量——「磁贴」听起来像
 * 桌面上的贴纸，没人能猜到它其实是「点一下就执行一个动作的按钮」。
 *
 * 所以 App 里一律叫「控制中心按钮」：
 *
 * | 名字 | 能不能改 | 位置 |
 * |---|---|---|
 * | 天气 | 固定，只显示温度和天气 | 控制中心 |
 * | 快捷按钮 ① | 功能自己选 | 控制中心 |
 * | 快捷按钮 ② | 功能自己选 | 控制中心 |
 *
 * ## 为什么不能"帮用户直接加进去"
 *
 * 控制中心放哪些格子**只有用户自己能决定**，这是系统故意留的边界：
 * Android 13 起应用只能调 [StatusBarManager.requestAddTileService] 发起一次「请求」，
 * 系统弹框由用户点「添加」；Android 12 及以下连这个请求都没有，必须手动编辑。
 *
 * HyperOS 上实测更直接：这个请求基本不弹框（[StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED]
 * 或干脆没反应）。所以 App 的策略是——**请求一次 + 把手动路径写成人话**，
 * 而不是假装一键搞定然后让用户面对一个空的控制中心。
 */
object TileCatalog {

    private const val TAG = "LSW"

    /**
     * 一个可添加的控制中心按钮。
     *
     * @param slot 0 = 固定天气；1 / 2 = 可自定义功能的两个槽位
     */
    data class Item(
        val slot: Int,
        val name: String,
        val summary: String,
        val iconRes: Int,
        val serviceClass: Class<out TileService>,
    )

    /** 顺序就是显示顺序，和用户下拉控制中心时看到的顺序无关（那个由用户拖） */
    val items: List<Item> = listOf(
        Item(
            slot = SLOT_WEATHER,
            name = "天气",
            summary = "固定功能：显示当前温度，点开进天气 App",
            iconRes = R.drawable.ic_tile_weather,
            serviceClass = WeatherTileService::class.java,
        ),
        Item(
            slot = 1,
            name = "快捷按钮 ①",
            summary = "功能自己选：扫一扫、收付款，或任意 App",
            iconRes = R.drawable.ic_tile_scan_alipay,
            serviceClass = Tile1Service::class.java,
        ),
        Item(
            slot = 2,
            name = "快捷按钮 ②",
            summary = "功能自己选：扫一扫、收付款，或任意 App",
            iconRes = R.drawable.ic_tile_scan_wechat,
            serviceClass = Tile2Service::class.java,
        ),
    )

    /** 天气按钮的槽位号。它没有可配置的动作，固定打开天气 App。 */
    const val SLOT_WEATHER = 0

    fun itemOf(slot: Int): Item? = items.firstOrNull { it.slot == slot }

    /**
     * 请系统把某个按钮加进控制中心。
     *
     * @param onResult 系统给出结果时回调（true = 已添加）。HyperOS 上大概率
     *   永远不会回调，所以界面**不能**依赖它来判断成败。
     * @return false 表示这次请求根本没发出去（系统版本太低或服务不可用）
     */
    fun requestAdd(ctx: Context, item: Item, onResult: (Boolean) -> Unit = {}): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "requestAddTile 需要 Android 13+，本机不支持，只能手动编辑")
            return false
        }
        val sbm = runCatching { ctx.getSystemService(StatusBarManager::class.java) }
            .getOrNull() ?: return false
        return runCatching {
            sbm.requestAddTileService(
                ComponentName(ctx, item.serviceClass),
                item.name,
                Icon.createWithResource(ctx, item.iconRes),
                ctx.mainExecutor,
            ) { result ->
                Log.i(TAG, "requestAddTile(${item.name}) -> result=$result")
                onResult(result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED)
            }
            true
        }.getOrElse {
            Log.w(TAG, "requestAddTile(${item.name}) 失败", it)
            false
        }
    }

    /**
     * 依次请求添加全部按钮。
     *
     * 逐个发、中间隔一点时间：系统弹框是模态的，连发三个会被后一个覆盖掉前一个。
     */
    fun requestAddAll(ctx: Context, onEach: (Item, Boolean) -> Unit = { _, _ -> }) {
        val handler = Handler(Looper.getMainLooper())
        items.forEachIndexed { i, item ->
            handler.postDelayed({ requestAdd(ctx, item) { ok -> onEach(item, ok) } }, i * 400L)
        }
    }
}
