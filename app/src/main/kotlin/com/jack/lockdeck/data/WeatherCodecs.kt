package com.jack.lockdeck.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 快照里那些「列表型」字段的存取。
 *
 * ## 为什么不直接上数据库
 *
 * 这些数据一次只有几十条（24 小时曲线、7 天预报、120 个降水点），
 * 而且**只保留最新一份**——没有查询、没有历史、没有关联。
 * 为它建 Room 属于给自行车装发动机。用 JSON 串存进 SharedPreferences，
 * 读一次解析一次，几十微秒的事。
 *
 * 唯一的代价是：每次刷新要序列化整串。量级在 2KB 左右，无所谓。
 */

/** 空气质量。 */
object AqiCodec {
    fun encode(a: Aqi): String = JSONObject().apply {
        put("v", a.value)
        a.pm25?.let { put("pm25", it) }
        a.pm10?.let { put("pm10", it) }
        a.o3?.let { put("o3", it) }
        a.no2?.let { put("no2", it) }
        a.so2?.let { put("so2", it) }
        a.co?.let { put("co", it.toDouble()) }
        put("primary", a.primary)
        put("suggest", a.suggest)
    }.toString()

    fun decode(s: String?): Aqi? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(s)
            Aqi(
                value = o.getInt("v"),
                pm25 = o.optIntOrNull("pm25"),
                pm10 = o.optIntOrNull("pm10"),
                o3 = o.optIntOrNull("o3"),
                no2 = o.optIntOrNull("no2"),
                so2 = o.optIntOrNull("so2"),
                co = o.optDouble("co", Double.NaN).let { if (it.isNaN()) null else it.toFloat() },
                primary = o.optString("primary"),
                suggest = o.optString("suggest"),
            )
        }.getOrNull()
    }

    private fun JSONObject.optIntOrNull(k: String): Int? =
        if (has(k)) optInt(k) else null
}

/** 分钟级降水。**120 个浮点数**，是这里唯一体积可观的东西。 */
object MinutelyCodec {
    fun encode(m: MinutelyRain): String = JSONObject().apply {
        put("v", JSONArray(m.values))
        put("icon", m.iconType)
        m.kmAway?.let { put("km", it) }
        put("pub", m.pubTime)
    }.toString()

    fun decode(s: String?): MinutelyRain? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(s)
            val arr = o.getJSONArray("v")
            val vals = ArrayList<Float>(arr.length())
            for (i in 0 until arr.length()) vals += arr.getDouble(i).toFloat()
            MinutelyRain(
                values = vals,
                iconType = o.optString("icon"),
                kmAway = if (o.has("km")) o.optInt("km") else null,
                pubTime = o.optString("pub"),
            )
        }.getOrNull()
    }
}

/** 逐小时曲线。 */
object HourCodec {
    fun encode(list: List<HourPoint>): String {
        if (list.isEmpty()) return ""
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONArray().apply {
                put(it.timeMs); put(it.hour); put(it.temp); put(it.weatherCode)
            })
        }
        return arr.toString()
    }

    fun decode(s: String?): List<HourPoint> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val a = arr.getJSONArray(i)
                    HourPoint(a.getLong(0), a.getInt(1), a.getInt(2), a.getInt(3))
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}

/** 未来几天。 */
object DayCodec {
    fun encode(list: List<DayPoint>): String {
        if (list.isEmpty()) return ""
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONArray().apply {
                put(it.date); put(it.weekday); put(it.weatherCode); put(it.text)
                put(it.tempMax); put(it.tempMin)
            })
        }
        return arr.toString()
    }

    fun decode(s: String?): List<DayPoint> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val a = arr.getJSONArray(i)
                    DayPoint(a.getString(0), a.getString(1), a.getInt(2),
                        a.getString(3), a.getInt(4), a.getInt(5))
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
