package com.jack.lockdeck.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 天气数据源。
 *
 * ## 为什么从 Open-Meteo 换成中央气象台
 *
 * 老版本用 Open-Meteo，它是 ECMWF/ICON 全球模式在某个格点上的**插值**，
 * 不是当地观测站。实测同一时刻温州：
 *
 *  - 中央气象台温州站 58659：20.9°C
 *  - Open-Meteo（同一坐标）：25.5°C
 *
 * 差 4.6 度。用户看到的就是「和小米官方差了 5 度」。
 * 小米天气、中国天气网、中央气象台用的是同一套地面观测站数据，
 * 换成中央气象台之后差距能压到 1 度内。
 *
 * ## 为什么不用中国天气网
 *
 * d1.weather.com.cn 更接近小米那份（区县级、连「多云转晴」这种描述都一样），
 * 但它有两个硬伤：
 *  - 必须带 Referer 头，且只支持 http（App 里要开 cleartextTraffic）
 *  - **限流很凶**：短时间内连测几次就整片 403 返回 Forbid_code
 *  - 预报字段不稳定：实测出现过 fc 数组缺失、最高温给 "999" 占位
 * 天气是每 5~10 分钟自动刷的东西，放到这种接口上迟早挂。
 *
 * 中央气象台（weather.cma.cn）连续 6 次请求 0 失败，且同时给实况 + 7 天预报。
 *
 * ## 区县精度
 *
 * 中央气象台按**气象站**给数据，同一个市下面各区县可能是不同的站
 * （温州 58659、乐清 58656、瑞安 58752、永嘉 58658…）。
 * 所以先用反查拿到的区县名（鹿城区）去省站表里匹配同名站，
 * 匹配不到再退回市名（温州 → 58659）。这样精度能贴到用户所在区县。
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
        if (daily != null) {
            runCatching {
                val days = daily.getJSONObject("data").getJSONArray("daily")
                val today = days.getJSONObject(0)
                tempMax = Math.round(today.getDouble("high")).toInt()
                tempMin = Math.round(today.getDouble("low")).toInt()
                code = today.optInt("dayCode", 0)
                text = today.optString("dayText").trim()
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
