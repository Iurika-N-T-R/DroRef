package org.droref

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

const val IMAGE = 0
const val NOTE = 1
const val GROUP = 2
const val DRAW = 3
const val GIF = 4
const val VIDEO = 5

const val NOTE_TEXT = 24f
const val NOTE_PAD = 12f
const val GROUP_PAD = 20f
const val NODE_BG = 0xFF3A3A3A.toInt()

const val T_PEN = 0
const val T_LINE = 1
const val T_RECT = 2
const val T_ELLIPSE = 3
const val T_ERASER = 4
const val S_SOLID = 0
const val S_DOTTED = 1
const val S_ARROW = 2

/** One drawn mark, in its drawing item's content coords. Shapes keep two corner points. */
class Stroke(val tool: Int, val color: Int, val width: Float, val style: Int, val pts: FloatArray) {
    fun shifted(dx: Float, dy: Float) = Stroke(tool, color, width, style, FloatArray(pts.size) { pts[it] + if (it % 2 == 0) dx else dy })

    /** Points along what's drawn, for bounds and the eraser. */
    fun outline(): FloatArray = when (tool) {
        T_RECT -> { val (a, b, c, d) = pts; floatArrayOf(a, b, c, b, c, d, a, d, a, b) }
        T_ELLIPSE -> {
            val (a, b, c, d) = pts
            val cx = (a + c) / 2; val cy = (b + d) / 2; val rx = (c - a) / 2; val ry = (d - b) / 2
            FloatArray(66) { val t = (it / 2) * Math.PI * 2 / 32; if (it % 2 == 0) (cx + rx * cos(t)).toFloat() else (cy + ry * sin(t)).toFloat() }
        }
        else -> pts
    }

    fun toJson(): JSONObject = JSONObject().put("tool", tool).put("color", color).put("width", width.toDouble()).put("style", style)
        .put("pts", JSONArray(pts.map { it.toDouble() }))

    companion object {
        fun fromJson(o: JSONObject): Stroke {
            val p = o.getJSONArray("pts")
            return Stroke(o.getInt("tool"), o.getInt("color"), o.getDouble("width").toFloat(), o.getInt("style"), FloatArray(p.length()) { p.getDouble(it).toFloat() })
        }
    }
}

/** One thing on the board. Immutable: edits make copies, so undo is a plain list snapshot. */
data class Item(
    val id: Long,
    val type: Int,
    val w: Float,                          // content size: original pixels, note box, group box, drawing bounds
    val h: Float,
    val x: Float = 0f,                     // board position of the content center
    val y: Float = 0f,
    val scale: Float = 1f,
    val rot: Float = 0f,                   // degrees
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val crop: RectF = RectF(0f, 0f, w, h), // visible part, in content coords
    val opacity: Float = 1f,
    val gray: Boolean = false,
    val locked: Boolean = false,
    val nearest: Boolean = false,          // nearest-neighbor sampling (pixel art)
    val file: String = "",
    val name: String = "",
    val source: String = "",
    val text: String = "",
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val bgColor: Int = 0xCC202020.toInt(),
    val parent: Long = 0,                  // moves with, and draws above, its parent
    val closed: Boolean = true,            // group: tapping a child selects the group
    val spans: String = "",                // note formatting, see Rich
    val strokes: List<Stroke> = emptyList(),
    val loopA: Float = 0f,                 // video loop, as fractions of the duration
    val loopB: Float = 1f,
    val noteWidth: Float = 0f,             // note wrap width; 0 = auto size (one line per paragraph)
    val comment: String = "",              // image comment (PureRef 2)
    val tag: Int = 0,                      // color label drawn around it; 0 = none
    val url: String = "",                  // link opened from its badge
    val node: Boolean = false,             // note shown as a node (rounded box, for links)
) {
    /** Content coords -> board coords. */
    fun matrix() = Matrix().apply {
        postTranslate(-w / 2, -h / 2)
        postScale(if (flipX) -scale else scale, if (flipY) -scale else scale)
        postRotate(rot)
        postTranslate(x, y)
    }

    /** Axis-aligned board bounds of the visible part. */
    fun bounds() = RectF(crop).also { matrix().mapRect(it) }

    fun moveBy(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)

    val isMedia get() = type == IMAGE || type == GIF || type == VIDEO
    /** "Verrouillage" splits the board in two: visuals (images, videos, drawings) and nodes (notes, nodes, links). */
    val isVisual get() = isMedia || type == DRAW

    /** [n] moved so its visible center stays where ours is. */
    fun keepCenter(n: Item): Item {
        val a = bounds()
        val b = n.bounds()
        return n.moveBy(a.centerX() - b.centerX(), a.centerY() - b.centerY())
    }

    /** Translate, then scale + rotate around the pivot (board coords). */
    fun transformed(px: Float, py: Float, ds: Float, dr: Float, dx: Float, dy: Float): Item {
        val p = floatArrayOf(x + dx, y + dy)
        Matrix().apply { postScale(ds, ds, px, py); postRotate(dr, px, py) }.mapPoints(p)
        return copy(x = p[0], y = p[1], scale = scale * ds, rot = rot + dr)
    }

    /** Takes position, rotation, scale and flip from [m], which maps local coords where ([lx], [ly]) is the content center. */
    fun placed(m: Matrix, lx: Float, ly: Float): Item {
        val v = FloatArray(9).also(m::getValues)
        val a = v[Matrix.MSCALE_X]
        val b = v[Matrix.MSKEW_Y]
        val c = v[Matrix.MSKEW_X]
        val d = v[Matrix.MSCALE_Y]
        val r = atan2(b, a)
        val sy = d * cos(r) - c * sin(r)
        val p = floatArrayOf(lx, ly).also(m::mapPoints)
        return copy(x = p[0], y = p[1], scale = hypot(a, b), rot = Math.toDegrees(r.toDouble()).toFloat(), flipX = false, flipY = sy < 0)
    }

    fun withText(t: String, sp: String = spans): Item {
        val (nw, nh) = noteSize(t, sp, noteWidth)
        return copy(text = t, spans = sp, w = nw, h = nh, crop = RectF(0f, 0f, nw, nh))
    }

    /** New strokes; the box is refit around them without moving anything on screen. */
    fun withStrokes(list: List<Stroke>): Item {
        if (list.isEmpty()) return copy(strokes = list)
        val first = list[0].outline()
        val r = RectF(first[0], first[1], first[0], first[1])
        for (s in list) { val o = s.outline(); for (i in o.indices step 2) r.union(o[i], o[i + 1]) }
        val pad = list.maxOf { it.width } * 2 + 2
        r.inset(-pad, -pad)
        val c = floatArrayOf(r.centerX(), r.centerY()).also(matrix()::mapPoints)
        return copy(strokes = list.map { it.shifted(-r.left, -r.top) }, w = r.width(), h = r.height(), crop = RectF(0f, 0f, r.width(), r.height()), x = c[0], y = c[1])
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type)
        put("w", w.toDouble()); put("h", h.toDouble()); put("x", x.toDouble()); put("y", y.toDouble())
        put("scale", scale.toDouble()); put("rot", rot.toDouble()); put("flipX", flipX); put("flipY", flipY)
        put("crop", JSONArray(listOf(crop.left, crop.top, crop.right, crop.bottom).map { it.toDouble() }))
        put("opacity", opacity.toDouble()); put("gray", gray); put("locked", locked); put("nearest", nearest)
        put("file", file); put("name", name); put("source", source); put("text", text)
        put("textColor", textColor); put("bgColor", bgColor)
        put("parent", parent); put("closed", closed); put("spans", spans)
        put("strokes", JSONArray(strokes.map { it.toJson() }))
        put("loopA", loopA.toDouble()); put("loopB", loopB.toDouble())
        put("noteWidth", noteWidth.toDouble()); put("comment", comment)
        put("tag", tag); put("url", url); put("node", node)
    }

    companion object {
        fun fromJson(o: JSONObject): Item {
            fun f(k: String, d: Float = 0f) = o.optDouble(k, d.toDouble()).toFloat()
            val c = o.getJSONArray("crop")
            fun cf(i: Int) = c.getDouble(i).toFloat()
            val st = o.optJSONArray("strokes")
            return Item(
                o.getLong("id"), o.getInt("type"), f("w"), f("h"), f("x"), f("y"), f("scale", 1f), f("rot"),
                o.optBoolean("flipX"), o.optBoolean("flipY"), RectF(cf(0), cf(1), cf(2), cf(3)),
                f("opacity", 1f), o.optBoolean("gray"), o.optBoolean("locked"), o.optBoolean("nearest"),
                o.optString("file"), o.optString("name"), o.optString("source"), o.optString("text"),
                o.optInt("textColor", -1), o.optInt("bgColor", 0xCC202020.toInt()),
                o.optLong("parent"), o.optBoolean("closed", true), o.optString("spans"),
                if (st == null) emptyList() else List(st.length()) { Stroke.fromJson(st.getJSONObject(it)) },
                f("loopA", 0f), f("loopB", 1f), f("noteWidth", 0f), o.optString("comment"),
                o.optInt("tag"), o.optString("url"), o.optBoolean("node"),
            )
        }
    }
}

/** Lays out a note's (rich) text: at [width] if set, else at its natural width (one line per paragraph, PureRef's auto size). */
fun noteLayout(text: String, spans: String, color: Int, paint: TextPaint, width: Float = 0f): StaticLayout {
    val s = Rich.build(text, spans)
    paint.color = color
    val margin = s.getSpans(0, s.length, LeadingMarginSpan::class.java).maxOfOrNull { it.getLeadingMargin(true) } ?: 0
    val w = if (width > 0) width.toInt() else ceil(Layout.getDesiredWidth(s, paint)).toInt() + margin
    return StaticLayout.Builder.obtain(s, 0, s.length, paint, w.coerceAtLeast(1)).setIncludePad(false).build()
}

private val measure = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NOTE_TEXT }

fun noteSize(text: String, spans: String = "", width: Float = 0f): Pair<Float, Float> = synchronized(measure) {
    val l = noteLayout(text, spans, -1, measure, width)
    (l.width + 2 * NOTE_PAD) to (l.height + 2 * NOTE_PAD)
}

fun List<Item>.union() = RectF().also { r -> forEach { r.union(it.bounds()) } }

private fun bounds(o: BitmapFactory.Options) = if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null

fun imageSize(f: File) = bounds(BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) })
fun imageSize(b: ByteArray) = bounds(BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(b, 0, b.size, it) })
fun imageMime(f: File): String? = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }.outMimeType

/** Decodes with power-of-two downsampling so the longest side is at most about [maxSide]. */
fun decode(f: File, maxSide: Int): Bitmap? {
    val (w, h) = imageSize(f) ?: return null
    var n = 1
    while (max(w, h) / (n * 2) >= maxSide) n *= 2
    return runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = n }) }.getOrNull()
}

/** Distance from (px, py) to a polyline. */
fun distance(poly: FloatArray, px: Float, py: Float): Float {
    if (poly.size < 4) return if (poly.size == 2) hypot(poly[0] - px, poly[1] - py) else Float.MAX_VALUE
    var best = Float.MAX_VALUE
    for (i in 0 until poly.size - 2 step 2) {
        val ax = poly[i]; val ay = poly[i + 1]; val bx = poly[i + 2]; val by = poly[i + 3]
        val dx = bx - ax; val dy = by - ay
        val len = dx * dx + dy * dy
        val t = if (len == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0f, 1f)
        best = min(best, hypot(ax + t * dx - px, ay + t * dy - py))
    }
    return best
}
