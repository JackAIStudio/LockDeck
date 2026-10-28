package com.jack.lockscreenweather

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
import com.jack.lockscreenweather.data.LocationHelper
import com.jack.lockscreenweather.data.Store
import com.jack.lockscreenweather.notify.WeatherNotifier
import com.jack.lockscreenweather.notify.WeatherWorker
import com.jack.lockscreenweather.ui.theme.LockScreenWeatherTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LockScreenWeatherTheme {
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
                            Text("锁屏天气", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "在锁屏的通知区域常驻显示当前温度与今日最高/最低。",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Card {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("锁屏天气卡片", fontWeight = FontWeight.Medium)
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

                            Text("点击区域绑定", fontWeight = FontWeight.Medium)
                            TargetRow(
                                title = "主区域（温度）",
                                desc = "点温度 -> 打开",
                                current = Store.getTarget(ctx, "main") ?: WeatherNotifier.WEATHER,
                                onPick = {
                                    ctx.startActivity(
                                        Intent(ctx, PickerActivity::class.java).putExtra("zone", "main")
                                    )
                                },
                            )
                            TargetRow(
                                title = "左下区域（今日温度）",
                                desc = "固定为刷新，锁屏下无需解锁",
                                current = "__refresh__",
                                onPick = null,
                            )
                            TargetRow(
                                title = "右下区域（箭头）",
                                desc = "点这里 -> 打开",
                                current = Store.getTarget(ctx, "right") ?: WeatherNotifier.CLOCK,
                                onPick = {
                                    ctx.startActivity(
                                        Intent(ctx, PickerActivity::class.java).putExtra("zone", "right")
                                    )
                                },
                            )

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
                                            log = "请先开启锁屏天气卡片"
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
                    if (current == "__refresh__") "刷新（内置）" else current,
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
