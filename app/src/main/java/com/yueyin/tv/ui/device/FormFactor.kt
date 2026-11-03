package com.yueyin.tv.ui.device

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
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
    Tablet;

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
 * 「是否为电视」取两个信号相或：
 * 1. `UI_MODE_TYPE_TELEVISION` —— 电视系统自己上报，最准；
 * 2. 没有触摸屏 —— 盒子类设备（部分电视棒 / 机顶盒）不上报 television，但确实没有触摸屏。
 *
 * 反过来说：**有触摸屏且没上报 television 的设备一定是手持设备**。手机不会漏判成电视，
 * 这是手机版能正常起来的前提。
 */
fun detectFormFactor(
    uiModeType: Int,
    smallestScreenWidthDp: Int,
    hasTouchscreen: Boolean,
): FormFactor {
    val isTv = uiModeType == Configuration.UI_MODE_TYPE_TELEVISION || !hasTouchscreen
    if (isTv) return FormFactor.Tv
    return if (smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP) FormFactor.Tablet else FormFactor.Phone
}

/** 从 Context 判定形态：供 `MainActivity` 在非 Compose 上下文里设置屏幕方向用。 */
fun resolveFormFactor(context: Context): FormFactor {
    val configuration = context.resources.configuration
    return detectFormFactor(
        uiModeType = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK,
        smallestScreenWidthDp = configuration.smallestScreenWidthDp,
        hasTouchscreen = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
    )
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
    return remember(context, uiModeType, smallestWidthDp) {
        detectFormFactor(
            uiModeType = uiModeType,
            smallestScreenWidthDp = smallestWidthDp,
            hasTouchscreen = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
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
