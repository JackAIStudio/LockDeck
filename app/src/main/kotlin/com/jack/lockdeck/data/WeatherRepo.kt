package com.jack.lockdeck.data

import android.content.Context

/**
 * 「刷新一次天气」的唯一实现。
 *
 * ## 为什么单独抽出来
 *
 * 以前刷新逻辑写在两个地方：后台的 [com.jack.lockdeck.notify.WeatherWorker]
 * 和前台界面的 `WeatherNotifier.refreshNow()`。两份几乎一样但细节不同
 * （一个反查城市名、一个只在没缓存时反查），结果就是**前后台行为不一致**：
 * 前台刷新能拿到温州，切到后台刷新又回到深圳。这种 bug 最难查，因为
 * 「有时候对、有时候不对」。
 *
 * 现在只有这一条路，两边都调它。
 *
 * ## 三个数据源，一个出口
 *
 * 用户可在设置页选 [WeatherSource]：
 *  - **小米**（默认）：和手机自带天气同一后端，数值一致，还带分钟级降水
 *  - **中央气象台**：免费无需 key 的老方案，最稳，但实况差 2 度上下
 *  - **Open-Meteo**：最后一道防线，免费有 SLA
 *
 * 选哪个就只用哪个，**不做自动降级**。理由：静默降级会让用户看到
 * 「温度忽然变了 2 度却不知道为什么」，比直接报错更难查。
 * 小米失效时设置页会把错误原样显示出来，用户自己动手切。
 *
 * ## 流程
 *
 *   定位 → 拿不到就报「等定位」，绝不编坐标
 *        → 反查市/区县/省
 *        → 按用户选的数据源抓
 *        → 抓到数据写缓存
 */
object WeatherRepo {

    /** 一次刷新的结果。失败时带上人话，直接能显示在界面上。 */
    sealed interface Result {
        data class Ok(val weather: Weather) : Result
        /** 没有定位权限 */
        data object NoPermission : Result
        /** 有权限但系统没给位置 */
        data object NoLocation : Result
        /** 定位有了，但天气接口失败 */
        data class Failed(val message: String) : Result
    }

    /** 一次刷新用的位置是从哪来的，用来判断「后台刷新到底有没有真定位」。 */
    enum class FixSource { FRESH, CACHED, PERSISTED }

    /**
     * 刷新一次并写入缓存。
     *
     * @param allowActiveLocation 允不允许主动请求一次定位（前台传 true）。
     *        后台调度时也传 true：没后台权限的话系统会静默失败，
     *        会自动退到「上次成功定位过的地方」，不会卡住。
     */
    fun refresh(ctx: Context, allowActiveLocation: Boolean = true): Result {
        // 1. 定位。拿不到就停在这里——不往下编数据。
        if (!LocationHelper.hasPermission(ctx)) {
            Store.setError(ctx, "没有定位权限，无法确定你所在的位置")
            return Result.NoPermission
        }
        val got = LocationHelper.getWithSource(ctx, allowActive = allowActiveLocation)
            ?: run {
                Store.setError(ctx, "定位暂时不可用（可能在室内或刚开机）")
                return Result.NoLocation
            }
        val fix = got.first

        // 2. 反查地名。
        //
        //    这里以前是「反查失败就整个放弃」。但反查是一次额外的网络请求，
        //    失败率比天气源高得多（实测偶尔超时）。而坐标本身已经拿到了——
        //    为了省一次请求把整次刷新作废，用户看到的就是「明明有网还是没数据」。
        //
        //    改成：反查失败就退到区域中心点继续抓天气。宁可气象站选得远一点，
        //    也要把温度显示出来。
        var place = LocationHelper.reversePlace(fix.lat, fix.lon)
        if (place.city.isBlank() && place.district.isBlank()) {
            place = LocationHelper.nearestCity(fix.lat, fix.lon) ?: run {
                Store.setError(ctx, "定位成功但认不出这是哪个城市，请检查网络")
                return Result.Failed("地名反查失败")
            }
        }

        // 3. 按用户选的数据源抓天气。
        val source = Store.getSource(ctx)
        val w: Weather = try {
            when (source) {
                WeatherSource.XIAOMI -> XiaomiWeatherApi.fetch(fix.lat, fix.lon, place)
                WeatherSource.CMA -> WeatherApi.fetch(place).getOrThrow()
                WeatherSource.OPEN_METEO -> OpenMeteoApi.fetch(fix.lat, fix.lon, place)
            }
        } catch (e: Throwable) {
            val hint = if (source == WeatherSource.XIAOMI) {
                "（小米是私有接口，可能已经失效，可在设置里改用中央气象台）"
            } else ""
            val msg = "${source.label}请求失败: ${e.javaClass.simpleName}: ${e.message}$hint"
            Store.setError(ctx, msg)
            return Result.Failed(msg)
        }

        Store.setError(ctx, null)
        Store.saveWeather(ctx, w)
        // 记下这次用的是哪一级的坐标，设置页会显示，方便判断后台有没有真定位
        Store.prefs(ctx).edit().putString("lastFixSource", got.second.name).apply()
        trackLocationDegradation(ctx, got.second)
        return Result.Ok(w)
    }

    /**
     * 连续多少次「拿不到新位置、只能退到旧坐标」才值得提醒。
     *
     * 3 次：按 5 分钟一次算，相当于连续 15 分钟都在用旧位置。
     * 单次退到 PERSISTED 很常见——刚进电梯、刚开机、地下室，
     * 一次就弹提示只会变成噪音。
     */
    private const val DEGRADE_ALERT_AFTER = 3

    /**
     * 盯着定位是不是"长期降级"。
     *
     * ## 为什么需要它
     *
     * 后台定位被用户改回「仅前台」之后，刷新本身**不会报错**，照样返回一份
     * 看起来正常的天气——只是那份天气是用上次的坐标算的。人一旦走远，
     * 温度就会安安静静地变成别的地方的，界面上没有任何异常。
     *
     * 这种"静默错误"比报错难查得多，所以要主动数：连续 [DEGRADE_ALERT_AFTER]
     * 次都没拿到新位置，就把原因写进 [Store.setError]，让设置页和通知看得见。
     * 下次真定位成功，计数清零、错误也清掉。
     */
    private fun trackLocationDegradation(ctx: Context, source: FixSource) {
        val p = Store.prefs(ctx)
        if (source == FixSource.FRESH) {
            // 拿到真位置：清零，并把之前那条降级提示撤掉
            p.edit().putInt("fixDegradeCount", 0).apply()
            if (Store.getError(ctx)?.contains("后台定位") == true) Store.setError(ctx, null)
            return
        }
        if (source == FixSource.CACHED) return // 系统缓存也算"有位置"，不计数

        val n = p.getInt("fixDegradeCount", 0) + 1
        p.edit().putInt("fixDegradeCount", n).apply()
        if (n >= DEGRADE_ALERT_AFTER) {
            Store.setError(
                ctx,
                "后台定位没生效：已经连续 $n 次拿不到新位置，正在用上次的坐标。" +
                    "去系统设置把定位改成「始终允许」。",
            )
        }
    }

    /** 给人看的一句话状态，设置页和通知都用它。 */
    fun statusText(ctx: Context): String {
        val err = Store.getError(ctx)
        if (!err.isNullOrBlank()) return err
        val w = Store.loadWeather(ctx) ?: return "还没有数据"
        val ago = (System.currentTimeMillis() - w.updatedAt) / 60_000
        return "更新于 $ago 分钟前"
    }
}
