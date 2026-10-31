package com.jack.lockdeck

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.jack.lockdeck.action.QuickAction
import com.jack.lockdeck.data.LocationHelper
import com.jack.lockdeck.data.Store
import com.jack.lockdeck.notify.WeatherForegroundService
import com.jack.lockdeck.notify.WeatherNotifier
import com.jack.lockdeck.notify.WeatherWorker
import com.jack.lockdeck.tile.WeatherTileInUse
import com.jack.lockdeck.ui.theme.LockDeckTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 旧版本的锁屏浮层设置在这里清掉（那套东西已经整个删了）
        Store.purgeLegacyOverlayPrefs(this)
        // 老版本在没定位时用写死的深圳坐标抓天气，那份假快照必须丢掉，
        // 否则升级后还挂着深圳的温度和地名，看起来像没修好。
        Store.purgeStaleWeather(this)
        // App 一起来就把常驻通知挂上，不依赖用户去点刷新按钮。
        if (Store.isEnabled(this)) {
            WeatherForegroundService.refresh(this, Store.loadWeather(this))
        }
        setContent {
            LockDeckTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val ctx = LocalContext.current
                    var weather by remember { mutableStateOf(Store.loadWeather(ctx)) }
                    var busy by remember { mutableStateOf(false) }
                    var log by remember { mutableStateOf("") }

                    fun refresh() {
                        busy = true
                        Thread {
                            val w = WeatherNotifier.refreshNow(ctx)
                            runOnUiThread {
                                weather = w
                                busy = false
                                log = "已更新 " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                if (Store.isEnabled(ctx)) WeatherNotifier.show(ctx, w)
                            }
                        }.start()
                    }

                    Scaffold { pad ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(pad)
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text("锁屏快捷台", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "通知栏：一条常驻天气信息，和解锁后的其它通知长一个样；" +
                                    "控制中心：天气 + 两个自定义磁贴。两个地方各管各的，不重叠。",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            // ---------- 天气速览 ----------
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(
                                        "通知栏现在显示的内容",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(weather?.icon ?: "…", fontSize = 30.sp)
                                        Spacer(Modifier.padding(6.dp))
                                        Text(
                                            weather?.let { "${it.tempNow}°" } ?: "--°",
                                            fontSize = 38.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                weather?.let { "今日 ${it.tempMin}° ~ ${it.tempMax}°" }
                                                    ?: "今日 --° ~ --°",
                                                fontSize = 14.sp,
                                            )
                                            Text(
                                                weather?.let {
                                                    it.description + if (it.place.isNotBlank()) " · ${it.place}" else ""
                                                } ?: "正在获取天气",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            // ---------- 通知栏常驻 ----------
                            var notifOn by remember { mutableStateOf(Store.isEnabled(ctx)) }
                            Card {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("通知栏常驻天气", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        Text(
                                            "左边下拉第一屏常驻一行，系统原生样式，不折叠",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = notifOn,
                                        onCheckedChange = { on ->
                                            notifOn = on
                                            Store.setEnabled(ctx, on)
                                            if (on) {
                                                permsLauncher.launch(
                                                    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                                                )
                                                WeatherWorker.schedule(ctx)
                                                WeatherWorker.enqueueNow(ctx)
                                                WeatherForegroundService.refresh(ctx, Store.loadWeather(ctx))
                                                log = "已开启，通知几秒内出现"
                                            } else {
                                                WeatherNotifier.cancel(ctx)
                                                WeatherForegroundService.stop(ctx)
                                                if (!WeatherTileInUse.marked(ctx)) WeatherWorker.cancel(ctx)
                                                log = "已关闭通知栏天气"
                                            }
                                        },
                                    )
                                }
                                Text(
                                    "锁屏上不会显示这条（HyperOS 的锁屏通知区只放系统级通知，" +
                                        "第三方的一律不进去；早期版本用无障碍浮层强行盖上去，" +
                                        "会挡住别人的通知还卡，已经删了）。锁屏看天气请下拉控制中心。",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                                )
                            }

                            // ---------- 刷新间隔 ----------
                            Text("刷新设置", fontWeight = FontWeight.Medium)
                            var interval by remember { mutableStateOf(Store.getRefreshMinutes(ctx)) }
                            Card {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text("刷新间隔：$interval 分钟", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "中央气象台观测站数据，免费无需 key；观测站约每 10 分钟更新一次",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(5, 10, 15, 30).forEach { m ->
                                            if (m == interval) {
                                                Button(onClick = {}) { Text("${m}分") }
                                            } else {
                                                OutlinedButton(
                                                    onClick = {
                                                        interval = m
                                                        Store.setRefreshMinutes(ctx, m)
                                                        WeatherWorker.schedule(ctx)
                                                        // 真正干活的是前台服务里那条循环，
                                                        // 让它按新间隔重排一次，否则改了要等这一轮走完才生效
                                                        WeatherForegroundService.reschedule(ctx)
                                                        log = "刷新间隔已设为 $m 分钟"
                                                    },
                                                ) { Text("${m}分") }
                                            }
                                        }
                                    }
                                }
                            }

                            HorizontalDivider()

                            // ---------- 控制中心磁贴 ----------
                            Text("控制中心磁贴", fontWeight = FontWeight.Medium)
                            Text(
                                "扫一扫、收付款都放在这里——下拉控制中心点一下就走。" +
                                    "HyperOS 的磁贴只显示图标，不显示文字。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TargetRow(
                                title = "磁贴 1",
                                desc = "控制中心点它 -> 运行",
                                current = Store.getTileAction(ctx, 1),
                                onPick = {
                                    ctx.startActivity(
                                        Intent(ctx, PickerActivity::class.java).putExtra("zone", "tile1")
                                    )
                                },
                            )
                            TargetRow(
                                title = "磁贴 2",
                                desc = "控制中心点它 -> 运行",
                                current = Store.getTileAction(ctx, 2),
                                onPick = {
                                    ctx.startActivity(
                                        Intent(ctx, PickerActivity::class.java).putExtra("zone", "tile2")
                                    )
                                },
                            )
                            TargetRow(
                                title = "通知点开后打开",
                                desc = "点通知栏那条天气 -> 打开",
                                current = Store.getTarget(ctx, "main") ?: QuickAction.openApp(QuickAction.PKG_WEATHER),
                                onPick = {
                                    ctx.startActivity(
                                        Intent(ctx, PickerActivity::class.java).putExtra("zone", "main")
                                    )
                                },
                            )
                            OutlinedButton(
                                onClick = {
                                    // 系统只允许应用主动请求添加一次（Android 13+）。
                                    // HyperOS 上系统弹窗不一定出现，所以失败时给手动路径。
                                    val ok = runCatching {
                                        val sbm = ctx.getSystemService(android.app.StatusBarManager::class.java)
                                        sbm?.requestAddTileService(
                                            android.content.ComponentName(
                                                ctx,
                                                com.jack.lockdeck.tile.Tile1Service::class.java,
                                            ),
                                            "磁贴 1",
                                            android.graphics.drawable.Icon.createWithResource(
                                                ctx,
                                                R.drawable.ic_tile_scan_alipay,
                                            ),
                                            ctx.mainExecutor,
                                        ) { _ -> }
                                        sbm != null
                                    }.getOrDefault(false)
                                    log = if (ok) {
                                        "系统弹出确认框时点「添加」；之后可下拉控制中心 -> 编辑 调整位置"
                                    } else {
                                        "请下拉控制中心 -> 点「编辑」-> 把「磁贴 1 / 磁贴 2 / 天气」拖到上面"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("把磁贴 1 添加到控制中心") }

                            HorizontalDivider()

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { refresh() },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text(if (busy) "更新中…" else "立即刷新") }

                                OutlinedButton(
                                    onClick = {
                                        if (Store.isEnabled(ctx)) {
                                            WeatherNotifier.show(ctx, Store.loadWeather(ctx))
                                            log = "已重写通知"
                                        } else {
                                            log = "请先开启通知栏常驻天气"
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("刷新通知") }
                            }

                            TextButton(
                                onClick = {
                                    permsLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.POST_NOTIFICATIONS,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        )
                                    )
                                },
                            ) { Text("授予通知与定位权限") }

                            if (log.isNotBlank()) {
                                Text(log, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("诊断", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    val notifPerm = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
                                    Text("通知权限：${if (notifPerm) "已开启" else "未开启"}", fontSize = 11.sp)
                                    Text(
                                        "定位权限：${if (LocationHelper.hasPermission(ctx)) "已开启" else "未开启"}",
                                        fontSize = 11.sp,
                                    )
                                    // 后台定位是独立的一档，必须单独看。没给的话后台刷新拿不到位置，
                                    // 表现就是「前台是温州、过一会儿变别的地方」。
                                    Text(
                                        "后台定位：${if (LocationHelper.hasBackgroundPermission(ctx)) "已允许（始终）" else "只允许在前台——后台刷新会用到上次的位置"}",
                                        fontSize = 11.sp,
                                    )
                                    Text(
                                        "当前位置：" + (runCatching {
                                            LocationHelper.get(ctx, allowActive = false)?.let {
                                                "%.4f, %.4f".format(it.lat, it.lon)
                                            }
                                        }.getOrNull() ?: "还没拿到（下拉一次控制中心或点立即刷新）"),
                                        fontSize = 11.sp,
                                    )
                                    // 上一次刷新用的是「刚定位的」还是「退到缓存的位置」。
                                    // 后台刷新如果长期停在 CACHED/PERSISTED，说明后台定位没真正生效，
                                    // 温度会跟着慢半拍——这是排查「过一会儿就变地方」的关键一行。
                                    Text(
                                        "上次定位来源：" + when (
                                            Store.prefs(ctx).getString("lastFixSource", null)
                                        ) {
                                            "FRESH" -> "刚定位（最新）"
                                            "CACHED" -> "系统缓存（可能已经移动过）"
                                            "PERSISTED" -> "上次成功定位（当前没拿到新位置）"
                                            else -> "还没刷新过"
                                        },
                                        fontSize = 11.sp,
                                    )
                                    Text("天气数据：中央气象台 weather.cma.cn（按区县匹配气象站）", fontSize = 11.sp)
                                    val err = Store.getError(ctx)
                                    if (!err.isNullOrBlank()) {
                                        Text("错误：$err", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetRow(
    title: String,
    desc: String,
    current: String,
    onPick: (() -> Unit)?,
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    LocalContext.current.let { QuickAction.label(it, current) },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (onPick != null) {
                TextButton(onClick = onPick) { Text("选择") }
            }
        }
    }
}
