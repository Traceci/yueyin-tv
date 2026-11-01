package com.yueyin.tv.core.audiofx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 双二阶（biquad）滤波器，系数按 RBJ Audio EQ Cookbook 生成。
 *
 * 用 Direct Form I 实现，状态按声道分开保存，可以直接处理**交错**（interleaved）PCM：
 * 对 `buf[i]` 的处理顺序是帧内按声道连续，因此不会把左右声道的历史状态搅在一起。
 *
 * 所有系数在 `set*` 时算好并归一化（除以 a0），处理时只剩乘加，没有三角函数。
 */
internal class Biquad(private val channels: Int) {

    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private val x1 = FloatArray(channels)
    private val x2 = FloatArray(channels)
    private val y1 = FloatArray(channels)
    private val y2 = FloatArray(channels)

    /** 当前是否是直通（系数为恒等）。直通时上层可以整段跳过，省掉每样本的乘加。 */
    val isBypass: Boolean
        get() = b0 == 1f && b1 == 0f && b2 == 0f && a1 == 0f && a2 == 0f

    fun reset() {
        x1.fill(0f); x2.fill(0f); y1.fill(0f); y2.fill(0f)
    }

    fun setBypass() {
        b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
    }

    /**
     * 峰值滤波（peaking EQ）。`q` 越大，影响的频带越窄。
     * 增益为 0 时直接退回直通，避免给平坦预设白算一道。
     */
    fun setPeaking(sampleRate: Float, centerHz: Float, gainDb: Float, q: Float = 1.41f) {
        if (gainDb == 0f || centerHz >= sampleRate * 0.5f) {
            setBypass()
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * centerHz / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha / a

        b0 = ((1.0 + alpha * a) / a0).toFloat()
        b1 = ((-2.0 * cosW) / a0).toFloat()
        b2 = ((1.0 - alpha * a) / a0).toFloat()
        a1 = ((-2.0 * cosW) / a0).toFloat()
        a2 = ((1.0 - alpha / a) / a0).toFloat()
    }

    /** 低架滤波：整体抬/降某个频率以下的能量，比峰值滤波更接近"低音增强"的听感。 */
    fun setLowShelf(sampleRate: Float, cornerHz: Float, gainDb: Float, slope: Float = 1f) {
        if (gainDb == 0f || cornerHz >= sampleRate * 0.5f) {
            setBypass()
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * cornerHz / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val a0 = (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha
        b0 = (a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha) / a0).toFloat()
        b1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosW) / a0).toFloat()
        b2 = (a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha) / a0).toFloat()
        a1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosW) / a0).toFloat()
        a2 = (((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha) / a0).toFloat()
    }

    /** 高架滤波：抬/降某个频率以上的能量（"空气感""齿音"用）。 */
    fun setHighShelf(sampleRate: Float, cornerHz: Float, gainDb: Float, slope: Float = 1f) {
        if (gainDb == 0f || cornerHz >= sampleRate * 0.5f) {
            setBypass()
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * cornerHz / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val a0 = (a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha
        b0 = (a * ((a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha) / a0).toFloat()
        b1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosW) / a0).toFloat()
        b2 = (a * ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha) / a0).toFloat()
        a1 = (2.0 * ((a - 1.0) - (a + 1.0) * cosW) / a0).toFloat()
        a2 = (((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha) / a0).toFloat()
    }

    /** 原地处理 `frames` 帧（每帧 `channels` 个样本）。 */
    fun process(buffer: FloatArray, frames: Int) {
        var i = 0
        for (frame in 0 until frames) {
            for (ch in 0 until channels) {
                val x = buffer[i]
                val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
                x2[ch] = x1[ch]
                x1[ch] = x
                y2[ch] = y1[ch]
                y1[ch] = y
                buffer[i] = y
                i++
            }
        }
    }
}
