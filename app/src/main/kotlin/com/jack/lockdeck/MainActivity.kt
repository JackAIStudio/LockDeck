package com.jack.lockdeck

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.jack.lockdeck.action.QuickAction
import com.jack.lockdeck.data.LocationHelper
import com.jack.lockdeck.data.Store
import com.jack.lockdeck.data.WeatherSource
import com.jack.lockdeck.notify.LockScreenCheck
import com.jack.lockdeck.notify.WeatherForegroundService
import com.jack.lockdeck.notify.WeatherNotifier
import com.jack.lockdeck.notify.WeatherWorker
import com.jack.lockdeck.tile.WeatherTileInUse
import com.jack.lockdeck.ui.theme.LockDeckTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    /**
     * 用户刚从权限弹框回来。
     *
     * 这个标记解决的是最容易被误解的一种状态：**有权限但还没数据**。
     * 没有它的时候，用户点完「允许」看到的仍然是「正在获取天气」，
     * 很容易以为 App 没修好——实际上只是还差一次刷新 + 反查地名。
     */
    private var justGrantedPerms by mutableStateOf(false)

    /**
     * 权限状态的「版本号」，每次回到这一屏就 +1。
     *
     * 权限是在**系统设置页**里才能改的（尤其「始终允许」后台定位），
     * 改完切回来时 Activity 不会重建。只有让这个值变一下，
     * 界面才会重新读一遍真实权限并刷新状态。
     */
    private var permVersion by mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        permVersion++
    }

    /**
     * 权限弹框的回调。
     *
     * 一次按钮可能连着走两轮：
     *   1. 前台定位 + 通知（这两个可以一次性要）
     *   2. 后台定位（Android 11+ 必须单独要，系统会跳到一个专门的设置页）
     * 用户走完第一步就立刻补刷一次，不让界面停在「正在获取天气」。
     */
    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            permVersion++
            if (!LocationHelper.hasPermission(this)) {
                // 用户点了「不允许」。把后果和下一步写清楚，别让他对着「正在获取天气」猜。
                Store.setError(this, "定位权限被拒绝——天气需要知道你所在的城市才能显示")
                justGrantedPerms = false
                return@registerForActivityResult
            }
            justGrantedPerms = true
            Store.setError(this, null)
            runCatching { WeatherForegroundService.refreshNow(this) }
            // 前台拿到了、后台还没有：同一颗按钮继续走第二步
            if (!LocationHelper.hasBackgroundPermission(this)) requestBackgroundLocation()
        }

    /**
     * 第二步：单独申请「始终允许」后台定位。
     *
     * 为什么必须单独要：Android 11+ 规定 `ACCESS_BACKGROUND_LOCATION`
     * 不能和前台定位放在同一个 requestPermissions 里，混在一起系统会整组拒绝。
     * 单独要时系统不弹普通授权框，而是把用户带到本 App 的定位设置页，
     * 让用户自己选「始终允许」。
     */
    private fun requestBackgroundLocation() {
        runCatching {
            permsLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }.onFailure {
            // 个别 ROM 直接不支持这个入口：退回到应用设置页，让用户自己点进去
            openAppSettings()
        }
    }

    /**
     * 一键申请：把天气要用到的权限一次走完。
     *
     * 顺序是死的，不能反：**先前台、再后台**。
     * 后台定位是前台定位的「加强档」，前台还没给的时候申请后台，系统直接忽略。
     */
    private fun requestAllPermissions() {
        // buildList 里的 this 是列表，不是 Activity，所以上下文先存一份
        val ctx = this
        val hasLocation = LocationHelper.hasPermission(ctx)
        val hasBackground = LocationHelper.hasBackgroundPermission(ctx)

        // 先要「前台级」的：定位 + 通知，这两个可以放在同一次请求里
        val need = buildList {
            if (!hasLocation) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (need.isNotEmpty()) {
            permsLauncher.launch(need.toTypedArray())
            return
        }
        // 前台级都齐了，只剩后台那档：这一档系统不让和前台混在一次里要
        if (!hasBackground) requestBackgroundLocation() else openAppSettings()
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null),
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 旧版本的锁屏浮层设置在这里清掉（那套东西已经整个删了）
        Store.purgeLegacyOverlayPrefs(this)
        // 老版本在没定位时用写死的深圳坐标抓天气，那份假快照必须丢掉，
        // 否则升级后还挂着深圳的温度和地名，看起来像没修好。
        Store.purgeStaleWeather(this)
        // 缓存里的快照如果不是当前数据源抓的，必须丢掉重抓，
        // 否则界面会拿小米的实况配中央气象台的高低温（差 2.6 度）。
        val foreignPurged = Store.purgeForeignWeather(this)
        // App 一起来就把常驻通知挂上，不依赖用户去点刷新按钮。
        if (Store.isEnabled(this)) {
            WeatherForegroundService.refresh(this, Store.loadWeather(this))
        }
        // 丢过旧快照就顺手补一次，不然用户会看到「正在获取天气」一直到下一轮定时
        if (foreignPurged) {
            runCatching { WeatherForegroundService.refreshNow(this) }
        }
        // 通知上写着「点开可手动刷新」——那就必须真的刷一次。
        //
        // 旧版点进来什么都不做：用户点完看不到任何变化，只能再点、再退、再进，
        // 最后判断成「点了没用」。没有缓存时刷是刚需；有缓存但已经旧了也该刷，
        // 否则「点开」只是把同一份旧数据重画一遍。
        refreshOnOpen()
        ensureLocationAccess()
        setContent {
            LockDeckTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val ctx = LocalContext.current
                    var weather by remember { mutableStateOf(Store.loadWeather(ctx)) }
                    var busy by remember { mutableStateOf(false) }
                    var log by remember { mutableStateOf("") }

                    // 跟着缓存走：刷新可能发生在这条 UI 之外（定时循环、点通知进来
                    // 触发的补刷、磁贴刷新），那些时候界面必须自己重新读一遍，
                    // 否则通知栏已经是新温度、这一屏还挂着打开那一刻的旧值。
                    DisposableEffect(ctx) {
                        val receiver = object : android.content.BroadcastReceiver() {
                            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                                weather = Store.loadWeather(context)
                            }
                        }
                        androidx.core.content.ContextCompat.registerReceiver(
                            ctx,
                            receiver,
                            android.content.IntentFilter(Store.ACTION_WEATHER_CHANGED),
                            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
                    }

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
                                    "控制中心：三个按钮（天气 + 两个自定义功能）。两个地方各管各的，不重叠。",
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
                                    weather?.let { w ->
                                        // 体感、湿度这些只有小米源给；没有就整行不显示，
                                        // 不拿 0 或 "--" 占位（那比不显示更让人困惑）
                                        val bits = buildList {
                                            w.feelsLike?.let { add("体感 $it°") }
                                            w.humidity?.let { add("湿度 $it%") }
                                            w.uvIndex?.let { add("紫外线 $it") }
                                            w.windDirection?.let { d ->
                                                val dir = com.jack.lockdeck.data.WeatherCodes.windDirection(d)
                                                w.windSpeed?.let { add("$dir $it km/h") } ?: add(dir)
                                            }
                                        }
                                        if (bits.isNotEmpty()) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                bits.joinToString(" · "),
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        w.aqi?.let { a ->
                                            Spacer(Modifier.height(6.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("🍃 空气${a.level}", fontSize = 11.sp)
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    "AQI ${a.value}", fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                a.pm25?.let {
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        "PM2.5 $it", fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                        // 降水：两种都要显示，因为它们的用途完全不同。
                                        // 「正在下/多久后下」来自分钟级雷达外推（2 小时内，准）；
                                        // 「今日降水概率」是彩云的日概率，只能当参考
                                        // （实测会出现报了小雨、概率却 0 的情况）。
                                        w.nextRain?.let { r ->
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                "☔ " + buildString {
                                                    append(r.headline)
                                                    r.lastsMinutes?.let { append("，约 ").append(it).append(" 分钟") }
                                                    if (!r.nearTerm) append("（预报）")
                                                },
                                                fontSize = 11.sp,
                                                color = if (r.minutes == 0) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        w.minutely?.let { m ->
                                            // 有雨的时候上面那行已经说了；这里只在「没雨」时补一句
                                            // 2 小时内的结论，免得看起来像没数据。
                                            if (!m.hasRain && w.nextRain == null) {
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    "☔ 2 小时内无雨",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        w.todayRainProbability?.takeIf { it > 0 }?.let { p ->
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "今日降水概率 $p%",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            // ---------- 权限 ----------
                            // 放在最上面第二屏的位置：天气卡片一旦停在「正在获取天气」，
                            // 用户第一件想知道的事就是「我是不是少给了什么」。
                            // 不管有没有申请过都显示真实状态。
                            PermissionCard(
                                version = permVersion,
                                justGranted = justGrantedPerms,
                                onRequestAll = { requestAllPermissions() },
                                onOpenSettings = { openAppSettings() },
                            )

                            // ---------- 未来 24 小时曲线 ----------
                            val hours = weather?.hourly.orEmpty()
                            if (hours.isNotEmpty()) {
                                Card {
                                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                        Text("未来 24 小时", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            "${hours.first().hour}:00 → ${hours.last().hour}:00" +
                                                "（${weather?.source?.label}）",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        TempCurve(
                                            hours = hours,
                                            modifier = Modifier.fillMaxWidth().height(72.dp),
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            hours.filterIndexed { i, _ -> i % 4 == 0 }.forEach {
                                                Text(
                                                    "${it.hour}时 ${it.temp}°",
                                                    fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // ---------- 未来几天 ----------
                            val days = weather?.daily.orEmpty()
                            if (days.isNotEmpty()) {
                                Card {
                                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                        Text(
                                            "未来 ${minOf(days.size, 7)} 天", fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        days.take(7).forEach { d ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    d.date, fontSize = 11.sp,
                                                    modifier = Modifier.width(64.dp),
                                                )
                                                Text(
                                                    d.weekday, fontSize = 11.sp,
                                                    modifier = Modifier.width(44.dp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Text(d.text, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                                // 降水概率只在 >0 时显示：一片 0% 既没信息量又占地方，
                                                // 而且这一列偶尔和「小雨」自相矛盾，别让它更显眼。
                                                d.rainProbability?.takeIf { it > 0 }?.let { p ->
                                                    Text(
                                                        "💧$p%",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.width(44.dp),
                                                    )
                                                }
                                                Text("${d.tempMin}° ~ ${d.tempMax}°", fontSize = 11.sp)
                                            }
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

                            // ---------- 锁屏可见性自检 ----------
                            // 这不是「设置项」，是现场验证：HyperOS 按应用决定能不能上锁屏，
                            // 只有真机点亮一次才知道答案，任何参数说明都不如它直接。
                            var checkText by remember { mutableStateOf(LockScreenCheck.summary(ctx)) }
                            // 默认折叠：这是排查工具，不是天天要看的东西。
                            // 展开状态记在 remember 里，切走再回来会复位——反而更合适。
                            var showCheck by remember { mutableStateOf(false) }
                            Card {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    CollapsibleHeader(
                                        title = "锁屏可见性自检",
                                        hint = if (showCheck) "点收起" else "点展开",
                                        expanded = showCheck,
                                        onToggle = { showCheck = !showCheck },
                                    )
                                    if (showCheck) {
                                        Text(
                                            checkText,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(top = 10.dp),
                                        ) {
                                            OutlinedButton(onClick = {
                                                LockScreenCheck.postProbe(ctx)
                                                checkText = LockScreenCheck.summary(ctx)
                                                log = "已发一条自检通知：锁屏点亮看一眼，有就是放行"
                                            }) { Text("发一条自检通知") }
                                            TextButton(onClick = {
                                                checkText = LockScreenCheck.summary(ctx)
                                            }) { Text("重新读取") }
                                        }
                                    }
                                }
                            }

                            // ---------- 数据源 ----------
                            Text("天气数据源", fontWeight = FontWeight.Medium)
                            var source by remember { mutableStateOf(Store.getSource(ctx)) }
                            // 刷新间隔放在这里而不是下面：换数据源时它的默认值会变，
                            // 两个区块要读写同一份 state，声明得在共同祖先上
                            var interval by remember { mutableStateOf(Store.getRefreshMinutes(ctx)) }
                            Card {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text(
                                        "当前用「${source.label}」。小米是默认源，数值和手机自带天气" +
                                            "完全一致（本来就是同一个后端）。降水那块是另一个接口：" +
                                            "「几分钟后下雨」来自分钟级雷达外推（2 小时内，准），" +
                                            "「今日降水概率」来自彩云（只当参考，它偶尔和「小雨」对不上）。",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    WeatherSource.entries.forEach { s ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    s.label + if (s == source) "  ✓" else "",
                                                    fontSize = 14.sp,
                                                    fontWeight = if (s == source) FontWeight.Medium else FontWeight.Normal,
                                                    color = if (s == source) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                                )
                                                Text(
                                                    s.detail,
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            if (s != source) {
                                                TextButton(onClick = {
                                                    source = s
                                                    Store.setSource(ctx, s)
                                                    // 两个源更新节奏不同（小米 5 分钟 / CMA 10 分钟）。
                                                    // 之前手动设过间隔的，换源时清掉，回到新源的默认值；
                                                    // 否则会出现「切到 CMA 了还按 5 分钟刷」这种没意义的请求。
                                                    Store.clearRefreshMinutes(ctx)
                                                    interval = Store.getRefreshMinutes(ctx)
                                                    WeatherWorker.schedule(ctx)
                                                    WeatherForegroundService.reschedule(ctx)
                                                    // 换源之后旧快照是另一个口径的数，必须重抓，
                                                    // 否则界面上会同时挂着「小米的18°」和「CMA的温州站」
                                                    log = "已切换到 ${s.label}，正在重新抓取…"
                                                    refresh()
                                                }) { Text("用这个") }
                                            }
                                        }
                                    }
                                    if (source == WeatherSource.XIAOMI) {
                                        Text(
                                            "小米这条是私有接口：没有文档、没有 SLA，哪天它改了校验就失效。" +
                                                "失效时这里会显示错误，点上面「用这个」切到中央气象台即可。",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 8.dp),
                                        )
                                    }
                                }
                            }

                            // ---------- 刷新间隔 ----------
                            Text("刷新设置", fontWeight = FontWeight.Medium)
                            Card {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text("刷新间隔：$interval 分钟", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (source == WeatherSource.XIAOMI) {
                                            "小米实况约 5 分钟更新一次，分钟级降水更快（官方约 1 分钟一条）；" +
                                                "5 分钟够跟上——降水是「未来两小时」的曲线，不是逐秒播报。" +
                                                "送外卖想随时确认，就在控制中心点一下天气按钮，立刻刷一次。"
                                        } else {
                                            "中央气象台观测站约每 10 分钟更新一次，免费无需 key"
                                        },
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

                            // ---------- 控制中心按钮 ----------
                            // 名字统一叫「按钮」：系统文档里的 tile 直译成「磁贴」，
                            // 听起来像贴纸，没人知道它其实是「点一下执行动作」。
                            Text("控制中心按钮", fontWeight = FontWeight.Medium)
                            Text(
                                "下拉控制中心那两排圆角方块，就是这里说的「按钮」。" +
                                    "系统里把它们叫 Quick Settings tile，中文 ROM 译作「磁贴」——" +
                                    "意思就是：点一下直接执行一个功能，不用先打开 App。" +
                                    "天气固定占一格，另外两格功能你自己选。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TargetRow(
                                title = "天气",
                                desc = "固定功能：显示当前温度，点开进天气 App",
                                current = QuickAction.openApp(QuickAction.PKG_WEATHER),
                                onPick = null,
                            )
                            TargetRow(
                                title = "快捷按钮 ①",
                                desc = "控制中心点它 → 执行下面这个功能",
                                current = Store.getTileAction(ctx, 1),
                                onPick = {
                                    ctx.startActivity(
                                        Intent(ctx, PickerActivity::class.java).putExtra("zone", "tile1")
                                    )
                                },
                            )
                            TargetRow(
                                title = "快捷按钮 ②",
                                desc = "控制中心点它 → 执行下面这个功能",
                                current = Store.getTileAction(ctx, 2),
                                onPick = {
                                    ctx.startActivity(
                                        Intent(ctx, PickerActivity::class.java).putExtra("zone", "tile2")
                                    )
                                },
                            )
                            TargetRow(
                                title = "点通知栏那条天气",
                                desc = "点开通知之后打开什么",
                                current = Store.getTarget(ctx, "main") ?: QuickAction.openApp(QuickAction.PKG_WEATHER),
                                onPick = {
                                    ctx.startActivity(
                                        Intent(ctx, PickerActivity::class.java).putExtra("zone", "main")
                                    )
                                },
                            )

                            // 系统只允许「请求添加」，最终点不点添加是用户的事（Android 13+）。
                            // Android 12 及以下没有这个 API，只能手动编辑——两条路都写在按钮下面。
                            OutlinedButton(
                                onClick = {
                                    val sent = com.jack.lockdeck.tile.TileCatalog.items.count {
                                        com.jack.lockdeck.tile.TileCatalog.requestAdd(ctx, it)
                                    }
                                    log = if (sent > 0) {
                                        "已请求添加 $sent 个按钮：系统弹出确认框时点「添加」。" +
                                            "没弹框就直接下拉控制中心 → 点「编辑」→ 从下面把按钮拖到上面。"
                                    } else {
                                        "这台机器的系统版本不支持应用直接请求添加。" +
                                            "请下拉控制中心 → 点「编辑」→ 把「天气 / 快捷按钮 ① / 快捷按钮 ②」拖到上面。"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("把这三个按钮加到控制中心") }
                            Text(
                                "手动路径：下拉控制中心 → 点「编辑」（或长按空白处）→ " +
                                    "在下方的按钮列表里找「天气」「快捷按钮 ①」「快捷按钮 ②」→ " +
                                    "按住拖到上面两排即可。控制中心里显示全部是系统自己染的白色剪影，" +
                                    "不显示名字，所以这里用不同形状区分。",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            HorizontalDivider()

                            // ---------- 立即刷新 ----------
                            // 放在最显眼的位置（不折叠、不藏进菜单），
                            // 因为它是「验证 API 准不准」唯一的可操作手段：
                            // 点一下 → 立刻重新定位、重新请求、把原始值摆出来对比。
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

                            // ---------- 数据核对 ----------
                            // 用户拿我们和小米官方 App 对温度时，只看两个整数分不清
                            // 是「数据源不同」还是「取整方式不同」。这里把接口原始值、
                            // 发布时间、观测站摊开，一眼能判。
                            val cur = weather
                            var showDiag by remember { mutableStateOf(false) }
                            if (cur != null) {
                                Card {
                                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                        CollapsibleHeader(
                                            title = "数据核对",
                                            hint = if (showDiag) "点收起"
                                            else "接口原始值 / 观测站",
                                            expanded = showDiag,
                                            onToggle = { showDiag = !showDiag },
                                        )
                                        if (showDiag) {
                                        Spacer(Modifier.height(8.dp))
                                        DiagRow(
                                            "接口原始值",
                                            if (cur.tempRaw.isNotBlank()) "${cur.tempRaw}°"
                                            else "（这个源不给原始值）",
                                        )
                                        DiagRow("界面显示", "${cur.tempNow}°（四舍五入）")
                                        DiagRow(
                                            "观测发布时间",
                                            // 接口给的是 2026-11-01T15:34:22+08:00，太长且不好读。
                                            // 只留「今天 15:34」这一段，和抓取时间并排比才有意义。
                                            cur.pubTime
                                                .substringAfter('T', cur.pubTime)
                                                .take(5)
                                                .takeIf { it.length == 5 && cur.pubTime.isNotBlank() }
                                                ?.let { "今天 $it" }
                                                ?: cur.pubTime.ifBlank { "源未提供" },
                                        )
                                        DiagRow(
                                            "抓取时间",
                                            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                                                .format(Date(cur.updatedAt)),
                                        )
                                        if (cur.stationInfo.isNotBlank()) {
                                            DiagRow("实况观测站", cur.stationInfo)
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "小米官方 App 用同一套后端，但它按更细的定位选站；" +
                                                "相邻两个区落到不同站时，差 1 度是正常的。" +
                                                "如果「观测发布时间」停在很久以前，那才是刷新没生效。",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        }
                                    }
                                }
                            }

                            if (log.isNotBlank()) {
                                Text(log, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            ) {
                                var showDiagCard by remember { mutableStateOf(false) }
                                Column(
                                    Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    CollapsibleHeader(
                                        title = "诊断",
                                        hint = if (showDiagCard) "点收起" else "权限 / 定位 / 数据源",
                                        expanded = showDiagCard,
                                        onToggle = { showDiagCard = !showDiagCard },
                                    )
                                    if (showDiagCard) {
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
                                    val curW = Store.loadWeather(ctx)
                                    Text(
                                        "天气数据：${Store.getSource(ctx).label}" +
                                            (curW?.let { "（这份快照来自 ${it.source.label}）" } ?: ""),
                                        fontSize = 11.sp,
                                    )
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

    /**
     * 用户点通知进来时，真的补刷一次。
     *
     * 通知正文在没有数据时写的是「点开可手动刷新」，那就得兑现这句话。
     * 两个条件任何一个满足就刷：
     *   - 还没有任何缓存（界面停在「正在获取天气」，除了刷没别的出路）
     *   - 缓存已经超过一个刷新间隔（点开只看旧数据等于没点）
     *
     * 走的是前台服务那条刷新链路，和手动点「立即刷新」完全一样，
     * 不额外开线程、也不会和定时器撞车。
     */
    private fun refreshOnOpen() {
        if (!LocationHelper.hasPermission(this)) return
        val w = Store.loadWeather(this)
        val staleAfter = Store.getRefreshMinutes(this) * 60_000L
        val need = w == null || System.currentTimeMillis() - w.updatedAt > staleAfter
        if (need) runCatching { WeatherForegroundService.refreshNow(this) }
    }

    /**
     * 进这一屏时把「还差哪种定位」问清楚。
     *
     * 只在真的没有前台定位时才弹系统框——有权限的用户不该每次开 App 都吃一个弹框。
     * 后台那一档不在这里自动弹：它要跳到系统设置页，属于「用户自己决定」的事，
     * 做成设置页里的一行状态 + 一颗按钮更合适。
     */
    private fun ensureLocationAccess() {
        if (LocationHelper.hasPermission(this)) return
        permsLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        )
    }
}

/**
 * 权限卡片：每一项都显示**真实状态**，并提供当下该做的那一下。
 *
 * ## 为什么状态必须常显
 *
 * 「正在获取天气」有两种完全不同的原因：没给定位、或者给了但还没抓回来。
 * 界面上分不清这两者时，用户只能反复点、反复重启，不知道该去改哪里。
 * 所以这张卡不看「有没有点过申请」——权限是什么样就显示什么样。
 *
 * ## 为什么不能全自动
 *
 * 定位和通知都是**危险权限**，弹框必须由用户点；「始终允许」后台定位
 * 更是只能由用户去系统设置页里选。App 能做的是把顺序排好、把状态摊开、
 * 把路径写成一句话，而不是绕过系统。
 */
@Composable
private fun PermissionCard(
    version: Int,
    justGranted: Boolean,
    onRequestAll: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    // version 是刷新触发器：从系统设置页改完权限切回来时，靠它重新读一遍真实值
    val state = remember(version) { PermissionState.read(ctx) }

    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("权限", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.allGood) "已齐全" else "还差 ${state.missingCount} 项",
                    fontSize = 11.sp,
                    color = if (state.allGood) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))

            PermissionRow(
                name = "定位",
                granted = state.location,
                detail = if (state.location) "已允许" else "没有它取不到你所在的城市，天气会一直停在「正在获取」",
            )
            PermissionRow(
                name = "后台定位",
                granted = state.backgroundLocation,
                detail = if (state.backgroundLocation) "始终允许"
                else if (!state.location) "先给上面的定位，再来给这一项"
                else "只允许「使用 App 时」。后台刷新会退回上次的位置，人走远了温度会不对",
            )
            PermissionRow(
                name = "通知",
                granted = state.notification,
                detail = if (state.notification) "已开启"
                else "没有它常驻天气挂不出来，控制中心以外就看不到天气",
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequestAll, modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            // 「始终允许」系统不让 App 直接弹，只能把人送到设置页
                            state.needsOnlyBackground -> "去开启「始终允许」"
                            state.allGood -> "权限都齐了"
                            else -> "一键申请权限"
                        }
                    )
                }
                OutlinedButton(onClick = onOpenSettings) { Text("系统设置") }
            }

            if (justGranted && state.location && !state.allGood) {
                Text(
                    "定位已给。只剩「后台定位」——下拉控制中心或打开 App 都会刷一次，" +
                        "但关掉 App 后要靠它才不会退回旧位置。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** 一行权限状态：左边是什么、右边给没给。 */
@Composable
private fun PermissionRow(name: String, granted: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            if (granted) "✓" else "✕",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.width(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 一次读取当前所有权限的真实状态。 */
private data class PermissionState(
    val location: Boolean,
    val backgroundLocation: Boolean,
    val notification: Boolean,
) {
    val allGood: Boolean get() = location && backgroundLocation && notification
    val missingCount: Int get() = listOf(location, backgroundLocation, notification).count { !it }
    val needsOnlyBackground: Boolean get() = location && !backgroundLocation

    companion object {
        fun read(ctx: android.content.Context) = PermissionState(
            location = LocationHelper.hasPermission(ctx),
            backgroundLocation = LocationHelper.hasBackgroundPermission(ctx),
            notification = NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
        )
    }
}

/**
 * 可折叠区块的标题行：左边标题、右边一句「点展开 / 点收起」。
 *
 * 用纯文字 + 点击而不是 Material 的 ExpandMore 图标：
 * 这里没有加载动画的需要，箭头反而要多解释一次「它是能点的」。
 */
@Composable
private fun CollapsibleHeader(
    title: String,
    hint: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(
            hint,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (expanded) " ▲" else " ▼",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 诊断面板里的一行「标签 → 值」。 */
@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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

/**
 * 温度曲线。
 *
 * 用 Canvas 手画而不是拖一个图表库：这里只需要一条折线 + 一条填充，
 * 引一个 200KB 的图表库来画 24 个点不值当。
 *
 * 纵轴按这批数据的实际高低自适应（不写死 0~40），因为**看曲线的人关心的是
 * "接下来怎么变"**，不是绝对刻度——温州 11 月的数据全挤在 18~29 之间，
 * 固定 0~40 的轴会把曲线压成一条平线。
 */
@Composable
private fun TempCurve(hours: List<com.jack.lockdeck.data.HourPoint>, modifier: Modifier = Modifier) {
    if (hours.size < 2) return
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val temps = hours.map { it.temp.toFloat() }
        val lo = temps.min()
        val hi = temps.max()
        // 上下各留 15% 余量，免得最高点/最低点贴着边框
        val span = (hi - lo).takeIf { it > 0.5f } ?: 1f
        val pad = span * 0.15f
        val minT = lo - pad
        val maxT = hi + pad
        val range = (maxT - minT).takeIf { it > 0f } ?: 1f

        val w = size.width
        val h = size.height
        val stepX = w / (temps.size - 1)

        fun yFor(t: Float): Float = h - ((t - minT) / range) * h

        val path = Path().apply {
            moveTo(0f, yFor(temps[0]))
            for (i in 1 until temps.size) lineTo(i * stepX, yFor(temps[i]))
        }
        // 填充：把折线首尾连到画布底部
        val fill = Path().apply {
            addPath(path)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            fill,
            brush = Brush.verticalGradient(
                listOf(fillColor.copy(alpha = 0.25f), fillColor.copy(alpha = 0.02f)),
            ),
        )
        drawPath(path, color = lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    }
}
