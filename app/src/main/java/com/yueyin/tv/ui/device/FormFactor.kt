package com.yueyin.tv.ui.device

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Build
import android.util.Log
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * 设备形态。决定走哪一套界面壳与密度折算方式。
 *
 * 为什么要单独判一次、而不是在各页面里散着判断 `screenWidthDp`：
 * 电视和手机的区别不只是宽度，还有**输入方式**（遥控器 vs 手指）和**观看距离**
 * （3 米 vs 30 厘米）。同一个 1280dp 宽的设备可能是 4K 电视也可能是横放的平板，
 * 只看宽度必然判错。所以这里以「是否为电视」为第一判据，宽度只在手持设备内部区分手机/平板。
 */
enum class FormFactor {
    /** 电视 / 电视盒子：遥控器操作，10 英尺观看距离，按 960×540 设计画布做密度折算。 */
    Tv,

    /** 手机：竖屏触屏，近距单手操作，不做画布折算。 */
    Phone,

    /** 平板 / 折叠屏展开态：大画布触屏。P0 暂与手机共用一套壳，P5 再拆双栏。 */
    Tablet,

    /** 车机：安卓车载设备（含不声明触摸屏的机型），复用手机壳但启用车机档位。 */
    Car;

    /** 是否手持触屏形态。TV 之外都不做 960×540 折算，sp 与系统字体缩放正常生效。 */
    val isHandheld: Boolean get() = this != Tv
}

/**
 * 平板判据：最小宽度 600dp（官方 `sw600dp` 资源限定符同口径）。
 * 低于它的手持设备一律按手机处理。
 */
private const val TABLET_SMALLEST_WIDTH_DP = 600

/**
 * 纯函数形态判定，Activity 与非 Compose 上下文共用同一套逻辑。
 *
 * 「是否为电视」取三个信号相或：
 * 1. `UI_MODE_TYPE_TELEVISION` —— 电视系统自己上报，最准；
 * 2. 声明了 `FEATURE_LEANBACK` —— Android TV 的启动器能力，电视盒子必有；
 * 3. 没有触摸屏 —— 盒子类设备（部分电视棒 / 机顶盒）不上报 television，但确实没有触摸屏。
 *
 * 第 2 条是为「固件错报」准备的：部分电视盒子把 uiMode 报成 CAR、或误声明 automotive
 * （带前置小触摸屏的媒体播放器盒子尤其常见），单看前两条会被判成车机。
 *
 * 反过来说：**有触摸屏、没上报 television、也没声明 leanback 的设备按手持设备处理**。
 * 手机不会漏判成电视，这是手机版能正常起来的前提。
 */
fun detectFormFactor(
    uiModeType: Int,
    smallestScreenWidthDp: Int,
    hasTouchscreen: Boolean,
    isAutomotive: Boolean = false,
    hasLeanback: Boolean = false,
    /** 是否声明了 `FEATURE_TELEVISION`（真电视一般都有，非认证盒子通常没有）。 */
    hasTelevision: Boolean = false,
    /** 是否支持 HDMI CEC：电视与播放器基本都有，手机 / 平板 / 车机没有。 */
    hasHdmiCec: Boolean = false,
    /**
     * 实测有没有触摸输入设备（遍历 InputManager 的输入设备列表）。
     * 默认退回系统特性声明，调用方应尽量传实测值。
     */
    hasRealTouchInput: Boolean = hasTouchscreen,
): FormFactor {
    // 电视的强信号：系统自报电视 / 声明电视或 Leanback 能力。任一命中即电视，
    // 且优先于车机判断——固件把 uiMode 错报成 CAR 的盒子基本都带这些能力。
    if (uiModeType == Configuration.UI_MODE_TYPE_TELEVISION) return FormFactor.Tv
    if (hasTelevision || hasLeanback) return FormFactor.Tv
    // 车机必须有实据：只凭 uiMode==CAR 太宽，不少电视盒子 / 播放器固件会错报。
    // 带 HDMI CEC 的可以确定不是车机（车机没有 CEC 输出），这条把它们捞回来。
    if (isAutomotive) return FormFactor.Car
    if (uiModeType == Configuration.UI_MODE_TYPE_CAR && !hasHdmiCec) return FormFactor.Car
    // 实测没有任何触摸输入设备 → 电视 / 盒子。
    // 用输入设备列表而不是 FEATURE_TOUCHSCREEN：UHD3000 这类工程固件会声明 touchscreen
    // 特性、实际却没有触摸屏，只看特性会把它误判成平板。
    if (!hasRealTouchInput) return FormFactor.Tv
    return if (smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP) FormFactor.Tablet else FormFactor.Phone
}

/**
 * 一次性读全形态判据。
 *
 * 集中读一次：`hasSystemFeature` 与输入设备遍历都有开销，`resolveFormFactor` 与
 * `rememberFormFactor` 又要用同一批值，分开各读一遍既慢也容易出现不一致。
 */
internal data class FormFactorSignals(
    val hasTouchscreen: Boolean,
    val hasRealTouchInput: Boolean,
    val hasLeanback: Boolean,
    val hasTelevision: Boolean,
    val hasHdmiCec: Boolean,
    val isAutomotive: Boolean,
) {
    companion object {
        fun of(context: Context): FormFactorSignals {
            val pm = context.packageManager
            return FormFactorSignals(
                hasTouchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
                hasRealTouchInput = hasTouchInputDevice(context),
                hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
                hasTelevision = pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION),
                // 直接写特性名：PackageManager 没有对应的公开常量（部分 SDK 里缺失）。
                hasHdmiCec = pm.hasSystemFeature("android.hardware.hdmi.cec"),
                isAutomotive = pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
            )
        }
    }
}

/**
 * 实测有没有触摸输入设备。
 *
 * 不信 `FEATURE_TOUCHSCREEN`：部分播放器 / 盒子固件会声明该特性、实际却没有触摸屏
 * （UHD3000 这类工程版固件就是），只看声明会把它判成平板。
 */
private fun hasTouchInputDevice(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(InputManager::class.java) ?: return@runCatching false
    manager.inputDeviceIds.any { id ->
        val device = manager.getInputDevice(id) ?: return@any false
        (device.sources and InputDevice.SOURCE_TOUCHSCREEN) != 0
    }
}.getOrDefault(false)

/** 从 Context 判定形态：供 `MainActivity` 在非 Compose 上下文里设置屏幕方向用。 */
fun resolveFormFactor(context: Context): FormFactor {
    val configuration = context.resources.configuration
    val uiModeType = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    val signals = FormFactorSignals.of(context)
    val detected = detectFormFactor(
        uiModeType = uiModeType,
        smallestScreenWidthDp = configuration.smallestScreenWidthDp,
        hasTouchscreen = signals.hasTouchscreen,
        isAutomotive = signals.isAutomotive,
        hasLeanback = signals.hasLeanback,
        hasTelevision = signals.hasTelevision,
        hasHdmiCec = signals.hasHdmiCec,
        hasRealTouchInput = signals.hasRealTouchInput,
    )
    // 判错时靠这一行定位：全部判据 + 型号一次打全，不必再靠猜。
    Log.i(
        "YueYinFormFactor",
        "uiMode=$uiModeType sw=${configuration.smallestScreenWidthDp}" +
            " touchFeature=${signals.hasTouchscreen} touchInput=${signals.hasRealTouchInput}" +
            " leanback=${signals.hasLeanback} television=${signals.hasTelevision}" +
            " cec=${signals.hasHdmiCec} automotive=${signals.isAutomotive}" +
            " model=${Build.MODEL} => $detected",
    )
    return DeviceLayoutOverride.resolve(context, detected)
}

/**
 * Compose 侧形态读取。
 *
 * `remember` 的 key 取的是**判据字段**而不是 `Configuration` 实例：
 * `LocalConfiguration` 在屏幕旋转时会换成新实例，但同一次旋转里也可能复用旧实例并原地改字段，
 * 用实例做 key 会出现「转屏了但形态没重算」。
 */
@Composable
fun rememberFormFactor(): FormFactor {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val uiModeType = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    val smallestWidthDp = configuration.smallestScreenWidthDp
    // 系统特性与输入设备在运行期不会变，按 context 缓存一次即可。
    val signals = remember(context) { FormFactorSignals.of(context) }
    val layoutOverride = DeviceLayoutOverride.get(context)
    return remember(context, uiModeType, smallestWidthDp, signals, layoutOverride) {
        DeviceLayoutOverride.resolve(
            context,
            detectFormFactor(
                uiModeType = uiModeType,
                smallestScreenWidthDp = smallestWidthDp,
                hasTouchscreen = signals.hasTouchscreen,
                isAutomotive = signals.isAutomotive,
                hasLeanback = signals.hasLeanback,
                hasTelevision = signals.hasTelevision,
                hasHdmiCec = signals.hasHdmiCec,
                hasRealTouchInput = signals.hasRealTouchInput,
            ),
        )
    }
}

/**
 * 当前形态的 CompositionLocal。
 *
 * 默认值取 [FormFactor.Tv] 而不是 Phone：任何没有显式提供该值的调用点
 * （现存全部 TV 界面、单元测试、Compose 预览）都会自动保持原有行为，
 * 不会因为漏传一个 Local 就悄悄换成手机布局。
 */
val LocalFormFactor = staticCompositionLocalOf { FormFactor.Tv }
