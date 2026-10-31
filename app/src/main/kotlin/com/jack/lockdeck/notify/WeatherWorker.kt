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
 * WorkManager 兜底刷新。
 *
 * ## 它现在只是备胎
 *
 * 真正干活的是 [WeatherForegroundService] 里那条定时循环。原因见那个类的注释：
 * HyperOS 3 上实测这个 Worker **从来不会被执行**——任务在队列里躺着
 * （`state=0`），`run_attempt_count` 永远是 0，用 `jobscheduler run -f`
 * 强制触发也照样不跑。
 *
 * 留着它是因为：
 *  - 万一服务被杀、系统又没自愈，这里至少还有一次补救机会
 *  - 原生 Android（没有 HyperOS 那套限制）上它是正常工作的，
 *    那种情况下前台服务反而可能因为省电策略被停掉
 *
 * 所以它不做任何「主逻辑」，只是把同样的刷新再调一遍。
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
