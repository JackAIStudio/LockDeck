package com.jack.lockdeck.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * 复用系统定位，而不是自己另起一套 GPS。
 *
 * 思路：小米天气自己也是从系统定位服务拿位置，所以这里直接读系统缓存里的
 * lastKnownLocation（GPS + 网络 + 被动三种 provider 取最新的一个），
 * 既有和天气 App 一致的位置来源，又不会额外耗电。
 */
object LocationHelper {

    data class Fix(val lat: Double, val lon: Double, val time: Long)

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 取系统缓存里最新的那个位置；没有就返回 null。 */
    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Fix? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        return best?.let { Fix(it.latitude, it.longitude, it.time) }
    }

    /** 兜底位置：定位还没就绪时先给一个，保证卡片不会空着。 */
    val FALLBACK = Fix(22.5431, 114.0579, 0L) // 深圳
}
