package com.jack.lockscreenweather.data

/** 一条天气快照。字段全部来自 Open-Meteo 当前+当日预报。 */
data class Weather(
    val tempNow: Int,
    val tempMax: Int,
    val tempMin: Int,
    val weatherCode: Int,
    val isDay: Boolean,
    val city: String,
    val updatedAt: Long,
    /** 今日降水概率（%），没有数据时为 -1 */
    val rainChance: Int = -1,
    /** 未来 1 小时降水概率（%），用来提示"马上要下雨" */
    val rainChanceNextHour: Int = -1,
) {
    /** WMO 天气代码 → 简短中文描述 */
    val description: String
        get() = when (weatherCode) {
            0 -> "晴"
            1 -> "晴间多云"
            2 -> "多云"
            3 -> "阴"
            45, 48 -> "雾"
            51, 53, 55 -> "毛毛雨"
            56, 57 -> "冻雨"
            61 -> "小雨"
            63 -> "中雨"
            65 -> "大雨"
            66, 67 -> "冻雨"
            71 -> "小雪"
            73 -> "中雪"
            75 -> "大雪"
            77 -> "雪粒"
            80, 81 -> "阵雨"
            82 -> "强阵雨"
            85, 86 -> "阵雪"
            95 -> "雷阵雨"
            96, 99 -> "雷暴冰雹"
            else -> "—"
        }

    /** 天气图标（emoji，不依赖图片资源） */
    val icon: String
        get() = when (weatherCode) {
            0 -> if (isDay) "☀" else "☾"
            1 -> if (isDay) "🌤" else "☾"
            2 -> "⛅"
            3 -> "☁"
            45, 48 -> "🌫"
            in 51..57 -> "🌦"
            in 61..67, in 80..82 -> "🌧"
            in 71..77, in 85..86 -> "🌨"
            in 95..99 -> "⛈"
            else -> "—"
        }

    /**
     * 是否要提示带伞。
     * 优先看未来一小时；没有就用全天概率。
     */
    val umbrellaHint: String?
        get() {
            val hour = rainChanceNextHour
            val day = rainChance
            return when {
                hour >= 50 -> "1小时内可能下雨 · 带伞"
                hour in 20..49 -> "1小时内可能有雨"
                hour < 0 && day >= 60 -> "今日降水概率 $day% · 带伞"
                day in 30..59 -> "今日降水概率 $day%"
                else -> null
            }
        }

    /** 降雨风险等级：0 无 / 1 低 / 2 中 / 3 高 */
    val rainLevel: Int
        get() {
            val v = if (rainChanceNextHour >= 0) rainChanceNextHour else rainChance
            return when {
                v < 0 -> 0
                v >= 60 -> 3
                v >= 30 -> 2
                v >= 10 -> 1
                else -> 0
            }
        }
}

/** 城市解析结果，失败时降级为坐标 */
data class Place(val name: String)
