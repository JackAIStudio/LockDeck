package com.jack.lockscreenweather.overlay

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * 从锁屏点击卡片后，真正把目标 App 送到前台。
 *
 * 为什么需要这个透明中间页：
 * 锁屏上直接 startActivity 时，系统会把这次启动「挂起」，等用户自己上滑解锁后才显示，
 * 表现为「点了没反应，得手动退出锁屏」。这里改成主动请求解锁：
 *
 *   有密码/指纹 -> 弹出解锁界面，验证通过后自动跳转目标 App
 *   已解锁      -> 立刻跳转，不再有任何等待
 *
 * 全程透明无动画，用户感知上就是「点了就进」。
 */
class LaunchTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 允许自己在锁屏之上显示，否则锁屏时这个 Activity 根本起不来
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val pkg = intent.getStringExtra(EXTRA_PKG)
        val km = getSystemService(KeyguardManager::class.java)

        // 已经解锁：直接走，不弹任何东西
        if (km == null || !km.isKeyguardLocked) {
            launchAndFinish(pkg)
            return
        }

        // 锁着：请求系统解锁，成功后自动接着跳
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Log.i("LSW", "keyguard dismissed -> launch $pkg")
                launchAndFinish(pkg)
            }

            override fun onDismissCancelled() {
                Log.i("LSW", "keyguard dismiss cancelled")
                finish()
            }

            override fun onDismissError() {
                Log.w("LSW", "keyguard dismiss error, try launch anyway")
                launchAndFinish(pkg)
            }
        })
    }

    private fun launchAndFinish(pkg: String?) {
        if (pkg.isNullOrBlank()) {
            finish()
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Log.e("LSW", "no launch intent for $pkg")
            finish()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(launch) }
            .onFailure { Log.e("LSW", "launch failed", it) }

        // 关掉自己，并把无动画收尾做干净
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_PKG = "target_pkg"
    }
}
