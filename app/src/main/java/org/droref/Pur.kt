package org.droref

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * PureRef legacy .pur (1.10 / 1.11 format), read and write.
 * Ported from FyorDev/PureRef-format (MIT): https://github.com/FyorDev/PureRef-format
 * PureRef 2.x opens these, and saves them via "Save as > Legacy PureRef scene".
 * Big-endian. Strings are u32 byte length + UTF-16BE (0xFFFFFFFF = null).
 * Matrices are Qt QTransform rows: m11 m12 0 m21 m22 0, then dx dy.
 */
/** A PureRef 2 native file: not readable, ask for "Save as > Legacy PureRef scene". */
class Pur2Exception : Exception("PureRef 2 format")

object Pur {
    private const val IMG = 34 // byte length of "GraphicsImageItem" in UTF-16
    private const val TXT = 32 // "GraphicsTextItem"
    private val UTF16 = Charsets.UTF_16BE
    private val PNG_HEAD = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val PNG_FOOT = byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    private class Img(val png: ByteArray?, val dupOf: Int) // dupOf: id of an item whose image this reuses; -1 = linked file
    private class Raw(val id: Int, val z: Double, val m: Matrix, val image: Boolean, val crop: RectF?, val name: String,
                      val source: String, val text: String, val fg: Int, val bg: Int)

    /** Fills [scene] (writing images into it). Returns how many items were skipped (linked, not embedded). */
    fun read(bytes: ByteArray, scene: Scene): Int {
        val b = ByteBuffer.wrap(bytes)
        val vlen = b.getInt(0)
        val version = if (vlen in 2..64) String(bytes, 4, vlen, UTF16) else ""
        if (!version.startsWith("1.")) throw Pur2Exception()
        var pos = 224
        fun u16() = (b.getShort(pos).toInt() and 0xFFFF).also { pos += 2 }
        fun str(): String? {
            val n = b.getInt(pos); pos += 4
            if (n == -1) return null
            return String(bytes, pos, n, UTF16).also { pos += n }
        }

        // image data: PNGs, with 4-byte "same as item N" records in between
        val imgs = HashMap<Int, Img>()
        while (true) {
            val start = find(bytes, PNG_HEAD, pos)
            if (start < 0) break
            if (start - pos >= 4) {
                imgs[pos] = Img(null, b.getInt(pos)); pos += 4
            } else {
                val end = find(bytes, PNG_FOOT, start) + PNG_FOOT.size
                require(end > start) { "Broken image data" }
                imgs[start] = Img(bytes.copyOfRange(start, end), 0); pos = end
            }
        }
        fun typeAt(p: Int) = if (p + 12 <= bytes.size) b.getInt(p + 8) else 0
        while (pos + 12 <= bytes.size && typeAt(pos) != IMG && typeAt(pos) != TXT) {
            imgs[pos] = Img(null, b.getInt(pos)); pos += 4
        }

        val raws = ArrayList<Raw>()
        fun qt(parent: Matrix?): Matrix {
            val v = floatArrayOf(
                b.getDouble(pos).toFloat(), b.getDouble(pos + 24).toFloat(), b.getDouble(pos + 48).toFloat(),
                b.getDouble(pos + 8).toFloat(), b.getDouble(pos + 32).toFloat(), b.getDouble(pos + 56).toFloat(),
                0f, 0f, 1f,
            )
            pos += 64
            return Matrix().apply { setValues(v); parent?.let { postConcat(it) } }
        }
        fun color(): Int {
            val hsv = bytes[pos].toInt() == 2; pos++
            val a = u16(); val r = u16(); val g = u16(); val bl = u16()
            return if (hsv) Color.HSVToColor(a / 257, floatArrayOf(r / 35900f * 360f, g / 65535f, bl / 65535f))
            else Color.argb(a / 257, r / 257, g / 257, bl / 257)
        }
        fun text(parent: Matrix?) {
            val end = b.getLong(pos).toInt()
            pos += 12 + b.getInt(pos + 8)
            val t = str().orEmpty()
            val m = qt(parent); pos += 8
            val id = b.getInt(pos); pos += 4
            val z = b.getDouble(pos); pos += 8
            val fg = color(); pos += 2
            val bg = color()
            val kids = b.getInt(pos + 2)
            pos = end
            raws += Raw(id, z, m, false, null, "", "", t, fg, bg)
            repeat(kids) { text(m) }
        }
        fun image() {
            val end = b.getLong(pos).toInt()
            pos += 12 + b.getInt(pos + 8)
            val bruteForce = b.getInt(pos) == 0
            if (bruteForce) pos += 4
            val source = if (b.getInt(pos) == -1) { pos += 4; "" } else str().orEmpty()
            val name = if (bruteForce) "" else if (b.getInt(pos) == -1) { pos += 4; "" } else str().orEmpty()
            pos += 8
            val m = qt(null); pos += 8
            val id = b.getInt(pos); pos += 4
            val z = b.getDouble(pos); pos += 8
            pos += 48 + 24 // matrix before crop, crop x/y/scale
            val n = b.getInt(pos); pos += 4
            val xs = FloatArray(n)
            val ys = FloatArray(n)
            for (i in 0 until n) { xs[i] = b.getDouble(pos + 4).toFloat(); ys[i] = b.getDouble(pos + 12).toFloat(); pos += 20 }
            val kids = b.getInt(pos + 21)
            pos = end
            val crop = if (n > 0) RectF(xs.min(), ys.min(), xs.max(), ys.max()) else null
            raws += Raw(id, z, m, true, crop, name, source, "", 0, 0)
            repeat(kids) { text(m) }
        }
        while (typeAt(pos) == IMG || typeAt(pos) == TXT) if (typeAt(pos) == IMG) image() else text(null)

        str() // folder location
        val refs = HashMap<Int, Int>() // item id -> image data address
        repeat(raws.count { it.image }) { refs[b.getInt(pos)] = b.getLong(pos + 4).toInt(); pos += 20 }
        fun png(id: Int, depth: Int = 0): ByteArray? {
            val img = imgs[refs[id] ?: return null] ?: return null
            return img.png ?: if (img.dupOf != -1 && depth < 16) png(img.dupOf, depth + 1) else null
        }

        val files = IdentityHashMap<ByteArray, String>()
        var skipped = 0
        for (r in raws.sortedBy { it.z }) {
            if (r.image) {
                val data = png(r.id)
                val size = data?.let(::imageSize)
                if (data == null || size == null) { skipped++; continue }
                val file = files.getOrPut(data) { UUID.randomUUID().toString().also { File(scene.images, it).writeBytes(data) } }
                val w = size.first.toFloat()
                val h = size.second.toFloat()
                // PureRef crop points are centered on the image
                val crop = r.crop?.let { RectF(it.left + w / 2, it.top + h / 2, it.right + w / 2, it.bottom + h / 2) }
                    ?.takeIf { it.intersect(0f, 0f, w, h) && it.width() > 1 && it.height() > 1 } ?: RectF(0f, 0f, w, h)
                val name = r.name.ifEmpty { r.source.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.') }
                scene.items += Item(scene.newId(), IMAGE, w, h, crop = crop, file = file, name = name, source = r.source).placed(r.m, 0f, 0f)
            } else {
                // ponytail: note size uses our font, so imported notes are only roughly the PureRef size
                val n = Item(scene.newId(), NOTE, 0f, 0f, textColor = r.fg, bgColor = r.bg).withText(r.text)
                scene.items += n.placed(r.m, n.w / 2, n.h / 2) // Qt text origin is top-left
            }
        }
        return skipped
    }

    /**
     * Writes [items] (in drawing order) as a legacy .pur. Call off the UI thread: images are re-read from [images].
     * The legacy format only knows images and text: drawings go in as images, videos as their first frame,
     * groups are dropped (their contents stay).
     */
    fun write(items: List<Item>, images: File, out: OutputStream) {
        val w = Buf()
        w.n = 224
        w.putU32(0, 8)
        "1.10".toByteArray(UTF16).copyInto(w.a, 4)

        val z = items.withIndex().associate { (i, it) -> it.id to (i + 1).toDouble() }
        fun key(it: Item) = if (it.type == DRAW) "draw:${it.id}" else it.file
        val groups = items.filter { (it.isMedia && File(images, it.file).exists()) || (it.type == DRAW && it.strokes.isNotEmpty()) }.groupBy(::key)
        val notes = items.filter { it.type == NOTE }
        val ids = HashMap<Long, Int>()
        var next = 0
        groups.values.forEach { g -> g.forEach { ids[it.id] = next++ } }
        val imageCount = next
        notes.forEach { ids[it.id] = next++ }

        w.putU16(12, imageCount + notes.size)
        w.putU16(14, imageCount)
        w.putU32(24, 12)
        w.putU32(40, 64)
        w.putU32(108, next)
        // canvas rect is x, y, width, height (checked against PureRef 2.1.3's exportScene)
        val r = items.union().takeIf { !it.isEmpty }?.apply { inset(-50f, -50f) } ?: RectF(-1000f, -1000f, 1000f, 1000f)
        w.putF64(112, r.left); w.putF64(120, r.top); w.putF64(128, r.width()); w.putF64(136, r.height())
        w.putF64(144, 1.0); w.putF64(176, 1.0); w.putF64(208, 1.0)

        // image data; k = PNG pixels per content pixel (we may have to downscale huge images)
        val addr = HashMap<String, IntArray>()
        val k = HashMap<String, Float>()
        for ((file, g) in groups) {
            val png = pngFor(g[0], images) ?: continue
            val start = w.n
            w.bytes(png)
            addr[file] = intArrayOf(start, w.n)
            k[file] = (imageSize(png)?.first ?: g[0].w.toInt()) / g[0].w
            repeat(g.size - 1) { w.u32(ids.getValue(g[0].id)) }
        }

        fun matrix(m: Matrix) {
            val v = FloatArray(9).also(m::getValues)
            w.f64(v[Matrix.MSCALE_X]); w.f64(v[Matrix.MSKEW_Y]); w.f64(0f)
            w.f64(v[Matrix.MSKEW_X]); w.f64(v[Matrix.MSCALE_Y]); w.f64(0f)
            w.f64(v[Matrix.MTRANS_X]); w.f64(v[Matrix.MTRANS_Y])
        }
        fun color(c: Int) { w.u16(Color.alpha(c) * 257); w.u16(Color.red(c) * 257); w.u16(Color.green(c) * 257); w.u16(Color.blue(c) * 257) }

        for ((file, g) in groups) {
            val s = k[file] ?: continue
            for (it in g) {
                val start = w.n
                w.u64(0); w.u32(IMG); w.bytes("GraphicsImageItem".toByteArray(UTF16))
                w.str(it.source.ifEmpty { it.name }); w.str(it.name)
                w.f64(1.0)
                matrix(Matrix(it.matrix()).apply { preTranslate(it.w / 2, it.h / 2); preScale(1 / s, 1 / s) })
                w.f64(1.0)
                w.u32(ids.getValue(it.id)); w.f64(z.getValue(it.id))
                w.f64(1.0); w.f64(0.0); w.f64(0.0); w.f64(0.0); w.f64(1.0); w.f64(0.0) // matrix before crop
                val l = (it.crop.left - it.w / 2) * s
                val t = (it.crop.top - it.h / 2) * s
                val rr = (it.crop.right - it.w / 2) * s
                val bb = (it.crop.bottom - it.h / 2) * s
                // ponytail: crop x/y = top-left of the crop is a guess; PureRef-format only ever writes uncropped images
                w.f64(l); w.f64(t); w.f64(1.0)
                val pts = listOf(l to t, rr to t, rr to bb, l to bb, l to t)
                w.u32(pts.size)
                pts.forEachIndexed { i, (px, py) -> w.u32(if (i == 0) 0 else 1); w.f64(px); w.f64(py) }
                w.f64(0.0); w.u32(1); w.u8(0); w.u64(-1); w.u32(0)
                w.putU64(start, w.n.toLong())
            }
        }
        for (it in notes) {
            val start = w.n
            w.u64(0); w.u32(TXT); w.bytes("GraphicsTextItem".toByteArray(UTF16))
            w.str(it.text)
            matrix(it.matrix()) // our content coords are Qt's top-left text coords
            w.f64(1.0)
            w.u32(ids.getValue(it.id)); w.f64(z.getValue(it.id))
            w.u8(1); color(it.textColor)
            w.u16(0)
            w.u8(1); color(it.bgColor)
            w.u16(0); w.u32(0)
            w.putU64(start, w.n.toLong())
        }

        w.str("")
        w.putU64(16, w.n.toLong())
        for ((file, g) in groups) {
            val (start, end) = addr[file] ?: continue
            g.forEachIndexed { i, it ->
                w.u32(ids.getValue(it.id))
                if (i == 0) { w.u64(start.toLong()); w.u64(end.toLong()) }
                else { val o = end + (i - 1) * 4; w.u64(o.toLong()); w.u64(o + 4L) }
            }
        }
        val md5 = MessageDigest.getInstance("MD5").apply { update(w.a, 108, w.n - 108) }.digest()
        md5.joinToString("") { "%02x".format(it) }.toByteArray(UTF16).copyInto(w.a, 44)
        out.write(w.a, 0, w.n)
    }

    private fun pngFor(it: Item, images: File): ByteArray? {
        val bmp = when (it.type) {
            DRAW -> {
                val k = min(1f, 4096 / max(it.w, it.h))
                Bitmap.createBitmap(max(1, (it.w * k).toInt()), max(1, (it.h * k).toInt()), Bitmap.Config.ARGB_8888).also { b ->
                    val c = android.graphics.Canvas(b)
                    c.scale(k, k)
                    Painter().content(c, it, null, 255, false)
                }
            }
            VIDEO -> videoFrame(File(images, it.file)) ?: return null
            else -> return png(File(images, it.file))
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
    }

    private fun png(f: File): ByteArray? {
        val raw = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (raw.size > 8 && raw.copyOf(8).contentEquals(PNG_HEAD)) return raw
        val bmp = decode(f, 8192) ?: return null
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
    }

    private fun find(b: ByteArray, pat: ByteArray, from: Int): Int {
        outer@ for (i in max(0, from)..b.size - pat.size) {
            for (j in pat.indices) if (b[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private class Buf {
        var a = ByteArray(1 shl 20)
        var n = 0
            set(v) { room(v - field); field = v }
        private fun room(k: Int) { if (n + k > a.size) a = a.copyOf(max(a.size * 2, n + k)) }
        private fun bb() = ByteBuffer.wrap(a)
        fun u8(v: Int) { room(1); a[n] = v.toByte(); n++ }
        fun u16(v: Int) { room(2); bb().putShort(n, min(v, 0xFFFF).toShort()); n += 2 }
        fun u32(v: Int) { room(4); bb().putInt(n, v); n += 4 }
        fun u64(v: Long) { room(8); bb().putLong(n, v); n += 8 }
        fun f64(v: Double) = u64(java.lang.Double.doubleToRawLongBits(v))
        fun f64(v: Float) = f64(v.toDouble())
        fun bytes(b: ByteArray) { room(b.size); b.copyInto(a, n); n += b.size }
        fun str(s: String) { val b = s.toByteArray(UTF16); u32(b.size); bytes(b) }
        fun putU16(p: Int, v: Int) { bb().putShort(p, v.toShort()) }
        fun putU32(p: Int, v: Int) { bb().putInt(p, v) }
        fun putU64(p: Int, v: Long) { bb().putLong(p, v) }
        fun putF64(p: Int, v: Float) = putF64(p, v.toDouble())
        fun putF64(p: Int, v: Double) { bb().putDouble(p, v) }
    }
}
