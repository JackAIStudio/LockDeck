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
import com.jack.lockdeck.overlay.LaunchTrampolineActivity

/**
 * 控制中心磁贴的公共实现：点一下就跑一个动作。
 *
 * HyperOS 的控制中心不是 AOSP 那套，但第三方 TileService 仍然能被识别
 * （实测小红书、抖音的磁贴加进去后正常显示），所以这条路走得通。
 * 用户在「编辑」里把磁贴拖到前两行即可。
 *
 * 关键坑：磁贴的 onClick 里**不能**直接 startActivity。
 * TileService 进程没有可见 Activity，会被系统判定为「后台启动 Activity」直接拦掉
 * （logcat: Background activity launch blocked! BAL_BLOCK），表现就是点了没反应。
 * 必须走 startActivityAndCollapse(PendingIntent)：系统认这个来源，才会放行并收起面板。
 */
abstract class BaseActionTileService : TileService() {

    /** 1 或 2，对应设置页里的「磁贴 1 / 磁贴 2」 */
    abstract val slot: Int

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val id = Store.getTileAction(this, slot)
        android.util.Log.i("LSW", "tile$slot click -> $id")

        if (id == QuickAction.REFRESH) {
            // 刷新不需要界面，广播即可
            sendBroadcast(
                Intent(this, com.jack.lockdeck.notify.RefreshReceiver::class.java).apply {
                    action = com.jack.lockdeck.notify.RefreshReceiver.ACTION_REFRESH
                }
            )
            return
        }

        if (!QuickAction.isAvailable(this, id)) {
            android.util.Log.w("LSW", "tile$slot 目标未安装: $id")
            refreshTile()
            return
        }

        val trampoline = Intent(this, LaunchTrampolineActivity::class.java).apply {
            putExtra(LaunchTrampolineActivity.EXTRA_ACTION, id)
            putExtra(LaunchTrampolineActivity.EXTRA_ZONE, "tile$slot")
        }
        val pi = PendingIntent.getActivity(
            this,
            1000 + slot,
            trampoline,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 系统会收起控制中心，并把这次启动当作"用户点的"，不会被 BAL 拦
        runCatching { startActivityAndCollapse(pi) }
            .onFailure { android.util.Log.e("LSW", "tile$slot startActivityAndCollapse failed", it) }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val id = Store.getTileAction(this, slot)
        tile.label = QuickAction.label(this, id)
        tile.state = if (QuickAction.isAvailable(this, id)) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.icon = iconFor(id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = QuickAction.shortLabel(this, id)
        }
        tile.updateTile()
    }

    /**
     * 磁贴图标。
     *
     * 不能用目标 App 的图标：HyperOS 的控制中心会把第三方磁贴图标
     * 统一染成纯白单色（实测支付宝、微信图标都变成白方块），
     * 所以这里改用"形状可区分"的矢量图，颜色由系统自己染。
     */
    private fun iconFor(id: String): Icon {
        val res = when (id) {
            QuickAction.ALIPAY_SCAN -> R.drawable.ic_tile_scan_alipay
            QuickAction.WECHAT_SCAN -> R.drawable.ic_tile_scan_wechat
            QuickAction.ALIPAY_PAY, QuickAction.WECHAT_PAY -> R.drawable.ic_tile_pay
            else -> R.drawable.ic_tile_app
        }
        return Icon.createWithResource(this, res)
    }
}

/** 磁贴 1，默认支付宝扫一扫 */
class Tile1Service : BaseActionTileService() {
    override val slot: Int = 1
}

/** 磁贴 2，默认微信扫一扫 */
class Tile2Service : BaseActionTileService() {
    override val slot: Int = 2
}
