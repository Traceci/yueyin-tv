package com.yueyin.tv

/**
 * 预生成模糊背景的像素算法（纯 Kotlin / 纯 JVM，不依赖 android.graphics）。
 *
 * 借鉴酷狗「切歌时把封面预生成一张模糊底图」的流水线：原图降采样 → 模糊 →
 * 上下分区翻转 → 边缘渐变。整条链路只在切歌时跑一次，播放页每帧只画一张静态位图，
 * 因此既绕开 API 30 及以下没有 RenderEffect 的限制，也不再有每帧实时模糊的 GPU 开销。
 *
 * 用 3 次滑动窗口盒式模糊近似高斯：O(w*h)，常数极小，且不依赖 RenderScript / Renderscript
 * 兼容库，minSdk 23 也能跑。
 */
object BackdropBlur {
    /** 近似高斯模糊，原地修改 [pixels]（ARGB_8888，行主序）。 */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0 || width <= 0 || height <= 0 || pixels.size < width * height) return
        val r = radius.coerceAtMost((minOf(width, height) / 2).coerceAtLeast(1))
        repeat(3) { boxBlur(pixels, width, height, r) }
    }

    private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val temp = IntArray(pixels.size)
        val window = (2 * radius + 1).toLong()

        // 水平方向：每行用滑动窗口求和，左右越界处夹取边缘像素。
        for (y in 0 until height) {
            val row = y * width
            var a = 0L
            var r = 0L
            var g = 0L
            var b = 0L
            for (x in -radius..radius) {
                val p = pixels[row + x.coerceIn(0, width - 1)]
                a += (p ushr 24) and 0xFF
                r += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (x in 0 until width) {
                temp[row + x] = pack(a / window, r / window, g / window, b / window)
                val leaving = pixels[row + (x - radius).coerceIn(0, width - 1)]
                val entering = pixels[row + (x + radius + 1).coerceIn(0, width - 1)]
                a += channel(entering, 24) - channel(leaving, 24)
                r += channel(entering, 16) - channel(leaving, 16)
                g += channel(entering, 8) - channel(leaving, 8)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }

        // 垂直方向：同上，结果写回 pixels。
        for (x in 0 until width) {
            var a = 0L
            var r = 0L
            var g = 0L
            var b = 0L
            for (y in -radius..radius) {
                val p = temp[y.coerceIn(0, height - 1) * width + x]
                a += (p ushr 24) and 0xFF
                r += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (y in 0 until height) {
                pixels[y * width + x] = pack(a / window, r / window, g / window, b / window)
                val leaving = temp[(y - radius).coerceIn(0, height - 1) * width + x]
                val entering = temp[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                a += channel(entering, 24) - channel(leaving, 24)
                r += channel(entering, 16) - channel(leaving, 16)
                g += channel(entering, 8) - channel(leaving, 8)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    /** 下半部分镜像上半部分，形成上下呼应的柔光底（酷狗的「翻转拉伸」思路）。 */
    fun mirrorLowerHalf(pixels: IntArray, width: Int, height: Int) {
        val half = height / 2
        for (y in half until height) {
            val sourceY = (height - 1 - y).coerceIn(0, half - 1)
            System.arraycopy(pixels, sourceY * width, pixels, y * width, width)
        }
    }

    /** 上下 12% 做亮度羽化，避免模糊底图与页面压暗层叠加后出现硬边。 */
    fun applyEdgeFade(pixels: IntArray, width: Int, height: Int) {
        val fade = (height * 0.12f).coerceAtLeast(1f)
        for (y in 0 until height) {
            val distance = minOf(y, height - 1 - y).toFloat() / fade
            val factor = 0.55f + 0.45f * distance.coerceIn(0f, 1f)
            for (x in 0 until width) {
                val index = y * width + x
                val p = pixels[index]
                pixels[index] = pack(
                    a = (p ushr 24) and 0xFF,
                    r = (((p ushr 16) and 0xFF) * factor).toInt(),
                    g = (((p ushr 8) and 0xFF) * factor).toInt(),
                    b = ((p and 0xFF) * factor).toInt(),
                )
            }
        }
    }

    private fun channel(pixel: Int, shift: Int): Long = ((pixel ushr shift) and 0xFF).toLong()

    private fun pack(a: Long, r: Long, g: Long, b: Long): Int = (
        ((a.coerceIn(0, 255) shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255))
        ).toInt()

    private fun pack(a: Int, r: Int, g: Int, b: Int): Int = (
        ((a shl 24) or (r shl 16) or (g shl 8) or b)
        )
}
