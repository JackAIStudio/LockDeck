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
import com.jack.lockdeck.data.Store
import com.jack.lockdeck.data.WeatherRepo
import com.jack.lockdeck.tile.WeatherTileInUse
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

        // 刷新走统一入口，和前台「立即刷新」是同一条路——
        // 以前这里单独写了一份，导致后台刷新会用写死的深圳坐标。
        val r = WeatherRepo.refresh(applicationContext)
        return when (r) {
            is WeatherRepo.Result.Ok -> {
                // 重写常驻通知，保证下拉第一屏上是新数据
                WeatherNotifier.show(applicationContext, r.weather)
                Result.success()
            }
            // 没有定位权限：重试也没用，要用户去给权限。别烧电量重试。
            WeatherRepo.Result.NoPermission -> {
                WeatherNotifier.show(applicationContext, Store.loadWeather(applicationContext))
                Result.success()
            }
            // 暂时没位置（室内/刚开机）：过一阵再试
            WeatherRepo.Result.NoLocation -> Result.retry()
            // 接口挂了：重试
            is WeatherRepo.Result.Failed -> Result.retry()
        }
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
