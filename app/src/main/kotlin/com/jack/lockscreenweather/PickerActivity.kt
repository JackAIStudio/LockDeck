package com.jack.lockscreenweather

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
import com.jack.lockscreenweather.data.Store
import com.jack.lockscreenweather.notify.WeatherNotifier
import com.jack.lockscreenweather.ui.theme.LockScreenWeatherTheme

/** 选择某个点击区要打开哪个应用。 */
class PickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val zone = intent.getStringExtra("zone") ?: "main"
        enableEdgeToEdge()
        setContent {
            LockScreenWeatherTheme {
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

                    Scaffold(
                        topBar = {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("选择应用", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "绑到：${if (zone == "main") "主区域" else "右下区域"}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    ) { pad ->
                        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                            item {
                                ListItem(
                                    headlineContent = { Text("使用默认（天气 / 闹钟）") },
                                    supportingContent = { Text("恢复系统默认行为") },
                                    modifier = Modifier.clickable {
                                        Store.setTarget(ctx, zone, null)
                                        WeatherNotifier.show(ctx, Store.loadWeather(ctx))
                                        startActivity(Intent(ctx, MainActivity::class.java))
                                        finish()
                                    },
                                )
                                HorizontalDivider()
                            }
                            items(apps) { (pkg, label) ->
                                ListItem(
                                    headlineContent = { Text(label) },
                                    supportingContent = { Text(pkg, fontSize = 10.sp) },
                                    modifier = Modifier.clickable {
                                        Store.setTarget(ctx, zone, pkg)
                                        WeatherNotifier.show(ctx, Store.loadWeather(ctx))
                                        startActivity(Intent(ctx, MainActivity::class.java))
                                        finish()
                                    },
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
