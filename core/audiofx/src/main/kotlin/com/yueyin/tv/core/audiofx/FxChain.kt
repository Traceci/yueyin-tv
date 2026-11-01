package com.yueyin.tv.core.audiofx

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tanh

/**
 * 音效处理链：十段均衡 → 低音架 → 空气感高架 → 声场宽度 → 压缩 → 限幅。
 *
 * 全部在 float 域、原地处理交错 PCM，不分配临时缓冲，可以直接跑在音频线程上。
 * `enabled == false` 时 `process` 立刻返回，不做任何一次乘加——关掉音效就是零成本。
 *
 * 顺序有讲究：
 * - 均衡放在压缩前，压缩器才能"看见"被抬高过的低频并据此让出余量；
 * - 限幅放在最后，兜住压缩器来不及处理的瞬态（电视喇叭上这几乎必然出现）。
 */
class FxChain(private val maxChannels: Int = 2) {

    private var sampleRate = 44_100f
    private var channels = 2
    private var configured = false
    private var settings = FxSettings.DEFAULT

    private var eq: Array<Biquad?> = arrayOfNulls(FxBands.COUNT)
    private var bassShelf: Biquad? = null
    private var airShelf: Biquad? = null

    private val compressor = Compressor()
    private val limiter = Limiter()

    val isEnabled: Boolean get() = settings.enabled

    /** 由 AudioProcessor 在 `configure` 阶段调用；采样率或声道数变化时会重建滤波器。 */
    fun configure(sampleRateHz: Int, channelCount: Int) {
        val sr = sampleRateHz.coerceIn(8_000, 192_000).toFloat()
        val ch = channelCount.coerceIn(1, maxChannels)
        if (configured && sr == sampleRate && ch == channels) return

        sampleRate = sr
        channels = ch
        configured = true

        if (eq.any { it == null }) eq = Array(FxBands.COUNT) { Biquad(ch) }
        bassShelf = Biquad(ch)
        airShelf = Biquad(ch)
        compressor.configure(sr)
        limiter.configure(sr)
        applyCoefficients()
    }

    fun update(newSettings: FxSettings) {
        settings = newSettings.normalized()
        if (configured) applyCoefficients()
    }

    fun reset() {
        eq.forEach { it?.reset() }
        bassShelf?.reset()
        airShelf?.reset()
        compressor.reset()
        limiter.reset()
    }

    private fun applyCoefficients() {
        val gains = settings.gainsDb
        for (i in 0 until FxBands.COUNT) {
            val filter = eq[i] ?: continue
            // 低段（31/62/125）用更宽的 Q，避免和大喇叭的箱体共振撞在一起发闷
            val q = if (i <= 2) 0.9f else 1.41f
            filter.setPeaking(sampleRate, FxBands.CENTER_HZ[i], gains.getOrElse(i) { 0f }, q)
        }
        bassShelf?.setLowShelf(sampleRate, 100f, settings.bassDb)
        airShelf?.setHighShelf(sampleRate, 7_000f, if (settings.nightMode) 1.5f else 0f)

        if (settings.nightMode) {
            compressor.setProfile(thresholdDb = -30f, ratio = 4f, kneeDb = 10f, attackMs = 5f, releaseMs = 250f, makeupDb = 6f)
        } else {
            compressor.setProfile(thresholdDb = -20f, ratio = 2.5f, kneeDb = 8f, attackMs = 8f, releaseMs = 180f, makeupDb = 2f)
        }
    }

    /** 原地处理 `frames` 帧。`buffer` 必须是交错排列，长度 ≥ frames × channels。 */
    fun process(buffer: FloatArray, frames: Int) {
        if (!settings.enabled || frames <= 0) return

        for (i in 0 until FxBands.COUNT) {
            val filter = eq[i] ?: continue
            if (!filter.isBypass) filter.process(buffer, frames)
        }
        bassShelf?.takeIf { !it.isBypass }?.process(buffer, frames)
        airShelf?.takeIf { !it.isBypass }?.process(buffer, frames)

        if (channels == 2) applyWidth(buffer, frames)
        compressor.process(buffer, frames, channels)
        if (settings.limiterEnabled) limiter.process(buffer, frames, channels)
    }

    /** M/S 处理：只动差信号，人声（多在 M 上）不会被推远。 */
    private fun applyWidth(buffer: FloatArray, frames: Int) {
        val width = settings.stereoWidth
        if (width == 1f) return
        var i = 0
        for (frame in 0 until frames) {
            val l = buffer[i]
            val r = buffer[i + 1]
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * width
            buffer[i] = mid + side
            buffer[i + 1] = mid - side
            i += 2
        }
    }
}

/**
 * 软拐点压缩器。用 dB 域包络跟随，增益变化再走一次 attack/release 平滑，
 * 避免"一压一放"带来的抽吸感（电视小音量下尤其明显）。
 */
private class Compressor {
    private var thresholdDb = -20f
    private var ratio = 2.5f
    private var kneeDb = 8f
    private var attackMs = 8f
    private var releaseMs = 180f
    private var makeupDb = 2f

    private var envDb = -90f
    private var gain = 1f
    private var envAttack = 0f
    private var envRelease = 0f
    private var gainAttack = 0f
    private var gainRelease = 0f
    private var sampleRate = 44_100f

    fun setProfile(
        thresholdDb: Float,
        ratio: Float,
        kneeDb: Float,
        attackMs: Float,
        releaseMs: Float,
        makeupDb: Float,
    ) {
        this.thresholdDb = thresholdDb
        this.ratio = ratio
        this.kneeDb = kneeDb
        this.attackMs = attackMs
        this.releaseMs = releaseMs
        this.makeupDb = makeupDb
        if (sampleRate > 0f) recomputeCoefficients()
    }

    fun configure(sampleRateHz: Float) {
        sampleRate = sampleRateHz
        recomputeCoefficients()
        reset()
    }

    private fun recomputeCoefficients() {
        envAttack = coef(attackMs)
        envRelease = coef(releaseMs)
        // 增益平滑比包络再慢一点，防止每帧 dB 抖动直接变成音量抖动
        gainAttack = coef(attackMs * 0.5f)
        gainRelease = coef(releaseMs * 1.5f)
    }

    private fun coef(ms: Float): Float =
        exp(-1.0 / (sampleRate * (ms / 1000.0).coerceAtLeast(0.0001))).toFloat()

    fun reset() {
        envDb = -90f
        gain = 1f
    }

    fun process(buffer: FloatArray, frames: Int, channels: Int) {
        val slope = 1f - 1f / ratio
        var i = 0
        for (frame in 0 until frames) {
            var peak = 0f
            for (ch in 0 until channels) peak = max(peak, abs(buffer[i + ch]))

            val peakDb = 20f * log10(max(peak, 1e-7f))
            envDb = if (peakDb > envDb) {
                envAttack * envDb + (1f - envAttack) * peakDb
            } else {
                envRelease * envDb + (1f - envRelease) * peakDb
            }

            val over = envDb - thresholdDb
            val reductionDb = when {
                over <= -kneeDb * 0.5f -> 0f
                over >= kneeDb * 0.5f -> over * slope
                else -> {
                    // 二次插值：拐点区间内增益约减量平滑接入，听不到"突然开始压"的台阶
                    val t = over + kneeDb * 0.5f
                    slope * t * t / (2f * kneeDb)
                }
            }

            val targetGain = 10.0.pow(((makeupDb - reductionDb) / 20f).toDouble()).toFloat()
            gain = if (targetGain < gain) {
                gainAttack * gain + (1f - gainAttack) * targetGain
            } else {
                gainRelease * gain + (1f - gainRelease) * targetGain
            }

            for (ch in 0 until channels) buffer[i + ch] *= gain
            i += channels
        }
    }
}

/**
 * 峰值限幅器：在压缩之后兜底。
 *
 * 没有做真正的 lookahead（那需要额外延迟一整个窗口，会让歌词和声音对不上），
 * 而是"瞬时压制 + 慢恢复 + tanh 软削波"三层，音乐素材上足够，且不会引入可感知的延迟。
 */
private class Limiter {
    private var ceiling = 0.96f
    private var release = 0f
    private var gain = 1f

    fun configure(sampleRateHz: Float, releaseMs: Float = 80f) {
        release = exp(-1.0 / (sampleRateHz * (releaseMs / 1000.0))).toFloat()
        gain = 1f
    }

    fun reset() {
        gain = 1f
    }

    fun process(buffer: FloatArray, frames: Int, channels: Int) {
        var i = 0
        for (frame in 0 until frames) {
            var peak = 0f
            for (ch in 0 until channels) peak = max(peak, abs(buffer[i + ch]))

            val target = if (peak * gain > ceiling) ceiling / max(peak, 1e-6f) else 1f
            gain = if (target < gain) target else release * gain + (1f - release)

            for (ch in 0 until channels) {
                val v = buffer[i + ch] * gain
                // tanh 近似：在 ±ceiling 内近似线性，越界后平滑贴合，避免硬削的"咔"
                buffer[i + ch] = if (v > ceiling) {
                    ceiling - ceiling * 0.12f * tanh((v - ceiling) / (ceiling * 0.12f))
                } else if (v < -ceiling) {
                    -ceiling + ceiling * 0.12f * tanh((-v - ceiling) / (ceiling * 0.12f))
                } else {
                    v
                }
            }
            i += channels
        }
    }
}
