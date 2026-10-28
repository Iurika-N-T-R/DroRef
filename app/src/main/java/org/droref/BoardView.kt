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
import android.view.InputDevice
import android.view.KeyEvent
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
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

const val GRID = 50f
/** Marks drags that started in DroRef itself, so the board doesn't take its own drops. */
val DRAG_SELF = Any()
private const val MIN_ZOOM = 0.005f
private const val MAX_ZOOM = 64f
const val TOOL_SELECT = 0
const val TOOL_HAND = 1
const val TOOL_CONNECT = 2

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
                if (it.node) { // a node: rounded box
                    fill.color = it.bgColor; fill.alpha = Color.alpha(it.bgColor) * alpha / 255; fill.colorFilter = f
                    val r = min(it.h / 2, 22f)
                    c.drawRoundRect(0f, 0f, it.w, it.h, r, r, fill)
                } else rect(c, it, it.bgColor, alpha, f)
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
fun renderItems(items: List<Item>, images: File, maxSide: Int, gray: Boolean, links: List<Link> = emptyList()): Bitmap {
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
    drawLinks(c, LinkPainter(), links, items, 3f / s)
    return out
}

/**
 * The infinite board.
 * One finger: move what's under it (selection or camera). Two fingers: also scale + rotate.
 * Tap selects, long-press an item adds/removes it from the selection,
 * long-press empty space then drag draws a selection rectangle (release without dragging = canvas menu).
 * Double-tap focuses an item (again = back), opens a closed group, edits a note or drawing.
 * Mouse, as in PureRef: left-drag on empty space selects (Shift adds), Shift-click adds an item, Shift-drag moves in straight lines,
 * Ctrl-drag rotates, Ctrl+Alt-drag scales, middle / right / Alt-drag pans, right-click opens the menu, wheel zooms.
 * Touchpad: two-finger swipe pans, pinch zooms, Ctrl + swipe zooms.
 */
class BoardView(ctx: Context, scene: Scene, val floating: Boolean = false) : View(ctx) {
    interface Host {
        fun selectionChanged()
        fun lockedPressed(item: Item)
        fun canvasMenu()
        fun picked(info: String, hex: String)
        fun editItem(item: Item)
        fun itemMenu() {} // right-click on an item
        fun boardDrawn() {} // after every frame: the selection may have moved on screen
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
    /** Touch tool: select and move (default), hand (only pans and zooms: nothing moves by accident), connect (drag from item to item). */
    var tool = TOOL_SELECT
        set(v) { field = v; if (v != TOOL_SELECT) clearSelection(); invalidate() }
    var linkSel = -1L // a selected link (an item selection and a link selection don't mix)
        private set

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
    private enum class G { NONE, CAMERA, ITEMS, RUBBER, CROP, PICK, DRAW, ROTATE, SCALE, LINK, HANDLE_SCALE, HANDLE_ROTATE }
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

    // links and handles
    private val linkPainter = LinkPainter()
    private var linkFrom = -1L
    private val linkEnd = FloatArray(2) // board coords of the finger while dragging out a link
    private var handleBase: Map<Long, Item> = emptyMap() // the selection when a handle was grabbed
    private val handlePivot = FloatArray(2)
    private var handleStart = 0f // distance to the pivot (scale) or angle (rotate) when grabbed
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF4FA3FF.toInt() }
    private val sizeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val sizeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1E1E1E.toInt() }
    private val anchorDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF9800.toInt() }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val containerFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tagLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linkBadge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4F9DFF.toInt() }
    private val linkIcon by lazy { context.getDrawable(R.drawable.ic_link)!!.mutate().apply { setTint(Color.WHITE) } }
    private val rotateIcon by lazy { context.getDrawable(R.drawable.ic_rotate_cw)!!.mutate().apply { setTint(0xFF4FA3FF.toInt()) } }

    // mouse and touchpad (PureRef's controls)
    private var mouse = false
    private var pad = false // touchpad two-finger swipe or pinch: always the camera
    private var shift = false
    private var ctrl = false
    private var rightClick = false
    private var axis = 0 // Shift-drag: 1 horizontal, 2 vertical
    private var pivot = FloatArray(2) // board coords, for Ctrl-drag rotate / Ctrl+Alt-drag scale
    private var turned = 0f
    private var scaled = 1f

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

    /** Locked items and everything under them: a locked group locks its content too. */
    private fun frozen(): Set<Long> {
        val locked = scene.items.filter { it.locked }.map { it.id }
        return if (locked.isEmpty()) emptySet() else withDescendants(locked)
    }

    /** The topmost locked item that [it] is (or is inside), for "unlock?" on a long press. */
    private fun lockedOwner(it: Item): Item? {
        val m = byId()
        var top: Item? = null
        var p: Item? = it
        var n = 0
        while (p != null && n++ < 64) { if (p.locked) top = p; p = m[p.parent] }
        return top
    }

    /** Can be selected and moved: not locked (nor inside something locked), and not in a layer locked from "Verrouillage". */
    fun editable(it: Item, frozen: Set<Long> = frozen()) = it.id !in frozen && !(scene.lockVisuals && it.isVisual) && !(scene.lockNodes && it.type == NOTE)

    private fun targets(): List<Item> {
        val s = selected()
        if (s.size == 1 && s[0].type == GROUP) return children(s[0].id)
        val f = frozen()
        return s.ifEmpty { scene.items.filter { editable(it, f) && it.type != GROUP && it.parent == 0L } }
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

    /** Changes every selected item, keeping each one's visible center in place. Locked ones (picked in the hierarchy) stay as they are. */
    fun edit(f: (Item) -> Item) = frozen().let { fr -> apply(selected().filter { editable(it, fr) }.map { it.keepCenter(f(it)) }) }

    private fun commit() {
        fitGroups()
        // links whose item is gone go with it
        val ids = scene.items.mapTo(HashSet()) { it.id }
        scene.links.removeAll { it.from !in ids || it.to !in ids }
        if (scene.links.none { it.id == linkSel }) linkSel = -1
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
        if (linkSel >= 0) return deleteLink()
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
        scene.links += scene.links.filter { it.from in map && it.to in map }.map { it.copy(id = scene.newId(), from = map.getValue(it.from), to = map.getValue(it.to)) }
        select(copies.filter { c -> map.entries.first { it.value == c.id }.key in selection })
        commit()
    }

    fun selectAll() = frozen().let { f -> select(scene.items.filter { editable(it, f) && it.parent == 0L }) }
    fun clearSelection() { entered = -1; select(emptyList()) }
    fun select(items: List<Item>) {
        linkSel = -1
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

    fun toggleLock() { apply(selected().map { it.copy(locked = !it.locked) }); clearSelection() } // not edit(): it must reach locked items to unlock them
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
        Boards.clipLinks = scene.links.filter { it.from in ids && it.to in ids }
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
        scene.links += Boards.clipLinks.filter { it.from in map && it.to in map }.map { it.copy(id = scene.newId(), from = map.getValue(it.from), to = map.getValue(it.to)) }
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

    fun addNote(text: String, spans: String = "", parentToSelection: Boolean = true, node: Boolean = false) {
        val (w, h) = noteSize(text, spans)
        val from = selected().singleOrNull()
        val parent = if (parentToSelection && !node) from?.takeIf { it.type != NOTE }?.id ?: 0L else 0L
        val n = Item(scene.newId(), NOTE, w, h, text = text, spans = spans, parent = parent, node = node, bgColor = if (node) NODE_BG else 0xCC202020.toInt())
        place(listOf(n))
        // a new node hangs off what was selected
        if (node && from != null) { scene.links += Link(scene.newId(), from.id, n.id); commit() }
    }

    /** A node where a link was dropped on empty space, linked from [from]. Returns it for editing. */
    private fun addNodeAt(p: FloatArray, from: Long): Item {
        val text = context.getString(R.string.node)
        val (w, h) = noteSize(text)
        val n = Item(scene.newId(), NOTE, w, h, x = p[0], y = p[1], scale = 0.7f * dp / camScale(), text = text, node = true, bgColor = NODE_BG)
        scene.checkpoint()
        scene.items += n
        scene.links += Link(scene.newId(), from, n.id)
        select(listOf(n))
        commit()
        return n
    }

    // ---- links

    fun addLink(from: Long, to: Long) {
        if (from == to || scene.links.any { (it.from == from && it.to == to) || (it.from == to && it.to == from) }) return
        scene.checkpoint()
        scene.links += Link(scene.newId(), from, to)
        commit()
    }

    private fun editLink(f: (Link) -> Link) {
        val i = scene.links.indexOfFirst { it.id == linkSel }.takeIf { it >= 0 } ?: return
        scene.checkpoint()
        scene.links[i] = f(scene.links[i])
        commit()
    }

    fun reverseLink() = editLink { it.copy(from = it.to, to = it.from) }
    fun toggleBothEnds() = editLink { it.copy(both = !it.both) }
    fun setLinkColor(c: Int) = editLink { it.copy(color = c) }
    fun selectedLink() = scene.links.find { it.id == linkSel }

    fun deleteLink() {
        if (linkSel < 0) return
        scene.checkpoint()
        scene.links.removeAll { it.id == linkSel }
        linkSel = -1
        commit()
    }

    private fun curveOf(l: Link): FloatArray? {
        val a = scene.items.find { it.id == l.from } ?: return null
        val b = scene.items.find { it.id == l.to } ?: return null
        return linkCurve(a.bounds(), b.bounds())
    }

    /** The link under a screen point, if any. */
    private fun linkAt(sx: Float, sy: Float): Link? {
        if (scene.lockNodes) return null
        val tol = 14 * dp
        return scene.links.lastOrNull { l ->
            val pts = curveOf(l)?.let(::sample)?.also(cam::mapPoints) ?: return@lastOrNull false
            distance(pts, sx, sy) < tol
        }
    }

    private fun selectLink(l: Link) {
        selection.clear()
        linkSel = l.id
        invalidate()
        host?.selectionChanged()
    }

    // ---- quick transforms (the selection bar's rotate and size menus)

    private fun aroundSelection(f: (Item, Float, Float) -> Item) {
        val sel = selected().filter(::editable).ifEmpty { return }
        val ids = withDescendants(sel.map { it.id })
        val r = sel.union()
        scene.checkpoint()
        scene.items.replaceAll { if (it.id in ids) f(it, r.centerX(), r.centerY()) else it }
        commit()
    }

    fun rotateBy(deg: Float) = aroundSelection { it, cx, cy -> it.transformed(cx, cy, 1f, deg, 0f, 0f) }
    fun scaleBy(k: Float) = aroundSelection { it, cx, cy -> it.transformed(cx, cy, k, 0f, 0f, 0f) }
    fun resetRotation() = edit { it.copy(rot = 0f) }
    fun resetSize() = edit { it.copy(scale = 1f) }

    /** Where the selection (or the selected link's middle) is on screen, for the bar that floats above it. */
    fun selectionScreenRect(): RectF? {
        selectedLink()?.let { l ->
            val c = curveOf(l) ?: return null
            val p = sample(c, 2).also(cam::mapPoints)
            return RectF(p[2], p[3], p[2], p[3])
        }
        val sel = selected().ifEmpty { return null }
        return sel.union().also(cam::mapRect)
    }

    /** Size on the board of what's selected: "1080 × 2400", with a decimal while it isn't a whole number. */
    private fun sizeLabel(): String {
        val sel = selected()
        val one = sel.singleOrNull()
        val w = one?.let { it.crop.width() * it.scale } ?: sel.union().width()
        val h = one?.let { it.crop.height() * it.scale } ?: sel.union().height()
        fun f(v: Float) = if (abs(v - v.roundToInt()) < 0.05f) "${v.roundToInt()}" else String.format("%.1f", v)
        return "${f(w)} × ${f(h)}"
    }

    private fun showHandles() = selection.isNotEmpty() && linkSel < 0 && !drawing && cropId < 0 && slides.isEmpty() && !viewOnly && !picking && !Prefs.canvasLocked &&
        tool == TOOL_SELECT && selected().any(::editable)

    /** 0-3 = a corner (top-left, top-right, bottom-right, bottom-left), 4 = the rotate handle, -1 = none. */
    private fun handleAt(sx: Float, sy: Float): Int {
        if (!showHandles()) return -1
        val r = selectionScreenRect() ?: return -1
        val tol = 22 * dp
        if (hypot(sx - (r.right + 26 * dp), sy - r.centerY()) < tol) return 4
        val corners = floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom)
        for (i in 0 until 4) if (hypot(sx - corners[i * 2], sy - corners[i * 2 + 1]) < tol) return i
        return -1
    }

    private fun grabHandle(i: Int, sx: Float, sy: Float): G {
        val sel = selected().filter(::editable)
        val ids = withDescendants(sel.map { it.id })
        handleBase = scene.items.filter { it.id in ids }.associateBy { it.id }
        val r = sel.union()
        val p = toBoard(sx, sy)
        if (i == 4) {
            handlePivot[0] = r.centerX(); handlePivot[1] = r.centerY()
            handleStart = atan2(p[1] - handlePivot[1], p[0] - handlePivot[0])
            return G.HANDLE_ROTATE
        }
        // scaling from a corner keeps the opposite one in place
        val opposite = floatArrayOf(r.right, r.bottom, r.left, r.bottom, r.left, r.top, r.right, r.top)
        handlePivot[0] = opposite[i * 2]; handlePivot[1] = opposite[i * 2 + 1]
        handleStart = hypot(p[0] - handlePivot[0], p[1] - handlePivot[1]).coerceAtLeast(1f)
        return G.HANDLE_SCALE
    }

    private fun dragHandle(sx: Float, sy: Float) {
        val p = toBoard(sx, sy)
        val px = handlePivot[0]; val py = handlePivot[1]
        scene.items.replaceAll { item ->
            val base = handleBase[item.id] ?: return@replaceAll item
            if (g == G.HANDLE_SCALE) base.transformed(px, py, (hypot(p[0] - px, p[1] - py) / handleStart).coerceIn(0.02f, 50f), 0f, 0f, 0f)
            else {
                var d = Math.toDegrees((atan2(p[1] - py, p[0] - px) - handleStart).toDouble()).toFloat()
                val snap = (d / 45f).roundToInt() * 45f
                if (abs(d - snap) < 5f) d = snap // sticks at 45° steps
                base.transformed(px, py, 1f, d, 0f, 0f)
            }
        }
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

    fun zoomBy(k: Float) { zoomAt(viewW() / 2f, viewH() / 2f, k); invalidate(); scene.save() }
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
        if (Prefs.container && scene.items.isNotEmpty()) { // a frame around everything
            val r = scene.items.union().also(cam::mapRect).apply { inset(-24 * dp, -24 * dp) }
            containerFill.color = if (Prefs.theme == 1) 0x12000000 else 0x14000000
            c.drawRoundRect(r, 16 * dp, 16 * dp, containerFill)
            containerFill.color = if (Prefs.theme == 1) 0x1A000000 else 0x1AFFFFFF
            containerFill.style = Paint.Style.STROKE; containerFill.strokeWidth = dp
            c.drawRoundRect(r, 16 * dp, 16 * dp, containerFill)
            containerFill.style = Paint.Style.FILL
        }
        if (Prefs.centerCross) { // the board's origin
            val o = floatArrayOf(0f, 0f).also(cam::mapPoints)
            crossPaint.color = if (Prefs.theme == 1) 0x80000000.toInt() else 0x99FFFFFF.toInt()
            crossPaint.strokeWidth = 1.5f * dp
            val arm = 14 * dp
            c.drawLine(o[0] - arm, o[1], o[0] + arm, o[1], crossPaint)
            c.drawLine(o[0], o[1] - arm, o[0], o[1] + arm, crossPaint)
        }
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
            if (it.tag != 0) { // color label
                c.save(); c.concat(it.matrix())
                tagLine.color = it.tag; tagLine.strokeWidth = 4 * dp / (it.scale * s)
                c.drawRect(it.crop, tagLine)
                c.restore()
            }
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
        val items = scene.items
        drawLinks(c, linkPainter, scene.links, items, 2.5f * dp / s, linkSel, 0xFF4FA3FF.toInt())
        if (g == G.LINK) items.find { it.id == linkFrom }?.let { a -> // the link being dragged out
            linkPainter.draw(c, linkCurve(a.bounds(), RectF(linkEnd[0], linkEnd[1], linkEnd[0], linkEnd[1])), 0xFFFF9800.toInt(), 2.5f * dp / s, false, dashed = true)
        }
        val f = frozen()
        if (tool == TOOL_CONNECT && !scene.lockNodes) for (it in items) if (it.type != GROUP && editable(it, f)) // where links attach
            for (a in anchors(it)) c.drawCircle(a.x, a.y, 4 * dp / s, anchorDot)
        stroke?.takeIf { it.size >= 2 && Prefs.penTool != T_ERASER }?.let { pts ->
            val p = pts.toFloatArray()
            val tool = Prefs.penTool
            painter.stroke(c, Stroke(tool, Prefs.penColor, Prefs.penWidth * dp / s, Prefs.penStyle,
                if (tool == T_PEN) p else floatArrayOf(p[0], p[1], p[p.size - 2], p[p.size - 1])), 255, null)
        }
        c.restore()
        for (it in scene.items) if (it.url.isNotEmpty()) { // link badge, screen-sized
            val b = badgeAt(it)
            c.drawCircle(b[0], b[1], 11 * dp, linkBadge)
            val r = (7 * dp).toInt()
            linkIcon.setBounds(b[0].toInt() - r, b[1].toInt() - r, b[0].toInt() + r, b[1].toInt() + r)
            linkIcon.draw(c)
        }
        if (showHandles()) selectionScreenRect()?.let { r -> handles(c, r) }
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
        host?.boardDrawn()
    }

    /** Screen point of an item's link badge: just inside its top-left corner. */
    private fun badgeAt(it: Item): FloatArray {
        val b = it.bounds().also(cam::mapRect)
        return floatArrayOf(b.left + 14 * dp, b.top + 14 * dp)
    }

    /** Corner handles (drag to scale), the rotate handle on the right, and the size under the selection. */
    private fun handles(c: Canvas, r: RectF) {
        handleLine.strokeWidth = 1.5f * dp
        for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom)) {
            c.drawCircle(x, y, 7 * dp, handleFill)
            c.drawCircle(x, y, 7 * dp, handleLine)
        }
        val rx = r.right + 26 * dp
        c.drawCircle(rx, r.centerY(), 13 * dp, handleFill)
        val ir = (8 * dp).toInt()
        rotateIcon.setBounds(rx.toInt() - ir, r.centerY().toInt() - ir, rx.toInt() + ir, r.centerY().toInt() + ir)
        rotateIcon.draw(c)
        val label = sizeLabel()
        sizeText.textSize = 12 * dp
        val tw = sizeText.measureText(label)
        val ty = r.bottom + 26 * dp
        c.drawRoundRect(r.centerX() - tw / 2 - 8 * dp, ty - 15 * dp, r.centerX() + tw / 2 + 8 * dp, ty + 6 * dp, 6 * dp, 6 * dp, sizeBg)
        c.drawText(label, r.centerX(), ty, sizeText)
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
    private fun hitRaw(sx: Float, sy: Float): Item? { val f = frozen(); return ordered().lastOrNull { editable(it, f) && under(it, sx, sy) } }
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
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> return true
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
        zoomAt(e.x, e.y, 1.15f.pow(e.getAxisValue(MotionEvent.AXIS_VSCROLL))) // touchpads send fractions
        invalidate()
        return true
    }

    private fun MotionEvent.has(meta: Int) = metaState and meta != 0

    private fun touchpadGesture(e: MotionEvent) = Build.VERSION.SDK_INT >= 34 && e.classification.let {
        it == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE || it == MotionEvent.CLASSIFICATION_PINCH
    }

    private fun down(e: MotionEvent) {
        downX = e.x; downY = e.y
        moved = false
        last = gesture(e, -1)
        pad = touchpadGesture(e)
        mouse = !pad && e.isFromSource(InputDevice.SOURCE_MOUSE)
        shift = e.has(KeyEvent.META_SHIFT_ON)
        ctrl = e.has(KeyEvent.META_CTRL_ON)
        val b = e.buttonState
        rightClick = mouse && b and MotionEvent.BUTTON_SECONDARY != 0
        axis = 0
        g = when {
            picking -> { pick(e.x, e.y); G.PICK }
            pad -> G.CAMERA
            // middle button, right button or Alt + left: pan, everywhere (also while drawing or cropping)
            mouse && (b and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY) != 0 || e.has(KeyEvent.META_ALT_ON) && !ctrl && !shift) -> G.CAMERA
            drawing -> {
                erasedOnce = false
                if (Prefs.penTool == T_ERASER) erase(e.x, e.y) else stroke = arrayListOf<Float>().also { toBoard(e.x, e.y).forEach(it::add) }
                G.DRAW
            }
            cropId >= 0 -> if (cropDown(e.x, e.y)) G.CROP else G.CAMERA
            !mouse && handleAt(e.x, e.y) >= 0 -> grabHandle(handleAt(e.x, e.y), e.x, e.y)
            tool == TOOL_HAND -> G.CAMERA
            tool == TOOL_CONNECT && !viewOnly && !Prefs.canvasLocked -> {
                val from = if (scene.lockNodes) null else hit(e.x, e.y)?.takeIf { it.type != GROUP }
                if (from == null) G.CAMERA else { linkFrom = from.id; toBoard(e.x, e.y).copyInto(linkEnd); G.LINK }
            }
            else -> {
                downHit = if (Prefs.canvasLocked || viewOnly) null else hit(e.x, e.y)
                when {
                    !mouse -> if (downHit != null) G.ITEMS else G.CAMERA
                    downHit != null && ctrl -> if (e.has(KeyEvent.META_ALT_ON)) G.SCALE else G.ROTATE
                    downHit != null -> G.ITEMS
                    Prefs.canvasLocked || viewOnly -> G.CAMERA
                    else -> { rubberOn = true; rubber.set(e.x, e.y, e.x, e.y); G.RUBBER } // left-drag on empty space selects, like PureRef
                }
            }
        }
    }

    private fun startMove() {
        if (moved) return
        moved = true
        if (g == G.HANDLE_SCALE || g == G.HANDLE_ROTATE) { scene.checkpoint(); return }
        if (g != G.ITEMS && g != G.ROTATE && g != G.SCALE) return
        val h = downHit ?: return
        if (h.id !in selection) select(listOf(h))
        if (Prefs.bringToFront) bringSelectionToFront()
        scene.checkpoint()
        val ids = withDescendants(selected().filter { editable(it) }.map { it.id })
        raw = HashMap(scene.items.filter { it.id in ids }.associateBy { it.id })
        raw.values.toList().union().let { pivot = floatArrayOf(it.centerX(), it.centerY()) }
        turned = 0f; scaled = 1f
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
            G.ROTATE, G.SCALE -> turnOrScale(e)
            G.HANDLE_SCALE, G.HANDLE_ROTATE -> dragHandle(e.x, e.y)
            G.LINK -> toBoard(e.x, e.y).copyInto(linkEnd)
            G.NONE -> {}
        }
    }

    private fun transform(e: MotionEvent) {
        val n = gesture(e, -1)
        val multi = e.pointerCount > 1
        var dx = n[0] - last[0]
        var dy = n[1] - last[1]
        var ds = if (multi && last[2] > 0f) n[2] / last[2] else 1f
        if (pad && ctrl && !multi) { ds = exp(dy / (200 * dp)); dx = 0f; dy = 0f } // Ctrl + two-finger swipe zooms
        if (mouse && shift && g == G.ITEMS) { // Shift-drag: straight lines
            if (axis == 0) axis = if (abs(n[0] - downX) >= abs(n[1] - downY)) 1 else 2
            if (axis == 1) dy = 0f else dx = 0f
        }
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

    /** Mouse: Ctrl-drag rotates around the selection's center (Ctrl+Shift: 45° steps), Ctrl+Alt-drag scales (right = bigger). */
    private fun turnOrScale(e: MotionEvent) {
        val c = floatArrayOf(pivot[0], pivot[1]).also(cam::mapPoints)
        var ds = 1f
        var dr = 0f
        if (g == G.ROTATE) {
            fun ang(x: Float, y: Float) = Math.toDegrees(atan2((y - c[1]).toDouble(), (x - c[0]).toDouble())).toFloat()
            var total = ang(e.x, e.y) - ang(downX, downY)
            if (e.has(KeyEvent.META_SHIFT_ON)) total = (total / 45f).roundToInt() * 45f
            dr = total - turned
            turned = total
        } else {
            // ponytail: 150dp of drag doubles or halves; make it a setting if it feels wrong on big screens
            val total = 2f.pow((e.x - downX) / (150 * dp)).coerceIn(0.02f, 50f)
            ds = total / scaled
            scaled = total
        }
        raw.replaceAll { _, it -> it.transformed(pivot[0], pivot[1], ds, dr, 0f, 0f) }
        scene.items.replaceAll { item -> raw[item.id] ?: item }
    }

    // sticks to multiples of 45° when within 4°
    private fun snapAngle(r: Float): Float {
        val n = (r / 45f).roundToInt() * 45f
        return if (abs(r - n) < 4f) n else r
    }

    private fun up(e: MotionEvent) {
        when (g) {
            G.ITEMS, G.ROTATE, G.SCALE -> if (!moved) downHit?.let { if (mouse && shift) toggle(it) else tap(it, e.x, e.y) } else {
                if (Prefs.snap && g == G.ITEMS) snapToGrid()
                if (g == G.ITEMS) dropIntoGroups()
                commit()
            }
            G.CAMERA -> when {
                moved -> scene.save()
                rightClick -> if (!drawing) contextMenu(e.x, e.y)
                !drawing && !viewOnly && linkAt(e.x, e.y) != null -> selectLink(linkAt(e.x, e.y)!!)
                selection.isNotEmpty() || entered >= 0 || linkSel >= 0 -> clearSelection()
            }
            G.HANDLE_SCALE, G.HANDLE_ROTATE -> if (moved) commit()
            G.LINK -> {
                val to = hit(e.x, e.y)
                when {
                    !moved -> to?.let { select(listOf(it)) } // a tap still selects
                    to != null && to.id != linkFrom && to.type != GROUP -> addLink(linkFrom, to.id)
                    to == null -> host?.editItem(addNodeAt(linkEnd, linkFrom)) // dropped on empty space: a new node, named right away
                }
                linkFrom = -1
            }
            G.RUBBER -> {
                rubberOn = false
                when {
                    moved -> selectRubber(add = mouse && shift)
                    mouse -> if (!shift) clearSelection()
                    else -> host?.canvasMenu()
                }
            }
            G.CROP -> scene.save()
            G.DRAW -> if (Prefs.penTool == T_ERASER) { if (erasedOnce) commit() } else finishStroke()
            G.PICK, G.NONE -> {}
        }
        g = G.NONE
        raw.clear()
    }

    /** Right-click: the item's menu (selecting it first), or the canvas menu on empty space. */
    private fun contextMenu(sx: Float, sy: Float) {
        val h = hit(sx, sy) ?: return run { host?.canvasMenu() }
        if (h.id !in selection) select(listOf(h))
        host?.itemMenu()
    }

    private fun toggle(it: Item) {
        if (!selection.remove(it.id)) selection += it.id
        invalidate()
        host?.selectionChanged()
    }

    private fun tap(it: Item, sx: Float, sy: Float) {
        // the link badge in its corner opens the item's link
        if (it.url.isNotEmpty() && badgeAt(it).let { b -> hypot(sx - b[0], sy - b[1]) < 18 * dp }) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
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
        if (picking || cropId >= 0 || drawing || rightClick) return false
        val raw = hitRaw(e.x, e.y)
        val top = raw?.let(::resolve)
        if (viewOnly || Prefs.canvasLocked) { if (raw == null) focusAll() else focus(listOf(top ?: raw)); return true } // a locked canvas only looks
        when {
            raw == null -> focusAll()
            top != null && top.id != raw.id && top.type == GROUP -> { entered = top.id; select(listOf(resolve(raw))) } // open the group
            raw.type == NOTE || raw.type == DRAW -> host?.editItem(raw)
            Prefs.doubleTapFocus -> focus(listOf(top ?: raw))
        }
        return true
    }

    private fun longPress(e: MotionEvent) {
        if (moved || mouse || pad || picking || cropId >= 0 || drawing || viewOnly || g == G.NONE) return
        if (g == G.LINK || g == G.HANDLE_SCALE || g == G.HANDLE_ROTATE || tool == TOOL_HAND) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val any = hitAny(e.x, e.y)
        val h = downHit
        when {
            h == null && any?.let(::lockedOwner) != null -> { g = G.NONE; host?.lockedPressed(lockedOwner(any!!)!!) }
            h != null && selection.size == 1 && h.id in selection && h.isMedia && dragOut(h) -> g = G.NONE
            h != null -> { toggle(h); g = G.NONE }
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

    private fun selectRubber(add: Boolean = false) {
        val a = toBoard(min(rubber.left, rubber.right), min(rubber.top, rubber.bottom))
        val b = toBoard(max(rubber.left, rubber.right), max(rubber.top, rubber.bottom))
        val r = RectF(a[0], a[1], b[0], b[1])
        val f = frozen()
        val hits = scene.items.filter { editable(it, f) && RectF.intersects(r, it.bounds()) }.map(::resolve)
        select((if (add) selected() + hits else hits).distinctBy { it.id })
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
        val f = frozen()
        val groups = ordered().filter { it.type == GROUP && it.id !in moved && editable(it, f) } // a locked group takes nothing in
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
