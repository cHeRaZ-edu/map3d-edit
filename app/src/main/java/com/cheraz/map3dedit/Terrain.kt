package com.cheraz.map3dedit

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Terreno como grid regular de (n x n) vértices en el plano XZ, centrado en el origen.
 * heights[z * n + x] guarda la altura (Y). Es la fuente de verdad: el heightmap.
 */
class Terrain(val n: Int = 128, val size: Float = 20f) {

    val heights = FloatArray(n * n)
    val step = size / (n - 1)

    init {
        generateDemo()
    }

    private fun generateDemo() {
        for (z in 0 until n) {
            for (x in 0 until n) {
                val wx = x * step - size / 2f
                val wz = z * step - size / 2f
                heights[z * n + x] =
                    1.6f * sin(wx * 0.35f) * cos(wz * 0.30f) +
                    0.6f * sin(wx * 0.9f + wz * 0.7f) +
                    0.2f * sin(wx * 2.3f) * sin(wz * 2.1f)
            }
        }
    }

    fun heightAt(x: Int, z: Int): Float =
        heights[z.coerceIn(0, n - 1) * n + x.coerceIn(0, n - 1)]

    /** Vértices intercalados: x, y, z, nx, ny, nz */
    fun buildVertices(): FloatArray {
        val out = FloatArray(n * n * 6)
        var i = 0
        for (z in 0 until n) {
            for (x in 0 until n) {
                val h = heightAt(x, z)
                // Normal por diferencias centrales
                val dx = heightAt(x + 1, z) - heightAt(x - 1, z)
                val dz = heightAt(x, z + 1) - heightAt(x, z - 1)
                var nx = -dx
                var ny = 2f * step
                var nz = -dz
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                nx /= len; ny /= len; nz /= len

                out[i++] = x * step - size / 2f
                out[i++] = h
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
