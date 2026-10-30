package com.jack.lockdeck.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 定位。
 *
 * ## 这里以前错在哪
 *
 * 老版本在拿不到位置时直接返回一个写死的深圳坐标：
 * `FALLBACK = Fix(22.5431, 114.0579)`。这个「兜底」是最坏的一种设计：
 *
 *  - 界面上看不出任何异常。深圳 32°、温州 25°，差了 7 度，用户只会以为
 *    天气接口不准，不会想到「拿的根本不是我的位置」。
 *  - 城市名那一栏还写着反查出来的名字，看起来更像真的。
 *  - 用户明明在温州，却看到深圳的天气，而且没有任何提示。
 *
 * 现在的规矩：**拿不到位置就返回 null，宁可不显示，也不编一个**。
 * 上层拿到 null 就显示「等待定位」，不写数字。
 *
 * ## 为什么要主动请求，而不是只读 lastKnownLocation
 *
 * 老版本只读系统缓存（getLastKnownLocation）。这有两个问题：
 *
 *  1. 缓存可能是几小时前的，人早就走开了（跑外卖一小时能跨好几个区）。
 *  2. 后台刷新时（WorkManager，没有前台界面）缓存往往直接是 null，
 *     因为没人向系统请求过定位，系统不会主动更新。
 *
 * 所以这里改成：缓存够新就用缓存（省电、瞬间返回），
 * 缓存太旧就主动请求一次定位。请求带超时，拿不到就返回 null。
 *
 * ## 后台定位的坑
 *
 * Android 10+ 把定位权限拆成「前台」和「后台」两档。只申请前台档时，
 * 后台任务里调 requestLocationUpdates 会被系统静默拒绝（不报错，就是不给）。
 *
 * 处理办法是两层：
 *  - 申请 ACCESS_BACKGROUND_LOCATION（用户要在系统设置里额外点「始终允许」）
 *  - 就算没给后台权限，也把「最后一次成功定位」持久化下来，后台刷新用这份，
 *    总比跳到一个写死的城市强
 */
object LocationHelper {

    data class Fix(val lat: Double, val lon: Double, val time: Long)

    /**
     * 缓存多久算「还新鲜」。
     *
     * 原来给的是 10 分钟，实测有问题：坐车 10 分钟能开出十几公里，
     * 这期间刷新出来的天气还是出发地的。而且系统缓存**只在有 App 请求定位时才更新**，
     * 没人请求时它会一直停在老位置——人已经走了，缓存却「看起来很新」。
     *
     * 收到 2 分钟：正常刷新间隔是 5~10 分钟，也就是说每次刷新都会真正定位一次，
     * 代价是多花几秒，换来的是位置跟得上人。
     */
    private const val FRESH_ENOUGH_MS = 2 * 60 * 1000L

    /** 主动定位最多等这么久，超时就用缓存/返回 null。 */
    private const val ACTIVE_TIMEOUT_MS = 8_000L

    /**
     * 两个位置差多少米还算「同一个地方」。
     *
     * 200 米：足够吸收 GPS 漂移和不同 provider 的差异，
     * 又远小于「换了个区县」的尺度。超过它就要重新定位/重新选气象站。
     */
    private const val SAME_PLACE_M = 200.0

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * 有没有「后台也能定位」的权限。
     *
     * Android 10 起这是独立的一档，用户必须在系统设置里手动选「始终允许」。
     * 我们的定位是给常驻通知/磁贴用的，后台拿不到位置就等于没用，
     * 所以设置页要能看出这一档给没给。
     */
    fun hasBackgroundPermission(ctx: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasPermission(ctx)
        }

    /**
     * 取一个可用的位置。拿不到返回 null——**不返回任何编出来的坐标**。
     *
     * @param allowActive 允不允许主动请求定位。后台刷新也传 true，
     *                    但没有后台权限时系统会静默失败，自动走缓存兜底。
     */
    @SuppressLint("MissingPermission")
    fun get(ctx: Context, allowActive: Boolean = true): Fix? =
        getWithSource(ctx, allowActive)?.first

    /** 和 [get] 一样，但同时告诉你这个位置是「刚定位的」还是「退到缓存」的。 */
    @SuppressLint("MissingPermission")
    fun getWithSource(
        ctx: Context,
        allowActive: Boolean = true,
    ): Pair<Fix, WeatherRepo.FixSource>? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val cached = bestCached(lm)

        // 缓存够新、而且和上次用的是同一个位置，就直接用：省电、也不惊动系统。
        //
        // 这里多了一个「位置没变」的判断。只看时间戳是不够的：
        // 系统缓存的时间戳在**没人请求定位时不会推进**，于是可能出现
        // 「缓存看起来是 30 秒前的，实际那是三小时前停的地方」。
        // 加上「和历史位置一致」这一条，只要人动过，下次刷新就会真的去定位。
        if (cached != null && isFreshAndSame(ctx, cached)) {
            persist(ctx, cached)
            return cached to WeatherRepo.FixSource.CACHED
        }

        if (allowActive) {
            val fresh = requestOnce(lm)
            if (fresh != null) {
                persist(ctx, fresh)
                return fresh to WeatherRepo.FixSource.FRESH
            }
        }

        // 主动拿不到：退到缓存，再退到「上次成功定位过的地方」。
        // 注意那是**这台设备真实待过的地方**，不是别的城市。
        if (cached != null) {
            persist(ctx, cached)
            return cached to WeatherRepo.FixSource.CACHED
        }
        return persisted(ctx)?.let { it to WeatherRepo.FixSource.PERSISTED }
    }

    /** 缓存既是「新的」、又和上次用的一样才算可直接复用。 */
    private fun isFreshAndSame(ctx: Context, fix: Fix): Boolean {
        if (System.currentTimeMillis() - fix.time >= FRESH_ENOUGH_MS) return false
        val (oldLat, oldLon) = persisted(ctx) ?: return true // 之前没定位过，用这份
        return Geo.distanceMeters(oldLat, oldLon, fix.lat, fix.lon) < SAME_PLACE_M
    }

    /**
     * 系统缓存里最新的那个位置。
     *
     * **遍历系统现有的全部 provider，不写死三个。**
     *
     * 老版本只问 gps / network / passive。这在原生 Android 上够用，
     * 但国产 ROM 常加自己的 provider（小米的 `com.xiaomi.location.fused`
     * 暴露成 fused，还有各家的 metok 网络定位），基站辅助定位也可能只写进自己的 provider。
     * 只问那三个的话，明明系统里有新位置却读不到，
     * 表现就是「位置一直不动」——和「没权限」看起来一模一样，极难排查。
     */
    @SuppressLint("MissingPermission")
    private fun bestCached(lm: LocationManager): Fix? {
        var best: Location? = null
        val providers = runCatching { lm.allProviders }.getOrNull()
            ?: listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
        for (provider in providers) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        return best?.let { Fix(it.latitude, it.longitude, it.time) }
    }

    /** 当前系统里可用于主动请求的 provider，网络/融合优先（快、省电），再退 GPS。 */
    private fun activeProviders(lm: LocationManager): List<String> {
        val all = runCatching { lm.allProviders }.getOrNull().orEmpty()
        val usable = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)
        return buildList {
            // 融合/网络定位先试：室内也能出结果，也不用等卫星
            if (all.contains(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (all.contains(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            if (usable && all.contains(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            // 兜底：系统里还有别的能用就都算上
            all.filterNot { it == LocationManager.PASSIVE_PROVIDER || it in this }
                .forEach { add(it) }
        }
    }

    /**
     * 主动请求一次定位，最多等 [ACTIVE_TIMEOUT_MS]。
     *
     * 用 CountDownLatch 而不是 getCurrentLocation：后者在部分国产 ROM 上
     * 回调可能不触发（也不报错），表现就是卡住；这里用超时兜住，
     * 最坏情况是等 8 秒然后返回 null，不会把刷新流程拖死。
     */
    @SuppressLint("MissingPermission")
    private fun requestOnce(lm: LocationManager): Fix? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Location? = null

        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                if (result == null) result = location
                latch.countDown()
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("兼容旧接口")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }

        val main = android.os.Looper.getMainLooper()
        // 同时在所有可用 provider 上挂监听，谁先给就用谁的。
        // 只挂 network 的话，纯 GPS 环境（户外、没网）永远等不到；
        // 只挂 gps 的话，室内永远等不到。两个一起挂，8 秒内谁先来算谁的。
        val registered = mutableListOf<String>()
        for (provider in activeProviders(lm)) {
            val ok = runCatching {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, main)
                true
            }.getOrDefault(false)
            if (ok) registered += provider
        }

        latch.await(ACTIVE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        runCatching { lm.removeUpdates(listener) }

        return result?.let { Fix(it.latitude, it.longitude, it.time) }
    }

    // ---- 「上次成功定位」持久化 ----

    private fun persist(ctx: Context, fix: Fix) {
        Store.prefs(ctx).edit()
            .putString("fixLat", fix.lat.toString())
            .putString("fixLon", fix.lon.toString())
            .putLong("fixAt", fix.time)
            .apply()
    }

    private fun persisted(ctx: Context): Fix? {
        val p = Store.prefs(ctx)
        val lat = p.getString("fixLat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("fixLon", null)?.toDoubleOrNull() ?: return null
        return Fix(lat, lon, p.getLong("fixAt", 0L))
    }

    /**
     * 反查城市名 / 区县名。
     *
     * 以前这个函数只在「缓存里没有城市名」时才被调用，结果城市名一旦写进去
     * 就再也不更新：从深圳到温州，温度换成温州的、城市名还挂着旧的，看起来
     * 就像反查接口在说谎。现在每次都跟着坐标一起刷新，并且额外返回区县名
     * （鹿城区这种），让显示精度和气象台对齐。
     *
     * ## 繁简字这个坑
     *
     * 接口默认给**繁体**（实测乐清返回「樂清市」，温州返回「温州市」——后者繁简同形
     * 所以一直没人发现）。中央气象台的站表是简体，拿繁体去匹配永远匹配不上，
     * 表现就是「明明在乐清，却用了温州站的温度」。
     *
     * 两道保险：
     *  1. 请求时用 `zh-Hans`，实测能直接拿到简体
     *  2. 回来后再过一遍 [toSimplified]，防止它哪天又不认语言参数
     */
    fun reversePlace(lat: Double, lon: Double): Place = runCatching {
        val url = URL(
            "https://api.bigdatacloud.net/data/reverse-geocode-client" +
                "?latitude=$lat&longitude=$lon&localityLanguage=zh-Hans"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
        }
        try {
            if (conn.responseCode !in 200..299) return Place()
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            // city 是地级市（温州市），locality 是区县（乐清市 / 鹿城区），
            // principalSubdivision 是省（浙江省）——省用来查省站表选气象站。
            Place(
                city = toSimplified(o.optString("city").trim()),
                district = toSimplified(o.optString("locality").trim()),
                province = toSimplified(o.optString("principalSubdivision").trim()),
            )
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(Place())

    /**
     * 反查失败时的保底：用坐标在**已知城市表**里找最近的一个。
     *
     * 为什么需要它：反查是 extras 请求，失败率比气象台高。以前一失败就整次刷新作废，
     * 用户看到的是「明明有网却一直没数据」。这里用一张很小的城市表兜住——
     * 宁可气象站选到 30 公里外的，也要先把温度显示出来。
     *
     * 表只收常见城市（跑外卖、出差会去的那些），不追求全国覆盖：
     * 真掉到表外，就老老实实返回 null，让上层显示「认不出位置」，
     * 也不编一个假城市。
     */
    fun nearestCity(lat: Double, lon: Double): Place? {
        val known = listOf(
            Triple("温州市", "浙江省", 28.00 to 120.65),
            Triple("杭州市", "浙江省", 30.27 to 120.16),
            Triple("宁波市", "浙江省", 29.87 to 121.54),
            Triple("深圳市", "广东省", 22.54 to 114.06),
            Triple("广州市", "广东省", 23.13 to 113.26),
            Triple("东莞市", "广东省", 23.02 to 113.75),
            Triple("上海市", "上海市", 31.23 to 121.47),
            Triple("北京市", "北京市", 39.90 to 116.41),
            Triple("南京市", "江苏省", 32.06 to 118.80),
            Triple("苏州市", "江苏省", 31.30 to 120.58),
            Triple("成都市", "四川省", 30.57 to 104.07),
            Triple("武汉市", "湖北省", 30.59 to 114.31),
            Triple("西安市", "陕西省", 34.34 to 108.94),
            Triple("重庆市", "重庆市", 29.56 to 106.55),
            Triple("长沙市", "湖南省", 28.23 to 112.94),
            Triple("郑州市", "河南省", 34.75 to 113.63),
            Triple("青岛市", "山东省", 36.07 to 120.38),
            Triple("厦门市", "福建省", 24.48 to 118.09),
            Triple("福州市", "福建省", 26.07 to 119.30),
        )
        // 150 公里内才算「就在附近」。再远就不是「选站远一点」的问题了，
        // 而是彻底认错城市，那还不如直接报错。
        val hit = known
            .map { it to Geo.distanceMeters(it.third.first, it.third.second, lat, lon) }
            .minByOrNull { it.second }
            ?.takeIf { it.second <= 150_000 }
            ?: return null
        return Place(city = hit.first.first, province = hit.first.second)
    }

    /**
     * 常见繁体地名用字 → 简体。
     *
     * 只收地名词里真会出现的那些字，不做全表（全表要拖一个 mapping 库，
     * 为了几个地名不值当）。漏掉了也不会错，只是退回用市名。
     */
    private fun toSimplified(s: String): String {
        if (s.isEmpty()) return s
        val map = mapOf(
            '樂' to '乐', '陽' to '阳', '東' to '东', '興' to '兴', '慶' to '庆',
            '龍' to '龙', '灣' to '湾', '寧' to '宁', '臺' to '台',
            '雲' to '云', '華' to '华', '區' to '区', '縣' to '县', '鎮' to '镇',
            '溫' to '温', '廣' to '广', '開' to '开', '濟' to '济', '蘭' to '兰',
            '廈' to '厦', '門' to '门', '豐' to '丰', '順' to '顺', '義' to '义',
            '滄' to '沧', '遼' to '辽', '錦' to '锦', '營' to '营', '橋' to '桥',
        )
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(map[c] ?: c)
        return sb.toString()
    }
}
