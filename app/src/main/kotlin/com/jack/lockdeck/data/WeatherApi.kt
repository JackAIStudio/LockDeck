package com.jack.lockdeck.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Open-Meteo：免费、无需 API key、无需注册。
 *
 * 一次请求同时拿到：
 *  - 当前温度 / 天气代码 / 昼夜
 *  - 今日最高最低 + 全天降水概率
 *  - 未来 1 小时降水概率（用于"带伞"提醒）
 */
object WeatherApi {

    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"

    fun fetch(lat: Double, lon: Double): Result<Weather> = runCatching {
        val url = URL(
            "$FORECAST?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&hourly=precipitation_probability" +
                "&timezone=auto&forecast_days=1"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): Weather {
        val root = JSONObject(body)
        val cur = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")

        val tMax = daily.getJSONArray("temperature_2m_max").getDouble(0)
        val tMin = daily.getJSONArray("temperature_2m_min").getDouble(0)

        // 全天降水概率（可能缺失）
        val dayRain = runCatching {
            daily.getJSONArray("precipitation_probability_max").let {
                if (it.isNull(0)) -1 else it.getInt(0)
            }
        }.getOrDefault(-1)

        // 未来 1 小时降水概率：从 hourly 里找当前时刻之后的一个点
        val nextHourRain = runCatching {
            val hourly = root.getJSONObject("hourly")
            val probs = hourly.getJSONArray("precipitation_probability")
            val times = hourly.getJSONArray("time")
            val nowIso = cur.optString("time", "")
            if (nowIso.isBlank()) {
                -1
            } else {
                var idx = 0
                for (i in 0 until minOf(times.length(), probs.length())) {
                    if (times.getString(i) >= nowIso) { idx = i; break }
                }
                if (probs.isNull(idx)) -1 else probs.getInt(idx)
            }
        }.getOrDefault(-1)

        return Weather(
            tempNow = Math.round(cur.getDouble("temperature_2m")).toInt(),
            tempMax = Math.round(tMax).toInt(),
            tempMin = Math.round(tMin).toInt(),
            weatherCode = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            city = "",
            updatedAt = System.currentTimeMillis(),
            rainChance = dayRain,
            rainChanceNextHour = nextHourRain,
        )
    }

    /**
     * 反查城市名（BigDataCloud 免费接口）。
     * 失败返回空串，不影响主流程。
     */
    fun reverseCity(lat: Double, lon: Double): String = runCatching {
        val url = URL(
            "https://api.bigdatacloud.net/data/reverse-geocode-client" +
                "?latitude=$lat&longitude=$lon&localityLanguage=zh"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
        }
        try {
            if (conn.responseCode !in 200..299) return ""
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            sequenceOf("city", "locality", "principalSubdivision")
                .map { o.optString(it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        } finally {
            conn.disconnect()
        }
    }.getOrDefault("")
}
