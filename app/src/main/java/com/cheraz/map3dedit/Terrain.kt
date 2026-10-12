package com.cheraz.map3dedit

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Terreno como grid regular de (n x n) vértices en el plano XZ, centrado en el origen.
 * heights[z * n + x] guarda la altura (Y). Es la fuente de verdad: el heightmap.
 *
 * Todas las fuentes (ruido, PNG) producen valores 0..1, se estiran a su min/max
 * y se escalan a [-heightRange/2, +heightRange/2].
 */
class Terrain(val n: Int = 128, val size: Float = 20f) {

    @Volatile var heights = FloatArray(n * n)
        private set

    val step = size / (n - 1)

    /** Rango total de altura en unidades de mundo. */
    var heightRange = 6f

    var minH = 0f
        private set
    var maxH = 1f
        private set

    init {
        generateNoise(seed = 1)
    }

    // ---------- Fuentes de heightmap ----------

    fun generateNoise(seed: Int, baseFreq: Float = 3f, octaves: Int = 6) {
        val out = FloatArray(n * n)
        for (z in 0 until n) {
            for (x in 0 until n) {
                val u = x / (n - 1f)
                val v = z / (n - 1f)
                var amp = 1f
                var freq = baseFreq
                var sum = 0f
                var total = 0f
                for (o in 0 until octaves) {
                    sum += amp * valueNoise(u * freq, v * freq, seed + o * 101)
                    total += amp
                    amp *= 0.5f
                    freq *= 2f
                }
                out[z * n + x] = sum / total
            }
        }
        applyNormalized(out)
    }

    /** Luminancia del bitmap (reescalado a n x n) como altura. */
    fun loadFromBitmap(src: Bitmap) {
        val bmp = Bitmap.createScaledBitmap(src, n, n, true)
        val px = IntArray(n * n)
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        val out = FloatArray(n * n)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        applyNormalized(out)
    }

    private fun applyNormalized(values: FloatArray) {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in values) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val span = if (hi - lo < 1e-6f) 1f else hi - lo
        for (i in values.indices) {
            values[i] = ((values[i] - lo) / span - 0.5f) * heightRange
        }
        minH = -heightRange / 2f
        maxH = heightRange / 2f
        heights = values
    }

    // ---------- Ruido de valor ----------

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7fffffff) / 2147483647f
    }

    private fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val fx = x - xi
        val fy = y - yi
        val u = fx * fx * (3f - 2f * fx)
        val v = fy * fy * (3f - 2f * fy)
        val a = hash(xi, yi, seed)
        val b = hash(xi + 1, yi, seed)
        val c = hash(xi, yi + 1, seed)
        val d = hash(xi + 1, yi + 1, seed)
        val top = a + (b - a) * u
        val bottom = c + (d - c) * u
        return top + (bottom - top) * v
    }

    // ---------- Consulta de altura ----------

    /** Altura en coordenadas de mundo, interpolación bilineal. */
    fun heightAtWorld(x: Float, z: Float): Float {
        val h = heights
        val fx = ((x + size / 2f) / step).coerceIn(0f, n - 1.001f)
        val fz = ((z + size / 2f) / step).coerceIn(0f, n - 1.001f)
        val x0 = fx.toInt()
        val z0 = fz.toInt()
        val tx = fx - x0
        val tz = fz - z0
        val a = h[z0 * n + x0]
        val b = h[z0 * n + x0 + 1]
        val c = h[(z0 + 1) * n + x0]
        val d = h[(z0 + 1) * n + x0 + 1]
        val top = a + (b - a) * tx
        val bottom = c + (d - c) * tx
        return top + (bottom - top) * tz
    }

    // ---------- Malla ----------

    private fun heightAt(h: FloatArray, x: Int, z: Int): Float =
        h[z.coerceIn(0, n - 1) * n + x.coerceIn(0, n - 1)]

    /** Vértices intercalados: x, y, z, nx, ny, nz */
    fun buildVertices(): FloatArray {
        val h = heights
        val out = FloatArray(n * n * 6)
        var i = 0
        for (z in 0 until n) {
            for (x in 0 until n) {
                val dx = heightAt(h, x + 1, z) - heightAt(h, x - 1, z)
                val dz = heightAt(h, x, z + 1) - heightAt(h, x, z - 1)
                var nx = -dx
                var ny = 2f * step
                var nz = -dz
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                nx /= len; ny /= len; nz /= len

                out[i++] = x * step - size / 2f
                out[i++] = h[z * n + x]
                out[i++] = z * step - size / 2f
                out[i++] = nx
                out[i++] = ny
                out[i++] = nz
            }
        }
        return out
    }

    fun buildIndices(): ShortArray {
        val out = ShortArray((n - 1) * (n - 1) * 6)
        var i = 0
        for (z in 0 until n - 1) {
            for (x in 0 until n - 1) {
                val a = (z * n + x).toShort()
                val b = (z * n + x + 1).toShort()
                val c = ((z + 1) * n + x).toShort()
                val d = ((z + 1) * n + x + 1).toShort()
                out[i++] = a; out[i++] = c; out[i++] = b
                out[i++] = b; out[i++] = c; out[i++] = d
            }
        }
        return out
    }
}
