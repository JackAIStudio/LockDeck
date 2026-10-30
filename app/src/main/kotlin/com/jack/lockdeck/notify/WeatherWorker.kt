package com.jack.lockdeck.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jack.lockdeck.data.LocationHelper
import com.jack.lockdeck.data.Store
import com.jack.lockdeck.tile.WeatherTileInUse
import com.jack.lockdeck.data.WeatherApi
import java.util.concurrent.TimeUnit

/**
 * 定时刷新天气。
 *
 * 小米会杀后台，所以：
 * - 用 WorkManager（系统级调度，比自建 Service 抗杀）
 * - 有网络约束
 * - 刷新完立刻重写通知，保证锁屏上是最新的
 */
class WeatherWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // 通知栏关着时：若天气磁贴已经加进控制中心，还是得抓——它读的是这份缓存，
        // 不抓就一直停在「暂无数据」。没加磁贴就不为空跑请求。
        val tileUsed = WeatherTileInUse.marked(applicationContext)
        if (!Store.isEnabled(applicationContext) && !tileUsed) return Result.success()

        val fix = LocationHelper.lastKnown(applicationContext) ?: LocationHelper.FALLBACK
        val fetched = WeatherApi.fetch(fix.lat, fix.lon).getOrNull()
            ?: return Result.retry()

        // 城市名只在没缓存过时反查，省流量
        val cachedCity = Store.loadWeather(applicationContext)?.city.orEmpty()
        val city = cachedCity.ifBlank { WeatherApi.reverseCity(fix.lat, fix.lon) }

        val w = fetched.copy(city = city)
        Store.saveWeather(applicationContext, w)

        // 重写常驻通知，保证下拉第一屏和锁屏上都是新数据
        WeatherNotifier.show(applicationContext, w)

        return Result.success()
    }

    companion object {
        private const val PERIODIC = "lsw_periodic"
        private const val ONCE = "lsw_once"

        fun schedule(ctx: Context) {
            val minutes = com.jack.lockdeck.data.Store.getRefreshMinutes(ctx).toLong()
            val req = PeriodicWorkRequestBuilder<WeatherWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        }

        /** 立刻刷一次（点刷新时用） */
        fun enqueueNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<WeatherWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                ONCE, ExistingWorkPolicy.REPLACE, req,
            )
        }
    }
}
