package org.droref

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Reference simplifier settings. [detail]: working size in px (smaller = fewer details, blocky);
 *  [blur]: Gaussian blur, in half-percents of the image's long side, so it looks the same at any detail (0 = off);
 *  [flat]: Kuwahara radius, textures merge into flat masses (0 = off); [tones]: value bands (0 = off). */
data class Simp(val detail: Int, val flat: Int, val tones: Int, val blur: Int = 0) {
    override fun toString() = "$detail,$flat,$tones,$blur" // stored in settings and presets; older ones have no blur

    companion object {
        val DETAIL = 16..1024; val BLUR = 0..20; val FLAT = 0..8; val TONES = 0..16
        fun parse(s: String) = s.split(",").mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 3 || it.size == 4 }
            ?.let { v -> Simp(v[0].coerceIn(DETAIL), v[1].coerceIn(FLAT), if (v[2] < 2) 0 else v[2].coerceAtMost(TONES.last), v.getOrElse(3) { 0 }.coerceIn(BLUR)) }
    }
}

/** Starting presets, coarse to fine; the user's own replace them once saved. */
val DEFAULT_SIMPS = listOf(Simp(48, 2, 3), Simp(96, 3, 4), Simp(160, 3, 6), Simp(256, 2, 10))

/** Shrink → blur → Kuwahara → enlarge smoothly → value bands. Keeps hue and saturation. */
fun simplify(src: Bitmap, p: Simp): Bitmap {
    val k = min(1f, p.detail / max(src.width, src.height).toFloat())
    val w = max(1, (src.width * k).roundToInt()); val h = max(1, (src.height * k).roundToInt())
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
    if (small !== src) small.recycle()
    if (p.blur > 0) gaussian(px, w, h, max(1, (p.blur * max(w, h) / 200f).roundToInt()))
    val flat = if (p.flat > 0) kuwahara(px, w, h, p.flat) else px
    val tmp = Bitmap.createBitmap(flat, w, h, Bitmap.Config.ARGB_8888)
    val n = max(1, 1024 / max(w, h)) // ponytail: enlarged to ~1024 px so value bands get smooth edges; fixed, as the board's quality cap is per image anyway
    val up = if (n > 1) Bitmap.createScaledBitmap(tmp, w * n, h * n, true).also { tmp.recycle() } else tmp
    if (p.tones == 0) return up
    val out = IntArray(up.width * up.height).also { up.getPixels(it, 0, up.width, 0, 0, up.width, up.height) }
    posterizeValue(out, p.tones)
    return Bitmap.createBitmap(out, up.width, up.height, Bitmap.Config.ARGB_8888).also { up.recycle() }
}

/** Gaussian blur, in place: three box blurs in a row come within a few percent of it, at O(1) per pixel whatever [r]. */
fun gaussian(px: IntArray, w: Int, h: Int, r: Int) {
    val tmp = IntArray(px.size)
    repeat(3) { box(px, tmp, w, h, r, 1, w); box(tmp, px, h, w, r, w, 1) }
}

/** One box blur pass along rows ([n] lines of [len] pixels, [step] apart, lines [line] apart); edges repeat. */
private fun box(src: IntArray, dst: IntArray, len: Int, n: Int, r: Int, step: Int, line: Int) {
    val d = 2 * r + 1
    for (l in 0 until n) {
        val o = l * line
        fun at(i: Int) = src[o + i.coerceIn(0, len - 1) * step]
        var a = 0; var rr = 0; var g = 0; var b = 0
        for (i in -r..r) { val c = at(i); a += c ushr 24; rr += c shr 16 and 255; g += c shr 8 and 255; b += c and 255 }
        for (i in 0 until len) {
            dst[o + i * step] = (a / d shl 24) or (rr / d shl 16) or (g / d shl 8) or (b / d)
            val add = at(i + r + 1); val sub = at(i - r)
            a += (add ushr 24) - (sub ushr 24); rr += (add shr 16 and 255) - (sub shr 16 and 255)
            g += (add shr 8 and 255) - (sub shr 8 and 255); b += (add and 255) - (sub and 255)
        }
    }
}

/** Classic Kuwahara: each pixel takes the mean color of the least varied of its 4 quadrants. Summed-area tables: O(1) per pixel. */
fun kuwahara(px: IntArray, w: Int, h: Int, r: Int): IntArray {
    val W = w + 1
    val sr = DoubleArray(W * (h + 1)); val sg = DoubleArray(sr.size); val sb = DoubleArray(sr.size)
    val sy = DoubleArray(sr.size); val sy2 = DoubleArray(sr.size)
    for (y in 0 until h) for (x in 0 until w) {
        val c = px[y * w + x]
        val R = (c shr 16 and 255).toDouble(); val G = (c shr 8 and 255).toDouble(); val B = (c and 255).toDouble()
        val L = 0.299 * R + 0.587 * G + 0.114 * B
        val i = (y + 1) * W + x + 1; val a = i - 1; val u = i - W; val d = u - 1
        sr[i] = R + sr[a] + sr[u] - sr[d]; sg[i] = G + sg[a] + sg[u] - sg[d]; sb[i] = B + sb[a] + sb[u] - sb[d]
        sy[i] = L + sy[a] + sy[u] - sy[d]; sy2[i] = L * L + sy2[a] + sy2[u] - sy2[d]
    }
    fun sum(t: DoubleArray, x0: Int, y0: Int, x1: Int, y1: Int) = t[(y1 + 1) * W + x1 + 1] - t[y0 * W + x1 + 1] - t[(y1 + 1) * W + x0] + t[y0 * W + x0]
    val out = IntArray(px.size)
    for (y in 0 until h) for (x in 0 until w) {
        var best = Double.MAX_VALUE; var color = px[y * w + x]
        for (q in 0 until 4) {
            val x0 = if (q and 1 == 0) max(0, x - r) else x; val x1 = if (q and 1 == 0) x else min(w - 1, x + r)
            val y0 = if (q < 2) max(0, y - r) else y; val y1 = if (q < 2) y else min(h - 1, y + r)
            val n = ((x1 - x0 + 1) * (y1 - y0 + 1)).toDouble()
            val m = sum(sy, x0, y0, x1, y1) / n
            val v = sum(sy2, x0, y0, x1, y1) / n - m * m
            if (v < best) {
                best = v
                color = (px[y * w + x] and 0xFF000000.toInt()) or
                    ((sum(sr, x0, y0, x1, y1) / n).roundToInt() shl 16) or
                    ((sum(sg, x0, y0, x1, y1) / n).roundToInt() shl 8) or
                    (sum(sb, x0, y0, x1, y1) / n).roundToInt()
            }
        }
        out[y * w + x] = color
    }
    return out
}

/** Snaps each pixel's value (luminance) to [tones] bands and shifts r, g, b by the same amount, so the color stays. */
fun posterizeValue(px: IntArray, tones: Int) {
    val step = 255f / (tones - 1)
    for (i in px.indices) {
        val c = px[i]
        val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val d = ((y / step).roundToInt() * step - y).roundToInt()
        px[i] = (c and 0xFF000000.toInt()) or ((r + d).coerceIn(0, 255) shl 16) or ((g + d).coerceIn(0, 255) shl 8) or (b + d).coerceIn(0, 255)
    }
}
