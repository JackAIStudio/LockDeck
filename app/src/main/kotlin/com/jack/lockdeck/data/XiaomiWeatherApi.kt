package com.jack.lockdeck.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 小米天气私有接口。
 *
 * ## 这是什么东西
 *
 * 小米天气 App（`com.miui.weather2`）自己用的后端 `weatherapi.market.xiaomi.com`，
 * **不是公开 API**：没有文档、没有 SLA、没有使用条款。
 * `appKey` / `sign` 是 Breezy Weather 开源代码里硬编码的常量，我们照抄。
 *
 * 实测（2026-11-01）：
 *  - 连打 120 次（8 并发）全部 200，没有限流迹象
 *  - `sign` **只校验非空，不校验内容**——填个 `WRONGSIGN123` 照样返回数据。
 *    这说明它对我们是敞开的，但也意味着**哪天它开始真校验，我们立刻失效**。
 *  - 不带 `locationKey` 也能按坐标返回正确数据（服务端会自己解析）
 *
 * ## 为什么值这个风险
 *
 * 数值口径和手机自带天气**完全一致**——本来就是同一个后端。
 * 实测同一时刻：小米 18.0°C / 中国天气网鹿城 18.2°C / 中央气象台温州站 20.6°C。
 * 用户手机上看到 18°，我们显示 20.6°，那就是 bug。
 *
 * 所以在 [WeatherSource] 里它是默认值，失效时用户在设置页一键切到
 * 中央气象台或 Open-Meteo，见 [WeatherRepo]。
 *
 * ## 一次请求拿全部？早就不是了
 *
 * `/weather/all` 一次返回实况、15 天预报、23 小时逐时、空气质量、生活指数、预警。
 *
 * **分钟级降水不在里面了**（2026-11 实测：响应里根本没有 `minutely` 这个键，
 * 但顶层文档和旧代码都以为有）。官方 App 早就把它拆成了独立接口：
 *
 *     /wtr-v3/weather/xm/forecast/minutely?latitude=&longitude=&locationKey=
 *
 * 必须是这个路径。`/weather/all?minutely=1` 之类的参数一律无效，返回里也不会有。
 *
 * 这个拆分我们漏跟了：解析代码写得没错，只是它读的键不存在，永远返回 null，
 * 于是「约 X 分钟后下雨」从上线起就没出现过。这类 bug 不报错、不崩，
 * 只是安静地少一个功能——只有拿真数据比对才看得出来。
 */
object XiaomiWeatherApi {

    private const val BASE = "https://weatherapi.market.xiaomi.com/wtr-v3"

    /** Breezy Weather 里硬编码的那两个常量。改了就要跟着改。 */
    private const val APP_KEY = "weather20151024"
    private const val SIGN = "zUFJoAR2ZVrDy1vF3D07"

    /**
     * 拿不到真实 locationKey 时兜的默认值。
     *
     * 实测服务端只用它做非空校验、真正的定位靠 lat/lon，所以填这个
     * 温州鹿城的 key 不会让别人看到温州的天气。留着是为了「宁可降水曲线
     * 稍微不准，也不要在这一步整个失败」——temperature 才是主功能。
     */
    private const val DEFAULT_LOCATION_KEY = "weathercn:101210710"

    /** 坐标 → locationKey 的缓存，只在内存里，进程重启后重查一次。 */
    private val keyCache = HashMap<String, String>()

    /**
     * 拉一次完整天气。
     *
     * @param lat 纬度
     * @param lon 经度
     * @param place 反查到的地名，只用来填显示用的区县名（接口自己也会返区县名）
     */
    fun fetch(lat: Double, lon: Double, place: Place, now: Long = System.currentTimeMillis()): Weather {
        val json = getJson(
            "$BASE/weather/all?latitude=$lat&longitude=$lon&isLocated=true" +
                "&days=15&appKey=$APP_KEY&sign=$SIGN&isGlobal=false&locale=zh_cn"
        )

        val current = json.optJSONObject("current") ?: error("接口没返回 current")
        val daily = json.optJSONObject("forecastDaily")
        val hourly = json.optJSONObject("forecastHourly")

        // ---- 实况 ----
        val tempNow = optInt(current.optJSONObject("temperature")) ?: error("实况没有温度")
        val code = current.optInt("weather", 0)

        // ---- 今日高低温：daily.temperature.value[0] = {from: 最高, to: 最低} ----
        var tempMax = tempNow
        var tempMin = tempNow
        val dayList = mutableListOf<DayPoint>()
        val temps = daily?.optJSONObject("temperature")?.optJSONArray("value")
        val dayCodes = daily?.optJSONObject("weather")?.optJSONArray("value")
        val dayProbs = daily?.optJSONObject("precipitationProbability")?.optJSONArray("value")
        if (temps != null && temps.length() > 0) {
            runCatching {
                val d0 = temps.getJSONObject(0)
                tempMax = Math.round(d0.optString("from").toDoubleOrNull() ?: tempNow.toDouble()).toInt()
                tempMin = Math.round(d0.optString("to").toDoubleOrNull() ?: tempNow.toDouble()).toInt()
            }
            dayList += buildDays(temps, dayCodes, dayProbs)
        }

        // ---- 逐小时温度曲线 ----
        val hours = buildHours(hourly)

        // ---- 空气质量 ----
        val aqi = parseAqi(json.optJSONObject("aqi"))

        // ---- 分钟级降水 ----
        // 独立接口，不能从上面那个 json 里取（详见类注释）。
        // 它失败不该让整次刷新失败——温度是主功能，降水是加分项。
        val locationKey = runCatching { resolveLocationKey(lat, lon) }.getOrDefault("")
        val minutely = runCatching {
            fetchMinutely(lat, lon, locationKey.ifBlank { DEFAULT_LOCATION_KEY })
        }.getOrNull()

        // ---- 实况其余要素 ----
        val wind = current.optJSONObject("wind")
        val windDir = wind?.optJSONObject("direction")?.optString("value")?.toDoubleOrNull()
        val windSpd = wind?.optJSONObject("speed")?.optString("value")?.toDoubleOrNull()

        val nowCal = Calendar.getInstance().apply { timeInMillis = now }

        return Weather(
            tempNow = tempNow,
            tempMax = tempMax,
            tempMin = tempMin,
            weatherCode = code,
            isDay = nowCal.get(Calendar.HOUR_OF_DAY) in 6..17,
            // 接口不给站名，地名全用反查结果；接口自己认出的区县另存一份
            city = place.city,
            district = place.district.ifBlank { resolveDistrictName(json, lat, lon) },
            updatedAt = now,
            text = WeatherCodes.text(code),
            feelsLike = optInt(current.optJSONObject("feelsLike")),
            humidity = optInt(current.optJSONObject("humidity")),
            pressure = optInt(current.optJSONObject("pressure")),
            uvIndex = current.optString("uvIndex").toIntOrNull(),
            windDirection = windDir?.let { Math.round(it).toInt() },
            windSpeed = windSpd?.let { Math.round(it).toInt() },
            aqi = aqi,
            minutely = minutely,
            hourly = hours,
            daily = dayList,
            source = WeatherSource.XIAOMI,
            tempRaw = current.optJSONObject("temperature")?.optString("value").orEmpty(),
            pubTime = current.optString("pubTime").orEmpty(),
            stationInfo = stationOf(json),
        )
    }

    /**
     * 按坐标反查小米自己的城市（区县）。
     *
     * 这个只返回**区县**级别的名字（鹿城 / 瓯海 / 龙湾），没有街道。
     * App 界面上那种「鹿城区 方池巷」的巷子名不在这里——那个是高德定位 SDK
     * 给的，走的是另一套服务，跟天气无关。
     */
    fun resolveDistrict(lat: Double, lon: Double): String = runCatching {
        val arr = getJsonArray("$BASE/location/city/geo?latitude=$lat&longitude=$lon&locale=zh_cn")
        arr.optJSONObject(0)?.optString("name").orEmpty()
    }.getOrDefault("")

    // ---------------------------------------------------------------- 解析

    /**
     * 逐日预报。日期接口不给，自己从今天往前推。
     *
     * 降水概率是单独一列（`forecastDaily.precipitationProbability.value`），
     * 和温度/天气那两列**下标对齐**，所以必须三个数组一起读。
     */
    private fun buildDays(temps: JSONArray, codes: JSONArray?, probs: JSONArray?): List<DayPoint> {
        val out = mutableListOf<DayPoint>()
        val fmtDate = SimpleDateFormat("M月d日", Locale.CHINA)
        val fmtWeek = SimpleDateFormat("EEE", Locale.CHINA)
        for (i in 0 until minOf(temps.length(), 15)) {
            runCatching {
                val t = temps.getJSONObject(i)
                val hi = Math.round(t.optString("from").toDouble()).toInt()
                val lo = Math.round(t.optString("to").toDouble()).toInt()
                val c = codes?.optJSONObject(i)
                val dayCode = c?.optString("from")?.toIntOrNull() ?: 0
                val draft = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, i) }
                out += DayPoint(
                    date = fmtDate.format(draft.time),
                    weekday = if (i == 0) "今天" else fmtWeek.format(draft.time),
                    weatherCode = dayCode,
                    text = WeatherCodes.text(dayCode),
                    tempMax = hi,
                    tempMin = lo,
                    // 接口给的是字符串数组，缺项会被省掉，所以按下标取、取不到就是 null
                    rainProbability = probs?.optString(i)?.toIntOrNull(),
                )
            }
        }
        return out
    }

    /**
     * 逐小时温度曲线。
     *
     * 接口给的是 23 个时次（不是 24），每个时次带 `datetime`，
     * 所以时间以它为准，不听 `pubTime`。
     */
    private fun buildHours(hourly: JSONObject?): List<HourPoint> {
        val temps = hourly?.optJSONObject("temperature")?.optJSONArray("value") ?: return emptyList()
        val codes = hourly.optJSONObject("weather")?.optJSONArray("value")
        val winds = hourly.optJSONObject("wind")?.optJSONArray("value")
        val out = mutableListOf<HourPoint>()

        // 时次的时间戳藏在 wind.value[i].datetime 里——temperature 数组本身没有时间。
        // 这正是这个接口让人头疼的地方：拿温度就必须同时拿风。
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }

        for (i in 0 until temps.length()) {
            runCatching {
                val temp = Math.round(temps.getDouble(i)).toInt()
                val w = winds?.optJSONObject(i)
                val raw = w?.optString("datetime").orEmpty()
                // "2026-11-01T09:00:00.000+08:00" -> 去掉毫秒再解析
                val clean = raw.substringBefore('.').take(19)
                val ms = fmt.parse(clean)?.time ?: 0L
                val cal = Calendar.getInstance().apply { timeInMillis = ms }
                out += HourPoint(
                    timeMs = ms,
                    hour = cal.get(Calendar.HOUR_OF_DAY),
                    temp = temp,
                    weatherCode = codes?.optInt(i, 0) ?: 0,
                )
            }
        }
        return out
    }

    private fun parseAqi(o: JSONObject?): Aqi? {
        if (o == null) return null
        val v = o.optString("aqi").toIntOrNull() ?: return null
        return Aqi(
            value = v,
            pm25 = o.optString("pm25").toIntOrNull(),
            pm10 = o.optString("pm10").toIntOrNull(),
            o3 = o.optString("o3").toIntOrNull(),
            no2 = o.optString("no2").toIntOrNull(),
            so2 = o.optString("so2").toIntOrNull(),
            co = o.optString("co").toFloatOrNull(),
            primary = o.optString("primary"),
            suggest = o.optString("suggest"),
        )
    }

    /**
     * 分钟级降水：**独立接口**，120 个点、1 分钟一个，共 2 小时。
     *
     * ## 为什么不能从 `/weather/all` 里拿
     *
     * 那个响应里已经**没有** `minutely` 键了（2026-11 实测）。
     * 旧代码一直解析它，永远拿到 null——所以这个功能从来没生效过，
     * 而且不报错。详见类注释。
     *
     * ## 参数
     *
     * - `locationKey` **必填**，缺了直接 400（`Param locationKey error`）。
     *   但实测它**不校验内容**：填一个别的城市的 key、甚至假的 key，
     *   返回的仍是按 lat/lon 算出来的那份。所以取不到 key 时兜一个默认值，
     *   不要因为这一步失败就丢掉降水预报。
     * - 频次比实况高得多（官方大约 1 分钟一更），`pubTime` 会精确到秒。
     *   我们按 5 分钟刷，够用——这是「未来两小时」的曲线，不是逐秒播报。
     *
     * ## 文案不要用接口给的
     *
     * `description` 里偶尔带 `%d`（那是小米 App 自己往模板里填 kmNum 的），
     * 直接显示会漏出占位符。只取结构化的 `value` / `kmNum` / `headIconType`，
     * 文案由 `MinutelyRain.summary` 自己拼。
     */
    private fun fetchMinutely(lat: Double, lon: Double, locationKey: String): MinutelyRain? {
        val json = getJson(
            "$BASE/weather/xm/forecast/minutely?latitude=$lat&longitude=$lon" +
                "&locale=zh_cn&isLocated=true&locationKey=$locationKey" +
                "&appKey=$APP_KEY&sign=$SIGN&isGlobal=false"
        )
        val p = json.optJSONObject("precipitation") ?: return null
        val arr = p.optJSONArray("value") ?: return null
        if (arr.length() == 0) return null
        val vals = ArrayList<Float>(arr.length())
        for (i in 0 until arr.length()) {
            vals += runCatching { arr.getDouble(i).toFloat() }.getOrDefault(0f)
        }
        return MinutelyRain(
            values = vals,
            iconType = p.optString("headIconType"),
            kmAway = p.optString("kmNum").toIntOrNull(),
            pubTime = p.optString("pubTime"),
        )
    }

    /**
     * 按坐标反查小米自己的 locationKey（`weathercn:101210710` = 鹿城）。
     *
     * 分钟级降水那个接口要求带它。按城市粒度取，一次刷新只需要查一次，
     * 查到的结果缓存在内存里——同一个城市反复刷新不必重复请求。
     */
    private fun resolveLocationKey(lat: Double, lon: Double): String {
        // 坐标抖一点点（室内定位）就当成同一个城市，别每刷一次都换 key
        val cacheKey = "%.2f,%.2f".format(lat, lon)
        keyCache[cacheKey]?.let { return it }
        val arr = getJsonArray("$BASE/location/city/geo?latitude=$lat&longitude=$lon&locale=zh_cn")
        val key = arr.optJSONObject(0)?.optString("locationKey").orEmpty()
        if (key.isNotBlank()) keyCache[cacheKey] = key
        return key
    }

    /**
     * 接口不返回中文地名时，补一次 geo 反查。
     *
     * 只在 [fetch] 里 `place.district` 为空时才调——正常路径（[LocationHelper]
     * 反查成功）不会走到这里，所以多出来的这**一次请求**很少发生。
     */
    private fun resolveDistrictName(json: JSONObject, lat: Double, lon: Double): String {
        // 响应本身没有地名（实测 locationKey 之外啥都没有），只能另查
        return resolveDistrict(lat, lon)
    }

    /**
     * 这次实况用的是哪个观测站。
     *
     * `sourceMaps.current.temperature` 的形如 `weatherbj(locationKey=101210710)`：
     *   - weatherbj = 北京气象局供的数（小米聚合了多家）
     *   - locationKey = 中国天气网的城市码，同一个码才是同一个站
     *
     * 小米官方 App 也读这个接口，但它在自己那边按「更细的定位」选站；
     * 我们从系统拿到的坐标（尤其室内是基站定位）可能落到隔壁站，
     * 于是出现 1 度差。把站码显示出来，「对不上」就变成了可解释的事。
     */
    private fun stationOf(json: JSONObject): String {
        val raw = json.optJSONObject("sourceMaps")
            ?.optJSONObject("current")
            ?.optString("temperature")
            .orEmpty()
        if (raw.isBlank()) return ""
        // weatherbj(locationKey=101210710) -> 北京气象局 · 101210710
        val provider = raw.substringBefore('(')
        val key = raw.substringAfter("locationKey=", "").substringBefore(')')
        val providerName = when (provider) {
            "weatherbj" -> "北京气象局"
            "caiyun" -> "彩云天气"
            "moji" -> "墨迹天气"
            else -> provider
        }
        return if (key.isBlank()) providerName else "$providerName · $key"
    }

    private fun optInt(o: JSONObject?): Int? {
        if (o == null) return null
        val s = o.optString("value")
        return s.toDoubleOrNull()?.let { Math.round(it).toInt() }
    }

    // ---------------------------------------------------------------- http

    private fun getJson(url: String): JSONObject {
        val body = getRaw(url)
        return JSONObject(body)
    }

    private fun getJsonArray(url: String): JSONArray {
        val body = getRaw(url)
        return JSONArray(body)
    }

    private fun getRaw(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16)")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
