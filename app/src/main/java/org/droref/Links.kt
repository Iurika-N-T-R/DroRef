package org.droref

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

const val LINK_COLOR = 0xFFB4B4B4.toInt()

/** An arrow between two items (DroRef's nodes). It follows both ends; .pur files get it as a drawing. */
data class Link(val id: Long, val from: Long, val to: Long, val color: Int = LINK_COLOR, val both: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("from", from).put("to", to).put("color", color).put("both", both)

    companion object {
        fun fromJson(o: JSONObject) = Link(o.getLong("id"), o.getLong("from"), o.getLong("to"), o.optInt("color", LINK_COLOR), o.optBoolean("both"))
    }
}

/** Middles of the left, top, right and bottom sides of what's visible of [it], in board coords. */
fun anchors(it: Item): List<PointF> {
    val c = it.crop
    val pts = floatArrayOf(c.left, c.centerY(), c.centerX(), c.top, c.right, c.centerY(), c.centerX(), c.bottom).also(it.matrix()::mapPoints)
    return List(4) { i -> PointF(pts[i * 2], pts[i * 2 + 1]) }
}

/**
 * The curve from [a] to [b]: it leaves the side of [a] facing [b] and enters the side of [b] facing [a],
 * each end going straight out of its side first (cubic Bézier, like node editors).
 */
fun linkCurve(a: RectF, b: RectF): FloatArray {
    val dx = b.centerX() - a.centerX()
    val dy = b.centerY() - a.centerY()
    val sideways = abs(dx) * a.height().coerceAtLeast(1f) >= abs(dy) * a.width().coerceAtLeast(1f) * 0.6f
    val (sx, sy, ex, ey) = if (sideways) {
        if (dx >= 0) floatArrayOf(a.right, a.centerY(), b.left, b.centerY()) else floatArrayOf(a.left, a.centerY(), b.right, b.centerY())
    } else {
        if (dy >= 0) floatArrayOf(a.centerX(), a.bottom, b.centerX(), b.top) else floatArrayOf(a.centerX(), a.top, b.centerX(), b.bottom)
    }
    val reach = max(hypot(ex - sx, ey - sy) * 0.45f, 20f)
    return if (sideways) {
        val k = if (dx >= 0) reach else -reach
        floatArrayOf(sx, sy, sx + k, sy, ex - k, ey, ex, ey)
    } else {
        val k = if (dy >= 0) reach else -reach
        floatArrayOf(sx, sy, sx, sy + k, ex, ey - k, ex, ey)
    }
}

/** Points along a curve from [linkCurve], for hit tests and for drawings in .pur files. */
fun sample(c: FloatArray, n: Int = 24): FloatArray = FloatArray((n + 1) * 2) { i ->
    val t = (i / 2) / n.toFloat()
    val u = 1 - t
    val k = i % 2
    u * u * u * c[k] + 3 * u * u * t * c[2 + k] + 3 * u * t * t * c[4 + k] + t * t * t * c[6 + k]
}

class LinkPainter {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    /** [w] = line width in board units (screen-sized lines: pass dp / camera scale). */
    fun draw(c: Canvas, curve: FloatArray, color: Int, w: Float, both: Boolean, dashed: Boolean = false) {
        line.color = color
        line.strokeWidth = w
        line.pathEffect = if (dashed) DashPathEffect(floatArrayOf(w * 3, w * 3), 0f) else null
        path.reset()
        path.moveTo(curve[0], curve[1])
        path.cubicTo(curve[2], curve[3], curve[4], curve[5], curve[6], curve[7])
        c.drawPath(path, line)
        line.pathEffect = null
        head(c, curve[6], curve[7], curve[4], curve[5], w)
        if (both) head(c, curve[0], curve[1], curve[2], curve[3], w)
    }

    /** Arrow head at (x, y), pointing away from the control point (cx, cy). */
    private fun head(c: Canvas, x: Float, y: Float, cx: Float, cy: Float, w: Float) {
        val a = atan2(y - cy, x - cx)
        val len = w * 5
        for (d in floatArrayOf(2.5f, -2.5f)) c.drawLine(x, y, x + len * cos(a + d), y + len * sin(a + d), line)
    }
}

/** Draws every link of [items] (board coords). */
fun drawLinks(c: Canvas, p: LinkPainter, links: List<Link>, items: List<Item>, w: Float, highlight: Long = -1, highlightColor: Int = 0) {
    val m = items.associateBy { it.id }
    for (l in links) {
        val a = m[l.from] ?: continue
        val b = m[l.to] ?: continue
        p.draw(c, linkCurve(a.bounds(), b.bounds()), if (l.id == highlight) highlightColor else l.color, if (l.id == highlight) w * 1.6f else w, l.both)
    }
}

/** Links as drawings, for .pur files (PureRef has no links). */
fun linksAsDrawings(links: List<Link>, items: List<Item>, newId: () -> Long): List<Item> {
    val m = items.associateBy { it.id }
    val all = items.union()
    val w = max(all.width(), all.height()) / 500f
    return links.mapNotNull { l ->
        val a = m[l.from] ?: return@mapNotNull null
        val b = m[l.to] ?: return@mapNotNull null
        Item(newId(), DRAW, 0f, 0f, name = "link").withStrokes(listOf(Stroke(T_PEN, l.color, w.coerceAtLeast(2f), S_ARROW, sample(linkCurve(a.bounds(), b.bounds())))))
    }
}
