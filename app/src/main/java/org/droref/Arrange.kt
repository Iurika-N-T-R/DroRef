package org.droref

import kotlin.math.max
import kotlin.math.sqrt

/** Arrange / align / distribute / stack / normalize. Pure functions: items in, moved items out. */
object Arrange {
    private val PAD get() = Prefs.padding.toFloat()

    /** Rows in the given order, wrapping at a width that makes the block roughly [aspect] (w/h). */
    fun rows(items: List<Item>, aspect: Float, left: Float, top: Float): List<Item> {
        if (items.isEmpty()) return items
        val area = items.sumOf { it.bounds().let { b -> (b.width() * b.height()).toDouble() } }
        val rowW = max(sqrt(area * aspect).toFloat(), items.maxOf { it.bounds().width() })
        var x = left
        var y = top
        var rowH = 0f
        return items.map {
            val b = it.bounds()
            if (x > left && x + b.width() > left + rowW) { x = left; y += rowH + PAD; rowH = 0f }
            val moved = it.moveBy(x - b.left, y - b.top)
            x += b.width() + PAD
            rowH = max(rowH, b.height())
            moved
        }
    }

    /** Pushes items toward a side until they hit the edge or another item. dir: 0 left, 1 right, 2 top, 3 bottom. */
    fun align(items: List<Item>, dir: Int): List<Item> {
        // [lo, hi] along the push axis, flipped so pushing always lowers lo; [c0, c1] across it
        fun axis(it: Item) = it.bounds().let { r ->
            when (dir) {
                0 -> floatArrayOf(r.left, r.right, r.top, r.bottom)
                1 -> floatArrayOf(-r.right, -r.left, r.top, r.bottom)
                2 -> floatArrayOf(r.top, r.bottom, r.left, r.right)
                else -> floatArrayOf(-r.bottom, -r.top, r.left, r.right)
            }
        }
        val start = items.minOf { axis(it)[0] }
        val placed = ArrayList<FloatArray>()
        return items.sortedBy { axis(it)[0] }.map {
            val v = axis(it)
            val lo = placed.filter { p -> p[2] < v[3] && p[3] > v[2] }.maxOfOrNull { p -> p[1] + PAD }?.let { l -> max(l, start) } ?: start
            val d = lo - v[0]
            placed += floatArrayOf(v[0] + d, v[1] + d, v[2], v[3])
            when (dir) {
                0 -> it.moveBy(d, 0f)
                1 -> it.moveBy(-d, 0f)
                2 -> it.moveBy(0f, d)
                else -> it.moveBy(0f, -d)
            }
        }
    }

    /** One straight row (or column), keeping the current left-to-right (top-to-bottom) order. */
    fun distribute(items: List<Item>, horizontal: Boolean): List<Item> {
        val r = items.union()
        var at = if (horizontal) r.left else r.top
        return items.sortedBy { if (horizontal) it.bounds().centerX() else it.bounds().centerY() }.map {
            val b = it.bounds()
            val m = if (horizontal) it.moveBy(at - b.left, r.centerY() - b.centerY()) else it.moveBy(r.centerX() - b.centerX(), at - b.top)
            at += (if (horizontal) b.width() else b.height()) + PAD
            m
        }
    }

    /** Piles everything on one center; layer order is untouched. */
    fun stack(items: List<Item>): List<Item> {
        val r = items.union()
        return items.map { val b = it.bounds(); it.moveBy(r.centerX() - b.centerX(), r.centerY() - b.centerY()) }
    }

    /** kind: height, width, size (longest side), area, scale. Target is the first item or the average. */
    fun normalize(items: List<Item>, kind: String, fromFirst: Boolean): List<Item> {
        fun metric(it: Item): Float {
            val w = it.crop.width() * it.scale
            val h = it.crop.height() * it.scale
            return when (kind) {
                "height" -> h
                "width" -> w
                "size" -> max(w, h)
                "area" -> sqrt(w * h)
                else -> it.scale
            }
        }
        val target = if (fromFirst) metric(items.first()) else items.map(::metric).average().toFloat()
        return items.map { it.keepCenter(it.copy(scale = it.scale * target / metric(it))) }
    }
}
