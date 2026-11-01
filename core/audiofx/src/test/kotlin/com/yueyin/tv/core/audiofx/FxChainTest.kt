package com.yueyin.tv.core.audiofx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class FxChainTest {

    private val sampleRate = 44_100

    /** 生成一段单频正弦（双声道同相），幅度默认 0.05 ≈ -26 dBFS，落在压缩器阈值以下。 */
    private fun sine(freqHz: Float, frames: Int, amplitude: Float = 0.05f): FloatArray {
        val buffer = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = (amplitude * sin(2.0 * PI * freqHz * i / sampleRate)).toFloat()
            buffer[i * 2] = v
            buffer[i * 2 + 1] = v
        }
        return buffer
    }

    /** 跳过前 10% 的滤波器瞬态，用剩下的算 RMS。 */
    private fun rms(buffer: FloatArray, frames: Int): Double {
        val start = frames / 10
        var sum = 0.0
        var count = 0
        for (i in start until frames) {
            val l = buffer[i * 2].toDouble()
            sum += l * l
            count++
        }
        return sqrt(sum / count.coerceAtLeast(1))
    }

    private fun gainOf(preset: FxPreset, freqHz: Float): Double {
        val frames = sampleRate / 2
        val input = sine(freqHz, frames)
        val reference = rms(input, frames)

        val chain = FxChain()
        chain.configure(sampleRate, 2)
        chain.update(preset.toSettings(FxSettings.DEFAULT).copy(enabled = true, limiterEnabled = false))

        val output = input.copyOf()
        chain.process(output, frames)
        return rms(output, frames) / reference
    }

    @Test
    fun disabledChainPassesSamplesThroughUnchanged() {
        val frames = 4_096
        val input = sine(1_000f, frames)
        val expected = input.copyOf()

        val chain = FxChain()
        chain.configure(sampleRate, 2)
        chain.update(FxSettings.DEFAULT.copy(enabled = false))

        chain.process(input, frames)

        for (i in input.indices) {
            assertEquals("第 $i 个样本被改动", expected[i], input[i], 0f)
        }
    }

    @Test
    fun bassPresetBoostsLowEndMoreThanMids() {
        val low = gainOf(FxPreset.BASS, 62f)
        val mid = gainOf(FxPreset.BASS, 1_000f)

        assertTrue("62Hz 增益 $low 应大于 1.2", low > 1.2)
        assertTrue("62Hz($low) 应比 1kHz($mid) 至少高 1.5 倍", low / mid > 1.5)
    }

    @Test
    fun vocalPresetBoostsMidsMoreThanLows() {
        val mid = gainOf(FxPreset.VOCAL, 2_000f)
        val low = gainOf(FxPreset.VOCAL, 62f)

        assertTrue("2kHz 增益 $mid 应大于 1.2", mid > 1.2)
        assertTrue("2kHz($mid) 应高于 62Hz($low)", mid > low)
    }

    @Test
    fun limiterKeepsPeakUnderCeiling() {
        val frames = 8_192
        // 故意给一个远超 0dBFS 的信号，模拟"音效抬完 + 母带本身就压得很满"的极端情况
        val buffer = sine(220f, frames, amplitude = 1.5f)

        val chain = FxChain()
        chain.configure(sampleRate, 2)
        chain.update(
            FxPreset.BASS.toSettings(FxSettings.DEFAULT)
                .copy(enabled = true, limiterEnabled = true),
        )

        chain.process(buffer, frames)

        var peak = 0f
        for (v in buffer) peak = max(peak, abs(v))
        assertTrue("峰值 $peak 应被限制在 1.0 以内", peak <= 1.0f)
    }

    @Test
    fun repeatedConfigureKeepsFilterState() {
        val frames = 2_048
        val chain = FxChain()
        chain.configure(sampleRate, 2)
        chain.update(FxPreset.BASS.toSettings(FxSettings.DEFAULT).copy(enabled = true))

        val first = sine(62f, frames)
        chain.process(first, frames)

        // 播放过程中 Format 变化会再次调用 configure，若这里重建滤波器会产生可听见的爆音
        chain.configure(sampleRate, 2)
        val second = sine(62f, frames)
        chain.process(second, frames)

        assertTrue("第二次处理仍应产生有效输出", rms(second, frames) > 0.01)
    }

    private fun max(a: Float, b: Float): Float = if (a > b) a else b
}
