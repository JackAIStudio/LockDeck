package com.jack.lockdeck.data

/**
 * 一条天气快照。
 *
 * 数据来自中央气象台（weather.cma.cn）的观测站。和以前用 Open-Meteo
 * 的全球模式插值不同，这是**当地气象站实测**，所以能和手机自带天气对上。
 */
data class Weather(
    val tempNow: Int,
    val tempMax: Int,
    val tempMin: Int,
    /** 中央气象台自己的天气代码（0 晴 / 1 多云 / 2 阴 / 7 小雨 / 14 小雪…），只给图标兜底用 */
    val weatherCode: Int,
    val isDay: Boolean,
    /**
     * 显示用的地名。
     *
     * 为什么不是直接用气象站名：市区三个区（鹿城/龙湾/瓯海）**都没有专属气象站**，
     * 共用温州站 58659。如果直接把站名显示出来，用户看到的就是「温州」，
     * 而他手机上的小米天气和锁屏卡片写的是「鹿城区」——明明在同一栋楼里，
     * 两个 App 却给出不同的地名，会让人以为定位错了。
     *
     * 所以：优先用反查到的区县名（鹿城区），没有区县名才用站名（温州）。
     * 数据本身还是那个站的，只是把「你所在的地方」说清楚。
     */
    val city: String,
    /** 反查到的区县名（鹿城区 / 乐清市），可能为空 */
    val district: String = "",
    val updatedAt: Long,
    /**
     * 天气描述，直接取中央气象台返回的中文（「多云」「小雨」）。
     *
     * 以前是自己把 WMO 代码翻译成中文。换成中央气象台后它给的 code 不是 WMO
     * 那一套（0 晴、1 多云、7 小雨，和 WMO 的 0/3/61 完全不同），
     * 硬套会显示错词。接口本来就带中文，直接用，不维护第二张表。
     */
    val text: String = "",
) {
    /** 界面上该显示的地名：优先区县，退到站名。 */
    val place: String
        get() = district.ifBlank { city }

    /** 简短中文描述 */
    val description: String
        get() = if (text.isNotBlank()) text else when (weatherCode) {
            0 -> "晴"
            1 -> "多云"
            2 -> "阴"
            3, 4 -> "阵雨"
            7 -> "小雨"
            8, 9, 10 -> "中到大雨"
            13 -> "阵雪"
            14 -> "小雪"
            15 -> "大雪"
            else -> "—"
        }

    /** 天气图标（emoji，不依赖图片资源） */
    val icon: String
        get() {
            val d = description
            return when {
                d.contains("雷") -> "⛈"
                d.contains("雪") -> "🌨"
                d.contains("雨") -> "🌧"
                d.contains("雾") || d.contains("霾") -> "🌫"
                d.contains("阴") -> "☁"
                d.contains("多云") -> if (isDay) "⛅" else "☁"
                d.contains("晴") -> if (isDay) "☀" else "☾"
                else -> "☁"
            }
        }
}

/**
 * 地名解析结果。
 *
 * 分成市和区县两级，是因为气象站是按区县分布的
 * （温州 58659、乐清 58656、瑞安 58752…）。有了区县名就能挑到更近的站，
 * 而不是永远用市中心那个站。
 */
data class Place(
    /** 地级市，如「温州市」 */
    val city: String = "",
    /** 区县，如「鹿城区」 */
    val district: String = "",
    /** 省，如「浙江省」，用来在省站表里查站号 */
    val province: String = "",
) {
    /** 显示用：有区县优先区县，否则市 */
    val display: String
        get() = district.ifBlank { city }
}
