package org.droref

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.graphics.drawable.GradientDrawable
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

private const val REQ_ADD = 1
private const val REQ_REPLACE = 2
private const val REQ_OPEN = 3
private const val REQ_SAVE_AS = 4
private const val REQ_PNG = 5
private const val REQ_IMAGES = 6
private const val REQ_SETTINGS_OUT = 7
private const val REQ_SETTINGS_IN = 8
private const val CLIP_LABEL = "droref-items"

class MainActivity : Activity(), BoardView.Host {
    private lateinit var board: BoardView
    private lateinit var bar: LinearLayout
    private lateinit var barScroll: HorizontalScrollView
    private lateinit var info: TextView
    private var infoHex = ""
    private var exportItems: List<Item> = emptyList()
    private val actions = HashMap<Int, () -> Unit>()
    private val menuEntries = ArrayList<Pair<String, () -> Unit>>() // for the command palette
    private val barEntries = ArrayList<Pair<String, () -> Unit>>()
    private var menuPrefix = ""
    private var exportJpg = false
    private lateinit var titleView: TextView
    private lateinit var menuBtn: TextView
    private lateinit var eyeBtn: TextView
    private lateinit var addBtn: TextView
    private lateinit var topLeft: LinearLayout
    private lateinit var topRight: LinearLayout
    private lateinit var undoBtns: List<TextView>
    private var focusMode = false

    private fun s(id: Int, vararg args: Any) = getString(id, *args)

    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.localized())

    private val light get() = Prefs.theme == 1
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()
    private val chipBg get() = if (light) 0xF2FFFFFF.toInt() else 0xF2404040.toInt() // stands out from the canvas
    private val chipLine get() = if (light) 0x22000000 else 0x33FFFFFF
    private val chipFg get() = if (light) 0xFF222222.toInt() else 0xFFFFFFFF.toInt()

    /** Round floating button, VizRef-style. */
    private fun round(label: String, desc: String, f: () -> Unit) = TextView(this).apply {
        text = label
        contentDescription = desc
        gravity = Gravity.CENTER
        textSize = 18f
        setTextColor(chipFg)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(chipBg); setStroke(px(1), chipLine) }
        elevation = 6 * dp
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(px(44), px(44)).apply { setMargins(px(4), px(4), px(4), px(4)) }
    }

    private fun pill() = GradientDrawable().apply { cornerRadius = 24 * dp; setColor(chipBg); setStroke(px(1), chipLine) }

    override fun onCreate(state: Bundle?) {
        Prefs.init(this)
        setTheme(if (light) android.R.style.Theme_DeviceDefault_Light_NoActionBar else android.R.style.Theme_DeviceDefault_NoActionBar)
        super.onCreate(state)
        val scene = Scene.current(this, s(R.string.untitled))
        board = BoardView(this, scene).apply { host = this@MainActivity }
        info = TextView(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0xE6000000.toInt()) }
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(px(14), px(10), px(14), px(10))
            elevation = 6 * dp
            setOnClickListener { copyText(infoHex) }
        }
        bar = LinearLayout(this).apply { setPadding(px(6), 0, px(6), 0) }
        barScroll = HorizontalScrollView(this).apply {
            addView(bar)
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            background = pill()
            elevation = 8 * dp
        }
        // canvas fills the screen; everything else floats over it (VizRef's minimal UI)
        titleView = TextView(this).apply {
            setTextColor(chipFg)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = px(200)
            gravity = Gravity.CENTER_VERTICAL
            background = pill()
            elevation = 6 * dp
            setPadding(px(14), 0, px(14), 0)
            setOnClickListener { openScene() }
            layoutParams = LinearLayout.LayoutParams(-2, px(44)).apply { setMargins(px(4), px(4), px(4), px(4)) }
        }
        menuBtn = round("☰", s(R.string.settings)) { openMenu() }
        topLeft = LinearLayout(this).apply { addView(menuBtn); addView(titleView) }
        eyeBtn = round("👁", s(R.string.focus_mode)) { setFocusMode(!focusMode) }
        undoBtns = listOf(round("↶", s(R.string.undo)) { board.undo() }, round("↷", s(R.string.redo)) { board.redo() })
        topRight = LinearLayout(this).apply { undoBtns.forEach(::addView); addView(eyeBtn) }
        addBtn = round("＋", s(R.string.add_images)) { pickMedia(REQ_ADD, multiple = true) }.apply {
            textSize = 24f
            layoutParams = FrameLayout.LayoutParams(px(56), px(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(16), px(16)) }
        }
        val ui = FrameLayout(this).apply {
            clipToPadding = false
            addView(topLeft, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(px(8), px(8), 0, 0) })
            addView(topRight, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, px(8), px(8), 0) })
            addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(px(16), px(64), px(16), 0) })
            addView(barScroll, FrameLayout.LayoutParams(-2, px(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(px(12), 0, px(12), px(12)) })
            addView(addBtn)
            setOnApplyWindowInsetsListener { v, ins ->
                @Suppress("DEPRECATION")
                val i = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
                else intArrayOf(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                v.setPadding(i[0], i[1], i[2], i[3])
                ins
            }
        }
        setContentView(FrameLayout(this).apply { addView(board); addView(ui) })
        edgeToEdge() // needs the window's views, so after setContentView
        remember(scene)
        if (state == null) handle(intent)
    }

    /** Draw behind the status and navigation bars; the floating UI gets the insets as padding. */
    @Suppress("DEPRECATION")
    private fun edgeToEdge() {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val lightBars = if (light) android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0
            window.insetsController?.setSystemBarsAppearance(lightBars,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or (if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0)
        }
    }

    /** VizRef's eye: hide the UI and only look (pan, zoom, double-tap to zoom in); nothing can be moved by accident. */
    private fun setFocusMode(on: Boolean) {
        focusMode = on
        board.viewOnly = on
        if (on) board.clearSelection()
        val v = if (on) View.GONE else View.VISIBLE
        topLeft.visibility = v
        undoBtns.forEach { it.visibility = v }
        eyeBtn.alpha = if (on) 0.5f else 1f
        selectionChanged()
    }

    private fun openMenu() {
        val p = PopupMenu(this, menuBtn)
        buildMenu(p.menu)
        p.setOnMenuItemClickListener { item -> actions[item.itemId]?.let { it(); true } ?: false }
        p.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        board.invalidate() // the floating window may have changed the board
        selectionChanged()
    }

    override fun onPause() {
        super.onPause()
        board.scene.save()
    }

    override fun onStop() {
        super.onStop()
        board.releaseMedia()
    }

    private fun handle(i: Intent) {
        when (i.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION") // some apps only set EXTRA_STREAM, without clip data
                val u = uris(i.clipData).ifEmpty {
                    if (i.action == Intent.ACTION_SEND) listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                    else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
                if (u.isNotEmpty()) addUris(u) else i.getStringExtra(Intent.EXTRA_TEXT)?.let { pasteText(it, fromShare = true) }
            }
            Intent.ACTION_VIEW -> i.data?.let { importPur(it, link = false) }
        }
    }

    private fun remember(sc: Scene) {
        Boards.scene = sc
        Prefs.scene = sc.dir.name
        title = sc.name
        titleView.text = sc.name
    }

    private fun switchTo(sc: Scene) {
        board.scene.save()
        board.setScene(sc)
        remember(sc)
    }

    // ---- menu

    private fun Menu.act(title: String, always: Boolean = false, checked: Boolean? = null, name: String = title, f: () -> Unit) {
        val id = actions.size + 1
        actions[id] = f
        menuEntries += (menuPrefix + name) to f
        add(Menu.NONE, id, Menu.NONE, title).apply {
            if (always) setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            if (checked != null) { isCheckable = true; isChecked = checked }
        }
    }

    private fun buildMenu(menu: Menu) {
        menu.clear()
        actions.clear()
        menuEntries.clear()
        menuPrefix = ""
        menuEntries += s(R.string.undo) to { board.undo() }
        menuEntries += s(R.string.redo) to { board.redo() }
        menuEntries += s(R.string.add_images) to { pickMedia(REQ_ADD, multiple = true) }
        menuEntries += s(R.string.focus_mode) to { setFocusMode(!focusMode) }
        menu.act(s(R.string.command_palette)) { palette() }
        menu.act(s(R.string.paste)) { paste() }
        menu.act(s(R.string.add_note)) { noteEditor(null) }
        menu.act(s(R.string.draw)) { board.startDrawing() }
        menu.act(s(R.string.select_all)) { board.selectAll() }
        menu.act(s(R.string.hierarchy)) { hierarchy() }
        menu.act(s(R.string.slideshow)) { board.startSlideshow() }
        menu.act(s(R.string.always_on_top)) { alwaysOnTop() }
        menu.addSubMenu(s(R.string.file)).apply {
            menuPrefix = s(R.string.file) + " › "
            act(s(R.string.new_scene)) { switchTo(Scene.create(this@MainActivity, s(R.string.untitled))) }
            act(s(R.string.open_pur)) { openPur() }
            act(s(R.string.save)) { save() }
            act(s(R.string.save_as)) { saveAs() }
            act(s(R.string.my_scenes)) { openScene() }
            act(s(R.string.rename)) { editText(s(R.string.scene_name), board.scene.name) { board.scene.name = it; board.scene.save(); title = it; titleView.text = it } }
            act(s(R.string.duplicate_scene)) { board.scene.save(); switchTo(board.scene.copy(this@MainActivity, s(R.string.copy_of, board.scene.name))) }
            act(s(R.string.delete_scene)) { deleteScene() }
        }
        menu.addSubMenu(s(R.string.export)).apply {
            menuPrefix = s(R.string.export) + " › "
            act(s(R.string.export_png)) { exportItems = board.scene.items.toList(); create(REQ_PNG, "image/png", "${board.scene.name}.png") }
            act(s(R.string.export_images)) { exportItems = board.ordered(); exportFiles() }
        }
        menu.addSubMenu(s(R.string.view)).apply {
            menuPrefix = s(R.string.view) + " › "
            act(s(R.string.focus_canvas)) { board.focusAll() }
            act(s(R.string.reset_zoom)) { board.resetZoom() }
            act(s(R.string.reset_camera)) { board.resetCamera() }
            act(s(R.string.picker), checked = board.picking) { setPicking(!board.picking) }
            act(s(R.string.grayscale_canvas), checked = Prefs.canvasGray) { Prefs.canvasGray = !Prefs.canvasGray; board.invalidate() }
        }
        menu.addSubMenu(s(R.string.canvas)).apply {
            menuPrefix = s(R.string.canvas) + " › "
            act(s(R.string.grid_dots), checked = Prefs.grid == 2) { Prefs.grid = 2; board.invalidate() }
            act(s(R.string.grid_lines), checked = Prefs.grid == 1) { Prefs.grid = 1; board.invalidate() }
            act(s(R.string.grid_none), checked = Prefs.grid == 0) { Prefs.grid = 0; board.invalidate() }
            act(s(R.string.snap), checked = Prefs.snap) { Prefs.snap = !Prefs.snap }
            act(s(R.string.lock_canvas), checked = Prefs.canvasLocked) { toggleCanvasLock() }
            act(s(R.string.arrange_all)) { board.clearSelection(); board.arrange("optimal") }
        }
        menuPrefix = ""
        menu.act(s(R.string.settings)) { showSettings() }
        menu.act(s(R.string.about)) { about() }
    }

    private fun toggleCanvasLock() {
        Prefs.canvasLocked = !Prefs.canvasLocked
        toast(s(if (Prefs.canvasLocked) R.string.canvas_locked else R.string.canvas_unlocked))
    }

    // ---- the bottom bar: whatever fits the current mode or selection

    private fun compact(label: String, f: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        setTextColor(chipFg)
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0 // the button style sets both
        val p = (12 * resources.displayMetrics.density).toInt()
        setPadding(p, paddingTop, p, paddingBottom)
        setOnClickListener { f() }
    }

    private fun button(label: String, f: () -> Unit) {
        bar.addView(compact(label, f))
        barEntries += label to f
    }

    /** Text in the bar that isn't a command (frame number, slide count). */
    private fun label(text: String) = bar.addView(compact(text) {}.apply { isEnabled = false })

    override fun selectionChanged() {
        bar.removeAllViews()
        barEntries.clear()
        when {
            board.drawing -> drawBar()
            board.slides.isNotEmpty() -> slideBar()
            board.cropId >= 0 -> { button(s(R.string.done)) { board.endCrop() }; button(s(R.string.reset_crop)) { board.resetCrop() } }
            board.selection.isNotEmpty() -> selectionBar()
        }
        // a selected item's comment shows at the top, like PureRef's comment popup
        val comment = board.selected().singleOrNull()?.comment.orEmpty()
        if (!board.picking) {
            info.visibility = if (comment.isEmpty()) View.GONE else View.VISIBLE
            info.text = "💬 $comment"
        }
        barScroll.scrollTo(0, 0)
        barScroll.visibility = if (bar.childCount > 0) View.VISIBLE else View.GONE
        addBtn.visibility = if (bar.childCount > 0 || focusMode) View.GONE else View.VISIBLE
    }

    private fun drawBar() {
        button(s(R.string.done)) { board.stopDrawing() }
        button("↶") { board.undo() }
        listOf(T_PEN to R.string.pen, T_LINE to R.string.line, T_RECT to R.string.rectangle, T_ELLIPSE to R.string.ellipse, T_ERASER to R.string.eraser)
            .forEach { (t, l) -> button((if (Prefs.penTool == t) "● " else "") + s(l)) { Prefs.penTool = t; selectionChanged() } }
        button(s(R.string.color)) { pickColor(s(R.string.color), withNone = false) { Prefs.penColor = it } }
        button(s(R.string.width)) {
            choose(s(R.string.width), listOf(s(R.string.small), s(R.string.medium), s(R.string.large))) { Prefs.penWidth = intArrayOf(2, 4, 10)[it] }
        }
        button(s(R.string.style)) {
            choose(s(R.string.style), listOf(s(R.string.solid), s(R.string.dotted), s(R.string.arrow))) { Prefs.penStyle = it }
        }
    }

    private fun slideBar() {
        button("⏮") { board.slideStep(-1) }
        button(s(if (board.slidePaused) R.string.play else R.string.pause)) { board.toggleSlidePause() }
        button("⏭") { board.slideStep(1) }
        label("${board.slideIndex + 1} / ${board.slides.size}")
        button(s(R.string.stop)) { board.stopSlideshow() }
    }

    private fun selectionBar() {
        val sel = board.selected()
        val one = sel.singleOrNull()
        val groups = sel.any { it.type == GROUP }
        when (one?.type) {
            NOTE -> {
                button(s(R.string.edit)) { noteEditor(one) }
                button(s(R.string.colors)) { noteColors() }
                button(s(R.string.note_width)) {
                    val widths = floatArrayOf(0f, 240f, 480f, 800f)
                    choose(s(R.string.note_width), listOf(s(R.string.auto_size), s(R.string.narrow), s(R.string.medium), s(R.string.wide))) { i ->
                        board.edit { if (it.type == NOTE) it.copy(noteWidth = widths[i]).withText(it.text) else it }
                    }
                }
            }
            DRAW -> button(s(R.string.edit)) { board.startDrawing(one) }
            GIF -> board.gifOf(one)?.let { gif ->
                button(s(if (gif.playing) R.string.pause else R.string.play)) { gif.playing = !gif.playing; board.invalidate(); selectionChanged() }
                if (!gif.playing) label("${gif.frame() + 1} / ${gif.frames}")
                button("◀ ${s(R.string.frame)}") { gif.step(-1); board.invalidate(); selectionChanged() }
                button("${s(R.string.frame)} ▶") { gif.step(1); board.invalidate(); selectionChanged() }
                button(s(R.string.speed)) { speed { gif.speed = it } }
                button(s(R.string.scrub)) { scrub(gif.time.toFloat() / gif.duration) { f -> gif.playing = false; gif.seek(f); board.invalidate() } }
                button(s(R.string.frame_to_image)) { board.frameToImage(one) }
            }
            VIDEO -> board.vidOf(one)?.let { vid ->
                button(s(if (vid.playing) R.string.pause else R.string.play)) { vid.playing = !vid.playing; selectionChanged() }
                button(s(R.string.speed)) { speed { vid.speed = it } }
                button(s(R.string.loop)) { loopEditor(one, vid) }
                button(s(R.string.frame_to_image)) { board.frameToImage(one) }
            }
        }
        if (groups) {
            button(s(R.string.ungroup)) { board.ungroup() }
            button(s(R.string.group_color)) { pickColor(s(R.string.group_color), withNone = true) { c -> board.edit { if (it.type == GROUP) it.copy(bgColor = c) else it } } }
            val closed = sel.filter { it.type == GROUP }.all { it.closed }
            button(s(if (closed) R.string.unlock_group else R.string.lock_group)) { board.edit { if (it.type == GROUP) it.copy(closed = !closed) else it } }
        }
        button("⟨") { board.focusStep(-1) }
        button(s(R.string.focus)) { board.focus(board.selected()) }
        button("⟩") { board.focusStep(1) }
        if (one?.isMedia == true) button(s(R.string.crop)) { board.startCrop() }
        button(s(R.string.flip_h)) { board.edit { it.copy(flipX = !it.flipX) } }
        button(s(R.string.flip_v)) { board.edit { it.copy(flipY = !it.flipY) } }
        button(s(R.string.gray)) { board.edit { it.copy(gray = !it.gray) } }
        button(s(R.string.opacity)) { opacity() }
        button(s(R.string.arrange)) { arrangeMenu() }
        if (sel.size > 1 || groups) {
            button(s(R.string.align)) { choose(s(R.string.align), listOf(s(R.string.left), s(R.string.right), s(R.string.top), s(R.string.bottom))) { board.align(it) } }
            button(s(R.string.normalize)) { normalizeMenu() }
        }
        button(s(R.string.group)) { board.group(s(R.string.group)) }
        if (sel.size > 1) button(s(R.string.parent)) { board.parentToFirst() }
        button(s(R.string.front)) { board.toFront() }
        button(s(R.string.back)) { board.toBack() }
        button(s(R.string.duplicate)) { board.duplicate() }
        button(s(R.string.lock)) { board.toggleLock() }
        button(s(R.string.more)) { moreMenu(sel) }
        button(s(R.string.delete)) { board.delete() }
    }

    private fun moreMenu(sel: List<Item>) {
        val one = sel.singleOrNull()
        val entries = ArrayList<Pair<String, () -> Unit>>()
        entries += s(R.string.reset) to { board.edit { it.copy(scale = 1f, rot = 0f, flipX = false, flipY = false) } }
        if (sel.any { it.isMedia }) {
            entries += s(R.string.reset_crop) to { board.resetCrop() }
            val v = !sel.filter { it.isMedia }.all { it.nearest }
            entries += s(if (v) R.string.pixelated else R.string.smooth) to { board.edit { if (it.isMedia) it.copy(nearest = v) else it } }
        }
        if (one?.type == IMAGE || one?.type == GIF) entries += s(R.string.replace) to { pickImages(REQ_REPLACE) }
        if (one != null && one.source.startsWith("http")) entries += s(R.string.source) to { open(one.source) }
        if (one != null) entries += s(R.string.rename) to { editText(s(R.string.name), one.name) { board.rename(one, it) } }
        if (one != null) entries += s(R.string.comment) to {
            editText(s(R.string.comment), one.comment, allowEmpty = true) { c -> board.apply(listOf(one.copy(comment = c.trim()))) }
        }
        entries += s(R.string.copy) to { copyItems() }
        entries += s(R.string.cut) to { if (board.copySelection()) { markClipboard(); board.delete() } }
        if (sel.any { it.parent != 0L }) entries += s(R.string.unparent) to { board.edit { it.copy(parent = 0) } }
        entries += s(R.string.slideshow) to { board.startSlideshow() }
        entries += s(R.string.export) to {
            exportItems = sel.flatMap { s0 -> board.scene.items.filter { it.id in board.withDescendants(listOf(s0.id)) } }.distinctBy { it.id }
            choose(s(R.string.export), listOf(s(R.string.as_one_png), s(R.string.as_separate))) {
                if (it == 0) create(REQ_PNG, "image/png", "${board.scene.name}.png") else exportFiles()
            }
        }
        choose(s(R.string.more), entries.map { it.first }) { entries[it].second() }
    }

    private fun arrangeMenu() = choose(
        s(R.string.arrange),
        listOf(R.string.optimal, R.string.by_name, R.string.by_addition, R.string.by_order, R.string.by_path, R.string.randomly,
            R.string.stack, R.string.distribute_h, R.string.distribute_v).map { s(it) },
    ) {
        when (it) {
            0 -> board.arrange("optimal"); 1 -> board.arrange("name"); 2 -> board.arrange("addition")
            3 -> board.arrange("order"); 4 -> board.arrange("path"); 5 -> board.arrange("random")
            6 -> board.stack(); 7 -> board.distribute(true); else -> board.distribute(false)
        }
    }

    private fun normalizeMenu() {
        val kinds = listOf("height", "width", "size", "area", "scale")
        val labels = listOf(R.string.n_height, R.string.n_width, R.string.n_size, R.string.n_area, R.string.n_scale).map { s(it) } +
            s(if (Prefs.normalizeFirst) R.string.source_first else R.string.source_average)
        choose(s(R.string.normalize), labels) {
            if (it < kinds.size) board.normalize(kinds[it]) else { Prefs.normalizeFirst = !Prefs.normalizeFirst; normalizeMenu() }
        }
    }

    // ---- notes

    private fun noteColors() {
        val presets = listOf(
            Triple(R.string.light_on_dark, 0xFFFFFFFF.toInt(), 0xCC202020.toInt()),
            Triple(R.string.dark_on_light, 0xFF111111.toInt(), 0xEEF2F2F2.toInt()),
            Triple(R.string.sticky, 0xFF111111.toInt(), 0xFFFFE680.toInt()),
            Triple(R.string.white_no_bg, 0xFFFFFFFF.toInt(), 0),
            Triple(R.string.black_no_bg, 0xFF000000.toInt(), 0),
        )
        choose(s(R.string.colors), presets.map { s(it.first) }) { i -> board.edit { it.copy(textColor = presets[i].second, bgColor = presets[i].third) } }
    }

    /** Rich-text note editor: bold, italic, underline, strike, size, color, highlight, font, alignment, bullets, checklists, links. */
    private fun noteEditor(item: Item?) {
        val et = EditText(this).apply {
            setText(Rich.build(item?.text ?: "", item?.spans ?: ""), TextView.BufferType.SPANNABLE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setSelection(text.length)
        }
        val tools = LinearLayout(this)
        fun tool(label: String, f: (Editable, Int, Int) -> Unit) =
            tools.addView(compact(label) { f(et.text, min(et.selectionStart, et.selectionEnd), max(et.selectionStart, et.selectionEnd)) })
        tool("B") { e, a, b -> toggleSpan(e, a, b, "b") }
        tool("I") { e, a, b -> toggleSpan(e, a, b, "i") }
        tool("U") { e, a, b -> toggleSpan(e, a, b, "u") }
        tool("S̶") { e, a, b -> toggleSpan(e, a, b, "s") }
        tool("A+") { e, a, b -> resize(e, a, b, 1.25f) }
        tool("A−") { e, a, b -> resize(e, a, b, 0.8f) }
        tool(s(R.string.color)) { e, a, b -> pickColor(s(R.string.color), false) { c -> setSpan(e, a, b, "fg", c.toString()) } }
        tool(s(R.string.highlight)) { e, a, b -> pickColor(s(R.string.highlight), true) { c -> setSpan(e, a, b, "hl", c.toString()) } }
        tool(s(R.string.font)) { e, a, b ->
            val fonts = listOf("sans-serif", "serif", "monospace", "cursive")
            choose(s(R.string.font), fonts) { setSpan(e, a, b, "font", fonts[it]) }
        }
        tool("⇤") { e, a, b -> paragraphSpan(e, a, b, "align", Layout.Alignment.ALIGN_NORMAL.ordinal.toString()) }
        tool("↔") { e, a, b -> paragraphSpan(e, a, b, "align", Layout.Alignment.ALIGN_CENTER.ordinal.toString()) }
        tool("⇥") { e, a, b -> paragraphSpan(e, a, b, "align", Layout.Alignment.ALIGN_OPPOSITE.ordinal.toString()) }
        tool("•") { e, a, b -> paragraphSpan(e, a, b, "bullet", "") }
        tool("1.") { e, a, b -> numbered(e, a, b) }
        tool(Rich.UNCHECKED.toString()) { e, a, b -> checklist(e, a, b) }
        tool("⇥¶") { e, a, b -> indent(e, a, b, 1) }
        tool("⇤¶") { e, a, b -> indent(e, a, b, -1) }
        tool("🔗") { e, a, b -> editText(s(R.string.link), "https://") { setSpan(e, a, b, "url", it) } }
        tool(s(R.string.clear)) { e, a, b -> clearSpans(e, a, b) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(this@MainActivity).apply { addView(tools) })
            addView(et)
        }
        AlertDialog.Builder(this).setTitle(if (item == null) R.string.add_note else R.string.note).setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                BaseInputConnection.removeComposingSpans(et.text) // the keyboard's underline under the word being typed
                val text = et.text.toString()
                if (text.isBlank()) return@setPositiveButton
                val spans = Rich.toJson(et.text)
                if (item == null) board.addNote(text, spans) else board.editNote(item.id, text, spans)
            }
            .setNegativeButton(R.string.cancel, null).show()
            .window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        et.requestFocus()
    }

    /** Empty selection = the whole note (easier on a phone). */
    private fun range(e: Editable, a: Int, b: Int) = if (a == b) 0 to e.length else a to b

    private fun ours(e: Spanned, a: Int, b: Int, type: String) = e.getSpans(a, b, Any::class.java).filter { type in Rich.types(it) }

    private fun toggleSpan(e: Editable, a0: Int, b0: Int, type: String) {
        val (a, b) = range(e, a0, b0)
        val existing = ours(e, a, b, type)
        if (existing.isNotEmpty()) existing.forEach { e.removeSpan(it) } else setSpan(e, a, b, type, "")
    }

    private fun setSpan(e: Editable, a0: Int, b0: Int, type: String, v: String) {
        val (a, b) = range(e, a0, b0)
        if (type != "b" && type != "i" && type != "u" && type != "s") ours(e, a, b, type).forEach { e.removeSpan(it) }
        Rich.span(type, v)?.let { e.setSpan(it, a, b, Spanned.SPAN_EXCLUSIVE_INCLUSIVE) }
    }

    private fun resize(e: Editable, a0: Int, b0: Int, k: Float) {
        val (a, b) = range(e, a0, b0)
        val cur = e.getSpans(a, b, RelativeSizeSpan::class.java).firstOrNull()?.sizeChange ?: 1f
        setSpan(e, a, b, "size", (cur * k).coerceIn(0.4f, 6f).toString())
    }

    private fun paragraphs(e: Editable, a: Int, b: Int): Pair<Int, Int> {
        val t = e.toString()
        val start = t.lastIndexOf('\n', max(0, a - 1)).let { if (it < 0 || a == 0) 0 else it + 1 }
        val end = t.indexOf('\n', b).let { if (it < 0) t.length else it }
        return start to end
    }

    private fun paragraphSpan(e: Editable, a: Int, b: Int, type: String, v: String) {
        val (pa, pb) = paragraphs(e, a, b)
        val cls = if (type == "bullet") BulletSpan::class.java else AlignmentSpan::class.java
        val existing = e.getSpans(pa, pb, cls)
        existing.forEach { e.removeSpan(it) }
        if (type == "bullet" && existing.isNotEmpty()) return
        Rich.span(type, v)?.let { e.setSpan(it, pa, pb, Spanned.SPAN_EXCLUSIVE_INCLUSIVE) }
    }

    private fun checklist(e: Editable, a: Int, b: Int) {
        val (pa, pb) = paragraphs(e, a, b)
        val starts = ArrayList<Int>()
        var i = pa
        while (i <= pb) { starts += i; val n = e.indexOf('\n', i); if (n < 0 || n >= pb) break; i = n + 1 }
        val all = starts.all { e.getOrNull(it) == Rich.UNCHECKED || e.getOrNull(it) == Rich.CHECKED }
        for (st in starts.reversed()) {
            if (all) e.delete(st, min(st + 2, e.length)) else if (e.getOrNull(st) != Rich.UNCHECKED && e.getOrNull(st) != Rich.CHECKED) e.insert(st, "${Rich.UNCHECKED} ")
        }
    }

    /** Starts of the paragraphs touched by [a, b]. */
    private fun paragraphStarts(e: Editable, a: Int, b: Int): List<Int> {
        val (pa, pb) = paragraphs(e, a, b)
        val starts = ArrayList<Int>()
        var i = pa
        while (i <= pb) { starts += i; val n = e.indexOf('\n', i); if (n < 0 || n >= pb) break; i = n + 1 }
        return starts
    }

    /** "1. ", "2. "… in front of each paragraph, or off again. */
    private fun numbered(e: Editable, a: Int, b: Int) {
        val starts = paragraphStarts(e, a, b)
        val num = Regex("^\\d+\\. ")
        val all = starts.all { num.containsMatchIn(e.subSequence(it, e.length)) }
        for ((i, st) in starts.withIndex().reversed()) {
            val m = num.find(e.subSequence(st, e.length))
            if (m != null) e.delete(st, st + m.value.length)
            if (!all) e.insert(st, "${i + 1}. ")
        }
    }

    private fun indent(e: Editable, a: Int, b: Int, d: Int) {
        val (pa, pb) = paragraphs(e, a, b)
        val cur = e.getSpans(pa, pb, android.text.style.LeadingMarginSpan.Standard::class.java)
        val level = ((cur.maxOfOrNull { it.getLeadingMargin(true) } ?: 0) / Rich.INDENT + d).coerceIn(0, 8)
        cur.forEach { e.removeSpan(it) }
        if (level > 0) Rich.span("indent", level.toString())?.let { e.setSpan(it, pa, pb, Spanned.SPAN_EXCLUSIVE_INCLUSIVE) }
    }

    private fun clearSpans(e: Editable, a0: Int, b0: Int) {
        val (a, b) = range(e, a0, b0)
        for (t in listOf("b", "i", "u", "s", "size", "fg", "hl", "font", "url")) ours(e, a, b, t).forEach { e.removeSpan(it) }
    }

    // ---- small editors

    private val palette = listOf(
        R.string.c_red to 0xFFFF4040.toInt(), R.string.c_orange to 0xFFFF9900.toInt(), R.string.c_yellow to 0xFFFFE040.toInt(),
        R.string.c_green to 0xFF40C040.toInt(), R.string.c_blue to 0xFF4080FF.toInt(), R.string.c_purple to 0xFFA040FF.toInt(),
        R.string.c_white to 0xFFFFFFFF.toInt(), R.string.c_gray to 0xFF808080.toInt(), R.string.c_black to 0xFF000000.toInt(),
    )

    /** For backgrounds, colors come translucent and "none" is offered. */
    private fun pickColor(title: String, withNone: Boolean, f: (Int) -> Unit) {
        val list = palette.map { (n, c) -> s(n) to if (withNone) (c and 0xFFFFFF) or 0x50000000 else c } + if (withNone) listOf(s(R.string.c_none) to 0) else emptyList()
        choose(title, list.map { it.first }) { f(list[it].second) }
    }

    private fun speed(f: (Float) -> Unit) {
        val v = floatArrayOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)
        choose(s(R.string.speed), v.map { "$it×" }) { f(v[it]) }
    }

    private fun seekBar(start: Float, f: (Float) -> Unit) = SeekBar(this).apply {
        max = 1000
        progress = (start * 1000).toInt()
        setPadding(48, 32, 48, 32)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) { if (user) f(p / 1000f) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun scrub(start: Float, f: (Float) -> Unit) {
        AlertDialog.Builder(this).setTitle(R.string.scrub).setView(seekBar(start, f)).setPositiveButton(R.string.done) { _, _ -> selectionChanged() }.show()
    }

    /** AnimRef-style trimmed loop: drag start and end, the video jumps there so you can see the frame. */
    private fun loopEditor(item: Item, vid: Vid) {
        var a = item.loopA
        var b = item.loopB
        vid.playing = false
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
            addView(TextView(context).apply { text = s(R.string.loop_start); setPadding(48, 0, 48, 0) })
            addView(seekBar(a) { a = min(it, b - 0.01f); vid.seek(a) })
            addView(TextView(context).apply { text = s(R.string.loop_end); setPadding(48, 0, 48, 0) })
            addView(seekBar(b) { b = max(it, a + 0.01f); vid.seek(b) })
        }
        AlertDialog.Builder(this).setTitle(R.string.loop).setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> board.setLoop(item, a, b, true); vid.seek(a); vid.playing = true; selectionChanged() }
            .setNegativeButton(R.string.cancel) { _, _ -> vid.playing = true }
            .show()
    }

    private fun opacity() {
        val start = board.selected().firstOrNull()?.opacity ?: 1f
        board.beginOpacity()
        AlertDialog.Builder(this).setTitle(R.string.opacity).setView(seekBar(start) { board.setOpacity(it.coerceAtLeast(0.05f)) })
            .setPositiveButton(R.string.done, null).show()
    }

    // ---- hierarchy

    private fun label(it: Item) = it.name.ifEmpty {
        when (it.type) {
            NOTE -> it.text.lineSequence().firstOrNull().orEmpty().take(40)
            GROUP -> s(R.string.group)
            DRAW -> s(R.string.drawing)
            VIDEO -> s(R.string.video)
            else -> s(R.string.image)
        }
    }

    private fun icon(it: Item) = when (it.type) { NOTE -> "📝"; GROUP -> "▣"; DRAW -> "✎"; GIF -> "GIF"; VIDEO -> "▶"; else -> "🖼" }

    /** Tree of everything in the scene. Tap: select and focus. Long-press: rename, reorder, unparent, lock, delete. */
    private fun hierarchy() {
        val items = board.ordered()
        if (items.isEmpty()) return
        val labels = items.map {
            "　".repeat(board.depth(it)) + icon(it) + " " + label(it) + (if (it.locked) " 🔒" else "") + (if (it.comment.isNotEmpty()) " 💬" else "")
        }
        val dlg = AlertDialog.Builder(this).setTitle(R.string.hierarchy)
            .setItems(labels.toTypedArray()) { _, i -> board.select(listOf(items[i])); board.focus(listOf(items[i])) }
            .create()
        dlg.show()
        dlg.listView.setOnItemLongClickListener { _, _, i, _ ->
            dlg.dismiss()
            val it = items[i]
            val acts = listOf<Pair<String, () -> Unit>>(
                s(R.string.rename) to { editText(s(R.string.name), it.name) { n -> board.rename(it, n) } },
                s(R.string.move_up) to { board.moveAmongSiblings(it, -1); hierarchy() },
                s(R.string.move_down) to { board.moveAmongSiblings(it, 1); hierarchy() },
                s(R.string.parent_to) to {
                    val others = items.filter { o -> o.id !in board.withDescendants(listOf(it.id)) }
                    choose(s(R.string.parent_to), others.map { o -> icon(o) + " " + label(o) }) { j -> board.setParent(it, others[j].id) }
                },
                s(R.string.unparent) to { board.unparent(it) },
                s(if (it.locked) R.string.unlock else R.string.lock) to { board.apply(listOf(it.copy(locked = !it.locked))) },
                s(R.string.delete) to { board.select(listOf(it)); board.delete() },
            )
            choose(label(it), acts.map { a -> a.first }) { acts[it].second() }
            true
        }
    }

    // ---- host callbacks

    override fun lockedPressed(item: Item) {
        AlertDialog.Builder(this).setMessage(R.string.locked_item)
            .setPositiveButton(R.string.unlock) { _, _ -> board.unlock(item) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    override fun canvasMenu() {
        val acts = listOf<Pair<String, () -> Unit>>(
            s(R.string.add_images) to { pickMedia(REQ_ADD, multiple = true) },
            s(R.string.paste) to { paste() },
            s(R.string.add_note) to { noteEditor(null) },
            s(R.string.draw) to { board.startDrawing() },
            s(R.string.select_all) to { board.selectAll() },
            s(R.string.focus_canvas) to { board.focusAll() },
            s(R.string.arrange_all) to { board.clearSelection(); board.arrange("optimal") },
            s(if (Prefs.canvasLocked) R.string.unlock_canvas else R.string.lock_canvas) to { toggleCanvasLock() },
        )
        choose(s(R.string.canvas), acts.map { it.first }) { acts[it].second() }
    }

    override fun editItem(item: Item) {
        if (item.type == NOTE) noteEditor(item) else if (item.type == DRAW) board.startDrawing(item)
    }

    override fun picked(info: String, hex: String) {
        this.info.text = info + "\n" + s(R.string.tap_to_copy, hex)
        infoHex = hex
    }

    private fun setPicking(on: Boolean) {
        board.picking = on
        info.visibility = if (on) View.VISIBLE else View.GONE
        info.text = s(R.string.picker_hint)
    }

    // ---- floating window

    private fun alwaysOnTop() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast(s(R.string.overlay_permission))
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        board.scene.save()
        startService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
    }

    // ---- adding

    private fun uris(c: ClipData?) = if (c == null) emptyList() else (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri }

    private fun displayName(uri: Uri): String {
        val n = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "image"
        return n.substringAfterLast('/').substringBeforeLast('.')
    }

    private fun src(uri: Uri) = BoardView.Src(displayName(uri), "") { contentResolver.openInputStream(uri) }

    private fun addUris(u: List<Uri>) = board.addImages(u.map(::src)) { n ->
        if (n < u.size) toast(s(R.string.cant_open, u.size - n))
    }

    /** Copies the selection to DroRef's clipboard and marks the system clipboard, so Paste knows it's ours. */
    private fun copyItems() {
        if (!board.copySelection()) return
        markClipboard()
    }

    private fun markClipboard() =
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, "DroRef items"))

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return toast(s(R.string.clipboard_empty))
        if (clip.description?.label == CLIP_LABEL && Boards.clip.isNotEmpty()) return board.pasteItems()
        val u = uris(clip)
        if (u.isNotEmpty()) return addUris(u)
        val t = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (t.isEmpty()) toast(s(R.string.clipboard_empty)) else pasteText(t, fromShare = false)
    }

    /** A link becomes the image(s) behind it, like PureRef's paste-URL; anything else becomes a note. */
    private fun pasteText(t: String, fromShare: Boolean) {
        val url = Regex("https?://\\S+").find(t)?.value
        if (url != null && (fromShare || url == t)) download(url) else board.addNote(t, parentToSelection = Prefs.autoParentNotes)
    }

    private fun download(url: String) {
        toast(s(R.string.downloading))
        thread {
            val found = runCatching { webImages(url) }.getOrElse { emptyList() }
            runOnUiThread {
                if (found.isEmpty()) toast(s(R.string.no_images_link))
                else board.addImages(found.map { (u, bytes) ->
                    BoardView.Src(u.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').ifEmpty { "image" }, u) { bytes.inputStream() }
                })
            }
        }
    }

    // ---- scenes and .pur files

    private fun openScene() {
        board.scene.save()
        val dirs = Scene.list(this)
        val names = dirs.map { d -> Scene.peek(d).let { (if (d == board.scene.dir) "• " else "") + "${it.name}  (${it.items.size})" } }
        choose(s(R.string.my_scenes), names) { if (dirs[it] != board.scene.dir) switchTo(Scene.open(dirs[it])) }
    }

    private fun deleteScene() {
        AlertDialog.Builder(this).setMessage(s(R.string.delete_scene_q, board.scene.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                val d = board.scene.dir
                val next = Scene.list(this).firstOrNull { it != d }?.let { Scene.open(it) } ?: Scene.create(this, s(R.string.untitled))
                switchTo(next)
                d.deleteRecursively()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun openPur() = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
        REQ_OPEN,
    )

    /** Keeps access to a picked file across restarts, so Save can write back to it. */
    private fun keep(uri: Uri) = runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }.isSuccess

    private fun importPur(uri: Uri, link: Boolean) {
        toast(s(R.string.importing))
        val writable = link && keep(uri)
        thread {
            val sc = Scene.create(this, displayName(uri))
            val r = runCatching { Pur.read(contentResolver.openInputStream(uri)!!.use { it.readBytes() }, sc) }
            runOnUiThread {
                r.onSuccess { skipped ->
                    if (writable) sc.purUri = uri.toString()
                    sc.save()
                    switchTo(sc)
                    board.post { board.focusAll() }
                    if (skipped > 0) toast(s(R.string.skipped_linked, skipped))
                }.onFailure {
                    sc.dir.deleteRecursively()
                    AlertDialog.Builder(this).setTitle(R.string.couldnt_open)
                        .setMessage(if (it is Pur2Exception) s(R.string.pur2) else s(R.string.not_pur))
                        .setPositiveButton(R.string.ok, null).show()
                }
            }
        }
    }

    private fun save() {
        val u = board.scene.purUri
        if (u.isEmpty()) saveAs() else writePur(Uri.parse(u))
    }

    private fun saveAs() = create(REQ_SAVE_AS, "application/octet-stream", "${board.scene.name}.pur")

    private fun writePur(uri: Uri) {
        val items = board.ordered()
        val dir = board.scene.images
        val flattened = items.any { it.type == GROUP || it.type == DRAW || it.type == VIDEO }
        thread {
            val r = runCatching {
                // "wt" truncates; some providers only know "w"
                val out = runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull() ?: contentResolver.openOutputStream(uri, "w")!!
                out.use { Pur.write(items, dir, it) }
            }
            runOnUiThread {
                r.onSuccess { toast(s(if (flattened) R.string.saved_flattened else R.string.saved)) }
                    .onFailure { toast(s(R.string.save_failed, it.message ?: "")) }
            }
        }
    }

    // ---- export

    private fun exportPng(uri: Uri) {
        val items = exportItems.ifEmpty { return toast(s(R.string.nothing_export)) }
        val dir = board.scene.images
        val gray = Prefs.canvasGray
        toast(s(R.string.exporting))
        thread {
            val r = runCatching {
                val bmp = renderItems(items, dir, 4096, gray)
                contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
            runOnUiThread { toast(r.fold({ s(R.string.exported) }, { s(R.string.export_failed, it.message ?: "") })) }
        }
    }

    /** Separate files: originals stay as they are; cropped images need a format (PNG or JPG, as in PureRef 2). */
    private fun exportFiles() {
        if (exportItems.none { it.isMedia && it.type != VIDEO && it.crop != RectF(0f, 0f, it.w, it.h) }) return tree()
        choose(s(R.string.export_format), listOf("PNG", "JPG")) { exportJpg = it == 1; tree() }
    }

    private fun exportImages(tree: Uri) {
        val items = exportItems.filter { it.isMedia }
        val jpg = exportJpg
        val dir = board.scene.images
        thread {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            var n = 0
            items.forEachIndexed { i, it ->
                runCatching {
                    val f = File(dir, it.file)
                    val whole = it.crop == RectF(0f, 0f, it.w, it.h) || it.type == VIDEO
                    val mime = if (whole) imageMime(f) ?: if (it.type == VIDEO) "video/mp4" else "image/png" else if (jpg) "image/jpeg" else "image/png"
                    val doc = DocumentsContract.createDocument(contentResolver, parent, mime, it.name.ifEmpty { "image ${i + 1}" })!!
                    contentResolver.openOutputStream(doc)!!.use { out ->
                        if (whole) f.inputStream().use { s -> s.copyTo(out) } // untouched original
                        else cropped(f, it)!!.compress(if (jpg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpg) 92 else 100, out)
                    }
                    n++
                }
            }
            runOnUiThread { toast(s(R.string.exported_n, n)) }
        }
    }

    private fun cropped(f: File, it: Item): Bitmap? {
        val b = decode(f, 8192) ?: return null
        val k = b.width / it.w
        val l = (it.crop.left * k).toInt().coerceIn(0, b.width - 1)
        val t = (it.crop.top * k).toInt().coerceIn(0, b.height - 1)
        return Bitmap.createBitmap(b, l, t, (it.crop.width() * k).toInt().coerceIn(1, b.width - l), (it.crop.height() * k).toInt().coerceIn(1, b.height - t))
    }

    // ---- pickers

    private fun pickMedia(req: Int, multiple: Boolean) = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple), req,
    )

    private fun pickImages(req: Int) =
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), req)

    private fun create(req: Int, type: String, name: String) = startActivityForResult(
        Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), req,
    )

    private fun tree() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_IMAGES)

    @Deprecated("Platform Activity API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (res != RESULT_OK || data == null) return
        val uri = data.data
        when (req) {
            REQ_ADD -> addUris(uris(data.clipData).ifEmpty { listOfNotNull(uri) })
            REQ_REPLACE -> uri?.let { u -> board.selected().singleOrNull()?.let { board.replaceImage(it, src(u)) } }
            REQ_OPEN -> uri?.let { importPur(it, link = true) }
            REQ_SAVE_AS -> uri?.let {
                keep(it)
                board.scene.purUri = it.toString()
                board.scene.save()
                writePur(it)
            }
            REQ_PNG -> uri?.let(::exportPng)
            REQ_IMAGES -> uri?.let(::exportImages)
            REQ_SETTINGS_OUT -> uri?.let { u ->
                runCatching { contentResolver.openOutputStream(u, "wt")!!.use { it.write(Prefs.exportJson().toByteArray()) } }
                    .onSuccess { toast(s(R.string.exported)) }.onFailure { toast(s(R.string.export_failed, it.message ?: "")) }
            }
            REQ_SETTINGS_IN -> uri?.let { u ->
                runCatching { Prefs.importJson(contentResolver.openInputStream(u)!!.use { String(it.readBytes()) }) }
                    .onSuccess { toast(s(R.string.settings_imported)); recreate() }.onFailure { toast(s(R.string.couldnt_open)) }
            }
        }
    }

    // ---- settings

    private class Opt(val label: String, val values: List<String>, val get: () -> Int, val set: (Int) -> Unit)

    private fun settingsOpts(): List<Opt> {
        val onOff = listOf(s(R.string.off), s(R.string.on))
        fun flag(label: Int, get: () -> Boolean, set: (Boolean) -> Unit) = Opt(s(label), onOff, { if (get()) 1 else 0 }) { set(it == 1) }
        fun <T> pick(label: Int, values: List<T>, names: List<String>, get: () -> T, set: (T) -> Unit) =
            Opt(s(label), names, { values.indexOf(get()).coerceAtLeast(0) }) { set(values[it]) }
        val langs = listOf("", "en", "fr", "es", "ja", "ko", "zh-CN", "zh-TW")
        val langNames = listOf(s(R.string.lang_system), "English", "Français", "Español", "日本語", "한국어", "简体中文", "繁體中文")
        return listOf(
            pick(R.string.theme, listOf(0, 1, 2), listOf(s(R.string.theme_dark), s(R.string.theme_light), s(R.string.theme_glass)), { Prefs.theme }) { Prefs.theme = it },
            pick(R.string.language, langs, langNames, { Prefs.language }) { Prefs.language = it },
            pick(R.string.undo_size, listOf(50, 200, 500, 1000), listOf("50", "200", "500", "1000"), { Prefs.undoLimit }) { Prefs.undoLimit = it },
            flag(R.string.pack_new, { Prefs.packNew }) { Prefs.packNew = it },
            pick(R.string.pack_by, listOf(0, 1, 2), listOf(s(R.string.optimal), s(R.string.by_name), s(R.string.by_addition)), { Prefs.packBy }) { Prefs.packBy = it },
            flag(R.string.bring_front, { Prefs.bringToFront }) { Prefs.bringToFront = it },
            flag(R.string.dbl_focus, { Prefs.doubleTapFocus }) { Prefs.doubleTapFocus = it },
            pick(R.string.borders, listOf(0, 1, 2), listOf(s(R.string.borders_all), s(R.string.borders_selected), s(R.string.off)), { Prefs.borders }) { Prefs.borders = it },
            pick(R.string.padding, listOf(0, 5, 10, 20, 40), listOf("0", "5", "10", "20", "40"), { Prefs.padding }) { Prefs.padding = it },
            pick(R.string.sampling, listOf(false, true), listOf(s(R.string.smooth), s(R.string.pixelated)), { Prefs.nearest }) { Prefs.nearest = it },
            pick(R.string.slide_interval, listOf(3, 5, 10, 20, 30, 60), listOf(3, 5, 10, 20, 30, 60).map { s(R.string.seconds, it) }, { Prefs.slideSeconds }) { Prefs.slideSeconds = it },
            pick(R.string.slide_order, listOf(0, 1, 2), listOf(s(R.string.ordered), s(R.string.shuffle), s(R.string.randomly)), { Prefs.slideOrder }) { Prefs.slideOrder = it },
            flag(R.string.drag_groups, { Prefs.dragIntoGroups }) { Prefs.dragIntoGroups = it },
            flag(R.string.auto_parent, { Prefs.autoParentNotes }) { Prefs.autoParentNotes = it },
            pick(R.string.quality, listOf(1024, 2048, 4096), listOf("1024 px", "2048 px", "4096 px"), { Prefs.quality }) {
                Prefs.quality = it
                board.setScene(board.scene) // reload images at the new size
            },
        )
    }

    /** Settings list, plus export / import of all settings as a file (PureRef 2). */
    private fun showSettings() {
        val opts = settingsOpts()
        val extra = listOf(s(R.string.export_settings), s(R.string.import_settings))
        choose(s(R.string.settings), opts.map { "${it.label}: ${it.values[it.get()]}" } + extra) { i ->
            when (i - opts.size) {
                0 -> create(REQ_SETTINGS_OUT, "application/json", "droref-settings.json")
                1 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_SETTINGS_IN)
                else -> editSetting(i, opts[i], reopen = true)
            }
        }
    }

    private fun editSetting(i: Int, o: Opt, reopen: Boolean) = choose(o.label, o.values) { v ->
        o.set(v)
        if (i <= 1) recreate() else { board.invalidate(); if (reopen) showSettings() } // theme, language need a restart
    }

    /** Search every command: menus, the current bar, settings (PureRef's command palette). */
    private fun palette() {
        buildMenu(PopupMenu(this, menuBtn).menu) // fresh labels and checkmarks
        val opts = settingsOpts()
        val all = (barEntries + menuEntries).distinctBy { it.first } +
            opts.mapIndexed { i, o -> "${s(R.string.settings)} › ${o.label}" to { editSetting(i, o, reopen = false) } }
        var shown = all
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(all.map { it.first }))
        val input = EditText(this).apply { hint = s(R.string.search_hint); isSingleLine = true }
        val list = android.widget.ListView(this).apply { this.adapter = adapter }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
            addView(input)
            addView(list, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.5f).toInt()))
        }
        val dlg = AlertDialog.Builder(this).setTitle(R.string.command_palette).setView(box).create()
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: Editable) {
                val q = e.toString().trim().lowercase()
                shown = all.filter { it.first.lowercase().contains(q) }
                adapter.clear()
                adapter.addAll(shown.map { it.first })
            }
            override fun beforeTextChanged(t: CharSequence, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence, a: Int, b: Int, c: Int) {}
        })
        input.setOnEditorActionListener { _, _, _ -> shown.firstOrNull()?.let { dlg.dismiss(); it.second() }; true }
        list.setOnItemClickListener { _, _, i, _ -> dlg.dismiss(); shown[i].second() }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        input.requestFocus()
    }

    // ---- keyboard (tablets, Chromebooks); PureRef's shortcuts where they make sense

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed
        val shift = e.isShiftPressed
        when {
            ctrl && code == KeyEvent.KEYCODE_Z -> if (shift) board.redo() else board.undo()
            ctrl && code == KeyEvent.KEYCODE_Y -> board.redo()
            ctrl && code == KeyEvent.KEYCODE_V -> paste()
            ctrl && code == KeyEvent.KEYCODE_C -> copyItems()
            ctrl && code == KeyEvent.KEYCODE_X -> if (board.copySelection()) { markClipboard(); board.delete() }
            ctrl && shift && code == KeyEvent.KEYCODE_P -> palette()
            ctrl && code == KeyEvent.KEYCODE_A -> board.selectAll()
            ctrl && code == KeyEvent.KEYCODE_D && shift -> if (board.drawing) board.stopDrawing() else board.startDrawing()
            ctrl && code == KeyEvent.KEYCODE_D -> board.duplicate()
            ctrl && code == KeyEvent.KEYCODE_N -> noteEditor(null)
            ctrl && code == KeyEvent.KEYCODE_I -> pickMedia(REQ_ADD, multiple = true)
            ctrl && code == KeyEvent.KEYCODE_P -> board.arrange("optimal")
            ctrl && code == KeyEvent.KEYCODE_S -> if (shift) saveAs() else save()
            ctrl && (code == KeyEvent.KEYCODE_O || code == KeyEvent.KEYCODE_L) -> openPur()
            ctrl && code == KeyEvent.KEYCODE_G -> if (shift) board.ungroup() else board.group(s(R.string.group))
            ctrl && code == KeyEvent.KEYCODE_J -> hierarchy()
            ctrl && code == KeyEvent.KEYCODE_R -> toggleCanvasLock()
            e.isAltPressed && code == KeyEvent.KEYCODE_S -> board.startSlideshow()
            code == KeyEvent.KEYCODE_P -> board.parentToFirst()
            code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL -> board.delete()
            code == KeyEvent.KEYCODE_SPACE -> if (ctrl) board.focusAll() else board.focus(board.selected())
            code == KeyEvent.KEYCODE_DPAD_RIGHT -> if (board.slides.isNotEmpty()) board.slideStep(1) else board.focusStep(1)
            code == KeyEvent.KEYCODE_DPAD_LEFT -> if (board.slides.isNotEmpty()) board.slideStep(-1) else board.focusStep(-1)
            code == KeyEvent.KEYCODE_ESCAPE -> back()
            else -> return super.onKeyDown(code, e)
        }
        return true
    }

    /** Leaves the current mode first; returns false when there was nothing to leave. */
    private fun back(): Boolean {
        when {
            focusMode -> setFocusMode(false)
            board.picking -> setPicking(false)
            board.drawing -> board.stopDrawing()
            board.slides.isNotEmpty() -> board.stopSlideshow()
            board.cropId >= 0 -> board.endCrop()
            board.selection.isNotEmpty() -> board.clearSelection()
            else -> return false
        }
        return true
    }

    @Deprecated("Platform Activity API")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (!back()) super.onBackPressed()
    }

    // ---- small dialogs

    private fun choose(title: String, items: List<String>, f: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()) { _, w -> f(w) }.show()
    }

    private fun editText(title: String, initial: String, allowEmpty: Boolean = false, f: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(R.string.ok) { _, _ -> input.text.toString().takeIf { allowEmpty || it.isNotBlank() }?.let(f) }
            .setNegativeButton(R.string.cancel, null).show()
            .window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.requestFocus()
    }

    private fun about() {
        AlertDialog.Builder(this).setTitle(R.string.app_name).setMessage(R.string.about_text)
            .setPositiveButton(R.string.get_pureref) { _, _ -> open("https://www.pureref.com") }
            .setNegativeButton(R.string.close, null).show()
    }

    private fun open(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    private fun copyText(t: String) {
        if (t.isEmpty()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("color", t))
        toast(s(R.string.copied, t))
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}

// ---- web

private fun fetch(url: String): Pair<String?, ByteArray> {
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DroRef")
    c.connectTimeout = 15000
    c.readTimeout = 30000
    try {
        return c.contentType to c.inputStream.use { it.readBytes() }
    } finally {
        c.disconnect()
    }
}

/** The image at [url], or the images on the page at [url]. */
private fun webImages(url: String): List<Pair<String, ByteArray>> {
    val (type, body) = fetch(url)
    if (type?.startsWith("image/") == true || imageSize(body) != null) return listOf(url to body)
    val html = String(body)
    // ponytail: regex over og:image + <img src>, no JS-rendered galleries
    val links = Regex("""(?:og:image"\s+content|<img[^>]+?src)\s*=\s*["']([^"']+)""", RegexOption.IGNORE_CASE).findAll(html)
        .mapNotNull { runCatching { URL(URL(url), it.groupValues[1].replace("&amp;", "&")).toString() }.getOrNull() }
        .filter { it.startsWith("http") }.distinct().take(30).toList()
    return links.mapNotNull { u ->
        runCatching {
            val b = fetch(u).second
            imageSize(b)?.takeIf { (w, h) -> w >= 100 && h >= 100 }?.let { u to b } // skip icons and spacers
        }.getOrNull()
    }
}
