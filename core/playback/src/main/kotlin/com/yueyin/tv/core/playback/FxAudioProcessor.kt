package com.yueyin.tv.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.yueyin.tv.core.audiofx.FxChain
import com.yueyin.tv.core.audiofx.FxSettings
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 把 [FxChain] 接到 Media3 的音频管线上。
 *
 * 这里是整个音效方案的关键：**不碰解码器、不碰 AudioTrack、不申请任何特殊权限**，
 * 只是在「解码出的 PCM」和「送进扬声器」之间插一段处理。因此它天然对本地播放、
 * 在线播放、不同封装格式（flac/mp3/aac）一视同仁。
 *
 * 两条硬性约束决定了下面这些写法：
 * 1. `queueInput` 跑在播放线程上，**不能分配**。所有缓冲在 `onConfigure` 里一次备好。
 * 2. 输出必须沿用与输入相同的编码。设备不支持 float 输出时（很多盒子确实不支持），
 *    若强行改成 float，整条链会静默失效——听起来就是"开关点了没反应"。
 */
@OptIn(UnstableApi::class)
class FxAudioProcessor(
    initialSettings: FxSettings = FxSettings.DEFAULT,
) : BaseAudioProcessor() {

    private val chain = FxChain(maxChannels = MAX_CHANNELS)

    @Volatile
    private var settings: FxSettings = initialSettings

    private var inputEncoding: Int = C.ENCODING_INVALID
    private var bytesPerSample: Int = 2
    private var channelCount: Int = 2
    private var floatScratch: FloatArray = FloatArray(0)
    private var byteScratch: ByteArray = ByteArray(0)

    /** 当前音频格式不接管（多声道 / 非 PCM）：configure 后原样透传。 */
    @Volatile
    private var bypass = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val sampleRate = inputAudioFormat.sampleRate
        val inputChannels = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding

        // 音乐场景只有单声道和立体声；多声道（5.1 的 MV）与非 PCM 格式不接管。
        //
        // 注意这里必须原样返回入参格式，不能返回 NOT_SET：处理器现在是恒 active 的
        // （见 isActive 的注释），返回 NOT_SET 会让 Media3 认为整条链拿不到输出格式，
        // 直接抛 UnhandledAudioFormatException —— 表现为这类音频完全放不出来。
        if (inputChannels !in 1..MAX_CHANNELS ||
            (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT)
        ) {
            bypass = true
            return inputAudioFormat
        }

        bypass = false
        this.inputEncoding = encoding
        this.bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        this.channelCount = inputChannels
        chain.configure(sampleRate, inputChannels)
        chain.update(settings)
        return AudioProcessor.AudioFormat(sampleRate, inputChannels, encoding)
    }

    /**
     * 这个处理器**永远**留在音频链里。
     *
     * 原先写成 `settings.enabled`：关着音效时 isActive=false，处理器不参与 configure，
     * 等用户选了个预设再变 true。看起来省事，实际是致命的——
     * Media3 的 AudioProcessingPipeline 只在 `setOutputFormat` 阶段对「当时 isActive 的处理器」
     * 调一次 `onConfigure`，之后的 `flush()` 只做清理，**不会补 configure**。
     * 于是这个处理器从开机到关机都没被 configure 过：sampleRate/声道数停在缺省值，
     * `chain.configure()` 没跑过、EQ 系数一条都没算，所有滤波器都是 bypass。
     * 听感就是「音效选了，声音一点没变」。
     *
     * 现在恒 true：链上位置固定，configure 必然发生，开关只决定 queueInput 里
     * 是处理还是原样透传。代价是关闭时多一次内存拷贝，可以忽略。
     */
    override fun isActive(): Boolean = true

    /** 不足一帧或音效关闭时：原样搬一次，不做任何乘加。 */
    private fun passthrough(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) {
            replaceOutputBuffer(0).flip()
            return
        }
        // 仍走中间数组：replaceOutputBuffer 可能复用同一个底层 buffer，
        // 直接 `output.put(inputBuffer)` 会抛 "The source buffer is this buffer"。
        if (byteScratch.size < remaining) byteScratch = ByteArray(remaining)
        inputBuffer.get(byteScratch, 0, remaining)
        val output = replaceOutputBuffer(remaining)
        output.put(byteScratch, 0, remaining)
        output.flip()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (bypass || !settings.enabled) {
            passthrough(inputBuffer)
            return
        }
        val bytesPerFrame = bytesPerSample * channelCount
        val remaining = inputBuffer.remaining()
        val frameCount = remaining / bytesPerFrame
        if (frameCount <= 0) {
            passthrough(inputBuffer)
            return
        }

        val sampleCount = frameCount * channelCount
        if (floatScratch.size < sampleCount) floatScratch = FloatArray(sampleCount)
        if (byteScratch.size < remaining) byteScratch = ByteArray(remaining)

        inputBuffer.get(byteScratch, 0, remaining)
        decodeToFloat(byteScratch, floatScratch, sampleCount)
        chain.process(floatScratch, frameCount)
        encodeFromFloat(floatScratch, byteScratch, sampleCount)

        val output = replaceOutputBuffer(remaining)
        output.put(byteScratch, 0, remaining)
        output.flip()
    }

    private fun decodeToFloat(source: ByteArray, target: FloatArray, sampleCount: Int) {
        if (inputEncoding == C.ENCODING_PCM_FLOAT) {
            var b = 0
            for (i in 0 until sampleCount) {
                val bits = (source[b].toInt() and 0xFF) or
                    ((source[b + 1].toInt() and 0xFF) shl 8) or
                    ((source[b + 2].toInt() and 0xFF) shl 16) or
                    ((source[b + 3].toInt() and 0xFF) shl 24)
                target[i] = Float.fromBits(bits)
                b += 4
            }
        } else {
            var b = 0
            for (i in 0 until sampleCount) {
                val lo = source[b].toInt() and 0xFF
                val hi = source[b + 1].toInt()
                target[i] = ((hi shl 8) or lo).toShort() / 32_768f
                b += 2
            }
        }
    }

    private fun encodeFromFloat(source: FloatArray, target: ByteArray, sampleCount: Int) {
        if (inputEncoding == C.ENCODING_PCM_FLOAT) {
            var b = 0
            for (i in 0 until sampleCount) {
                val bits = source[i].toBits()
                target[b] = bits.toByte()
                target[b + 1] = (bits shr 8).toByte()
                target[b + 2] = (bits shr 16).toByte()
                target[b + 3] = (bits shr 24).toByte()
                b += 4
            }
        } else {
            var b = 0
            for (i in 0 until sampleCount) {
                val scaled = (source[i] * 32_767f).roundToInt().coerceIn(-32_768, 32_767)
                target[b] = scaled.toByte()
                target[b + 1] = (scaled shr 8).toByte()
                b += 2
            }
        }
    }

    override fun onFlush() {
        chain.reset()
        floatScratch = FloatArray(0)
        byteScratch = ByteArray(0)
    }

    override fun onReset() {
        chain.reset()
        floatScratch = FloatArray(0)
        byteScratch = ByteArray(0)
    }

    fun updateSettings(newSettings: FxSettings) {
        // 参数直接在 chain 上生效：处理器恒在链上（见 isActive），不需要 seek 触发 flush
        // 来「换入/换出」。顺带把「切音效时声音顿一下」也去掉了。
        settings = newSettings.normalized()
        chain.update(settings)
    }

    private companion object {
        const val MAX_CHANNELS = 2
    }
}

/**
 * 全局音效开关。
 *
 * 本地播放（[PlaybackService]）和在线播放页各有一个 ExoPlayer，也就各有一个处理实例。
 * 参数改动必须广播给**所有活着的实例**，否则会出现"在设置页调了 EQ、播放页没变"的经典问题。
 */
object AudioFxController {

    private val active = CopyOnWriteArrayList<FxAudioProcessor>()

    @Volatile
    var settings: FxSettings = FxSettings.DEFAULT
        private set

    /**
     * 供界面订阅的音效状态。
     *
     * 播放页的音效选择器和设置页的预设列表都要跟着它走：任何一处改了，
     * 另一处必须立刻同步（否则会出现「播放页选了低音，进设置还显示平直」）。
     */
    val state = MutableStateFlow(FxSettings.DEFAULT)

    /** 由 App 层在启动时注入的持久化写入器（core:playback 不依赖 Android Context）。 */
    @Volatile
    private var persist: ((FxSettings) -> Unit)? = null

    fun bindPersistence(writer: (FxSettings) -> Unit) {
        persist = writer
    }

    /** 从持久化恢复：只更新内存状态，不回写，避免启动时产生一次无意义的写盘。 */
    fun restore(newSettings: FxSettings) {
        settings = newSettings.normalized()
        state.value = settings
        // 播放服务可能先于 Activity 起来、已经建好了处理器实例。只改这里的 settings
        // 不会同步过去，结果是「界面显示上次选的音效，实际链路还是关闭状态」，
        // 非得用户再手动选一次才对——所以这里要一并广播。
        active.forEach { it.updateSettings(settings) }
    }

    fun createProcessor(): FxAudioProcessor = FxAudioProcessor(settings).also { active += it }

    fun release(processor: FxAudioProcessor) {
        active -= processor
    }

    fun update(newSettings: FxSettings) {
        settings = newSettings.normalized()
        state.value = settings
        active.forEach { it.updateSettings(settings) }
        persist?.invoke(settings)
    }
}
