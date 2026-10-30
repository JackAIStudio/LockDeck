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
import com.jack.lockdeck.notify.WeatherNotifier
import com.jack.lockdeck.notify.WeatherWorker
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
        // App 一起来就把常驻通知挂上（这是用户真正要的"通知栏一行"），
        // 不依赖用户去点刷新按钮。
        runCatching {
            if (com.jack.lockdeck.data.Store.isEnabled(this)) {
                com.jack.lockdeck.notify.WeatherForegroundService
                    .refresh(this, com.jack.lockdeck.data.Store.loadWeather(this))
            }
        }
        setContent {
            LockDeckTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val ctx = LocalContext.current
                    var enabled by remember { mutableStateOf(Store.isOverlayEnabled(ctx)) }
                    var a11y by remember { mutableStateOf(Store.isA11yConnected(ctx)) }
                    var weather by remember { mutableStateOf(Store.loadWeather(ctx)) }
                    var busy by remember { mutableStateOf(false) }
                    var log by remember { mutableStateOf("") }
                    var tick by remember { mutableStateOf(0) }

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
                                "锁屏卡片：天气 + 两个自定义按钮；控制中心：两个自定义磁贴。",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Card {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("锁屏小组件", fontWeight = FontWeight.Medium)
                                        Text(
                                            when {
                                                !enabled -> "未开启"
                                                !a11y -> "已开启，但缺少无障碍权限"
                                                else -> "已开启，锁屏亮屏时显示"
                                            },
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = { on ->
                                            enabled = on
                                            Store.setOverlayEnabled(ctx, on)
                                            if (on) {
                                                WeatherWorker.schedule(ctx)
                                                WeatherWorker.enqueueNow(ctx)
                                                if (!Store.isA11yConnected(ctx)) {
                                                    // 没开无障碍时引导去开
                                                    ctx.startActivity(
                                                        Intent("android.settings.ACCESSIBILITY_SETTINGS")
                                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    )
                                                }
                                            } else {
                                                WeatherWorker.cancel(ctx)
                                            }
                                        },
                                    )
                                }
                            }

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(
                                        "卡片预览（实际以锁屏为准）",
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
                                                    it.description + if (it.city.isNotBlank()) " · ${it.city}" else ""
                                                } ?: "正在获取天气",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            // ---------- 刷新间隔 ----------
                            Text("刷新设置", fontWeight = FontWeight.Medium)
                            var interval by remember { mutableStateOf(Store.getRefreshMinutes(ctx)) }
                            Card {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text("刷新间隔：$interval 分钟", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "Open-Meteo 免费额度充足，5~10 分钟都行",
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
                                                        log = "刷新间隔已设为 $m 分钟"
                                                    },
                                                ) { Text("${m}分") }
                                            }
                                        }
                                    }
                                }
                            }

                            // ---------- 卡片位置 ----------
                            var ovY by remember { mutableStateOf(Store.getOverlayY(ctx)) }
                            Card {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text("卡片位置", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Text(
                                        "锁屏上卡片的纵向位置（数值越大越靠下）",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text("当前：$ovY", fontSize = 13.sp)
                                    androidx.compose.material3.Slider(
                                        value = ovY.toFloat(),
                                        onValueChange = { ovY = it.toInt() },
                                        onValueChangeFinished = {
                                            Store.setOverlayY(ctx, ovY)
                                            Store.markCustomY(ctx)
                                            Store.bumpOverlayRefresh(ctx)
                                            log = "位置已保存：$ovY"
                                        },
                                        valueRange = 300f..1800f,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                ovY = 1150
                                                Store.setOverlayY(ctx, ovY)
                                                Store.markCustomY(ctx)
                                                Store.bumpOverlayRefresh(ctx)
                                                log = "已设为保守位置 1150"
                                            },
                                        ) { Text("用保守位置") }
                                        OutlinedButton(
                                            onClick = {
                                                ovY = 1000
                                                Store.setOverlayY(ctx, ovY)
                                                Store.markCustomY(ctx)
                                                Store.bumpOverlayRefresh(ctx)
                                            },
                                        ) { Text("靠上") }
                                    }
                                }
                            }

                            // ---------- 降雨提示 ----------
                            var rainOn by remember { mutableStateOf(Store.isRainHintEnabled(ctx)) }
                            Card {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("降雨提示", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        Text(
                                            "降雨概率高时在卡片上提示带伞",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = rainOn,
                                        onCheckedChange = {
                                            rainOn = it
                                            Store.setRainHintEnabled(ctx, it)
                                            Store.bumpOverlayRefresh(ctx)
                                            log = if (it) "已开启降雨提示" else "已关闭降雨提示"
                                        },
                                    )
                                }
                            }

                            // ---------- 通知栏样式 ----------
                            var notifCard by remember { mutableStateOf(Store.isNotifCardEnabled(ctx)) }
                            Card {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("通知栏用自定义卡片", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                            Text(
                                                "一行放下天气 + 两个按钮，点一下就扫。关掉则用系统原生样式。",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(
                                            checked = notifCard,
                                            onCheckedChange = {
                                                notifCard = it
                                                Store.setNotifCardEnabled(ctx, it)
                                                WeatherNotifier.show(ctx, Store.loadWeather(ctx))
                                                log = if (it) "通知栏已改为自定义卡片" else "通知栏已改回系统样式"
                                            },
                                        )
                                    }
                                    Text(
                                        "注：MIUI 会把不打扰的常驻通知折叠进「更多通知」，两种样式都一样。" +
                                            "左侧下拉第一屏能不能看到，取决于系统，App 改不了。",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Text("卡片按钮绑定", fontWeight = FontWeight.Medium)
                            TargetRow(
                                title = "天气区",
                                desc = "点温度那里 -> 运行",
                                current = Store.getTarget(ctx, "main")
                                    ?: QuickAction.openApp(QuickAction.PKG_WEATHER),
                                onPick = {
                                    ctx.startActivity(
                                        Intent(ctx, PickerActivity::class.java).putExtra("zone", "main")
                                    )
                                },
                            )
                            TargetRow(
                                title = "卡片按钮 1",
                                desc = "锁屏卡片左按钮 -> 运行",
                                current = Store.getTarget(ctx, "app1") ?: QuickAction.ALIPAY_SCAN,
                                onPick = {
                                    ctx.startActivity(
                                        Intent(ctx, PickerActivity::class.java).putExtra("zone", "app1")
                                    )
                                },
                            )
                            TargetRow(
                                title = "卡片按钮 2",
                                desc = "锁屏卡片右按钮 -> 运行",
                                current = Store.getTarget(ctx, "app2") ?: QuickAction.WECHAT_SCAN,
                                onPick = {
                                    ctx.startActivity(
                                        Intent(ctx, PickerActivity::class.java).putExtra("zone", "app2")
                                    )
                                },
                            )

                            Text("控制中心磁贴", fontWeight = FontWeight.Medium)
                            Text(
                                "下拉控制中心 -> 点「编辑」-> 把「天气 / 磁贴 1 / 磁贴 2」拖到上面。" +
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
                                        "请下拉控制中心 -> 点「编辑」-> 把「磁贴 1 / 磁贴 2」拖到上面"
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
                                        if (Store.isOverlayEnabled(ctx)) {
                                            Store.bumpOverlayRefresh(ctx)
                                            log = "已通知卡片刷新"
                                        } else {
                                            log = "请先开启锁屏卡片"
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("刷新卡片") }
                            }

                            Button(
                                onClick = {
                                    ctx.startActivity(
                                        Intent("android.settings.ACCESSIBILITY_SETTINGS")
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (a11y) "无障碍权限已开启" else "开启无障碍权限（必需）") }

                            TextButton(
                                onClick = {
                                    permsLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.POST_NOTIFICATIONS,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        )
                                    )
                                },
                            ) { Text("授予定位权限") }

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
                                    val notifOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
                                    Text("无障碍服务：${if (a11y) "已连接" else "未连接"}", fontSize = 11.sp)
                                    Text("通知权限：${if (notifOn) "已开启" else "未开启"}", fontSize = 11.sp)
                                    Text(
                                        "定位权限：${if (LocationHelper.hasPermission(ctx)) "已开启" else "未开启"}",
                                        fontSize = 11.sp,
                                    )
                                    Text(
                                        "系统定位复用：" +
                                            (if (LocationHelper.lastKnown(ctx) != null) "有缓存位置" else "无缓存，使用默认位置"),
                                        fontSize = 11.sp,
                                    )
                                    Text("天气数据：Open-Meteo（免费，无需 key）", fontSize = 11.sp)
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
