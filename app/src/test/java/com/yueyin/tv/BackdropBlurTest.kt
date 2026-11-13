package com.yueyin.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropBlurTest {
    private fun opaque(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(pixel: Int): Int = (pixel ushr 16) and 0xFF

    @Test
    fun `zero radius keeps every pixel`() {
        val pixels = IntArray(16) { opaque(it * 13 % 256, 40, 80) }
        val copy = pixels.copyOf()
        BackdropBlur.blur(pixels, 4, 4, 0)
        assertEquals(copy.toList(), pixels.toList())
    }

    @Test
    fun `uniform color is unchanged by blur`() {
        val pixels = IntArray(9 * 9) { opaque(120, 60, 30) }
        BackdropBlur.blur(pixels, 9, 9, 2)
        assertTrue(pixels.all { it == opaque(120, 60, 30) })
    }

    @Test
    fun `a single bright pixel spreads to its neighbours`() {
        val size = 9
        val center = 4
        val pixels = IntArray(size * size) { opaque(0, 0, 0) }
        pixels[center * size + center] = opaque(255, 255, 255)
        BackdropBlur.blur(pixels, size, size, 2)
        assertTrue(red(pixels[center * size + center]) < 255)
        assertTrue(red(pixels[center * size + center + 1]) > 0)
        assertTrue(red(pixels[(center + 1) * size + center]) > 0)
        assertTrue(red(pixels[center * size + center + 2]) > 0)
    }

    @Test
    fun `lower half mirrors the upper half`() {
        val width = 4
        val height = 6
        val pixels = IntArray(width * height) { index -> opaque(index / width, 0, 0) }
        BackdropBlur.mirrorLowerHalf(pixels, width, height)
        for (y in height / 2 until height) {
            val sourceY = (height - 1 - y).coerceIn(0, height / 2 - 1)
            for (x in 0 until width) {
                assertEquals(pixels[sourceY * width + x], pixels[y * width + x])
            }
        }
    }

    @Test
    fun `edge fade darkens top and bottom but keeps the middle`() {
        val width = 2
        val height = 20
        val pixels = IntArray(width * height) { opaque(200, 100, 50) }
        val middleBefore = red(pixels[height / 2 * width])
        BackdropBlur.applyEdgeFade(pixels, width, height)
        assertTrue(red(pixels[0]) < 200)
        assertTrue(red(pixels[(height - 1) * width]) < 200)
        assertEquals(middleBefore, red(pixels[height / 2 * width]))
    }

    @Test
    fun `invalid dimensions do not crash`() {
        val pixels = IntArray(4) { opaque(10, 20, 30) }
        val copy = pixels.copyOf()
        BackdropBlur.blur(pixels, 0, 0, 3)
        BackdropBlur.blur(pixels, 2, 2, 99)
        assertEquals(4, pixels.size)
        assertEquals(copy.toList(), pixels.toList())
    }
}
