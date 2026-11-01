package com.jack.lockdeck.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 中央气象台 weather.cma.cn —— 观测站数据。
 *
 * ## 定位：现在它是**备胎**，默认源是小米（见 [WeatherSource]）
 *
 * 这里保留它，是因为它是三者里最稳的一个：免费、无需 key、
 * 不依赖任何私有接口，跑了很久没出过问题。
 *
 * ## 为什么它给不出「和手机一样」的数
 *
 * 中央气象台按**气象站**给数据，而市区三个区（鹿城/龙湾/瓯海）
 * **只有鹿城有站（58659，在五马街道海坦山路）**，瓯海、龙湾都共用它。
 * 实测 2026-11-01 09:00 同一时刻：
 *
 *  - 小米（鹿城）：18°C     ← 用户手机上就是这个
 *  - 中国天气网鹿城：18.2°C
 *  - 中央气象台 58659：20.6°C
 *
 * 差 2.6 度。不是接口不准，是**站离你远**——58659 那份是国家站口径，
 * 而且这个站在山坡上（站表标注「山顶、市区」），比城区低一点是常态。
 *
 * 所以别指望换回来还能和手机对得上，鱼和熊掌。
 *
 * ## 为什么不用中国天气网
 *
 * 它反而有区县码（鹿城 101210710），比 CMA 更贴手机。
 * 但它必须带 Referer、只支持 http（App 里要开 cleartextTraffic），
 * 而且不是公开 API——本质和小米那条路一样是逆工程，却不如小米准。
 * 所以选小米，不选它。
 */
object WeatherApi {

    private const val NOW = "https://weather.cma.cn/api/now/"
    private const val DAILY = "https://weather.cma.cn/api/weather/"
    private const val PROVINCES = "https://weather.cma.cn/api/dict/province"
    private const val PROVINCE_DICT = "https://weather.cma.cn/api/dict/province/"

    /** 站号匹配结果缓存：城市名 → 站号，避免每次刷新都拉省站表。 */
    private val stationCache = HashMap<String, String>()

    /**
     * 抓一次天气。
     *
     * @param place 反查出来的地名；用于挑对应区县的气象站
     * @return 拿不到就返回失败，**上层不要编数据**
     */
    fun fetch(place: Place): Result<Weather> = runCatching {
        val station = resolveStation(place)
            ?: error("找不到「${place.display}」对应的气象站")

        val now = getJson(NOW + station)
        val daily = runCatching { getJson(DAILY + station) }.getOrNull()

        val nowObj = now.getJSONObject("data").getJSONObject("now")
        val tempNow = Math.round(nowObj.getDouble("temperature")).toInt()

        // 预报可能没有（接口偶尔只给实况），这时高低温退化成当前温度，
        // 界面上会显示成「28°~28°」，但至少不是假数字
        var tempMax = tempNow
        var tempMin = tempNow
        var code = 0
        var text = ""
        val dayPoints = mutableListOf<DayPoint>()
        if (daily != null) {
            runCatching {
                val days = daily.getJSONObject("data").getJSONArray("daily")
                val today = days.getJSONObject(0)
                tempMax = Math.round(today.getDouble("high")).toInt()
                tempMin = Math.round(today.getDouble("low")).toInt()
                code = today.optInt("dayCode", 0)
                text = today.optString("dayText").trim()

                val fmtDate = java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
                val fmtWeek = java.text.SimpleDateFormat("EEE", java.util.Locale.CHINA)
                val parse = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.CHINA)
                for (i in 0 until minOf(days.length(), 7)) {
                    val d = days.getJSONObject(i)
                    val dt = runCatching { parse.parse(d.optString("date")) }.getOrNull()
                    dayPoints += DayPoint(
                        date = dt?.let { fmtDate.format(it) } ?: d.optString("date"),
                        weekday = if (i == 0) "今天" else dt?.let { fmtWeek.format(it) }.orEmpty(),
                        weatherCode = d.optInt("dayCode", 0),
                        text = d.optString("dayText").trim(),
                        tempMax = Math.round(d.getDouble("high")).toInt(),
                        tempMin = Math.round(d.getDouble("low")).toInt(),
                    )
                }
            }
        }

        val stationName = now.getJSONObject("data")
            .getJSONObject("location").optString("name").trim()

        Weather(
            tempNow = tempNow,
            tempMax = tempMax,
            tempMin = tempMin,
            weatherCode = code,
            isDay = runCatching { isDaytime() }.getOrDefault(true),
            city = stationName.ifBlank { place.city },
            // 反查到的区县名跟着存下来：界面优先显示它（鹿城区），
            // 这样和手机自带天气的地名一致，不会因为「共用市级站」而显示成一个大市名
            district = place.district,
            updatedAt = System.currentTimeMillis(),
            text = text,
            feelsLike = Math.round(nowObj.optDouble("feelst", Double.NaN)).toInt()
                .let { if (it >= 0) it else null },
            humidity = Math.round(nowObj.optDouble("humidity", Double.NaN)).toInt()
                .let { if (it >= 0) it else null },
            pressure = Math.round(nowObj.optDouble("pressure", Double.NaN)).toInt()
                .let { if (it >= 0) it else null },
            windDirection = Math.round(nowObj.optDouble("windDirectionDegree", Double.NaN)).toInt()
                .let { if (it >= 0) it else null },
            windSpeed = Math.round(nowObj.optDouble("windSpeed", Double.NaN)).toInt()
                .let { if (it >= 0) it else null },
            daily = dayPoints,
            source = WeatherSource.CMA,
        )
    }

    /**
     * 按地名挑气象站。
     *
     * 顺序：区县名（鹿城）→ 市名（温州）→ 省份无匹配时放弃。
     * 每次都先在内存缓存里找，找到就不发请求。
     */
    private fun resolveStation(place: Place): String? {
        val candidates = buildList {
            if (place.district.isNotBlank()) add(place.district.trimEnd('区', '县', '市'))
            if (place.city.isNotBlank()) add(place.city.trimEnd('市', '区'))
        }.filter { it.isNotBlank() }.distinct()

        if (candidates.isEmpty()) return null

        candidates.forEach { stationCache[it]?.let { s -> return s } }

        // 省站表：用 CN-ZJ → AZJ 的约定拿整省站点
        val province = provinceCode(place.province)
        if (province != null) {
            val table = runCatching {
                getJsonRaw(PROVINCE_DICT + province).optString("data")
            }.getOrNull().orEmpty()
            if (table.isNotBlank()) {
                val stations = table.split('|').mapNotNull { line ->
                    val parts = line.split(',')
                    if (parts.size >= 2) parts[0].trim() to parts[1].trim() else null
                }
                for (name in candidates) {
                    // 精确同名优先，其次包含（「鹿城」vs「鹿城区」）。
                    // 用 `==` 之外还要比「包含」，是因为反查给的名字常带后缀或写法略有出入。
                    val hit = stations.firstOrNull { it.second == name }
                        ?: stations.firstOrNull {
                            it.second.contains(name) || name.contains(it.second)
                        }
                    if (hit != null) {
                        stationCache[name] = hit.first
                        return hit.first
                    }
                }
            }
        }
        return null
    }

    /**
     * 省份名 → CMA 省代码。
     *
     * 规律：`CN-ZJ` → `AZJ`，即把 `CN-` 换成 `A`。实测北京/上海/四川/广东/浙江全部吻合。
     */
    private fun provinceCode(province: String): String? {
        val dict = runCatching {
            getJsonRaw(PROVINCES).optString("data")
        }.getOrNull().orEmpty()
        if (dict.isBlank()) return null
        // 字典是 "ABJ,北京|ATJ,天津|..."，按中文名反查代码
        val hit = dict.split('|').firstOrNull {
            it.split(',').getOrNull(1)?.trim()?.let { n ->
                province.isNotBlank() && (n == province || province.contains(n))
            } == true
        } ?: return null
        return hit.split(',').firstOrNull()?.trim()
    }

    /** 现在是白天吗。用 6:00-18:00 粗判——只为挑「白天/夜里」图标，不值得多请求一次。 */
    private fun isDaytime(): Boolean {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return h in 6..17
    }

    // ---------------------------------------------------------------- http

    private fun getJson(url: String): JSONObject = getJsonRaw(url)

    private fun getJsonRaw(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            // 这个站没有公开 API 文档，但要个常规 UA 才稳定
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16)")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            if (o.optInt("code", 0) != 0) error("接口返回 code=${o.optInt("code")}")
            return o
        } finally {
            conn.disconnect()
        }
    }
}
