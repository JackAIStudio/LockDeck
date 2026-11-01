package com.jack.lockdeck.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Open-Meteo 全球模式。
 *
 * ## 为什么它还留着
 *
 * 它是**唯一一个免费、开源、有 SLA、不依赖任何私有接口**的源。
 * 小米那条路哪天失效、中央气象台也被限流的时候，这里是最后一道防线。
 *
 * ## 关键：必须显式指定 `cma_grapes_global`
 *
 * Open-Meteo 默认的 `best_match` 会自动挑一个「最优」模式，在中国大陆
 * 通常挑到 ECMWF/ICON。实测 2026-11-01 08:30 温州同一坐标：
 *
 * | 模式 | 温度 |
 * |---|---|
 * | `best_match` | 21.8°C |
 * | `ecmwf_ifs025` | 21.4°C |
 * | `icon_seamless` | 20.8°C |
 * | **`cma_grapes_global`** | **19.8°C** |
 * | 中央气象台温州站实测 | 19.8°C |
 *
 * 老版本就是没指定模式，才出现「和手机差 5 度」。指定中国气象局自己的
 * GRAPES 模式后，数值和本地观测对得上。
 *
 * ## 局限（写在这里，免得以后又踩）
 *
 * GRAPES 网格约 5 公里。实测鹿城 20.2 / 瓯海 20.3 / 龙湾 20.5——
 * 三个区它基本给同一个数。**它是格点插值，不是观测**，
 * 所以永远不可能像小米/气象台那样「和你手机一样」。
 */
object OpenMeteoApi {

    private const val BASE = "https://api.open-meteo.com/v1/forecast"

    /** 用中国气象局自己的模式，别用 best_match。 */
    private const val MODEL = "cma_grapes_global"

    fun fetch(
        lat: Double,
        lon: Double,
        place: Place,
        now: Long = System.currentTimeMillis(),
    ): Weather {
        val url = buildString {
            append("$BASE?latitude=$lat&longitude=$lon")
            append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,")
            append("weather_code,pressure_msl,wind_speed_10m,wind_direction_10m")
            append("&hourly=temperature_2m,weather_code")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min")
            append("&models=$MODEL&timezone=Asia%2FShanghai&forecast_days=7")
        }
        val json = getJson(url)

        val current = json.optJSONObject("current") ?: error("接口没返回 current")
        val tempNow = Math.round(current.optDouble("temperature_2m", Double.NaN)).toInt()
        if (tempNow == 0 && current.optDouble("temperature_2m", Double.NaN).isNaN()) {
            error("实况没有温度")
        }
        // Open-Meteo 回的是 WMO 代码，和气象台那套（0 晴 / 7 小雨）不是一回事，
        // 必须单独翻译，不能硬套 WeatherCodes。
        val wmoCode = current.optInt("weather_code", -1)
        val code = wmoToCmaCode(wmoCode)

        val daily = json.optJSONObject("daily")
        val hours = parseHours(json.optJSONObject("hourly"))
        val days = parseDays(daily)

        val rawMax = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN) ?: Double.NaN
        val rawMin = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN) ?: Double.NaN
        val tempMax = if (rawMax.isNaN()) Int.MIN_VALUE else Math.round(rawMax).toInt()
        val tempMin = if (rawMin.isNaN()) Int.MIN_VALUE else Math.round(rawMin).toInt()

        val cal = Calendar.getInstance().apply { timeInMillis = now }

        return Weather(
            tempNow = tempNow,
            tempMax = if (tempMax == Int.MIN_VALUE) tempNow else tempMax,
            tempMin = if (tempMin == Int.MIN_VALUE) tempNow else tempMin,
            weatherCode = code,
            isDay = cal.get(Calendar.HOUR_OF_DAY) in 6..17,
            city = place.city,
            district = place.district,
            updatedAt = now,
            text = WeatherCodes.text(code),
            feelsLike = current.optDouble("apparent_temperature", Double.NaN)
                .let { if (it.isNaN()) null else Math.round(it).toInt() },
            humidity = current.optDouble("relative_humidity_2m", Double.NaN)
                .let { if (it.isNaN()) null else Math.round(it).toInt() },
            pressure = current.optDouble("pressure_msl", Double.NaN)
                .let { if (it.isNaN()) null else Math.round(it).toInt() },
            windDirection = current.optDouble("wind_direction_10m", Double.NaN)
                .let { if (it.isNaN()) null else Math.round(it).toInt() },
            windSpeed = current.optDouble("wind_speed_10m", Double.NaN)
                .let { if (it.isNaN()) null else Math.round(it).toInt() },
            hourly = hours,
            daily = days,
            source = WeatherSource.OPEN_METEO,
        )
    }

    private fun parseHours(hourly: JSONObject?): List<HourPoint> {
        val times = hourly?.optJSONArray("time") ?: return emptyList()
        val temps = hourly.optJSONArray("temperature_2m") ?: return emptyList()
        val codes = hourly.optJSONArray("weather_code")
        val out = mutableListOf<HourPoint>()
        for (i in 0 until minOf(times.length(), temps.length())) {
            runCatching {
                val t = times.optString(i) // "2026-11-01T10:00"
                val h = t.substringAfter('T').take(2).toIntOrNull() ?: return@runCatching
                out += HourPoint(
                    timeMs = 0L,
                    hour = h,
                    temp = Math.round(temps.getDouble(i)).toInt(),
                    weatherCode = wmoToCmaCode(codes?.optInt(i, -1) ?: -1),
                )
                if (out.size >= 24) return out
            }
        }
        return out
    }

    private fun parseDays(daily: JSONObject?): List<DayPoint> {
        val dates = daily?.optJSONArray("time") ?: return emptyList()
        val maxs = daily.optJSONArray("temperature_2m_max") ?: return emptyList()
        val mins = daily.optJSONArray("temperature_2m_min") ?: return emptyList()
        val codes = daily.optJSONArray("weather_code")
        val out = mutableListOf<DayPoint>()
        val fmt = java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
        val fmtWeek = java.text.SimpleDateFormat("EEE", java.util.Locale.CHINA)
        val parse = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        for (i in 0 until minOf(dates.length(), maxs.length(), mins.length())) {
            runCatching {
                val d = parse.parse(dates.optString(i)) ?: return@runCatching
                val code = wmoToCmaCode(codes?.optInt(i, -1) ?: -1)
                out += DayPoint(
                    date = fmt.format(d),
                    weekday = if (i == 0) "今天" else fmtWeek.format(d),
                    weatherCode = code,
                    text = WeatherCodes.text(code),
                    tempMax = Math.round(maxs.getDouble(i)).toInt(),
                    tempMin = Math.round(mins.getDouble(i)).toInt(),
                )
            }
        }
        return out
    }

    /**
     * WMO 天气代码 → 气象台电码。
     *
     * 两套编码没有一一对应关系（WMO 按降水强度分 51/53/55/61/63/65…，
     * 电码按天气现象分 7/8/9），这里只做大致归并，够挑图标和写文案。
     */
    private fun wmoToCmaCode(wmo: Int): Int = when (wmo) {
        0 -> 0                      // 晴
        1 -> 1                      // 少云
        2 -> 1                      // 局部多云
        3 -> 2                      // 阴
        45, 48 -> 18                // 雾
        51, 53, 55 -> 7             // 毛毛雨 → 小雨
        56, 57 -> 19                // 冻毛毛雨 → 冻雨
        61 -> 7                     // 小雨
        63 -> 8                     // 中雨
        65 -> 9                     // 大雨
        66, 67 -> 19                // 冻雨
        71 -> 14                    // 小雪
        73 -> 15                    // 中雪
        75 -> 16                    // 大雪
        77 -> 14                    // 米雪
        80 -> 3                     // 阵雨
        81 -> 8                     // 强阵雨 → 中雨
        82 -> 9                     // 暴雨
        85, 86 -> 13                // 阵雪
        95 -> 4                     // 雷阵雨
        96, 99 -> 5                 // 雷阵雨伴冰雹
        else -> 0
    }

    private fun getJson(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "LockDeck/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
