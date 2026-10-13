package com.cheraz.map3dedit

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Guardado de proyecto (.m3d) y exportaciones (PNG 16 bits, RAW 16 bits, OBJ). */
object ProjectIO {

    private const val MAGIC = 0x4D334450 // "M3DP"
    private const val VERSION = 1

    // ---------- Proyecto (.m3d): alturas exactas en unidades de mundo ----------

    fun saveProject(out: OutputStream, t: Terrain) {
        val d = DataOutputStream(BufferedOutputStream(out))
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        d.writeInt(t.n)
        d.writeFloat(t.size)
        d.writeFloat(t.heightRange)
        for (v in t.heights) d.writeFloat(v)
        d.flush()
    }

    /** Devuelve false si el archivo no es válido o su resolución no coincide con la del terreno. */
    fun loadProject(input: InputStream, t: Terrain): Boolean {
        val d = DataInputStream(BufferedInputStream(input))
        if (d.readInt() != MAGIC) return false
        if (d.readInt() != VERSION) return false
        val n = d.readInt()
        if (n != t.n) return false
        d.readFloat() // size: informativo, el terreno usa el suyo
        val range = d.readFloat()
        val values = FloatArray(n * n)
        for (i in values.indices) values[i] = d.readFloat()
        t.setHeightsRaw(values, range)
        return true
    }

    // ---------- Exportaciones ----------

    class Quantized(val values: IntArray, val lo: Float, val hi: Float)

    /** Estira el heightmap a 0..65535 usando su min/max real. */
    fun quantize(t: Terrain): Quantized {
        val h = t.heights
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in h) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val span = if (hi - lo < 1e-6f) 1f else hi - lo
        val q = IntArray(h.size) { (((h[it] - lo) / span) * 65535f + 0.5f).toInt().coerceIn(0, 65535) }
        return Quantized(q, lo, hi)
    }

    /** RAW: uint16 little-endian, filas de arriba (z=0) hacia abajo, sin cabecera. */
    fun writeRaw16(out: OutputStream, t: Terrain): Quantized {
        val q = quantize(t)
        val bytes = ByteArray(q.values.size * 2)
        for (i in q.values.indices) {
            val v = q.values[i]
            bytes[i * 2] = v.toByte()
            bytes[i * 2 + 1] = (v shr 8).toByte()
        }
        out.write(bytes)
        out.flush()
        return q
    }

    /** PNG en escala de grises de 16 bits (Android no lo escribe, así que se arma a mano). */
    fun writePng16(out: OutputStream, t: Terrain): Quantized {
        val n = t.n
        val q = quantize(t)

        val raw = ByteArray(n * (1 + 2 * n))
        var p = 0
        for (z in 0 until n) {
            raw[p++] = 0 // filtro "None"
            for (x in 0 until n) {
                val v = q.values[z * n + x]
                raw[p++] = (v shr 8).toByte() // PNG es big-endian
                raw[p++] = v.toByte()
            }
        }
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(raw) }

        val ihdr = ByteBuffer.allocate(13)
            .putInt(n).putInt(n)
            .put(16).put(0).put(0).put(0).put(0) // 16 bits, gris, deflate, filtro 0, sin entrelazado
            .array()

        val d = DataOutputStream(out)
        d.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(d, "IHDR", ihdr)
        writeChunk(d, "IDAT", compressed.toByteArray())
        writeChunk(d, "IEND", ByteArray(0))
        d.flush()
        return q
    }

    private fun writeChunk(d: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        d.writeInt(data.size)
        d.write(typeBytes)
        d.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        d.writeInt(crc.value.toInt())
    }

    /** OBJ con vértices, normales y caras triangulares. Y arriba, unidades de mundo. */
    fun writeObj(out: OutputStream, t: Terrain) {
        val verts = t.buildVertices()
        val idx = t.buildIndices()
        val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 1 shl 16)
        w.write("# map3d-edit terreno ${t.n}x${t.n}, ${t.size}x${t.size} u\n")
        w.write("o terreno\n")
        val count = t.n * t.n
        for (i in 0 until count) {
            val o = i * 6
            w.write(String.format(Locale.US, "v %.4f %.4f %.4f\n", verts[o], verts[o + 1], verts[o + 2]))
        }
        for (i in 0 until count) {
            val o = i * 6
            w.write(String.format(Locale.US, "vn %.4f %.4f %.4f\n", verts[o + 3], verts[o + 4], verts[o + 5]))
        }
        var k = 0
        while (k < idx.size) {
            val a = (idx[k].toInt() and 0xFFFF) + 1 // OBJ cuenta desde 1
            val b = (idx[k + 1].toInt() and 0xFFFF) + 1
            val c = (idx[k + 2].toInt() and 0xFFFF) + 1
            w.write("f $a//$a $b//$b $c//$c\n")
            k += 3
        }
        w.flush()
    }
}
