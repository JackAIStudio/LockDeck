package com.jack.lockdeck.data

import android.content.Context

/** 极简本地存储：天气快照 + 每个点击区绑定的包名。 */
object Store {
    private const val P = "lsw"
    /**
     * 原始 SharedPreferences。
     *
     * 只在「另一个功能模块想存自己的小标记」时才该用（比如磁贴的 in-use 标记）。
     * 一般配置一律在本文件里加具名的 get/set——键散落在外面最容易变成一堆没人敢删的死键。
     */
    fun prefs(ctx: Context) = sp(ctx)

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
            .putString("text", w.text)
            .putLong("at", w.updatedAt)
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
            text = p.getString("text", "").orEmpty(),
        )
    }

    // ---- 点击区绑定 ----
    // zone: main / app1 / app2  →  action id（见 QuickAction）
    // 空 = 该区域不响应
    fun getTarget(ctx: Context, zone: String): String? =
        sp(ctx).getString("tgt_$zone", null)

    fun setTarget(ctx: Context, zone: String, id: String?) {
        sp(ctx).edit().apply { if (id.isNullOrBlank()) remove("tgt_$zone") else putString("tgt_$zone", id) }.apply()
    }

    // ---- 控制中心磁贴 ----
    fun getTileAction(ctx: Context, slot: Int): String =
        sp(ctx).getString("tile_$slot", null) ?: defaultTileAction(slot)

    fun setTileAction(ctx: Context, slot: Int, id: String?) {
        sp(ctx).edit().apply {
            if (id.isNullOrBlank()) remove("tile_$slot") else putString("tile_$slot", id)
        }.apply()
    }

    /** 磁贴 1 / 2 的出厂默认：支付宝扫一扫、微信扫一扫 */
    fun defaultTileAction(slot: Int): String = when (slot) {
        1 -> com.jack.lockdeck.action.QuickAction.ALIPAY_SCAN
        2 -> com.jack.lockdeck.action.QuickAction.WECHAT_SCAN
        else -> com.jack.lockdeck.action.QuickAction.ALIPAY_SCAN
    }

    // ---- 卡片开关 ----
    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("enabled", on).apply()

    // ---- 最近一次错误（诊断用）----
    fun setError(ctx: Context, msg: String?) =
        sp(ctx).edit().putString("lastError", msg).apply()

    fun getError(ctx: Context): String? = sp(ctx).getString("lastError", null)

    // ---- 刷新间隔（分钟），默认 10 ----
    fun getRefreshMinutes(ctx: Context): Int = sp(ctx).getInt("refreshMin", 10)
    fun setRefreshMinutes(ctx: Context, m: Int) =
        sp(ctx).edit().putInt("refreshMin", m.coerceIn(5, 120)).apply()

    /**
     * 清掉旧版本锁屏浮层留下的死键。
     *
     * 早期版本用无障碍浮层把卡片画在锁屏之上（键 ovEnabled / ovY / ovBump /
     * ovYCustom / a11y / rainHint / notifCard）。那套东西会盖在别人的通知上、
     * 还每 1.5 秒轮询一次无障碍节点树，又丑又卡，已经整个删掉。
     *
     * 升级上来的机器 SharedPreferences 里还留着这些键。留着不会崩，但以后
     * 翻到它们的人会以为是有用的配置，所以一次性清掉。
     */
    fun purgeLegacyOverlayPrefs(ctx: Context) {
        val p = sp(ctx)
        val legacy = listOf("ovEnabled", "ovY", "ovBump", "ovYCustom", "a11y", "rainHint", "notifCard")
        if (legacy.none { p.contains(it) }) return
        p.edit().apply { legacy.forEach { remove(it) } }.apply()
    }

    /**
     * 清掉「假位置」时代的旧快照。
     *
     * 老版本在没有定位时会用写死的深圳坐标去抓天气，那份快照会一直留在缓存里。
     * 升级后第一次启动必须把它丢掉，否则界面还挂着深圳的温度和「温州市」，
     * 用户会以为没修好。判断依据：没有 text 字段（老版本不存这个）→ 是老数据。
     */
    fun purgeStaleWeather(ctx: Context) {
        val p = sp(ctx)
        if (!p.contains("at")) return
        if (p.contains("text")) return
        p.edit().remove("at").remove("tNow").remove("tMax").remove("tMin")
            .remove("code").remove("day").remove("city").apply()
    }
}
