package com.yueyin.tv.ui.device

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * 界面形态手选档位。
 *
 * 兜底用：部分车机不上报 UI_MODE_TYPE_CAR / FEATURE_AUTOMOTIVE，甚至不声明触摸屏，
 * 会被旧判定归进 TV 壳。用户在手机设置里手动指定即可，不必为一台设备改判定规则。
 */
enum class LayoutOverride(val label: String) {
    Auto("自动"),
    Car("车机"),
    Phone("手机"),
}

object DeviceLayoutOverride {
    private const val PREFS = "device_layout_override"
    private const val KEY = "mode"

    fun get(context: Context): LayoutOverride = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, LayoutOverride.Auto.name)
        LayoutOverride.entries.firstOrNull { it.name == raw } ?: LayoutOverride.Auto
    }.getOrDefault(LayoutOverride.Auto)

    fun set(context: Context, value: LayoutOverride) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, value.name).apply()
        }
    }

    /** 只有用户显式选了档位才覆盖；Auto 完全保持 [detectFormFactor] 的结果。 */
    fun resolve(context: Context, detected: FormFactor): FormFactor = when (get(context)) {
        LayoutOverride.Auto -> detected
        LayoutOverride.Car -> FormFactor.Car
        LayoutOverride.Phone -> FormFactor.Phone
    }
}

/** 从 Compose 的 Context 找到宿主 Activity，切换形态后重建界面用。 */
internal fun Context.findHostActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
