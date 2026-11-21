package com.yueyin.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.LruCache
import com.yueyin.tv.core.model.CoverVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class ArtworkBitmapCache(
    private val scope: CoroutineScope,
    private val loader: suspend (coverId: String, variant: CoverVariant) -> Bitmap?,
    maxBytes: Int = DEFAULT_MAX_BYTES,
    maxConcurrentLoads: Int = DEFAULT_MAX_CONCURRENT_LOADS,
) {
    private data class Key(val coverId: String, val variant: CoverVariant)

    private val lock = Any()
    private val loadPermits = Semaphore(maxConcurrentLoads)
    private val memory = object : LruCache<Key, Bitmap>(maxBytes) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }
    private val inFlight = mutableMapOf<Key, Deferred<Bitmap?>>()
    private var generation = 0L

    fun peek(coverId: String, variant: CoverVariant): Bitmap? = synchronized(lock) {
        memory.get(Key(coverId, variant))
    }

    suspend fun get(coverId: String, variant: CoverVariant): Bitmap? {
        val key = Key(coverId, variant)
        val request = synchronized(lock) {
            memory.get(key)?.let { return it }
            inFlight[key] ?: createRequestLocked(key)
        }
        return request.await()
    }

    suspend fun getProgressively(
        coverId: String,
        variant: CoverVariant,
        fallbackVariant: CoverVariant?,
        onIntermediate: (Bitmap) -> Unit,
    ): Bitmap? {
        peek(coverId, variant)?.let { return it }
        val distinctFallback = fallbackVariant?.takeIf { it != variant }
        val fallback = distinctFallback?.let { get(coverId, it) }
        peek(coverId, variant)?.let { return it }
        fallback?.let(onIntermediate)
        return get(coverId, variant) ?: fallback
    }

    fun prefetch(coverId: String, variant: CoverVariant): Job? {
        val key = Key(coverId, variant)
        return synchronized(lock) {
            if (memory.get(key) != null) return null
            inFlight[key] ?: createRequestLocked(key)
        }
    }

    fun clear() {
        val pending = synchronized(lock) {
            generation += 1
            memory.evictAll()
            inFlight.values.toList().also { inFlight.clear() }
        }
        pending.forEach { it.cancel() }
    }

    private fun createRequestLocked(key: Key): Deferred<Bitmap?> {
        val requestGeneration = generation
        val request = scope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            loadPermits.withPermit { loader(key.coverId, key.variant) }?.also { bitmap ->
                synchronized(lock) {
                    if (generation == requestGeneration) memory.put(key, bitmap)
                }
            }
        }
        inFlight[key] = request
        request.invokeOnCompletion {
            synchronized(lock) {
                if (inFlight[key] === request) inFlight.remove(key)
            }
        }
        request.start()
        return request
    }

    private companion object {
        // 车机/高分屏会按显示像素取更大的封面档，解码结果更大，缓存上限相应放宽。
        const val DEFAULT_MAX_BYTES = 64 * 1024 * 1024
        const val DEFAULT_MAX_CONCURRENT_LOADS = 3
    }
}

internal fun decodeArtwork(bytes: ByteArray, targetLongEdge: Int): Bitmap? {
    if (bytes.size > 20 * 1024 * 1024) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8_192 || bounds.outHeight > 8_192) return null
    if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > 16_000_000L) return null
    // 采样后必须「不小于」目标长边。旧写法用 `> target` 判断，会在还剩一点时就再翻一倍：
    // 例如 1200px 源图配 510px 目标会降到 300px，再放大回 510px 就是糊的。
    // 改成「下一档仍能保持 >= 目标才继续降」，保证解码结果刚好覆盖显示尺寸。
    // 原始像素 >16M 已在上面直接拒绝，降采样只会更小，无需再按像素上限反复翻倍。
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (sample < 64 && longEdge / (sample * 2) >= targetLongEdge) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

/**
 * 榜单宣传图提亮：亮度 +18%、对比 +7%、饱和 +16%。
 *
 * 热歌榜 / 飙升榜这类封面是音源自己做的深色海报（原图平均亮度约 50/255，下缘压到近黑），
 * 原样铺进列表像蒙了一层灰。这里做一次显示用校正，参数是拿音源原图逐档比出来的：
 * 再低（+10%）看不出变化，走 gamma 提暗部会把整张图提成灰底。
 *
 * 只认榜单物料（酷我把榜单图放在 `zimg.kuwo.cn/bang/` 下），专辑封面原样返回。
 * 处理结果直接进内存缓存，滚动时不再重复计算；手机与电视共用这一层，两端观感一致。
 */
internal fun boostRankBannerArtwork(coverId: String, bitmap: Bitmap): Bitmap {
    if (!coverId.contains("/bang/", ignoreCase = true)) return bitmap
    val contrast = 1.07f
    // 平移量按 0..255 计：先按对比度绕 128 展开，再整体加 18% 亮度。
    val offset = (0.5f + 0.18f - contrast * 0.5f) * 255f
    val matrix = ColorMatrix().apply { setSaturation(1.16f) }
    matrix.postConcat(
        ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    // 处理失败就退回原图：宁可暗一点，也不能因为一张封面让列表空着。
    return runCatching {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            bitmap,
            0f,
            0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) },
        )
        output
    }.getOrDefault(bitmap)
}
