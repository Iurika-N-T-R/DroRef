package org.droref

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One filter of the simplifier: [k] is 'd' detail (working size in px, always first), 'b' blur, 'f' flat shapes, 'v' values.
 *  An off filter keeps its value, so turning it back on brings it back as it was. */
data class Step(val k: Char, val v: Int, val on: Boolean = true)

/** Reference simplifier settings: detail, then the other filters in the order they run. */
data class Simp(val steps: List<Step>) {
    val detail get() = steps.first { it.k == 'd' }
    override fun toString() = steps.joinToString(" ") { "${if (it.on) "" else "-"}${it.k}${it.v}" } // "d96 b2 -f3 v4"
    fun with(s: Step) = Simp(steps.map { if (it.k == s.k) s else it })

    /** Runs filter [k] one place earlier; detail stays first. */
    fun up(k: Char): Simp {
        val i = steps.indexOfFirst { it.k == k }
        return if (i <= 1) this else Simp(steps.toMutableList().apply { add(i - 1, removeAt(i)) })
    }

    private fun clamped() = Simp(steps.map { it.copy(v = it.v.coerceIn(RANGE.getValue(it.k))) })

    companion object {
        /** Blur and flat shapes are in 1/1000 and 1/200 of the image's long side, so they look the same at any detail. */
        val RANGE = mapOf('d' to 16..1024, 'b' to 1..50, 'f' to 1..8, 'v' to 2..16)

        /** 0 turns a filter off. */
        fun of(detail: Int, blur: Int, flat: Int, tones: Int) = Simp(listOf(Step('d', detail),
            Step('b', max(blur, 1), blur > 0), Step('f', max(flat, 1), flat > 0), Step('v', max(tones, 2), tones >= 2))).clamped()

        fun parse(s: String): Simp? {
            if (',' in s) { // 1.4.0 – 1.4.2: "detail,flat,tones[,blur]", blur then in 1/200
                val v = s.split(",").mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 3 || it.size == 4 } ?: return null
                return of(v[0], v.getOrElse(3) { 0 } * 5, v[1], v[2])
            }
            val st = s.trim().split(" ").mapNotNull { t ->
                val b = t.removePrefix("-")
                b.firstOrNull()?.takeIf { it in RANGE }?.let { k -> b.drop(1).toIntOrNull()?.let { Step(k, it, !t.startsWith("-")) } }
            }
            if (st.map { it.k }.sorted() != RANGE.keys.sorted()) return null
            return Simp(st.filter { it.k == 'd' } + st.filter { it.k != 'd' }).clamped()
        }
    }
}

/** Starting presets, coarse to fine; the user's own replace them once saved. */
val DEFAULT_SIMPS = listOf(Simp.of(48, 0, 4, 3), Simp.of(96, 0, 3, 4), Simp.of(160, 0, 2, 6), Simp.of(256, 0, 1, 10))

/** Shrink to [Simp.detail], enlarge smoothly to a working size of at least 512 px (so blur steps and band edges stay fine),
 *  then run the filters that are on, in their order. Keeps hue and saturation. */
fun simplify(src: Bitmap, p: Simp): Bitmap {
    val side = max(src.width, src.height)
    val s = if (p.detail.on) min(p.detail.v, side) else min(side, 1024) // ponytail: 1024 px cap per image, the Kuwahara tables grow with it
    val ws = if (p.detail.on) max(s, min(512, side)) else s
    val w = max(1, (src.width * s / side.toFloat()).roundToInt()); val h = max(1, (src.height * s / side.toFloat()).roundToInt())
    val W = max(1, (src.width * ws / side.toFloat()).roundToInt()); val H = max(1, (src.height * ws / side.toFloat()).roundToInt())
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val work = if (W != w || H != h) Bitmap.createScaledBitmap(small, W, H, true).also { if (small !== src) small.recycle() } else small
    var px = IntArray(W * H).also { work.getPixels(it, 0, W, 0, 0, W, H) }
    if (work !== src) work.recycle()
    for (st in p.steps) if (st.on) when (st.k) {
        'b' -> gaussian(px, W, H, max(1, (st.v * ws / 1000f).roundToInt()))
        'f' -> px = kuwahara(px, W, H, max(1, (st.v * ws / 200f).roundToInt()))
        'v' -> posterizeValue(px, st.v)
    }
    return Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888)
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
