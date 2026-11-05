package com.yueyin.tv.phone

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 车机档位参数。
 *
 * 不直接改 `PhoneSize` / `PhoneType` 里的常量——那些是手机端全局值，改了手机会跟着变大。
 * 这里用 CompositionLocal 覆盖：默认 Disabled 与手机现值完全一致，只有识别到
 * [com.yueyin.tv.ui.device.FormFactor.Car] 才启用 Enabled，手机/平板一行行为都不变。
 */
internal data class CarProfile(
    val enabled: Boolean,
    val touchTarget: Dp,
    val navRailWidth: Dp,
    val navRailItem: Dp,
    val gridMinItemDp: Int,
) {
    companion object {
        val Disabled = CarProfile(
            enabled = false,
            touchTarget = 44.dp,
            navRailWidth = 72.dp,
            navRailItem = 64.dp,
            gridMinItemDp = 150,
        )
        val Enabled = CarProfile(
            enabled = true,
            touchTarget = 56.dp,
            navRailWidth = 88.dp,
            navRailItem = 72.dp,
            gridMinItemDp = 180,
        )
    }
}

/** 与 [CarProfile.Disabled] 的取值保持一致，供窗口测量（非 Composable 上下文）读取。 */
internal const val CAR_NAV_RAIL_DP = 88
internal const val CAR_GRID_MIN_ITEM_DP = 180

internal val LocalCarProfile = staticCompositionLocalOf { CarProfile.Disabled }
