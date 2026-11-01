package com.jack.lockdeck

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jack.lockdeck.action.QuickAction
import com.jack.lockdeck.data.Store
import com.jack.lockdeck.notify.WeatherNotifier
import com.jack.lockdeck.ui.theme.LockDeckTheme

/**
 * 选择某个点击区（或控制中心按钮）要跑哪个动作。
 *
 * 分两段：
 *   1. 内置动作 —— 支付宝扫一扫、微信扫一扫这类一步到位的
 *   2. 任意 App  —— 从已安装应用里挑一个
 *
 * 现在只有控制中心按钮（tile1 / tile2）和「通知点开后打开」（main）会进来。
 * 锁屏浮层那套三区（main / app1 / app2）已经删了，只剩 main 还在用。
 */
class PickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // zone 可能是 main / app1 / app2，也可能是 tile1 / tile2
        val zone = intent.getStringExtra("zone") ?: "main"
        enableEdgeToEdge()
        setContent {
            LockDeckTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val ctx = LocalContext.current
                    val apps = remember {
                        ctx.packageManager.queryIntentActivities(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                            0,
                        )
                            .mapNotNull { ri ->
                                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                                val label = ri.loadLabel(ctx.packageManager).toString()
                                pkg to label
                            }
                            .distinctBy { it.first }
                            .sortedBy { it.second }
                    }

                    fun save(id: String?) {
                        if (zone.startsWith("tile")) {
                            Store.setTileAction(ctx, zone.removePrefix("tile").toIntOrNull() ?: 1, id)
                            // 通知系统磁贴状态变了
                            runCatching {
                                ctx.sendBroadcast(
                                    Intent("android.service.quicksettings.action.REQUEST_LISTENING").setPackage(ctx.packageName)
                                )
                            }
                        } else {
                            Store.setTarget(ctx, zone, id)
                            WeatherNotifier.show(ctx, Store.loadWeather(ctx))
                        }
                        startActivity(Intent(ctx, MainActivity::class.java))
                        finish()
                    }

                    Scaffold(
                        topBar = {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("选择动作", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    when {
                                        zone == "main" -> "绑到：点通知栏那条天气之后打开"
                                        zone == "tile1" -> "绑到：控制中心 · 快捷按钮 ①"
                                        zone == "tile2" -> "绑到：控制中心 · 快捷按钮 ②"
                                        else -> "绑到：$zone"
                                    },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    ) { pad ->
                        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                            item {
                                ListItem(
                                    headlineContent = { Text("不绑定") },
                                    supportingContent = { Text("这一格不做任何事") },
                                    modifier = Modifier.clickable { save(null) },
                                )
                                HorizontalDivider()
                            }
                            item {
                                Text(
                                    "内置动作",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                                )
                            }
                            items(QuickAction.presets) { id ->
                                ListItem(
                                    headlineContent = { Text(QuickAction.label(ctx, id)) },
                                    supportingContent = {
                                        Text(
                                            when {
                                                id == QuickAction.REFRESH -> "立即拉一次最新天气"
                                                QuickAction.isAvailable(ctx, id) -> "点一下直达，不用先开 App"
                                                else -> "这台机器上没装"
                                            },
                                            fontSize = 10.sp,
                                        )
                                    },
                                    modifier = Modifier.clickable { save(id) },
                                )
                                HorizontalDivider()
                            }
                            item {
                                Text(
                                    "任意 App",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                                )
                            }
                            items(apps) { (pkg, label) ->
                                ListItem(
                                    headlineContent = { Text(label) },
                                    supportingContent = { Text(pkg, fontSize = 10.sp) },
                                    modifier = Modifier.clickable { save(QuickAction.openApp(pkg)) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
