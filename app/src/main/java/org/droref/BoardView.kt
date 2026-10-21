package org.droref

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.URLSpan
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

const val GRID = 50f
/** Marks drags that started in DroRef itself, so the board doesn't take its own drops. */
val DRAG_SELF = Any()
private const val MIN_ZOOM = 0.005f
private const val MAX_ZOOM = 64f

/** Draws items. Paint isn't thread-safe, so one Painter per thread. */
class Painter {
    private val img = Paint(Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NOTE_TEXT }
    private val fill = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val layouts = HashMap<String, StaticLayout>()
    // PureRef's grayscale formula: (r*11 + g*16 + b*5) / 32
    private val grayFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        val r = 11f / 32; val g = 16f / 32; val b = 5f / 32
        set(floatArrayOf(r, g, b, 0f, 0f, r, g, b, 0f, 0f, r, g, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    })

    fun layout(it: Item): StaticLayout {
        if (layouts.size > 300) layouts.clear()
        return layouts.getOrPut("${it.textColor}|${it.noteWidth}|${it.spans}|${it.text}") { noteLayout(it.text, it.spans, it.textColor, text, it.noteWidth) }
    }

    /** Content only, in content coords. */
    fun content(c: Canvas, it: Item, bmp: Bitmap?, alpha: Int, gray: Boolean, gif: Gif? = null) {
        val f = if (gray) grayFilter else null
        when (it.type) {
            NOTE -> {
                rect(c, it, it.bgColor, alpha, f)
                val l = layout(it)
                text.color = it.textColor
                text.alpha = Color.alpha(it.textColor) * alpha / 255
                text.colorFilter = f
                c.save(); c.translate(NOTE_PAD, NOTE_PAD); l.draw(c); c.restore()
            }
            GROUP -> rect(c, it, it.bgColor, alpha, f)
            DRAW -> it.strokes.forEach { s -> stroke(c, s, alpha, f) }
            else -> when {
                gif != null -> { img.alpha = alpha; img.isFilterBitmap = !it.nearest; img.colorFilter = f; gif.draw(c, it.w, it.h, img) }
                bmp != null -> { img.alpha = alpha; img.isFilterBitmap = !it.nearest; img.colorFilter = f; c.drawBitmap(bmp, null, RectF(0f, 0f, it.w, it.h), img) }
                else -> rect(c, it, 0xFF444444.toInt(), alpha, null)
            }
        }
    }

    private fun rect(c: Canvas, it: Item, color: Int, alpha: Int, f: ColorFilter?) {
        fill.color = color
        fill.alpha = Color.alpha(color) * alpha / 255
        fill.colorFilter = f
        c.drawRect(0f, 0f, it.w, it.h, fill)
    }

    fun stroke(c: Canvas, s: Stroke, alpha: Int, f: ColorFilter?) {
        line.color = s.color
        line.alpha = Color.alpha(s.color) * alpha / 255
        line.strokeWidth = s.width
        line.colorFilter = f
        line.pathEffect = if (s.style == S_DOTTED) DashPathEffect(floatArrayOf(0.01f, s.width * 2), 0f) else null
        val p = s.pts
        when (s.tool) {
            T_RECT -> c.drawRect(min(p[0], p[2]), min(p[1], p[3]), max(p[0], p[2]), max(p[1], p[3]), line)
            T_ELLIPSE -> c.drawOval(min(p[0], p[2]), min(p[1], p[3]), max(p[0], p[2]), max(p[1], p[3]), line)
            else -> {
                val path = Path()
                path.moveTo(p[0], p[1])
                for (i in 2 until p.size step 2) path.lineTo(p[i], p[i + 1])
                if (p.size == 2) path.lineTo(p[0] + 0.01f, p[1])
                c.drawPath(path, line)
                if (s.style == S_ARROW && p.size >= 4) arrowHead(c, p, s.width)
            }
        }
    }

    private fun arrowHead(c: Canvas, p: FloatArray, w: Float) {
        val ex = p[p.size - 2]; val ey = p[p.size - 1]
        var i = p.size - 4
        while (i > 0 && hypot(p[i] - ex, p[i + 1] - ey) < w * 4) i -= 2
        val a = atan2(ey - p[i + 1], ex - p[i])
        val len = w * 4
        line.pathEffect = null
        for (d in floatArrayOf(2.6f, -2.6f)) c.drawLine(ex, ey, ex + len * cos(a + d), ey + len * sin(a + d), line)
    }

    /** Item with its transform, crop and opacity, in board coords. */
    fun item(c: Canvas, it: Item, bmp: Bitmap?, gray: Boolean, gif: Gif? = null) {
        c.save()
        c.concat(it.matrix())
        c.clipRect(it.crop)
        content(c, it, bmp, (it.opacity * 255).toInt(), gray || it.gray, gif)
        c.restore()
    }
}

/** Parents first, then their children, depth-first, keeping list order among siblings. */
fun drawOrder(items: List<Item>): List<Item> {
    val ids = items.mapTo(HashSet()) { it.id }
    val kids = items.groupBy { if (it.parent in ids) it.parent else 0L }
    val out = ArrayList<Item>(items.size)
    val seen = HashSet<Long>()
    fun walk(id: Long) { kids[id]?.forEach { if (seen.add(it.id)) { out += it; walk(it.id) } } }
    walk(0)
    if (out.size < items.size) items.filterTo(out) { it.id !in seen } // parent cycles: draw them anyway
    return out
}

/** Renders items into one bitmap, longest side at most [maxSide]. Call off the UI thread. */
fun renderItems(items: List<Item>, images: File, maxSide: Int, gray: Boolean): Bitmap {
    val r = items.union()
    val s = min(maxSide / max(r.width(), r.height()).coerceAtLeast(1f), 4f)
    val out = Bitmap.createBitmap(max(1, (r.width() * s).roundToInt()), max(1, (r.height() * s).roundToInt()), Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(Prefs.canvasColor())
    c.scale(s, s)
    c.translate(-r.left, -r.top)
    val p = Painter()
    for (it in drawOrder(items)) {
        val f = File(images, it.file)
        val b = when (it.type) {
            IMAGE, GIF -> decode(f, (max(it.w, it.h) * it.scale * s).roundToInt().coerceIn(64, 8192))
            VIDEO -> videoFrame(f)
            else -> null
        }
        p.item(c, it, b, gray)
        b?.recycle()
    }
    return out
}

/**
 * The infinite board.
 * One finger: move what's under it (selection or camera). Two fingers: also scale + rotate.
 * Tap selects, long-press an item adds/removes it from the selection,
 * long-press empty space then drag draws a selection rectangle (release without dragging = canvas menu).
 * Double-tap focuses an item (again = back), opens a closed group, edits a note or drawing.
 */
class BoardView(ctx: Context, scene: Scene, val floating: Boolean = false) : View(ctx) {
    interface Host {
        fun selectionChanged()
        fun lockedPressed(item: Item)
        fun canvasMenu()
        fun picked(info: String, hex: String)
        fun editItem(item: Item)
    }

    class Src(val name: String, val source: String, val open: () -> InputStream?)

    var host: Host? = null
    var scene: Scene = scene
        private set
    val selection = LinkedHashSet<Long>()
    var picking = false
    var viewOnly = false // focus mode: look, pan, zoom; nothing moves
    var dropHint = false // something is being dragged over the board from another app
        set(v) { field = v; invalidate() }
    var cropId = -1L
        private set
    var background = 0 // floating window: 0 solid, 1 glass, 2 none
        set(v) { field = v; invalidate() }

    private val ownCam = Matrix() // the floating window has its own view of the board
    private val cam get() = if (floating) ownCam else scene.cam
    private val painter = Painter()
    private val bitmaps = HashMap<String, Bitmap>()
    private val loading = HashSet<String>()
    private val loader = Executors.newSingleThreadExecutor()
    private val gifs = HashMap<Long, Gif>()
    private val vids = HashMap<Long, Vid>()
    private val dp = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF4FA3FF.toInt() }
    private val border = Paint().apply { style = Paint.Style.STROKE; color = 0x40000000 }
    private val handle = Paint().apply { color = 0xFF4FA3FF.toInt() }
    private val rubberFill = Paint().apply { color = 0x334FA3FF }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30808080; strokeCap = Paint.Cap.ROUND }

    // gesture state
    private enum class G { NONE, CAMERA, ITEMS, RUBBER, CROP, PICK, DRAW }
    private var g = G.NONE
    private var downHit: Item? = null
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var last = FloatArray(4)
    private var raw = HashMap<Long, Item>()
    private val rubber = RectF()
    private var rubberOn = false
    private var cropEdges = 0 // 1 left, 2 top, 4 right, 8 bottom, 15 whole
    private var cropLast = FloatArray(2)
    private var focused: Set<Long>? = null
    private var beforeFocus: FloatArray? = null
    private var lastArrange = ""
    private var entered = -1L // closed group opened by double-tap

    // drawing
    var drawing = false
        private set
    private var drawTarget = -1L
    private var drawParent = 0L
    private var stroke: ArrayList<Float>? = null
    private var erasedOnce = false

    // slideshow
    var slides: List<Long> = emptyList()
        private set
    var slideIndex = 0
        private set
    var slidePaused = false
        private set
    private val slideNext = Runnable { slideStep(1) }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) = longPress(e)
        override fun onDoubleTap(e: MotionEvent) = doubleTap(e)
    })

    fun setScene(s: Scene) {
        releaseMedia()
        stopSlideshow()
        scene = s
        bitmaps.clear(); loading.clear(); selection.clear()
        cropId = -1; focused = null; lastArrange = ""; entered = -1; drawing = false
        invalidate()
        host?.selectionChanged()
    }

    private fun byId() = scene.items.associateBy { it.id }
    fun ordered() = drawOrder(scene.items)
    fun selected() = scene.items.filter { it.id in selection }
    fun selectedInOrder(): List<Item> { val m = byId(); return selection.mapNotNull { m[it] } }
    fun children(id: Long) = scene.items.filter { it.parent == id }
    fun depth(it: Item): Int { val m = byId(); var d = 0; var p = m[it.parent]; while (p != null && d < 64) { d++; p = m[p.parent] }; return d }

    fun withDescendants(ids: Collection<Long>): Set<Long> {
        val out = HashSet(ids)
        var grew = true
        while (grew) { grew = false; for (it in scene.items) if (it.parent in out && out.add(it.id)) grew = true }
        return out
    }

    private fun targets(): List<Item> {
        val s = selected()
        if (s.size == 1 && s[0].type == GROUP) return children(s[0].id)
        return s.ifEmpty { scene.items.filter { !it.locked && it.type != GROUP && it.parent == 0L } }
    }

    private fun camScale() = FloatArray(9).also(cam::getValues)[Matrix.MSCALE_X]
    private fun toBoard(sx: Float, sy: Float) = floatArrayOf(sx, sy).also(Matrix().also { cam.invert(it) }::mapPoints)
    private fun viewW() = if (width > 0) width else resources.displayMetrics.widthPixels
    private fun viewH() = if (height > 0) height else resources.displayMetrics.heightPixels

    // ---- editing

    /** Replaces items by id, as one undo step. */
    fun apply(changed: List<Item>, undoable: Boolean = true) {
        if (changed.isEmpty()) return
        if (undoable) scene.checkpoint()
        val m = changed.associateBy { it.id }
        scene.items.replaceAll { m[it.id] ?: it }
        commit()
    }

    /** Changes every selected item, keeping each one's visible center in place. */
    fun edit(f: (Item) -> Item) = apply(selected().map { it.keepCenter(f(it)) })

    private fun commit() {
        fitGroups()
        invalidate()
        scene.save()
        host?.selectionChanged()
    }

    /** Groups wrap their children; empty groups go away (like PureRef). */
    private fun fitGroups() {
        val groups = scene.items.filter { it.type == GROUP }.sortedByDescending(::depth)
        for (gr in groups) {
            val idx = scene.items.indexOfFirst { it.id == gr.id }
            val kids = children(gr.id)
            if (kids.isEmpty()) { scene.items.removeAt(idx); selection.remove(gr.id); continue }
            val r = kids.union().apply { inset(-GROUP_PAD, -GROUP_PAD) }
            scene.items[idx] = scene.items[idx].copy(x = r.centerX(), y = r.centerY(), w = r.width(), h = r.height(),
                crop = RectF(0f, 0f, r.width(), r.height()), scale = 1f, rot = 0f, flipX = false, flipY = false)
        }
    }

    fun undo() = history(scene.undo())
    fun redo() = history(scene.redo())
    private fun history(changed: Boolean) {
        if (!changed) return
        selection.retainAll(scene.items.map { it.id }.toSet())
        cropId = -1
        commit()
    }

    fun delete() {
        if (selection.isEmpty()) return
        val ids = withDescendants(selection)
        scene.checkpoint()
        scene.items.removeAll { it.id in ids }
        selection.clear()
        commit()
    }

    fun duplicate() {
        val ids = withDescendants(selection).ifEmpty { return }
        val map = ids.associateWith { scene.newId() }
        val d = 20f / camScale()
        val copies = scene.items.filter { it.id in ids }.map { it.copy(id = map.getValue(it.id), parent = map[it.parent] ?: it.parent).moveBy(d, d) }
        scene.checkpoint()
        scene.items += copies
        select(copies.filter { c -> map.entries.first { it.value == c.id }.key in selection })
        commit()
    }

    fun selectAll() = select(scene.items.filter { !it.locked && it.parent == 0L })
    fun clearSelection() { entered = -1; select(emptyList()) }
    fun select(items: List<Item>) {
        selection.clear()
        items.mapTo(selection) { it.id }
        invalidate()
        host?.selectionChanged()
    }

    private fun bringSelectionToFront() {
        val ids = withDescendants(selection)
        val (sel, rest) = scene.items.partition { it.id in ids }
        scene.items.clear(); scene.items += rest + sel
    }

    fun toFront() { scene.checkpoint(); bringSelectionToFront(); commit() }
    fun toBack() {
        scene.checkpoint()
        val ids = withDescendants(selection)
        val (sel, rest) = scene.items.partition { it.id in ids }
        scene.items.clear(); scene.items += sel + rest
        commit()
    }

    fun toggleLock() { edit { it.copy(locked = !it.locked) }; clearSelection() }
    fun unlock(item: Item) { apply(listOf(item.copy(locked = false))); select(listOf(item)) }
    fun beginOpacity() = scene.checkpoint()
    fun setOpacity(v: Float) = apply(selected().map { it.copy(opacity = v) }, undoable = false)
    fun editNote(id: Long, text: String, spans: String) = scene.items.find { it.id == id }?.let { apply(listOf(it.keepCenter(it.withText(text, spans)))) }
    fun rename(item: Item, name: String) = apply(listOf(item.copy(name = name)))
    fun setLoop(item: Item, a: Float, b: Float, undoable: Boolean) {
        apply(listOf(item.copy(loopA = a, loopB = b)), undoable)
        vids[item.id]?.let { it.loopA = a; it.loopB = b }
    }

    // ---- groups, parenting, hierarchy

    /** Selected items that don't have a selected ancestor. */
    private fun selectionRoots(): List<Item> {
        val m = byId()
        return selected().filter { s -> var p = m[s.parent]; var top = true; while (p != null) { if (p.id in selection) { top = false; break }; p = m[p.parent] }; top }
    }

    fun group(name: String) {
        val roots = selectionRoots().ifEmpty { return }
        val ids = roots.map { it.id }.toSet()
        val parents = roots.map { it.parent }.toSet()
        val gr = Item(scene.newId(), GROUP, 1f, 1f, parent = if (parents.size == 1) parents.first() else 0L, name = name, bgColor = 0x30808080)
        scene.checkpoint()
        scene.items.add(scene.items.indexOfFirst { it.id in ids }, gr)
        scene.items.replaceAll { if (it.id in ids) it.copy(parent = gr.id) else it }
        selection.clear(); selection += gr.id
        commit()
    }

    /** Ungroups selected groups; anything else selected is unparented. */
    fun ungroup() {
        val sel = selected().ifEmpty { return }
        val groups = sel.filter { it.type == GROUP }.associateBy { it.id }
        scene.checkpoint()
        scene.items.replaceAll {
            when {
                groups.containsKey(it.parent) -> it.copy(parent = groups.getValue(it.parent).parent)
                it.id in selection && it.type != GROUP -> it.copy(parent = 0)
                else -> it
            }
        }
        scene.items.removeAll { it.id in groups }
        selection.clear()
        commit()
    }

    /** Parents the other selected items to the first one selected. */
    fun parentToFirst() {
        val sel = selectedInOrder()
        if (sel.size < 2) return
        val p = sel[0]
        val m = byId()
        val ancestors = HashSet<Long>()
        var a = m[p.parent]
        while (a != null && ancestors.add(a.id)) a = m[a.parent]
        val kids = sel.drop(1).filter { it.id !in ancestors }.map { it.id }.toSet()
        scene.checkpoint()
        scene.items.replaceAll { if (it.id in kids) it.copy(parent = p.id) else it }
        commit()
    }

    fun unparent(item: Item) = apply(listOf(item.copy(parent = 0)))

    /** Parents [item] to [parentId], refusing loops (an item can't go under its own child). */
    fun setParent(item: Item, parentId: Long) {
        if (parentId in withDescendants(listOf(item.id))) return
        apply(listOf(item.copy(parent = parentId)))
    }

    // ---- copy / cut / paste items

    fun copySelection(): Boolean {
        val ids = withDescendants(selection)
        if (ids.isEmpty()) return false
        Boards.clip = scene.items.filter { it.id in ids }
        Boards.clipImages = scene.images
        return true
    }

    fun cutSelection() { if (copySelection()) delete() }

    /** Pastes copied items at the center of the view; image files come along when pasting into another scene. */
    fun pasteItems() {
        val clip = Boards.clip.ifEmpty { return }
        val src = Boards.clipImages
        if (src != null && src != scene.images) for (f in clip.map { it.file }.filter { it.isNotEmpty() }.distinct()) {
            val from = File(src, f)
            val to = File(scene.images, f)
            if (from.exists() && !to.exists()) from.copyTo(to)
        }
        val map = clip.associate { it.id to scene.newId() }
        val roots = clip.filter { it.parent !in map }
        val r = roots.union()
        val c = toBoard(viewW() / 2f, viewH() / 2f)
        val copies = clip.map { it.copy(id = map.getValue(it.id), parent = map[it.parent] ?: 0L).moveBy(c[0] - r.centerX(), c[1] - r.centerY()) }
        scene.checkpoint()
        scene.items += copies
        selection.clear()
        roots.mapTo(selection) { map.getValue(it.id) }
        commit()
    }

    /** Moves an item up or down among its siblings (hierarchy order = layer order). */
    fun moveAmongSiblings(item: Item, d: Int) {
        val sib = scene.items.filter { it.parent == item.parent }
        val i = sib.indexOfFirst { it.id == item.id }
        val other = sib.getOrNull(i + d) ?: return
        scene.checkpoint()
        val a = scene.items.indexOfFirst { it.id == item.id }
        val b = scene.items.indexOfFirst { it.id == other.id }
        scene.items[a] = other; scene.items[b] = item
        commit()
    }

    /** The item a tap lands on: closed groups catch taps on their children. */
    private fun resolve(it: Item): Item {
        val m = byId()
        var top = it
        var p = m[it.parent]
        while (p != null) { if (p.type == GROUP && p.closed && p.id != entered) top = p; p = m[p.parent] }
        return top
    }

    // ---- arrange

    fun arrange(mode: String) {
        val t = targets().ifEmpty { return }
        val r = t.union()
        var order = when (mode) {
            "optimal" -> t.sortedByDescending { it.bounds().height() }
            "name" -> t.sortedBy { it.name.ifEmpty { it.file }.lowercase() }
            "addition" -> t.sortedBy { it.id }
            "path" -> t.sortedBy { it.source.lowercase() }
            "order" -> ordered().filter { o -> t.any { it.id == o.id } }
            else -> t.shuffled()
        }
        // same arrange twice in a row = reversed order, like PureRef
        if (mode == lastArrange && mode != "random") { order = order.reversed(); lastArrange = "" } else lastArrange = mode
        moveWithChildren(Arrange.rows(order, viewW().toFloat() / viewH(), r.left, r.top))
    }

    fun align(dir: Int) = moveWithChildren(Arrange.align(targets(), dir))
    fun distribute(horizontal: Boolean) = moveWithChildren(Arrange.distribute(targets(), horizontal))
    fun stack() = moveWithChildren(Arrange.stack(targets()))
    fun normalize(kind: String) = targets().takeIf { it.size > 1 }?.let { apply(Arrange.normalize(it, kind, Prefs.normalizeFirst)) }

    /** Applies moved items, dragging their children along. */
    private fun moveWithChildren(moved: List<Item>) {
        val old = byId()
        val shift = HashMap<Long, FloatArray>()
        moved.forEach { n -> old[n.id]?.let { o -> shift[n.id] = floatArrayOf(n.x - o.x, n.y - o.y) } }
        val all = HashMap<Long, Item>()
        for ((id, d) in shift) for (k in withDescendants(listOf(id))) {
            val item = all[k] ?: old.getValue(k)
            all[k] = if (k == id) moved.first { it.id == id } else item.moveBy(d[0], d[1])
        }
        apply(all.values.toList())
    }

    // ---- adding

    /** Copies images and videos into the scene folder (so the board survives the originals being deleted), then places them. */
    fun addImages(srcs: List<Src>, at: FloatArray? = null, done: (Int) -> Unit = {}) {
        val sc = scene
        thread {
            val made = srcs.mapNotNull { s ->
                runCatching {
                    val f = File(sc.images, UUID.randomUUID().toString())
                    s.open()!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                    val img = imageSize(f)
                    val vid = if (img == null) videoInfo(f) else null
                    when {
                        img != null -> Item(0, if (isAnimatedGif(f)) GIF else IMAGE, img.first.toFloat(), img.second.toFloat(),
                            file = f.name, name = s.name, source = s.source, nearest = Prefs.nearest)
                        vid != null -> Item(0, VIDEO, vid.first.toFloat(), vid.second.toFloat(), file = f.name, name = s.name, source = s.source)
                        else -> { f.delete(); null }
                    }
                }.getOrNull()
            }
            post {
                if (sc === scene && made.isNotEmpty()) place(made.map { it.copy(id = scene.newId()) }, at)
                done(made.size)
            }
        }
    }

    fun addNote(text: String, spans: String = "", parentToSelection: Boolean = true) {
        val (w, h) = noteSize(text, spans)
        val parent = if (parentToSelection) selected().singleOrNull()?.takeIf { it.type != NOTE }?.id ?: 0L else 0L
        place(listOf(Item(scene.newId(), NOTE, w, h, text = text, spans = spans, parent = parent)))
    }

    /** New items: sized to the screen, packed (PureRef's "select and pack new images"), centered, selected. */
    private fun place(new: List<Item>, at: FloatArray? = null) {
        val s = camScale()
        val sized = new.map {
            val k = if (it.type == NOTE) 0.7f * dp / s else min(1f, viewW() * 0.5f / (max(it.w, it.h) * s))
            it.copy(scale = k)
        }
        val packed = if (Prefs.packNew) Arrange.rows(
            when (Prefs.packBy) { 1 -> sized.sortedBy { it.name.lowercase() }; 2 -> sized; else -> sized.sortedByDescending { it.bounds().height() } },
            viewW().toFloat() / viewH(), 0f, 0f,
        ) else sized.mapIndexed { i, it -> it.moveBy(i * 30f / s, i * 30f / s) }
        val r = packed.union()
        val c = at?.let { toBoard(it[0], it[1]) } ?: toBoard(viewW() / 2f, viewH() / 2f) // dropped items land under the finger
        val placed = packed.map { it.moveBy(c[0] - r.centerX(), c[1] - r.centerY()) }
        scene.checkpoint()
        scene.items += placed
        selection.clear()
        placed.mapTo(selection) { it.id }
        commit()
    }

    fun replaceImage(item: Item, src: Src) {
        val sc = scene
        thread {
            val f = File(sc.images, UUID.randomUUID().toString())
            val size = runCatching { src.open()!!.use { i -> f.outputStream().use { i.copyTo(it) } }; imageSize(f) }.getOrNull()
            post {
                if (size == null) { f.delete(); return@post }
                if (sc !== scene) return@post
                val (w, h) = size.first.toFloat() to size.second.toFloat()
                val n = item.copy(file = f.name, w = w, h = h, crop = RectF(0f, 0f, w, h), name = src.name, source = src.source,
                    type = if (isAnimatedGif(f)) GIF else IMAGE, scale = item.scale * item.crop.width() / w)
                gifs.remove(item.id)
                apply(listOf(item.keepCenter(n)))
            }
        }
    }

    // ---- crop

    fun startCrop() {
        val it = selected().singleOrNull { it.isMedia } ?: return
        scene.checkpoint()
        cropId = it.id
        invalidate()
        host?.selectionChanged()
    }

    fun endCrop() { cropId = -1; commit() }
    fun resetCrop() = apply(selected().map { it.copy(crop = RectF(0f, 0f, it.w, it.h)) })

    // ---- GIF and video

    fun gifOf(it: Item): Gif? = if (it.type != GIF) null else gifs[it.id] ?: run {
        if (loading.add("gif:${it.id}")) {
            val f = File(scene.images, it.file)
            val id = it.id
            loader.execute { val gf = Gif(f); post { gifs[id] = gf; invalidate() } }
        }
        null
    }

    fun vidOf(it: Item): Vid? {
        if (it.type != VIDEO || Build.VERSION.SDK_INT < 29) return null
        return vids.getOrPut(it.id) {
            Vid(File(scene.images, it.file), it.w.toInt(), it.h.toInt()) { postInvalidate() }.apply { loopA = it.loopA; loopB = it.loopB }
        }
    }

    fun releaseMedia() {
        vids.values.forEach { it.release() }
        vids.clear()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseMedia()
        removeCallbacks(slideNext)
    }

    /** Current frame as a new image next to the GIF / video. */
    fun frameToImage(item: Item) {
        val sc = scene
        val gif = gifs[item.id]
        val pos = vids[item.id]?.position ?: 0
        thread {
            val b = if (item.type == GIF) gif?.bitmap() else videoFrame(File(sc.images, item.file), pos.toLong())
            b ?: return@thread
            val f = File(sc.images, UUID.randomUUID().toString())
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            post {
                if (sc !== scene) return@post
                val n = Item(scene.newId(), IMAGE, b.width.toFloat(), b.height.toFloat(), file = f.name, name = item.name + " frame",
                    scale = item.scale * item.w / b.width, rot = item.rot).let { it.moveBy(item.x + item.bounds().width() * 0.6f, item.y) }
                scene.checkpoint()
                scene.items += n
                select(listOf(n))
                commit()
            }
        }
    }

    // ---- drawing

    fun startDrawing(edit: Item? = null) {
        drawing = true
        cropId = -1
        drawTarget = edit?.id ?: -1L
        drawParent = if (edit == null) selected().singleOrNull()?.takeIf { it.type != DRAW }?.id ?: 0L else 0L
        invalidate()
        host?.selectionChanged()
    }

    fun stopDrawing() {
        drawing = false
        stroke = null
        scene.items.find { it.id == drawTarget }?.let { select(listOf(it)) }
        drawTarget = -1
        commit()
    }

    private fun finishStroke() {
        val pts = stroke?.toFloatArray() ?: return
        stroke = null
        if (pts.size < 2) return
        val s = camScale()
        val target = scene.items.find { it.id == drawTarget }
            ?: Item(scene.newId(), DRAW, 0f, 0f, x = pts[0], y = pts[1], parent = drawParent, name = context.getString(R.string.drawing))
        val inv = Matrix().also { target.matrix().invert(it) }
        val local = FloatArray(pts.size).also { inv.mapPoints(it, pts) }
        val tool = Prefs.penTool
        val st = Stroke(tool, Prefs.penColor, Prefs.penWidth * dp / s / target.scale, Prefs.penStyle,
            if (tool == T_RECT || tool == T_ELLIPSE || tool == T_LINE) floatArrayOf(local[0], local[1], local[local.size - 2], local[local.size - 1]) else local)
        val updated = target.withStrokes(target.strokes + st)
        scene.checkpoint()
        if (drawTarget == target.id) scene.items.replaceAll { if (it.id == target.id) updated else it }
        else { scene.items += updated; drawTarget = updated.id }
        commit()
    }

    private fun erase(sx: Float, sy: Float) {
        val target = scene.items.find { it.id == drawTarget } ?: ordered().lastOrNull { it.type == DRAW && under(it, sx, sy) } ?: return
        val p = local(target, sx, sy) ?: return
        val r = 14 * dp / (target.scale * camScale())
        val keep = target.strokes.filter { distance(it.outline(), p[0], p[1]) > r + it.width / 2 }
        if (keep.size == target.strokes.size) return
        if (!erasedOnce) { scene.checkpoint(); erasedOnce = true }
        if (keep.isEmpty()) scene.items.removeAll { it.id == target.id } else scene.items.replaceAll { if (it.id == target.id) target.withStrokes(keep) else it }
    }

    // ---- slideshow

    fun startSlideshow() {
        val base = targets().filter { it.type != GROUP }.ifEmpty { scene.items.filter { it.type != GROUP } }.map { it.id }.toSet()
        val order = ordered().filter { it.id in base }.map { it.id }
        if (order.isEmpty()) return
        slides = if (Prefs.slideOrder == 1) order.shuffled() else order
        slideIndex = 0
        slidePaused = false
        showSlide()
    }

    private fun showSlide() {
        val it = byId()[slides.getOrNull(slideIndex)] ?: return stopSlideshow()
        focused = null
        animateTo(fit(listOf(it).union()))
        removeCallbacks(slideNext)
        if (!slidePaused) postDelayed(slideNext, Prefs.slideSeconds * 1000L)
        host?.selectionChanged()
    }

    fun slideStep(d: Int) {
        if (slides.isEmpty()) return
        slideIndex = if (Prefs.slideOrder == 2 && d > 0) Random.nextInt(slides.size) else Math.floorMod(slideIndex + d, slides.size)
        if (Prefs.slideOrder == 1 && d > 0 && slideIndex == 0) slides = slides.shuffled() // shuffle: each once per loop
        showSlide()
    }

    fun toggleSlidePause() {
        slidePaused = !slidePaused
        removeCallbacks(slideNext)
        if (!slidePaused) postDelayed(slideNext, Prefs.slideSeconds * 1000L)
        host?.selectionChanged()
    }

    fun stopSlideshow() {
        if (slides.isEmpty()) return
        slides = emptyList()
        removeCallbacks(slideNext)
        host?.selectionChanged()
    }

    // ---- camera

    fun focus(items: List<Item>) {
        if (items.isEmpty()) return
        val ids = items.map { it.id }.toSet()
        if (ids == focused) { beforeFocus?.let(::animateTo); focused = null; return }
        if (focused == null) beforeFocus = FloatArray(9).also(cam::getValues)
        focused = ids
        animateTo(fit(items.union()))
    }

    fun focusAll() = focus(scene.items)

    /** Fits everything without animating (floating window start). */
    fun fitAll() { if (scene.items.isNotEmpty()) cam.setValues(fit(scene.items.union())); invalidate() }

    /** Next/previous image in the order added. */
    fun focusStep(d: Int) {
        val all = scene.items.filter { it.type != GROUP }.sortedBy { it.id }.ifEmpty { return }
        val cur = all.indexOfFirst { it.id == selection.firstOrNull() }
        val n = all[Math.floorMod(if (cur < 0 && d < 0) 0 else cur + d, all.size)]
        select(listOf(n))
        focused = null
        focus(listOf(n))
    }

    fun resetZoom() { val s = camScale(); cam.postScale(1 / s, 1 / s, viewW() / 2f, viewH() / 2f); focused = null; invalidate(); scene.save() }
    fun resetCamera() { cam.reset(); focused = null; invalidate(); scene.save() }

    private fun fit(r: RectF): FloatArray {
        val s = (min(viewW() / r.width().coerceAtLeast(1f), viewH() / r.height().coerceAtLeast(1f)) * 0.9f).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return floatArrayOf(s, 0f, viewW() / 2f - r.centerX() * s, 0f, s, viewH() / 2f - r.centerY() * s, 0f, 0f, 1f)
    }

    private fun animateTo(t: FloatArray) {
        val from = FloatArray(9).also(cam::getValues)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                cam.setValues(FloatArray(9) { from[it] + (t[it] - from[it]) * f })
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { scene.save() } })
            start()
        }
    }

    private fun zoomAt(px: Float, py: Float, ds: Float) {
        val s = camScale()
        val k = (s * ds).coerceIn(MIN_ZOOM, MAX_ZOOM) / s
        cam.postScale(k, k, px, py)
        focused = null
    }

    // ---- drawing on screen

    private fun bitmap(it: Item): Bitmap? {
        val f = File(scene.images, it.file)
        val key = f.path
        bitmaps[key]?.let { b -> return b }
        if (loading.add(key)) {
            val video = it.type == VIDEO
            loader.execute {
                // ponytail: one cached size per image (the quality setting); add tiles if deep zoom needs more
                val b = if (video) videoFrame(f) else decode(f, Prefs.quality)
                post { if (b != null) bitmaps[key] = b; invalidate() }
            }
        }
        return null
    }

    override fun onDraw(c: Canvas) {
        val bg = Prefs.canvasColor()
        when {
            !floating -> c.drawColor(bg)
            background == 0 -> c.drawColor(bg)
            background == 1 -> c.drawColor((bg and 0xFFFFFF) or 0x80000000.toInt())
            else -> c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
        if (!floating || background == 0) drawGrid(c)
        val s = camScale()
        val gray = Prefs.canvasGray
        val now = SystemClock.uptimeMillis()
        var animating = false
        val live = HashSet<Long>()
        c.save()
        c.concat(cam)
        for (it in ordered()) {
            live += it.id
            val gif = if (it.type == GIF) gifOf(it)?.also { gf -> if (gf.tick(now)) animating = true } else null
            val vid = vidOf(it)?.also { v -> v.tick() }
            val bmp = when (it.type) { IMAGE, GIF -> bitmap(it); VIDEO -> vid?.frame ?: bitmap(it); else -> null }
            if (it.id == cropId) { // the cropped-away part, faded
                c.save(); c.concat(it.matrix()); painter.content(c, it, bmp, 70, gray || it.gray, gif); c.restore()
            }
            painter.item(c, it, bmp, gray, gif)
            val sel = it.id in selection || it.id == cropId || (drawing && it.id == drawTarget)
            if (sel || (Prefs.borders == 0 && it.isMedia)) {
                c.save()
                c.concat(it.matrix())
                val k = 1f / (it.scale * s)
                if (sel) {
                    outline.strokeWidth = 2 * dp * k
                    c.drawRect(it.crop, outline)
                } else {
                    border.strokeWidth = k
                    c.drawRect(it.crop, border)
                }
                if (it.id == cropId) {
                    val hs = 7 * dp * k
                    val r = it.crop
                    for (x in floatArrayOf(r.left, r.centerX(), r.right)) for (y in floatArrayOf(r.top, r.centerY(), r.bottom))
                        if (x != r.centerX() || y != r.centerY()) c.drawRect(x - hs, y - hs, x + hs, y + hs, handle)
                }
                c.restore()
            }
        }
        for (it in scene.items) if (it.comment.isNotEmpty()) commentBadge(c, it)
        stroke?.takeIf { it.size >= 2 && Prefs.penTool != T_ERASER }?.let { pts ->
            val p = pts.toFloatArray()
            val tool = Prefs.penTool
            painter.stroke(c, Stroke(tool, Prefs.penColor, Prefs.penWidth * dp / s, Prefs.penStyle,
                if (tool == T_PEN) p else floatArrayOf(p[0], p[1], p[p.size - 2], p[p.size - 1])), 255, null)
        }
        c.restore()
        if (dropHint) {
            outline.strokeWidth = 4 * dp
            c.drawRect(2 * dp, 2 * dp, width - 2 * dp, height - 2 * dp, outline)
        }
        if (rubberOn) {
            val r = RectF(min(rubber.left, rubber.right), min(rubber.top, rubber.bottom), max(rubber.left, rubber.right), max(rubber.top, rubber.bottom))
            c.drawRect(r, rubberFill)
            outline.strokeWidth = dp
            c.drawRect(r, outline)
        }
        vids.keys.filter { it !in live }.forEach { vids.remove(it)?.release() }
        if (animating) postInvalidateOnAnimation()
    }

    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() }
    private val badgeDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt() }

    /** Little speech bubble on the top-right corner of commented items (board coords, screen-sized). */
    private fun commentBadge(c: Canvas, it: Item) {
        val b = it.bounds() // on screen top-right, whatever the item's rotation or flip
        val p = floatArrayOf(b.right, b.top)
        val k = 1f / camScale()
        val r = 9 * dp * k
        c.drawCircle(p[0], p[1], r, badge)
        for (d in -1..1) c.drawCircle(p[0] + d * r * 0.45f, p[1], r * 0.14f, badgeDot)
    }

    /** Dot or line background (RefCanvas / VizRef style). Spacing grows ×5 when zoomed out, so it never turns into noise. */
    private fun drawGrid(c: Canvas) {
        val grid = Prefs.grid
        if (grid == 0) return
        val light = Prefs.theme == 1
        val s = camScale()
        var step = GRID
        while (step * s < 18 * dp) step *= 5
        val v = FloatArray(9).also(cam::getValues)
        val tl = toBoard(0f, 0f)
        val br = toBoard(width.toFloat(), height.toFloat())
        val i0 = floor(tl[0] / step).toInt()
        val j0 = floor(tl[1] / step).toInt()
        val i1 = floor(br[0] / step).toInt() + 1
        val j1 = floor(br[1] / step).toInt() + 1
        if (grid == 1) {
            gridPaint.strokeWidth = 1f
            for (major in listOf(false, true)) {
                gridPaint.color = if (light) (if (major) 0x30000000 else 0x14000000) else (if (major) 0x30FFFFFF else 0x12FFFFFF)
                for (i in i0..i1) if ((Math.floorMod(i, 5) == 0) == major) { val x = i * step * s + v[2]; c.drawLine(x, 0f, x, height.toFloat(), gridPaint) }
                for (j in j0..j1) if ((Math.floorMod(j, 5) == 0) == major) { val y = j * step * s + v[5]; c.drawLine(0f, y, width.toFloat(), y, gridPaint) }
            }
        } else {
            gridPaint.color = if (light) 0x38000000 else 0x38FFFFFF
            gridPaint.strokeWidth = 2.6f * dp
            val pts = FloatArray(((i1 - i0 + 1) * (j1 - j0 + 1) * 2).coerceAtMost(400_000))
            var n = 0
            loop@ for (i in i0..i1) for (j in j0..j1) {
                if (n + 2 > pts.size) break@loop
                pts[n++] = i * step * s + v[2]; pts[n++] = j * step * s + v[5]
            }
            c.drawPoints(pts, 0, n, gridPaint)
        }
    }

    // ---- touch

    private fun local(it: Item, sx: Float, sy: Float): FloatArray? {
        val inv = Matrix()
        if (!it.matrix().apply { postConcat(cam) }.invert(inv)) return null
        return floatArrayOf(sx, sy).also(inv::mapPoints)
    }

    /** Drawings only catch touches near their strokes, so they don't block what's under them. */
    private fun under(it: Item, sx: Float, sy: Float): Boolean {
        val p = local(it, sx, sy) ?: return false
        if (!it.crop.contains(p[0], p[1])) return false
        if (it.type != DRAW) return true
        val tol = 16 * dp / (it.scale * camScale())
        return it.strokes.any { s -> distance(s.outline(), p[0], p[1]) < tol + s.width / 2 }
    }
    private fun hitAny(sx: Float, sy: Float) = ordered().lastOrNull { under(it, sx, sy) }
    private fun hitRaw(sx: Float, sy: Float) = ordered().lastOrNull { !it.locked && under(it, sx, sy) }
    private fun hit(sx: Float, sy: Float) = hitRaw(sx, sy)?.let(::resolve)

    // centroid x, centroid y, average span, angle of the first two pointers; skip = pointer being lifted
    private fun gesture(e: MotionEvent, skip: Int): FloatArray {
        val idx = (0 until e.pointerCount).filter { it != skip }
        val cx = idx.map { e.getX(it) }.average().toFloat()
        val cy = idx.map { e.getY(it) }.average().toFloat()
        val span = idx.map { hypot(e.getX(it) - cx, e.getY(it) - cy) }.average().toFloat()
        val ang = if (idx.size < 2) 0f else Math.toDegrees(
            atan2((e.getY(idx[1]) - e.getY(idx[0])).toDouble(), (e.getX(idx[1]) - e.getX(idx[0])).toDouble())
        ).toFloat()
        return floatArrayOf(cx, cy, span, ang)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (g == G.DRAW) { stroke = null; g = G.CAMERA; moved = true } // second finger while drawing = pan/zoom
                if (g == G.ITEMS || g == G.CAMERA) startMove()
                last = gesture(e, -1)
            }
            MotionEvent.ACTION_POINTER_UP -> last = gesture(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> move(e)
            MotionEvent.ACTION_UP -> up(e)
            MotionEvent.ACTION_CANCEL -> { rubberOn = false; stroke = null; if (moved) scene.save(); g = G.NONE }
        }
        invalidate()
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(e)
        zoomAt(e.x, e.y, if (e.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0) 1.15f else 1 / 1.15f)
        invalidate()
        return true
    }

    private fun down(e: MotionEvent) {
        downX = e.x; downY = e.y
        moved = false
        last = gesture(e, -1)
        g = when {
            picking -> { pick(e.x, e.y); G.PICK }
            drawing -> {
                erasedOnce = false
                if (Prefs.penTool == T_ERASER) erase(e.x, e.y) else stroke = arrayListOf<Float>().also { toBoard(e.x, e.y).forEach(it::add) }
                G.DRAW
            }
            cropId >= 0 -> if (cropDown(e.x, e.y)) G.CROP else G.CAMERA
            else -> {
                downHit = if (Prefs.canvasLocked || viewOnly) null else hit(e.x, e.y)
                if (downHit != null) G.ITEMS else G.CAMERA
            }
        }
    }

    private fun startMove() {
        if (moved) return
        moved = true
        if (g != G.ITEMS) return
        val h = downHit ?: return
        if (h.id !in selection) select(listOf(h))
        if (Prefs.bringToFront) bringSelectionToFront()
        scene.checkpoint()
        val ids = withDescendants(selected().filter { !it.locked }.map { it.id })
        raw = HashMap(scene.items.filter { it.id in ids }.associateBy { it.id })
    }

    private fun move(e: MotionEvent) {
        if (!moved) {
            if (e.pointerCount == 1 && hypot(e.x - downX, e.y - downY) < slop) return
            startMove()
        }
        when (g) {
            G.PICK -> pick(e.x, e.y)
            G.CROP -> cropMove(e.getX(0), e.getY(0))
            G.RUBBER -> { rubber.right = e.x; rubber.bottom = e.y }
            G.DRAW -> if (Prefs.penTool == T_ERASER) erase(e.x, e.y) else stroke?.let { s ->
                val p = toBoard(e.x, e.y)
                if (Prefs.penTool == T_PEN) { s += p[0]; s += p[1] } else { while (s.size > 2) s.removeAt(s.size - 1); s += p[0]; s += p[1] }
            }
            G.CAMERA, G.ITEMS -> transform(e)
            G.NONE -> {}
        }
    }

    private fun transform(e: MotionEvent) {
        val n = gesture(e, -1)
        val multi = e.pointerCount > 1
        val dx = n[0] - last[0]
        val dy = n[1] - last[1]
        val ds = if (multi && last[2] > 0f) n[2] / last[2] else 1f
        var dr = if (multi) n[3] - last[3] else 0f
        if (dr > 180) dr -= 360
        if (dr < -180) dr += 360
        last = n
        if (g == G.CAMERA) {
            if (slides.isNotEmpty() && !slidePaused) toggleSlidePause() // navigating pauses the slideshow, like PureRef
            cam.postTranslate(dx, dy)
            zoomAt(n[0], n[1], ds)
            return
        }
        val p = toBoard(n[0], n[1])
        val s = camScale()
        raw.replaceAll { _, it -> it.transformed(p[0], p[1], ds, dr, dx / s, dy / s) }
        val single = raw.size == 1
        scene.items.replaceAll { item -> raw[item.id]?.let { if (single) it.copy(rot = snapAngle(it.rot)) else it } ?: item }
    }

    // sticks to multiples of 45° when within 4°
    private fun snapAngle(r: Float): Float {
        val n = (r / 45f).roundToInt() * 45f
        return if (abs(r - n) < 4f) n else r
    }

    private fun up(e: MotionEvent) {
        when (g) {
            G.ITEMS -> if (!moved) downHit?.let { tap(it, e.x, e.y) } else {
                if (Prefs.snap) snapToGrid()
                dropIntoGroups()
                commit()
            }
            G.CAMERA -> if (!moved) { if (selection.isNotEmpty() || entered >= 0) clearSelection() } else scene.save()
            G.RUBBER -> { rubberOn = false; if (moved) selectRubber() else host?.canvasMenu() }
            G.CROP -> scene.save()
            G.DRAW -> if (Prefs.penTool == T_ERASER) { if (erasedOnce) commit() } else finishStroke()
            G.PICK, G.NONE -> {}
        }
        g = G.NONE
        raw.clear()
    }

    private fun tap(it: Item, sx: Float, sy: Float) {
        // a tap on the note you already selected can tick a checkbox or open a link
        if (it.type == NOTE && selection.size == 1 && it.id in selection && noteTap(it, sx, sy)) return
        select(listOf(it))
        if (Prefs.bringToFront) bringSelectionToFront()
    }

    private fun noteTap(it: Item, sx: Float, sy: Float): Boolean {
        val p = local(it, sx, sy) ?: return false
        val l = painter.layout(it)
        val line = l.getLineForVertical((p[1] - NOTE_PAD).toInt())
        val off = l.getOffsetForHorizontal(line, p[0] - NOTE_PAD)
        val t = it.text
        val ps = t.lastIndexOf('\n', l.getLineStart(line) - 1) + 1
        val box = t.getOrNull(ps)
        if ((box == Rich.UNCHECKED || box == Rich.CHECKED) && p[0] - NOTE_PAD < l.getPrimaryHorizontal(ps) + NOTE_TEXT * 1.5f) {
            val nt = t.substring(0, ps) + (if (box == Rich.UNCHECKED) Rich.CHECKED else Rich.UNCHECKED) + t.substring(ps + 1)
            apply(listOf(it.copy(text = nt)))
            return true
        }
        val url = Rich.build(t, it.spans).getSpans(off, off, URLSpan::class.java).firstOrNull()?.url ?: return false
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return true
    }

    private fun doubleTap(e: MotionEvent): Boolean {
        if (picking || cropId >= 0 || drawing) return false
        val raw = hitRaw(e.x, e.y)
        val top = raw?.let(::resolve)
        if (viewOnly) { if (raw == null) focusAll() else focus(listOf(top ?: raw)); return true }
        when {
            raw == null -> focusAll()
            top != null && top.id != raw.id && top.type == GROUP -> { entered = top.id; select(listOf(resolve(raw))) } // open the group
            raw.type == NOTE || raw.type == DRAW -> host?.editItem(raw)
            Prefs.doubleTapFocus -> focus(listOf(top ?: raw))
        }
        return true
    }

    private fun longPress(e: MotionEvent) {
        if (moved || picking || cropId >= 0 || drawing || viewOnly || g == G.NONE) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val any = hitAny(e.x, e.y)
        val h = downHit
        when {
            any != null && any.locked && h == null -> { g = G.NONE; host?.lockedPressed(any) }
            h != null && selection.size == 1 && h.id in selection && h.isMedia && dragOut(h) -> g = G.NONE
            h != null -> {
                if (!selection.remove(h.id)) selection += h.id
                g = G.NONE
                invalidate()
                host?.selectionChanged()
            }
            Prefs.canvasLocked -> { g = G.NONE; host?.canvasMenu() }
            else -> { g = G.RUBBER; rubberOn = true; rubber.set(e.x, e.y, e.x, e.y) }
        }
    }

    /** Starts a system drag of the image file, so it can be dropped into another app. */
    private fun dragOut(it: Item): Boolean {
        val f = File(scene.images, it.file)
        val thumb = bitmaps[f.path] ?: return false
        val mime = if (it.type == VIDEO) "video/mp4" else imageMime(f) ?: "image/png"
        val ext = mime.substringAfter('/').replace("jpeg", "jpg")
        val uri = Uri.Builder().scheme("content").authority("${context.packageName}.files")
            .appendPath(scene.dir.name).appendPath(it.file)
            .appendQueryParameter("name", "${it.name.ifEmpty { "image" }}.$ext").build()
        // ponytail: drags the original file; a cropped image goes out uncropped
        val clip = ClipData(ClipDescription(it.name, arrayOf(mime)), ClipData.Item(uri))
        return startDragAndDrop(clip, ThumbShadow(thumb, (160 * dp).toInt()), DRAG_SELF, DRAG_FLAG_GLOBAL or DRAG_FLAG_GLOBAL_URI_READ)
    }

    private fun selectRubber() {
        val a = toBoard(min(rubber.left, rubber.right), min(rubber.top, rubber.bottom))
        val b = toBoard(max(rubber.left, rubber.right), max(rubber.top, rubber.bottom))
        val r = RectF(a[0], a[1], b[0], b[1])
        select(scene.items.filter { !it.locked && RectF.intersects(r, it.bounds()) }.map(::resolve).distinctBy { it.id })
    }

    private fun snapToGrid() {
        scene.items.replaceAll { item ->
            if (item.id !in raw || item.parent in raw) item
            else item.bounds().let { b -> item.moveBy((b.left / GRID).roundToInt() * GRID - b.left, (b.top / GRID).roundToInt() * GRID - b.top) }
        }
    }

    /** Dropping an item inside a group adds it; dragging it out of its group removes it (PureRef's drag into groups). */
    private fun dropIntoGroups() {
        if (!Prefs.dragIntoGroups) return
        val moved = raw.keys
        val m = byId()
        val groups = ordered().filter { it.type == GROUP && it.id !in moved }
        scene.items.replaceAll { item ->
            if (item.id !in moved || item.parent in moved) return@replaceAll item
            val b = item.bounds()
            val target = groups.lastOrNull { it.bounds().contains(b.centerX(), b.centerY()) }
            val cur = m[item.parent]
            when {
                target != null -> item.copy(parent = target.id)
                cur?.type == GROUP -> item.copy(parent = cur.parent)
                else -> item
            }
        }
    }

    private fun cropDown(sx: Float, sy: Float): Boolean {
        val it = scene.items.find { it.id == cropId } ?: return false
        val p = local(it, sx, sy) ?: return false
        val tol = 24 * dp / (it.scale * camScale())
        val c = it.crop
        val inX = p[0] in c.left - tol..c.right + tol
        val inY = p[1] in c.top - tol..c.bottom + tol
        var m = 0
        if (inY && abs(p[0] - c.left) < tol) m = m or 1
        if (inX && abs(p[1] - c.top) < tol) m = m or 2
        if (inY && abs(p[0] - c.right) < tol) m = m or 4
        if (inX && abs(p[1] - c.bottom) < tol) m = m or 8
        if (m == 0 && c.contains(p[0], p[1])) m = 15
        cropEdges = m
        cropLast = p
        return m != 0
    }

    private fun cropMove(sx: Float, sy: Float) {
        val i = scene.items.indexOfFirst { it.id == cropId }.takeIf { it >= 0 } ?: return
        val it = scene.items[i]
        val p = local(it, sx, sy) ?: return
        val dx = p[0] - cropLast[0]
        val dy = p[1] - cropLast[1]
        cropLast = p
        val c = RectF(it.crop)
        val minSize = 8f
        if (cropEdges == 15) {
            c.offset(dx.coerceIn(-c.left, it.w - c.right), dy.coerceIn(-c.top, it.h - c.bottom))
        } else {
            if (cropEdges and 1 != 0) c.left = (c.left + dx).coerceIn(0f, c.right - minSize)
            if (cropEdges and 2 != 0) c.top = (c.top + dy).coerceIn(0f, c.bottom - minSize)
            if (cropEdges and 4 != 0) c.right = (c.right + dx).coerceIn(c.left + minSize, it.w)
            if (cropEdges and 8 != 0) c.bottom = (c.bottom + dy).coerceIn(c.top + minSize, it.h)
        }
        scene.items[i] = it.copy(crop = c)
    }

    /** Color and coordinates under the finger (PureRef's "show color code" + "show image coordinates"). */
    private fun pick(sx: Float, sy: Float) {
        val it = hitAny(sx, sy)
        val bg = Prefs.canvasColor()
        if (it == null) { val hex = "#%06X".format(bg and 0xFFFFFF); host?.picked(hex, hex); return }
        val p = local(it, sx, sy) ?: return
        val color = when {
            it.type == VIDEO -> vids[it.id]?.frame?.let { b -> runCatching { b.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }?.let { b -> pixel(b, it, p) } ?: Color.BLACK
            it.isMedia -> bitmaps[File(scene.images, it.file).path]?.let { b -> pixel(b, it, p) } ?: Color.BLACK
            else -> it.bgColor
        }
        val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
        val hex = "#%06X".format(color and 0xFFFFFF)
        host?.picked(
            "${it.name}  ${it.w.toInt()}×${it.h.toInt()}\n" +
                "X ${p[0].toInt()}  Y ${p[1].toInt()}\n" +
                "$hex   RGB ${Color.red(color)}, ${Color.green(color)}, ${Color.blue(color)}   " +
                "HSV ${hsv[0].roundToInt()}°, ${(hsv[1] * 100).roundToInt()}%, ${(hsv[2] * 100).roundToInt()}%",
            hex,
        )
    }

    private fun pixel(b: Bitmap, it: Item, p: FloatArray) =
        b.getPixel((p[0] * b.width / it.w).toInt().coerceIn(0, b.width - 1), (p[1] * b.height / it.h).toInt().coerceIn(0, b.height - 1))
}
