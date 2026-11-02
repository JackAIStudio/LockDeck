<img src="design/icon/svg/D-letterL.svg" width="88" align="right" alt="">

# 锁屏快捷台 · LockDeck

> 安卓控制中心里的天气读数 + 两个可自定义的快捷动作按钮。

一个很小、很个人的工具 App：把「下拉看一眼现在几度」和「点一下就扫码/付款」放进系统控制中心，不用先解锁、不用先找 App。

代码里那些长注释不是写给编译器看的，是写给下一个改它的人看的——**每一条都对应一次真机踩坑**，包括哪些路试过、为什么放弃。

## 它做什么

**控制中心三个格子**

| 按钮 | 功能 |
|---|---|
| 天气 | 显示当前温度，点开进天气 App |
| 快捷按钮 ① | 自己选：支付宝扫一扫 / 微信扫一扫 / 收付款 / 任意 App / 刷新天气 |
| 快捷按钮 ② | 同上，默认微信扫一扫 |

按钮一进来是空的——控制中心放哪些格子只有用户自己能决定，App 只能「请求添加」，最终要你在「编辑」里把按钮拖上去。

**通知栏常驻天气**

一行系统原生样式通知：温度、今日高低温、体感/湿度/紫外线/风、空气质量、未来 2 小时有没有雨。点它执行你绑定的动作。

**锁屏下点按钮不会被拦**

锁屏上直接 `startActivity` 会被系统挂起，用户得自己上滑解锁才看得到，表现就是「点了没反应」。这里走一层透明中间页主动请求解锁，验证通过后自动续跳——感知上就是「点了就进」。

## 天气数据从哪来

三个源，设置页自己切，**不做静默降级**（静默降级只会让人看到「温度忽然变了两度却不知道为什么」，比直接报错更难查）：

| 源 | 说明 |
|---|---|
| **小米天气**（默认） | 私有接口，数值和手机自带天气完全一致——本来就是同一个后端，还带分钟级降水与空气质量。没有文档、没有 SLA，哪天改了校验就失效 |
| **中央气象台** | `weather.cma.cn` 观测站，免费、无需 key、最稳。但按气象站给数据，市区几个区可能共用同一个站，实况和手机差 2 度上下 |
| **Open-Meteo** | 免费开源、有 SLA，最后一道防线。显式指定中国 CMA 模式，但仍是格点插值不是观测 |

小米那条的 `appKey` / `sign` 是从 [Breezy Weather](https://github.com/breezy-weather/breezy-weather)（LGPL-3.0）照抄的公开常量，不是本项目的资产。**用私有接口是有代价的**：它对我们是敞开的，也随时可能关上门。

定位拿不到就显示「等待定位」，**绝不编一个坐标顶上**——老版本干过这事，用户明明在 A 城却看到 B 城天气，界面上一点异常都没有。

## 构建

需要 JDK 17+ 和 Android SDK（`compileSdk = 37`）。

```sh
git clone https://github.com/JackAIStudio/LockDeck.git
cd LockDeck
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties`（里面只有 `sdk.dir`）不入库，Android Studio 打开会自动生成；命令行构建请自己写一行，或设 `ANDROID_HOME`。

| 组件 | 版本 |
|---|---|
| Kotlin | 2.3.20 |
| AGP | 9.0.1 |
| Gradle | 9.1.0 |
| Compose BOM | 2026.03.01 |
| minSdk / targetSdk | 31 (Android 12) / 37 |

## 已知限制

这些不是 TODO，是**已经查清的边界**，写下来省得后来者重跑一遍：

- **只在 HyperOS 3（Redmi Turbo 5 / Android 16）上做过真机验证。** 原生 Android 上照样能跑，但下面第一条和第三条限制只针对 HyperOS（第二条里 WorkManager 的问题在原生 ROM 上反而不存在，所以它留着当兜底）。
- **通知上不了锁屏。** HyperOS 的锁屏通知由 SystemUI 的 `KeyguardCoordinator` + `MiuiNotifCoordinator` 判定，第三方应用默认被判 `keyguard=false`。importance、静音、勿扰、焦点通知/超级岛都逐一排除过，全都不是原因——闸门是 MIUI 按应用下发的权限，App 侧无法用参数绕过。所以「锁屏看天气」实际走的是**下拉控制中心**，不是通知。早期版本用无障碍浮层强行盖上去，会挡住别人的通知还卡，已经整个删掉。
- **WorkManager 在 HyperOS 3 上不执行。** 任务躺在队列里（`state=0`），`run_attempt_count` 永远是 0，`cmd jobscheduler run -f` 强制跑也一样。真正干活的是前台服务里的 `Handler` 循环，WorkManager 只作为其他 ROM 上的兜底保留。
- **温度是画进图标里的。** HyperOS 控制中心磁贴只渲染图标、不渲染 label/subtitle，想在那里看到数字只能自己 `Canvas` 画字；同时 label 也照常填，原生控制中心上看得到。图标还会被系统统一染成纯白，所以只能靠形状和字形区分。
- **控制中心按钮不能由 App 直接添加。** Android 13+ 只能 `requestAddTileService` 发起一次请求，弹不弹框看系统；Android 12 及以下必须手动编辑。
- **扫一扫深链会随微信/支付宝版本变化。** 微信走的是它自己的 `ShortCutDispatchAction` + `launchType` extra，支付宝走 `alipayqr://` scheme，都依赖 `AndroidManifest.xml` 里的 `<queries>` 声明。哪天不灵了，先去看这两家有没有改入口。

## 项目结构

```
app/src/main/kotlin/com/jack/lockdeck/
├── MainActivity.kt        设置页 + 天气速览（Compose，单文件偏长）
├── PickerActivity.kt      选择动作
├── action/                快捷动作定义、Intent 构造、统一执行出口
├── launch/                锁屏跳转透明中间页
├── data/                  定位、三个天气源、编解码、SharedPreferences 缓存
├── notify/                常驻通知、前台服务、刷新调度、锁屏可见性自检
├── tile/                  控制中心按钮
└── ui/theme/              Compose 主题（含 Material 3 模板自带的 Apache 头）
design/icon/               图标生成脚本
```

图标是脚本生成的：`design/icon/final_L.py` 产出 `res/` 里那三个 vector 与 SVG 源，`gen_legacy.py` 产旧式 webp。**改几何改脚本，别只改 XML**，否则设计稿和实际图标会分叉。PNG 是产物，不入库。

## 隐私

没有账号、没有统计、没有崩溃上报，不向作者或任何第三方回传数据。只有三类出网请求，都是功能本身：

- 天气源（小米 / 中央气象台 / Open-Meteo）
- 定位反查地名（BigDataCloud）
- 你点击按钮时跳转的目标 App

配置和天气快照存在本机 `SharedPreferences`（文件名 `lsw`）。`allowBackup` 是开着的，所以系统会把它并进你自己的账号备份（跟着换机走），**不经过作者**；想彻底不要，把 `AndroidManifest.xml` 里那个开关关掉即可。

## 许可

[Apache License 2.0](LICENSE) © 2026 吴杰克Jack

`app/src/main/kotlin/com/jack/lockdeck/ui/theme/` 下的 `Color.kt` 与 `Theme.kt` 来自 Android Studio 的 Material 3 工程模板，文件头保留原始的 Apache-2.0 版权声明。
