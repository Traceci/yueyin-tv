package com.yueyin.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把封面一次性加工成可铺满播放页的模糊底图。
 *
 * 酷狗的做法是「生成高斯模糊图 → 顶部/底部翻转 → 拉伸 → 边缘渐变 → 与遮罩揉合」；
 * 这里保留同样的分区与羽化观感，但把整条流水线压缩成切歌时的一次 CPU 计算：
 * 先按 1/4 尺寸降采样（位图上限半屏），再做 3 次盒式模糊，最后镜像与羽化。
 * 输出是一张静态 Bitmap，播放期间 Canvas 不再创建 Brush、也不调用 Modifier.blur。
 */
object ArtworkBackdropGenerator {
    private const val DOWNSAMPLE = 4

    fun generate(source: Bitmap, maxWidthPx: Int, maxHeightPx: Int): Bitmap {
        val width = (maxWidthPx / DOWNSAMPLE).coerceIn(64, 512)
        val height = (maxHeightPx / DOWNSAMPLE).coerceIn(64, 512)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // 中心裁切铺满（等价于 ContentScale.Crop），避免封面被拉伸变形。
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        canvas.drawBitmap(source, null, RectF(left, top, left + drawWidth, top + drawHeight), paint)

        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)
        BackdropBlur.blur(pixels, width, height, radius = (minOf(width, height) / 18).coerceAtLeast(2))
        BackdropBlur.mirrorLowerHalf(pixels, width, height)
        BackdropBlur.applyEdgeFade(pixels, width, height)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
}

/**
 * 生成结果的内存缓存。
 *
 * 键由 Bitmap 身份 + 尺寸桶组成：切歌换对象会重新生成，进度/控制条重组命中缓存，
 * 不同屏幕尺寸各自留一份。上限 8MB（约 40 张 256×144 的 ARGB_8888 图），
 * 低内存时 LruCache 自行淘汰，也可由 Application 的 onTrimMemory 调用 [clear]。
 */
object ArtworkBackdropCache {
    private const val MAX_BYTES = 8 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    fun key(bitmap: Bitmap, widthPx: Int, heightPx: Int): String =
        "${bitmap.hashCode()}_${widthPx / 64}_${heightPx / 64}"

    @Synchronized
    fun get(key: String): Bitmap? = cache.get(key)?.takeUnless { it.isRecycled }

    @Synchronized
    fun put(key: String, bitmap: Bitmap): Bitmap {
        cache.put(key, bitmap)
        return bitmap
    }

    @Synchronized
    fun clear() = cache.evictAll()
}

/**
 * 播放页取模糊底图的统一入口：命中缓存直接返回，否则在 Dispatchers.Default 上生成。
 *
 * 只有 bitmap / 尺寸 / enabled 变化时才重算，播放进度驱动的高频重组不会触发生成。
 * 流畅档传 enabled=false，直接走纯色底，连这一次生成都省掉。
 */
@Composable
fun rememberArtworkBackdrop(
    bitmap: Bitmap?,
    maxWidthPx: Int,
    maxHeightPx: Int,
    enabled: Boolean = true,
): Bitmap? {
    var backdrop by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(bitmap, maxWidthPx, maxHeightPx, enabled) {
        if (!enabled || bitmap == null || bitmap.isRecycled || maxWidthPx <= 0 || maxHeightPx <= 0) {
            backdrop = null
            return@LaunchedEffect
        }
        val key = ArtworkBackdropCache.key(bitmap, maxWidthPx, maxHeightPx)
        backdrop = ArtworkBackdropCache.get(key) ?: withContext(Dispatchers.Default) {
            runCatching { ArtworkBackdropGenerator.generate(bitmap, maxWidthPx, maxHeightPx) }
                .getOrNull()
                ?.let { ArtworkBackdropCache.put(key, it) }
        }
    }
    return backdrop
}
