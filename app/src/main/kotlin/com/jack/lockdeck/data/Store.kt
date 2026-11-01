package com.jack.lockdeck.data

import android.content.Context

/** 极简本地存储：天气快照 + 每个点击区绑定的包名。 */
object Store {
    private const val P = "lsw"

    /**
     * 天气快照变更的广播 action。
     *
     * ## 为什么需要它
     *
     * 天气有三条刷新路径：设置页点「立即刷新」、前台服务的定时循环、
     * 以及「打开 App 时补刷」。后两条都在**别处**更新缓存，
     * 界面只是在打开那一刻读了一次 [loadWeather]，根本不知道数据变了。
     *
     * 结果是一种很别扭的状态：通知栏已经显示 27°，设置页上还挂着 28°，
     * 退出再进来才对得上。用户看到的就是「刷新没生效」。
     *
     * 用本地广播而不是硬塞回调：刷新发生在后台线程、甚至另一个进程状态的
     * Service 里，广播是唯一不需要把 UI 引用传来传去的做法。
     * Context 已注册接收器不受广播导出限制影响，所以这里能用隐式广播。
     */
    const val ACTION_WEATHER_CHANGED = "com.jack.lockdeck.WEATHER_CHANGED"
    /**
     * 原始 SharedPreferences。
     *
     * 只在「另一个功能模块想存自己的小标记」时才该用（比如磁贴的 in-use 标记）。
     * 一般配置一律在本文件里加具名的 get/set——键散落在外面最容易变成一堆没人敢删的死键。
     */
    fun prefs(ctx: Context) = sp(ctx)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    // ---- 数据源 ----

    /**
     * 用户选的天气数据源，默认小米。
     *
     * 为什么不自动降级：静默换源会让用户看到「温度忽然变了 2 度」
     * 却不知道该怀疑什么。宁可失败时报错、让用户自己切，也不要偷换。
     */
    fun getSource(ctx: Context): WeatherSource =
        WeatherSource.fromName(sp(ctx).getString("source", null))

    fun setSource(ctx: Context, s: WeatherSource) =
        sp(ctx).edit().putString("source", s.name).apply()

    // ---- 天气快照 ----
    fun saveWeather(ctx: Context, w: Weather) {
        sp(ctx).edit()
            .putInt("tNow", w.tempNow)
            .putInt("tMax", w.tempMax)
            .putInt("tMin", w.tempMin)
            .putInt("code", w.weatherCode)
            .putBoolean("day", w.isDay)
            .putString("city", w.city)
            .putString("district", w.district)
            .putString("text", w.text)
            .putString("src", w.source.name)
            .putLong("at", w.updatedAt)
            // 诊断用：接口原始值、发布时间、观测站。缺了就清掉，别留上一次的误导人。
            .apply {
                if (w.tempRaw.isBlank()) remove("tempRaw") else putString("tempRaw", w.tempRaw)
                if (w.pubTime.isBlank()) remove("pubTime") else putString("pubTime", w.pubTime)
                if (w.stationInfo.isBlank()) remove("station") else putString("station", w.stationInfo)
            }
            // 以下是小米源才有的东西，没有就不写（留着旧值比写 null 好读）
            .apply {
                w.feelsLike?.let { putInt("feels", it) } ?: remove("feels")
                w.humidity?.let { putInt("hum", it) } ?: remove("hum")
                w.pressure?.let { putInt("pres", it) } ?: remove("pres")
                w.uvIndex?.let { putInt("uv", it) } ?: remove("uv")
                w.windDirection?.let { putInt("wdir", it) } ?: remove("wdir")
                w.windSpeed?.let { putInt("wspd", it) } ?: remove("wspd")
                w.aqi?.let { putString("aqiJson", AqiCodec.encode(it)) } ?: remove("aqiJson")
                w.minutely?.let { putString("rainJson", MinutelyCodec.encode(it)) } ?: remove("rainJson")
                putString("hoursJson", HourCodec.encode(w.hourly))
                putString("daysJson", DayCodec.encode(w.daily))
            }
            .apply()

        // 写完再喊一声，让界面重新读。放在 apply() 之后：订阅者收到广播时
        // 立刻 loadWeather 必须读到新值，顺序反了就会拿到旧快照。
        notifyWeatherChanged(ctx)
    }

    /**
     * 通知界面天气变了。
     *
     * 用 sendBroadcast + 包名限定：只发给本应用，不会漏到外面去。
     * 失败无所谓（比如界面没在前台，没人监听），不能因为通知失败让刷新中断。
     */
    fun notifyWeatherChanged(ctx: Context) {
        runCatching {
            ctx.sendBroadcast(
                android.content.Intent(ACTION_WEATHER_CHANGED).setPackage(ctx.packageName)
            )
        }.onFailure { android.util.Log.w("LSW", "weather broadcast failed", it) }
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
            district = p.getString("district", "").orEmpty(),
            updatedAt = p.getLong("at", 0),
            text = p.getString("text", "").orEmpty(),
            feelsLike = p.getIntOrNull("feels"),
            humidity = p.getIntOrNull("hum"),
            pressure = p.getIntOrNull("pres"),
            uvIndex = p.getIntOrNull("uv"),
            windDirection = p.getIntOrNull("wdir"),
            windSpeed = p.getIntOrNull("wspd"),
            aqi = AqiCodec.decode(p.getString("aqiJson", null)),
            minutely = MinutelyCodec.decode(p.getString("rainJson", null)),
            hourly = HourCodec.decode(p.getString("hoursJson", null)),
            daily = DayCodec.decode(p.getString("daysJson", null)),
            source = WeatherSource.fromName(p.getString("src", null)),
            tempRaw = p.getString("tempRaw", "").orEmpty(),
            pubTime = p.getString("pubTime", "").orEmpty(),
            stationInfo = p.getString("station", "").orEmpty(),
        )
    }

    private fun android.content.SharedPreferences.getIntOrNull(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    // ---- 点击区绑定 ----
    // zone: main / app1 / app2  →  action id（见 QuickAction）
    // 空 = 该区域不响应
    fun getTarget(ctx: Context, zone: String): String? =
        sp(ctx).getString("tgt_$zone", null)

    fun setTarget(ctx: Context, zone: String, id: String?) {
        sp(ctx).edit().apply { if (id.isNullOrBlank()) remove("tgt_$zone") else putString("tgt_$zone", id) }.apply()
    }

    // ---- 控制中心按钮（系统里叫 Quick Settings tile）----
    fun getTileAction(ctx: Context, slot: Int): String =
        sp(ctx).getString("tile_$slot", null) ?: defaultTileAction(slot)

    fun setTileAction(ctx: Context, slot: Int, id: String?) {
        sp(ctx).edit().apply {
            if (id.isNullOrBlank()) remove("tile_$slot") else putString("tile_$slot", id)
        }.apply()
    }

    /** 快捷按钮 ① / ② 的出厂默认：支付宝扫一扫、微信扫一扫 */
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

    // ---- 刷新间隔（分钟）----

    /**
     * 默认刷新间隔，按数据源给不同的值。
     *
     * 实测两个源的更新节奏差很多（2026-11-01 采样）：
     *  - 小米 `updateTime` 每 5 分钟推进一次，`current.pubTime` 跟着走
     *  - 中央气象台观测站约 10 分钟一批（08:20 → 08:35 → 08:50）
     *
     * 所以小米默认 5 分钟、其它默认 10 分钟。用户手动改过之后以用户为准。
     *
     * 按 5 分钟算我们的请求量：一天 288 次、一个月约 8600 次。
     * 小米那个接口实测 120 次连打（8 并发）全部 200，这个量级不是问题。
     */
    fun defaultRefreshMinutes(source: WeatherSource): Int = when (source) {
        WeatherSource.XIAOMI -> 5
        WeatherSource.CMA, WeatherSource.OPEN_METEO -> 10
    }

    fun getRefreshMinutes(ctx: Context): Int =
        sp(ctx).getInt("refreshMin", defaultRefreshMinutes(getSource(ctx)))

    fun setRefreshMinutes(ctx: Context, m: Int) =
        sp(ctx).edit().putInt("refreshMin", m.coerceIn(5, 120)).apply()

    /** 丢掉用户设过的间隔，回到当前数据源的默认值（换源时用）。 */
    fun clearRefreshMinutes(ctx: Context) =
        sp(ctx).edit().remove("refreshMin").apply()

    /**
     * 清掉「不是当前数据源抓的」旧快照。
     *
     * ## 为什么需要
     *
     * 缓存的温度和「高低温、体感、AQI」是同一个源在同一次请求里一起给的。
     * 升级版本、或者用户切了数据源之后，如果还挂着上一份快照，界面会把
     * **两个源的口径混在一起**：比如温州真实 18°，界面上却写着「21°，今日 20°~29°」，
     * 而那组高低温是中央气象台温州站（在山上）的。
     *
     * 实测代价很具体：小米 18° / CMA 58659 20.6°，差 2.6 度。
     * 混着的界面比干脆显示「正在获取」更难解释，所以宁可丢掉。
     *
     * @return true 表示丢掉了一份旧快照（调用方应该立刻重抓）
     */
    fun purgeForeignWeather(ctx: Context): Boolean {
        val p = sp(ctx)
        if (!p.contains("at")) return false
        val cached = WeatherSource.fromName(p.getString("src", null))
        // 老版本没有 src 键，那时用的就是中央气象台
        val cachedSource = if (p.contains("src")) cached else WeatherSource.CMA
        if (cachedSource == getSource(ctx)) return false

        p.edit()
            .remove("at").remove("tNow").remove("tMax").remove("tMin")
            .remove("code").remove("day").remove("city").remove("district").remove("text")
            .remove("src").remove("feels").remove("hum").remove("pres").remove("uv")
            .remove("wdir").remove("wspd").remove("aqiJson").remove("rainJson")
            .remove("hoursJson").remove("daysJson")
            .remove("tempRaw").remove("pubTime").remove("station")
            .apply()
        return true
    }

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
