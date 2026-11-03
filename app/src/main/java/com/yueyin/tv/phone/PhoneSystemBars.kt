package com.yueyin.tv.phone

import android.view.Window
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 手机壳的系统栏策略，单点定义。
 *
 * 为什么抽出来：这套规则有两个使用点——`MainActivity` 在启动与转屏时应用，
 * 播放页在退出沉浸时恢复。两处各写一遍，迟早出现「从播放页返回后横屏又冒出状态栏」，
 * 而人很难当场联想到是两个地方各写了一遍。
 */
internal object PhoneSystemBars {

    /**
     * 手持设备：横屏隐藏状态栏、竖屏显示。
     *
     * 横屏只有 411dp 高，状态栏要吃掉 24~32dp；而且播放页本来就沉浸，
     * 其它页显示、播放页隐藏，来回切会闪一下。隐藏后设 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，
     * 从顶部下滑仍能临时看到时间与通知，不牺牲可读性。
     *
     * 不碰导航条：三键导航的返回键长在导航条上，藏掉等于把返回入口一起藏了。
     */
    fun applyByOrientation(window: Window, landscape: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (landscape) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        // 应用整体是深蓝黑底，状态栏图标固定浅色：交给 auto 会跟随系统明暗，
        // 系统处于浅色模式时图标变深，在深色底上看不见。
        controller.isAppearanceLightStatusBars = false
    }
}
