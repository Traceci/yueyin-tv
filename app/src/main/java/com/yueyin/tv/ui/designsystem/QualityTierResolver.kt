package com.yueyin.tv.ui.designsystem

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.yueyin.tv.core.model.preferences.QualityTier

/**
 * 把用户选择（含 Auto）解析成实际生效的档位。
 *
 * 判定依据全部是标准 Android API，不做机型白名单：
 * - `ActivityManager.isLowRamDevice()`：系统标记的低内存设备，直接落流畅档；
 * - `MemoryInfo.lowMemory` / `totalMem`：运行时可用内存不足或总量偏小；
 * - `Build.VERSION.SDK_INT < 31`：无原生 RenderEffect 模糊，且旧设备 GPU 普遍更弱；
 * - `UI_MODE_TYPE_TELEVISION`：电视/盒子的 GPU 通常弱于同期手机。
 *
 * 策略偏保守：拿不准就降一档，宁可画面朴素也不要卡顿。
 */
fun resolveEffectiveTier(context: Context, selected: QualityTier): QualityTier {
    if (selected != QualityTier.Auto) return selected
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val isLowRam = manager?.isLowRamDevice == true
    val memoryInfo = ActivityManager.MemoryInfo().also { runCatching { manager?.getMemoryInfo(it) } }
    val totalMb = memoryInfo.totalMem / (1024L * 1024L)
    val isTv = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    val legacyApi = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    return when {
        isLowRam || memoryInfo.lowMemory || (totalMb in 1..2047) -> QualityTier.Smooth
        legacyApi || isTv || totalMb < 4096L -> QualityTier.Balanced
        else -> QualityTier.Rich
    }
}
