package com.jack.lockdeck.data

/**
 * 一条天气快照。
 *
 * 数据可以是「小米私有接口」也可以是「中央气象台观测站」，取决于
 * [Store.getSource]。但无论哪个源，界面只认这一个模型——
 * 换源不该让界面跟着改。
 */
data class Weather(
    val tempNow: Int,
    val tempMax: Int,
    val tempMin: Int,
    /**
     * 天气代码，**小米那套**（0 晴 / 1 多云 / 2 阴 / 7 小雨 / 14 小雪…）。
     *
     * 中央气象台用的也是同一套码（实测 58659 的 daily 给 0/1/2/7），
     * 所以两个源可以共用一个字段。只给图标和文案兜底用，
     * 正常情况直接读 [text]。
     */
    val weatherCode: Int,
    val isDay: Boolean,
    /**
     * 显示用的地名。
     *
     * 小米源直接给「鹿城」「瓯海」「龙湾」这种区县名，和我们反查到的名字
     * 是同一套（都是中国天气网的城市码体系），所以能跟手机自带天气对上。
     * 中央气象台源只能给到站名（温州），那时退回用反查到的区县名。
     */
    val city: String,
    /** 反查到的区县名（鹿城区 / 乐清市），可能为空 */
    val district: String = "",
    val updatedAt: Long,
    /**
     * 天气描述，直接用源给的中文（「多云」「小雨」「晴转多云」）。
     *
     * 以前是自己把 WMO 代码翻译成中文。换成中央气象台后它给的 code 不是 WMO
     * 那一套（0 晴、1 多云、7 小雨，和 WMO 的 0/3/61 完全不同），
     * 硬套会显示错词。接口本来就带中文，直接用，不维护第二张表。
     */
    val text: String = "",
    /** 体感温度，源不给就是 null（不编） */
    val feelsLike: Int? = null,
    /** 相对湿度 %，源不给就是 null */
    val humidity: Int? = null,
    /** 气压 hPa */
    val pressure: Int? = null,
    /** 紫外线指数 */
    val uvIndex: Int? = null,
    /** 风向（度） */
    val windDirection: Int? = null,
    /** 风速 km/h */
    val windSpeed: Int? = null,
    /** 空气质量（CNEMC），源不给就是 null */
    val aqi: Aqi? = null,
    /** 未来 2 小时逐分钟降水，见 [MinutelyRain] */
    val minutely: MinutelyRain? = null,
    /** 逐 3 小时温度曲线，最多 7 天；源不给就是空 */
    val hourly: List<HourPoint> = emptyList(),
    /** 未来几天的高低温，给「趋势」用 */
    val daily: List<DayPoint> = emptyList(),
    /** 数据来源，界面上要如实标出来 */
    val source: WeatherSource = WeatherSource.CMA,
    /**
     * 接口给的**原始**温度字符串（可能是 "27.6"），不做任何取整。
     *
     * 为什么要留着它：小米官方 App 显示 27、我们显示 28 的时候，
     * 光看两个整数没法判断是谁错了——可能接口给的就是 27.6，
     * 官方 App 向下取整、我们用四舍五入。把原始值摆出来，一眼就能分清
     * 「数据源不一致」还是「取整方式不一致」。
     */
    val tempRaw: String = "",
    /**
     * 实况数据**发布**的时间（接口的 pubTime），不是我们请求的时间。
     *
     * [updatedAt] 是「我们什么时候抓的」，这个是「气象台什么时候观测的」。
     * 两个差得多说明接口本身没更新，不是我们没刷。
     */
    val pubTime: String = "",
    /**
     * 这次实况取自哪个观测站/数据商（接口 sourceMaps 里的原话）。
     *
     * 小米按坐标选站，相邻两个区可能落到不同站点，温度差 1 度很正常。
     * 把它显示出来，用户就能理解「为什么差别区就变了一度」。
     */
    val stationInfo: String = "",
) {
    /** 界面上该显示的地名：优先区县，退到站名。 */
    val place: String
        get() = district.ifBlank { city }

    /** 简短中文描述 */
    val description: String
        get() = if (text.isNotBlank()) text else WeatherCodes.text(weatherCode)

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

    /**
     * 「接下来会不会下雨」——跑单最该先看到的那一句。
     *
     * 分两级，因为两个信息的**可信度和时间尺度完全不同**：
     *
     * 1. **未来 2 小时**（[minutely]）：雷达外推，逐分钟，说「约 25 分钟后下雨」
     *    就是真的快要下了。这条能直接拿来做「现在出不出门」的判断。
     * 2. **2 小时以外**（[hourly] 里第一个降水时次）：模式预报，只给到整点，
     *    说「明天 2 点前后有雨」。参考价值有，但别当闹钟。
     *
     * 最近的优先。都没有就返回 null——**不编造任何「无雨」以外的说法**。
     */
    val nextRain: RainOutlook?
        get() {
            val m = minutely
            if (m != null && m.hasRain) {
                val start = m.startsInMinutes ?: 0
                return RainOutlook(
                    headline = if (start == 0) "正在下雨" else "约 $start 分钟后下雨",
                    minutes = start,
                    lastsMinutes = m.lastsMinutes,
                    nearTerm = true,
                )
            }
            // 2 小时以外的第一场：只认逐小时里明确标成降水的时次
            val wet = hourly.firstOrNull { it.isWet && it.timeMs > updatedAt } ?: return null
            val hoursAhead = ((wet.timeMs - updatedAt) / 3_600_000L).toInt()
            if (hoursAhead <= 2) return null // 两小时内的交给 minutely，别重复说

            // 「2 点前后」这种话必须带日期词，否则晚上 11 点看到「2 点有雨」
            // 根本分不清是凌晨还是下午。按日历天算，不按「几小时后」：
            // 23:50 说的「明天 2 点」和 01:00 说的「今天 2 点」是同一个时刻。
            return RainOutlook(
                headline = "${dayWordFor(wet.timeMs)}${wet.hour} 点前后有雨",
                minutes = hoursAhead * 60,
                lastsMinutes = null,
                nearTerm = false,
            )
        }

    /**
     * 某个时刻相对 [updatedAt] 该说「今天 / 明天 / 后天」。
     *
     * 24 小时的「小时数差」在这里不够用：今天 23:00 与明天 01:00 只差 2 小时，
     * 但一个是今天、一个是明天。所以比日历天。
     */
    private fun dayWordFor(targetMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = updatedAt
        val baseDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = targetMs
        val targetDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
        return when (targetDay - baseDay) {
            0 -> "今天 "
            1 -> "明天 "
            else -> ""
        }
    }

    /** 今日降水概率，接口不给就是 null（见 [DayPoint.rainProbability] 的说明）。 */
    val todayRainProbability: Int?
        get() = daily.firstOrNull()?.rainProbability
}

/**
 * 一场雨。见 [Weather.nextRain]。
 *
 * @param headline 给人看的一句话（「约 25 分钟后下雨」）
 * @param minutes 从现在起还有多少分钟
 * @param lastsMinutes 会下多久，不知道就是 null
 * @param nearTerm true = 雷达外推的分钟级（可信），false = 模式预报（参考）
 */
data class RainOutlook(
    val headline: String,
    val minutes: Int,
    val lastsMinutes: Int?,
    val nearTerm: Boolean,
)

/** 天气数据源。用户可以在设置里切换。 */enum class WeatherSource(val label: String, val detail: String) {
    /**
     * 小米天气私有接口（默认）。
     *
     * 数值口径和手机自带的小米天气完全一致——它俩就是同一个后端。
     * 代价是私有接口：没有文档、没有 SLA，`sign` 哪天变了就失效。
     * 失效时设置页会报错，用户手动切到 [CMA] 即可。
     */
    XIAOMI(
        label = "小米天气",
        detail = "和手机自带天气同一后端，数值一致；含分钟级降水与空气质量",
    ),

    /**
     * 中央气象台 weather.cma.cn 观测站。
     *
     * 免费、无需 key、跑了很久都稳定，但是国家站口径：
     * 温州站 58659 在鹿城五马街道，而市区三个区共用它（瓯海、龙湾没有自己的站），
     * 实测实况会比手机高 2 度上下。作为小米失效后的主力备胎。
     */
    CMA(
        label = "中央气象台",
        detail = "免费无需 key，最稳；按气象站给数据，市区三区共用温州站，实况可能差 2 度",
    ),

    /**
     * Open-Meteo 全球模式。
     *
     * 免费开源、无需 key、有 SLA，是最后一道防线。默认 `best_match`
     * 会挑 ECMWF/ICON，和本地观测差得多（实测温州同一时刻差 2 度）；
     * 这里显式指定 `cma_grapes_global`（中国气象局自己的模式），
     * 实测和温州站观测数值一致。
     *
     * 缺点：模式插值不是观测，且网格约 5 公里——鹿城/瓯海/龙湾
     * 三个坐标它给出的差值不到 0.5 度，等于没有区分度。
     */
    OPEN_METEO(
        label = "Open-Meteo",
        detail = "免费开源有 SLA，全球模式插值；已指定中国 CMA 模式，但仍是格点不是观测",
    ),
    ;

    companion object {
        fun fromName(name: String?): WeatherSource =
            entries.firstOrNull { it.name == name } ?: XIAOMI
    }
}

/** 空气质量。源给 AQI 和六项污染物，全部来自中国环境监测总站。 */
data class Aqi(
    val value: Int,
    val pm25: Int? = null,
    val pm10: Int? = null,
    val o3: Int? = null,
    val no2: Int? = null,
    val so2: Int? = null,
    val co: Float? = null,
    val primary: String = "",
    val suggest: String = "",
) {
    /** 国标分级，给文案和颜色用。 */
    val level: String
        get() = when {
            value <= 50 -> "优"
            value <= 100 -> "良"
            value <= 150 -> "轻度污染"
            value <= 200 -> "中度污染"
            value <= 300 -> "重度污染"
            else -> "严重污染"
        }
}

/**
 * 未来 2 小时的逐分钟降水。
 *
 * 小米给的是 120 个点、单位 mm/h。`values` 里的数字直接就是降水强度，
 * 0 表示没雨。
 *
 * ## 点是 1 分钟一个，不是 2 分钟
 *
 * 这一点是拿真实数据反推出来的，不是猜的：
 * 宁波某次接口给的文案是「10分钟左右雨渐停，不过25分钟左右还会下雨」，
 * 而数组恰好是「第 0-5 点有雨、6-21 点无雨、22 点起又有雨」——
 * 换算成 1 分钟一点就是「6 分钟停、22 分钟又下」，和文案对得上；
 * 按 2 分钟一点就成了「12 分钟停、44 分钟又下」，差了一倍。
 * 所以 [MINUTES_PER_POINT] 是 1，不是旧代码里写的 2。
 *
 * ## 别直接显示源给的 shortDescription
 *
 * 源会返回「正在下雨」「2小时内无降雨」「%d公里外正在下雨」这种模板串，
 * 里面的 `%d` 是**小米 App 自己往模板里套 kmNum 的**，直接显示会漏出
 * `%d` 字样。所以这里只存结构化数据，文案由 [summary] 自己拼。
 */
data class MinutelyRain(
    val values: List<Float>,
    /** 源建议的图标类型：rain_0 / rain_1 / rain_2 … 越大越强 */
    val iconType: String = "",
    /** 雨带距离你多少公里（0 = 就在头上） */
    val kmAway: Int? = null,
    val pubTime: String = "",
) {
    companion object {
        /** 每个点代表多少分钟。见类注释：实测是 1。 */
        const val MINUTES_PER_POINT = 1
    }

    /** 有没有雨（含「马上就下」）。 */
    val hasRain: Boolean
        get() = values.any { it > 0f }

    /**
     * 从现在开始，还要多少分钟才开始下雨。
     *
     * @return 0 = 正在下；null = 两小时内不下
     */
    val startsInMinutes: Int?
        get() {
            val i = values.indexOfFirst { it > 0f }
            return if (i < 0) null else i * MINUTES_PER_POINT
        }

    /** 这轮雨还能下多久（分钟），从第一个非零点算到最后一个非零点。 */
    val lastsMinutes: Int?
        get() {
            val first = values.indexOfFirst { it > 0f }
            if (first < 0) return null
            val last = values.indexOfLast { it > 0f }
            return (last - first + 1) * MINUTES_PER_POINT
        }

    /** 一句话文案。自己拼，不用源的模板串（含 %d）。 */
    val summary: String
        get() {
            if (!hasRain) {
                val km = kmAway
                return when {
                    km == null || km <= 0 -> "2 小时内无雨"
                    km < 100 -> "雨带在 ${km} 公里外"
                    else -> "2 小时内无雨"
                }
            }
            return when (val s = startsInMinutes) {
                null -> "2 小时内无雨"
                0 -> "正在下雨" + (lastsMinutes?.let { "，约 ${it} 分钟" } ?: "")
                else -> "约 $s 分钟后下雨" + (lastsMinutes?.let { "，持续 ${it} 分钟" } ?: "")
            }
        }
}

/** 逐小时曲线上的一个点。 */
data class HourPoint(
    /** 时间戳（毫秒），用来判断「现在」和画轴 */
    val timeMs: Long,
    /** 当地钟点，0-23，用来排序和格式化 */
    val hour: Int,
    val temp: Int,
    val weatherCode: Int = 0,
) {
    /** 是否白天（6:00-18:00），挑图标用 */
    val isDay: Boolean get() = hour in 6..17

    /** 这个时次会不会下（雨/雪/雷），用来找「下一场雨」。 */
    val isWet: Boolean get() = WeatherCodes.isPrecipitation(weatherCode)
}

/** 未来某一天。 */
data class DayPoint(
    val date: String,
    val weekday: String,
    val weatherCode: Int,
    val text: String,
    val tempMax: Int,
    val tempMin: Int,
    /**
     * 这一天的降水概率（0-100），接口不给我就是 null。
     *
     * 小米给的这一列是**彩云**算的（看 `sourceMaps.daily.preciProbability`），
     * 不是中国天气网那套。实测它跟「会不会下雨」并不总是一致：
     * 长沙 11/7 报小雨、这一列却给 0。所以它只能当参考，
     * 不能拿来判断「今天下不下」——那件事用 [weatherCode] 和分钟级降水判断。
     */
    val rainProbability: Int? = null,
)

/**
 * 地名解析结果。
 *
 * 分成市和区县两级，是因为气象站是按区县分布的
 * （温州 58659、乐清 58656、瑞安 58752…）。有了区县名就能挑到更近的站，
 * 而不是永远用市中心那个站。
 *
 * 小米源则直接按坐标反查自己的 locationKey（`weathercn:101210710` = 鹿城），
 * 反查结果也写回这里，两个源共用同一份地名。
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
