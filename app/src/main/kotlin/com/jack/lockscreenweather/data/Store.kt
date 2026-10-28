package com.jack.lockscreenweather.data

import android.content.Context

/** 极简本地存储：天气快照 + 每个点击区绑定的包名。 */
object Store {
    private const val P = "lsw"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    // ---- 天气快照 ----
    fun saveWeather(ctx: Context, w: Weather) {
        sp(ctx).edit()
            .putInt("tNow", w.tempNow)
            .putInt("tMax", w.tempMax)
            .putInt("tMin", w.tempMin)
            .putInt("code", w.weatherCode)
            .putBoolean("day", w.isDay)
            .putString("city", w.city)
            .putLong("at", w.updatedAt)
            .putInt("rainDay", w.rainChance)
            .putInt("rainHour", w.rainChanceNextHour)
            .apply()
    }

    fun loadWeather(ctx: Context): Weather? {
        val p = sp(ctx)
        if (!p.contains("at")) return null
        return Weather(
            tempNow = p.getInt("tNow", 0),
            tempMax = p.getInt("tMax", 0),
            tempMin = p.getInt("tMin", 0),
            weatherCode = p.getInt("code", 0),
            isDay = p.getBoolean("day", true),
            city = p.getString("city", "").orEmpty(),
            updatedAt = p.getLong("at", 0),
            rainChance = p.getInt("rainDay", -1),
            rainChanceNextHour = p.getInt("rainHour", -1),
        )
    }

    // ---- 点击区绑定 ----
    // zone: main / left / right  →  package name（空 = 用默认天气 App）
    fun getTarget(ctx: Context, zone: String): String? =
        sp(ctx).getString("tgt_$zone", null)

    fun setTarget(ctx: Context, zone: String, pkg: String?) {
        sp(ctx).edit().apply { if (pkg.isNullOrBlank()) remove("tgt_$zone") else putString("tgt_$zone", pkg) }.apply()
    }

    // ---- 卡片开关 ----
    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("enabled", on).apply()

    // ---- 最近一次错误（诊断用）----
    fun setError(ctx: Context, msg: String?) =
        sp(ctx).edit().putString("lastError", msg).apply()

    fun getError(ctx: Context): String? = sp(ctx).getString("lastError", null)

    // ---- 锁屏浮层开关 ----
    fun isOverlayEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("ovEnabled", false)
    fun setOverlayEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("ovEnabled", on).apply()

    // ---- 无障碍服务连接状态（供 UI 显示）----
    fun setA11yConnected(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("a11y", on).apply()

    fun isA11yConnected(ctx: Context): Boolean = sp(ctx).getBoolean("a11y", false)

    // ---- 浮层纵向位置 ----
    fun getOverlayY(ctx: Context): Int = sp(ctx).getInt("ovY", 980)
    fun setOverlayY(ctx: Context, y: Int) = sp(ctx).edit().putInt("ovY", y).apply()

    // ---- 手动触发浮层刷新（App 内点击时用）----
    fun bumpOverlayRefresh(ctx: Context) =
        sp(ctx).edit().putLong("ovBump", System.currentTimeMillis()).apply()

    fun getOverlayBump(ctx: Context): Long = sp(ctx).getLong("ovBump", 0L)


    // ---- 刷新间隔（分钟），默认 10 ----
    fun getRefreshMinutes(ctx: Context): Int = sp(ctx).getInt("refreshMin", 10)
    fun setRefreshMinutes(ctx: Context, m: Int) =
        sp(ctx).edit().putInt("refreshMin", m.coerceIn(5, 120)).apply()

    // ---- 浮层是否显示降雨提示 ----
    fun isRainHintEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("rainHint", true)
    fun setRainHintEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("rainHint", on).apply()

    // ---- 卡片是否已被用户手动定位过 ----
    fun hasCustomY(ctx: Context): Boolean = sp(ctx).getBoolean("ovYCustom", false)
    fun markCustomY(ctx: Context) = sp(ctx).edit().putBoolean("ovYCustom", true).apply()
}
