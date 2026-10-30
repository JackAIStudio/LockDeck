package com.jack.lockdeck.data

/** 坐标计算。 */
object Geo {

    private const val EARTH_R = 6_371_000.0

    /**
     * 两点间距离（米），球面余弦公式。
     *
     * 不用 Location.distanceBetween：那个要传 float 数组、要 android 依赖，
     * 这里只是判断「换地方了没有」，几米误差无所谓，但要好测。
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * EARTH_R * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }
}
