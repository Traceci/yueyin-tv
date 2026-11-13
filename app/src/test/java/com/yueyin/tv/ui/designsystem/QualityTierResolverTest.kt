package com.yueyin.tv.ui.designsystem

import androidx.test.core.app.ApplicationProvider
import com.yueyin.tv.core.model.preferences.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 用 Robolectric 提供真实 Context，验证档位解析入口（不依赖真机）。 */
@RunWith(RobolectricTestRunner::class)
class QualityTierResolverTest {

    @Test
    fun `explicit tier bypasses device detection`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        QualityTier.entries.filter { it != QualityTier.Auto }.forEach { tier ->
            assertEquals(tier, resolveEffectiveTier(context, tier))
        }
    }

    @Test
    fun `auto always resolves to a concrete tier`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolved = resolveEffectiveTier(context, QualityTier.Auto)
        assertTrue(resolved != QualityTier.Auto)
        assertTrue(resolved in listOf(QualityTier.Smooth, QualityTier.Balanced, QualityTier.Rich))
        // 解析结果必须能推导出可用策略。
        assertTrue(resolved.policy().tier == resolved)
    }
}
