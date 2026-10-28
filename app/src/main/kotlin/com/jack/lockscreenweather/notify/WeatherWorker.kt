package com.jack.lockscreenweather.notify

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
import com.jack.lockscreenweather.data.LocationHelper
import com.jack.lockscreenweather.data.Store
import com.jack.lockscreenweather.data.WeatherApi
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
        if (!Store.isEnabled(applicationContext)) return Result.success()

        val fix = LocationHelper.lastKnown(applicationContext) ?: LocationHelper.FALLBACK
        val fetched = WeatherApi.fetch(fix.lat, fix.lon).getOrNull()
            ?: return Result.retry()

        // 城市名只在没缓存过或位置变了才反查，省流量
        val cached = Store.loadWeather(applicationContext)
        val city = if (cached?.city.isNullOrBlank()) {
            WeatherApi.reverseCity(fix.lat, fix.lon)
        } else {
            cached!!.city
        }

        val w = fetched.copy(city = city)
        Store.saveWeather(applicationContext, w)
        WeatherNotifier.show(applicationContext, w)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "lsw_periodic"
        private const val ONCE = "lsw_once"

        fun schedule(ctx: Context) {
            val minutes = com.jack.lockscreenweather.data.Store.getRefreshMinutes(ctx).toLong()
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
