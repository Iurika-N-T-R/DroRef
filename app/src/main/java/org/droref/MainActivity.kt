package org.droref

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.widget.PopupWindow
import android.widget.ScrollView
import kotlin.math.roundToInt
import android.graphics.RectF
import android.graphics.drawable.Drawable
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
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ArrayAdapter
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var info: TextView
    private var infoHex = ""
    private var exportItems: List<Item> = emptyList()
    private var exportJpg = false
    private lateinit var titleView: TextView
    private lateinit var menuBtn: ImageView
    private lateinit var eyeBtn: ImageView
    private lateinit var addBtn: ImageView
    private lateinit var topLeft: LinearLayout
    private lateinit var topRight: LinearLayout
    private lateinit var undoBtns: List<ImageView>
    private lateinit var rail: LinearLayout
    private var barFloats = false // the selection bar sits above what's selected; mode bars (draw, crop, slideshow) stay at the bottom
    private var focusMode = false

    // formatting only with arguments: a plain "100 %" would otherwise throw
    private fun s(id: Int, vararg args: Any) = if (args.isEmpty()) getString(id) else getString(id, *args)

    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.localized())

    private val light get() = Prefs.theme == 1
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()
    private val chipBg get() = if (light) 0xF2FFFFFF.toInt() else 0xF2404040.toInt() // stands out from the canvas
    private val chipLine get() = if (light) 0x22000000 else 0x33FFFFFF
    private val chipFg get() = if (light) 0xFF222222.toInt() else 0xFFFFFFFF.toInt()

    /** A Lucide icon (res/drawable/ic_*), tinted. */
    private fun ic(id: Int, color: Int = chipFg): Drawable = getDrawable(id)!!.mutate().apply { setTint(color) }

    /** The theme's text color, for icons in dialogs. */
    private val textColor by lazy { obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).run { getColor(0, chipFg).also { recycle() } } }

    private fun tip(v: View, text: String) { v.contentDescription = text; if (Build.VERSION.SDK_INT >= 26) v.tooltipText = text }

    /** Round floating button, VizRef-style. */
    private fun round(iconId: Int, desc: String, f: () -> Unit) = ImageView(this).apply {
        setImageDrawable(ic(iconId))
        scaleType = ImageView.ScaleType.CENTER
        tip(this, desc)
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
        // the toolbar: a short pill of icons that fits the screen (no sideways scrolling); the rest is in "more"
        bar = LinearLayout(this).apply {
            setPadding(px(6), 0, px(6), 0)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
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
            minWidth = 0
        }
        menuBtn = round(R.drawable.ic_menu, s(R.string.menu)) { mainSheet() }
        // the name gets whatever room is left between the buttons, shrinking with "…" in narrow windows
        topLeft = LinearLayout(this).apply { addView(menuBtn); addView(titleView) }
        eyeBtn = round(R.drawable.ic_eye, s(R.string.focus_mode)) { setFocusMode(!focusMode) }
        undoBtns = listOf(round(R.drawable.ic_undo_2, s(R.string.undo)) { board.undo() }, round(R.drawable.ic_redo_2, s(R.string.redo)) { board.redo() })
        topRight = LinearLayout(this).apply { undoBtns.forEach(::addView); addView(eyeBtn) }
        addBtn = round(R.drawable.ic_plus, s(R.string.add_section)) {}.apply {
            setOnClickListener { addMenu(it) }
            layoutParams = FrameLayout.LayoutParams(px(56), px(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(16), px(16)) }
        }
        val ui = FrameLayout(this).apply {
            clipToPadding = false
            addView(LinearLayout(this@MainActivity).apply {
                addView(topLeft, LinearLayout.LayoutParams(0, -2, 1f))
                addView(topRight, LinearLayout.LayoutParams(-2, -2))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(px(8), px(8), px(8), 0) })
            addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(px(16), px(64), px(16), 0) })
            addView(bar, FrameLayout.LayoutParams(-2, px(52), Gravity.TOP or Gravity.START)) // placed by placeBar()
            addView(addBtn)
            rail = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(rail, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(px(8), 0, 0, px(84)) })
            setOnApplyWindowInsetsListener { v, ins ->
                @Suppress("DEPRECATION")
                val i = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
                else intArrayOf(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                v.setPadding(i[0], i[1], i[2], i[3])
                ins
            }
        }
        board.setOnDragListener { _, e -> onDrag(e) }
        setContentView(FrameLayout(this).apply { addView(board); addView(ui) })
        renderRail()
        edgeToEdge() // needs the window's views, so after setContentView
        remember(scene)
        if (state == null) handle(intent)
        keyboardHint()
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
        rail.visibility = v
        undoBtns.forEach { it.visibility = v }
        eyeBtn.alpha = if (on) 0.6f else 1f
        eyeBtn.setImageDrawable(ic(if (on) R.drawable.ic_eye_off else R.drawable.ic_eye))
        selectionChanged()
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

    // ---- drag and drop from other apps (gallery, browser, files…)

    private fun onDrag(e: DragEvent): Boolean = when (e.action) {
        DragEvent.ACTION_DRAG_STARTED -> e.localState !== DRAG_SELF
        DragEvent.ACTION_DRAG_ENTERED -> { board.dropHint = true; true }
        DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { board.dropHint = false; true }
        DragEvent.ACTION_DROP -> { board.dropHint = false; drop(e); true }
        else -> true
    }

    /** Images and videos land where they're dropped; a dropped link brings in its images; other text becomes a note. */
    private fun drop(e: DragEvent) {
        val clip = e.clipData ?: return
        val perms = requestDragAndDropPermissions(e) // read access to the other app's files, until released
        val u = uris(clip)
        if (u.isNotEmpty()) {
            board.addImages(u.map(::src), floatArrayOf(e.x, e.y)) { n ->
                perms?.release()
                if (n < u.size) toast(s(R.string.cant_open, u.size - n))
            }
        } else {
            perms?.release()
            clip.getItemAt(0).coerceToText(this)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { pasteText(it, fromShare = true) }
        }
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
        if (Prefs.scene != sc.dir.name) Prefs.prevScene = Prefs.scene
        Prefs.scene = sc.dir.name
        title = sc.name
        titleView.text = sc.name
    }

    private fun switchTo(sc: Scene) {
        val old = board.scene
        old.save()
        val items = old.items.toList()
        val links = old.links.toList()
        if (old.dir.exists()) thread { old.writeThumb(items, links) } // the scene browser's preview
        board.setScene(sc)
        remember(sc)
    }

    /** Ctrl+Shift+L: back to the scene used before this one. */
    private fun previousScene() {
        val d = File(Scene.root(this), Prefs.prevScene)
        if (Prefs.prevScene.isNotEmpty() && File(d, "scene.json").exists()) switchTo(Scene.open(d))
    }

    // ---- the main panel: actions grouped by purpose, settings and search apart

    private fun mainSections(): List<Section> = listOf(
        Section(s(R.string.add_section), Palette.GREEN, listOf(
            Tile(R.drawable.ic_image_plus, s(R.string.add_images)) { pickMedia(REQ_ADD, multiple = true) },
            Tile(R.drawable.ic_clipboard_paste, s(R.string.paste)) { paste() },
            Tile(R.drawable.ic_sticky_note, s(R.string.add_note)) { noteEditor(null) },
            Tile(R.drawable.ic_pencil, s(R.string.draw)) { board.startDrawing() },
        )),
        Section(s(R.string.board_section), Palette.BLUE, listOf(
            Tile(R.drawable.ic_scan, s(R.string.select_all)) { board.selectAll() },
            Tile(R.drawable.ic_layout_dashboard, s(R.string.arrange_all)) { board.clearSelection(); board.arrange("optimal") },
            Tile(R.drawable.ic_list_tree, s(R.string.hierarchy)) { hierarchy() },
            Tile(R.drawable.ic_presentation, s(R.string.slideshow)) { board.startSlideshow() },
            Tile(R.drawable.ic_picture_in_picture_2, s(R.string.always_on_top)) { alwaysOnTop() },
            Tile(R.drawable.ic_eye, s(R.string.focus_mode)) { setFocusMode(true) },
        )),
        Section(s(R.string.file), Palette.ORANGE, listOf(
            Tile(R.drawable.ic_folders, s(R.string.my_scenes)) { openScene() },
            Tile(R.drawable.ic_file_plus, s(R.string.new_scene)) { switchTo(Scene.create(this, s(R.string.untitled))) },
            Tile(R.drawable.ic_folder_open, s(R.string.open_pur)) { openPur() },
            Tile(R.drawable.ic_save, s(R.string.save)) { save() },
            Tile(R.drawable.ic_save_all, s(R.string.save_as)) { saveAs() },
            Tile(R.drawable.ic_image_down, s(R.string.export_png)) { exportItems = board.scene.items.toList(); create(REQ_PNG, "image/png", "${board.scene.name}.png") },
            Tile(R.drawable.ic_images, s(R.string.export_images)) { exportItems = board.ordered(); exportFiles() },
            Tile(R.drawable.ic_pencil_line, s(R.string.rename)) { editText(s(R.string.scene_name), board.scene.name) { board.scene.name = it; board.scene.save(); title = it; titleView.text = it } },
            Tile(R.drawable.ic_copy, s(R.string.duplicate_scene)) { board.scene.save(); switchTo(board.scene.copy(this, s(R.string.copy_of, board.scene.name))) },
            Tile(R.drawable.ic_trash, s(R.string.delete_scene), color = Palette.RED) { deleteScene() },
        )),
        Section(s(R.string.view), Palette.PURPLE, listOf(
            Tile(R.drawable.ic_grip, s(R.string.grid_dots), Prefs.grid == 2) { Prefs.grid = 2; board.invalidate() },
            Tile(R.drawable.ic_grid_3x3, s(R.string.grid_lines), Prefs.grid == 1) { Prefs.grid = 1; board.invalidate() },
            Tile(R.drawable.ic_square, s(R.string.grid_none), Prefs.grid == 0) { Prefs.grid = 0; board.invalidate() },
            Tile(R.drawable.ic_magnet, s(R.string.snap), Prefs.snap) { Prefs.snap = !Prefs.snap },
            Tile(R.drawable.ic_lock, s(R.string.lock_canvas), Prefs.canvasLocked) { toggleCanvasLock() },
            Tile(R.drawable.ic_contrast, s(R.string.grayscale_canvas), Prefs.canvasGray) { Prefs.canvasGray = !Prefs.canvasGray; board.invalidate() },
            Tile(R.drawable.ic_plus, s(R.string.center_cross), Prefs.centerCross) { Prefs.centerCross = !Prefs.centerCross; board.invalidate() },
            Tile(R.drawable.ic_frame, s(R.string.container), Prefs.container) { Prefs.container = !Prefs.container; board.invalidate() },
            Tile(R.drawable.ic_pipette, s(R.string.picker), board.picking) { setPicking(!board.picking) },
            Tile(R.drawable.ic_maximize, s(R.string.focus_canvas)) { board.focusAll() },
            Tile(R.drawable.ic_zoom_in, s(R.string.reset_zoom)) { board.resetZoom() },
            Tile(R.drawable.ic_locate_fixed, s(R.string.reset_camera)) { board.resetCamera() },
        )),
    )

    private fun mainSheet() = sheet(board.scene.name, mainSections(), light, listOf(
        Triple(R.drawable.ic_command, s(R.string.command_palette)) { palette() },
        Triple(R.drawable.ic_settings, s(R.string.settings)) { showSettings() },
        Triple(R.drawable.ic_keyboard, s(R.string.guide)) { keyboardGuide() },
        Triple(R.drawable.ic_info, s(R.string.about)) { about() },
    ))

    private fun toggleCanvasLock() {
        Prefs.canvasLocked = !Prefs.canvasLocked
        toast(s(if (Prefs.canvasLocked) R.string.canvas_locked else R.string.canvas_unlocked))
    }

    // ---- the toolbar: a pill of icon buttons for what's selected or the current mode

    private fun barIcon(iconId: Int, desc: String, selected: Boolean = false, swatch: Int? = null, f: (View) -> Unit) {
        bar.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            tip(this, desc)
            when {
                swatch != null -> setImageDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setSize(px(22), px(22)); setColor(swatch); setStroke(px(2), chipFg) })
                else -> setImageDrawable(ic(iconId, if (selected) Palette.BLUE else chipFg))
            }
            background = if (selected) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((Palette.BLUE and 0xFFFFFF) or 0x33000000) }
            else obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).run { getDrawable(0).also { recycle() } }
            setOnClickListener { v -> f(v) }
        }, LinearLayout.LayoutParams(px(46), px(46)).apply { setMargins(px(1), 0, px(1), 0) })
    }

    /** Text in the bar that isn't a command (frame number, slide count). */
    private fun label(text: String) = bar.addView(TextView(this).apply {
        this.text = text
        setTextColor(chipFg)
        textSize = 13f
        setPadding(px(8), 0, px(8), 0)
    })

    override fun selectionChanged() {
        bar.removeAllViews()
        barFloats = false
        when {
            board.drawing -> drawBar()
            board.slides.isNotEmpty() -> slideBar()
            board.cropId >= 0 -> {
                barIcon(R.drawable.ic_check, s(R.string.done)) { board.endCrop() }
                barIcon(R.drawable.ic_rotate_ccw, s(R.string.reset_crop)) { board.resetCrop() }
            }
            board.linkSel >= 0 -> { linkBar(); barFloats = true }
            board.selection.isNotEmpty() -> { selectionBar(); barFloats = true }
        }
        if (::rail.isInitialized) renderRail()
        // a selected item's comment shows at the top, like PureRef's comment popup
        val comment = board.selected().singleOrNull()?.comment.orEmpty()
        if (!board.picking) {
            info.visibility = if (comment.isEmpty()) View.GONE else View.VISIBLE
            info.text = comment
            info.setCompoundDrawablesRelative(ic(R.drawable.ic_message_square, Color.WHITE).apply { setBounds(0, 0, px(18), px(18)) }, null, null, null)
            info.compoundDrawablePadding = px(8)
        }
        bar.visibility = if (bar.childCount > 0) View.VISIBLE else View.GONE
        addBtn.visibility = if ((bar.childCount > 0 && !barFloats) || focusMode) View.GONE else View.VISIBLE
        bar.post(::placeBar)
    }

    override fun boardDrawn() = placeBar()

    /** The selection bar floats just above the selection (below it when there's no room), mode bars sit at the bottom. */
    private fun placeBar() {
        if (bar.visibility != View.VISIBLE) return
        val parent = bar.parent as? View ?: return
        val pw = parent.width - parent.paddingLeft - parent.paddingRight
        val ph = parent.height - parent.paddingTop - parent.paddingBottom
        val bw = bar.width
        val bh = bar.height
        if (bw == 0 || pw == 0) return
        val r = if (barFloats) board.selectionScreenRect() else null
        var x = (pw - bw) / 2f
        var y = (ph - bh - px(12)).toFloat()
        if (r != null) {
            x = r.centerX() - parent.paddingLeft - bw / 2f
            y = r.top - parent.paddingTop - bh - px(20)
            if (y < px(56)) y = r.bottom - parent.paddingTop + px(48) // under the size label
            x = x.coerceIn(px(8).toFloat(), maxOf(px(8), pw - bw - px(8)).toFloat())
            y = y.coerceIn(px(56).toFloat(), maxOf(px(56), ph - bh - px(8)).toFloat())
        }
        bar.translationX = x
        bar.translationY = y
    }

    // ---- small menus anchored to their button (instead of a full sheet for everyday actions)

    /** One line of a small menu: label, icon at the right; [check] makes it a toggle that keeps the menu open. A null label is a divider. */
    private class Row(
        val label: String?, val icon: Int = 0, val check: (() -> Boolean)? = null, val enabled: Boolean = true,
        val ticked: Boolean = false, val tint: Int? = null, val header: Int? = null, // header = a section title in that color
        val f: () -> Unit = {},
    )

    private fun popover(anchor: View, title: String?, rows: List<Row>) {
        val pw = PopupWindow(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(if (light) 0xFAFFFFFF.toInt() else 0xFA2E2E2E.toInt()); setStroke(px(1), chipLine) }
            setPadding(0, px(6), 0, px(6))
        }
        title?.let { box.addView(TextView(this).apply {
            text = it; setTextColor(chipFg); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setPadding(px(20), px(10), px(20), px(6))
        }) }
        for (r in rows) {
            if (r.label == null) {
                box.addView(View(this).apply { setBackgroundColor(chipLine) }, LinearLayout.LayoutParams(-1, px(1)).apply { setMargins(px(16), px(4), px(16), px(4)) })
                continue
            }
            if (r.header != null) {
                box.addView(TextView(this).apply {
                    text = r.label.uppercase(); setTextColor(r.header); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f
                    setPadding(px(20), px(14), px(20), px(4))
                })
                continue
            }
            val end = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER }
            fun refresh() {
                val on = r.check?.invoke()
                end.setImageDrawable(when {
                    on == true || (on == null && r.ticked) -> ic(R.drawable.ic_check, Palette.BLUE)
                    on == false -> null
                    r.icon != 0 -> ic(r.icon, r.tint ?: chipFg)
                    else -> null
                })
            }
            refresh()
            box.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(20), 0, px(14), 0)
                alpha = if (r.enabled) 1f else 0.4f
                background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
                addView(TextView(this@MainActivity).apply { text = r.label; setTextColor(r.tint ?: chipFg); textSize = 15f }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(end, LinearLayout.LayoutParams(px(32), px(32)).apply { marginStart = px(16) })
                if (r.enabled) setOnClickListener { if (r.check == null) pw.dismiss(); r.f(); refresh() }
                minimumHeight = px(46)
                setPadding(px(20), px(6), px(14), px(6))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        // placed by hand: below the button if it fits, else above, scrolling if taller than the screen
        val w = px(270)
        box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val root = window.decorView
        val margin = px(12)
        // a fixed, moderate height: long menus (⋮) scroll inside instead of covering the screen
        val h = minOf(box.measuredHeight, px(420), (root.height * 0.6f).toInt())
        val at = IntArray(2).also(anchor::getLocationInWindow)
        val x = (if (at[0] + w <= root.width - margin) at[0] else at[0] + anchor.width - w).coerceIn(margin, maxOf(margin, root.width - w - margin))
        val below = at[1] + anchor.height + px(4)
        val y = (if (below + h <= root.height - margin - px(24)) below else at[1] - h - px(4)).coerceIn(margin + px(24), maxOf(margin, root.height - h - margin))
        pw.contentView = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box) }
        pw.width = w
        pw.height = h
        pw.isFocusable = true
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(ColorDrawable(0))
        pw.elevation = 12 * dp
        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    private fun addMenu(anchor: View) = popover(anchor, s(R.string.add_section), listOf(
        Row(s(R.string.add_images), R.drawable.ic_image_plus) { pickMedia(REQ_ADD, multiple = true) },
        Row(s(R.string.paste), R.drawable.ic_clipboard_paste) { paste() },
        Row(s(R.string.add_note), R.drawable.ic_sticky_note) { noteEditor(null) },
        Row(s(R.string.add_node), R.drawable.ic_workflow) { noteEditor(null, node = true) },
        Row(s(R.string.draw), R.drawable.ic_pencil) { board.startDrawing() },
    ))

    private fun rotateMenu(anchor: View) = popover(anchor, s(R.string.rotation), listOf(
        Row(s(R.string.rotate_90), R.drawable.ic_rotate_cw) { board.rotateBy(90f) },
        Row(s(R.string.rotate_180)) { board.rotateBy(180f) },
        Row(s(R.string.rotate_270), R.drawable.ic_rotate_ccw) { board.rotateBy(270f) },
        Row(null),
        Row(s(R.string.reset_rotation)) { board.resetRotation() },
    ))

    private fun sizeMenu(anchor: View) = popover(anchor, s(R.string.size), listOf(
        Row(s(R.string.reset_size), R.drawable.ic_scaling) { board.resetSize() },
        Row(s(R.string.double_size), R.drawable.ic_zoom_in) { board.scaleBy(2f) },
        Row(s(R.string.half_size)) { board.scaleBy(0.5f) },
    ))

    private fun infoMenu(anchor: View) {
        val sel = board.selected()
        val one = sel.singleOrNull()
        val r = sel.union()
        val rows = ArrayList<Row>()
        // label above its value, like a details panel
        one?.let { if (it.name.isNotEmpty()) rows += Row(it.name, R.drawable.ic_type) }
        if (one?.isMedia == true) rows += Row("${s(R.string.original_size)}\n${one.w.toInt()} × ${one.h.toInt()}", R.drawable.ic_image)
        val w = one?.let { it.crop.width() * it.scale } ?: r.width()
        val h = one?.let { it.crop.height() * it.scale } ?: r.height()
        rows += Row("${s(R.string.board_size)}\n${w.roundToInt()} × ${h.roundToInt()}", R.drawable.ic_scaling)
        one?.let { rows += Row("${s(R.string.rotation)}\n${(((it.rot % 360) + 360) % 360).roundToInt()}°", R.drawable.ic_rotate_cw) }
        one?.url?.takeIf { it.isNotEmpty() }?.let { u -> rows += Row(u, R.drawable.ic_external_link) { openUrl(u) } }
        popover(anchor, s(R.string.info_title), rows)
    }

    /** Everything that can be done to the selection, in one scrolling list, section by section. */
    private fun moreMenu(anchor: View) {
        val rows = ArrayList<Row>()
        for (sec in moreSections()) {
            rows += Row(sec.title, header = sec.color)
            sec.tiles.forEach { t -> rows += Row(t.label, t.icon, ticked = t.checked, tint = t.color) { t.f() } }
        }
        popover(anchor, null, rows)
    }

    private fun openUrl(u: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }.onFailure { toast(u) } }

    /** A selected connection: direction, arrows, color, delete. */
    private fun linkBar() {
        val l = board.selectedLink() ?: return
        barIcon(R.drawable.ic_arrow_right_left, s(R.string.reverse)) { board.reverseLink() }
        barIcon(R.drawable.ic_move_horizontal, s(R.string.both_ends), selected = l.both) { board.toggleBothEnds() }
        barIcon(0, s(R.string.color), swatch = l.color) { pickColor(s(R.string.color), withNone = false, current = l.color) { c -> board.setLinkColor(c) } }
        barIcon(R.drawable.ic_trash, s(R.string.delete_connection)) { board.deleteLink() }
    }

    // ---- tool rail (bottom left): select, hand, connect, draw, lock

    private fun renderRail() {
        rail.removeAllViews()
        fun tool(icon: Int, desc: String, on: Boolean, f: (View) -> Unit) = rail.addView(round(icon, desc) {}.apply {
            if (on) { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.BLUE) }; setImageDrawable(ic(icon, Color.WHITE)) }
            setOnClickListener { v -> f(v) }
        })
        tool(R.drawable.ic_mouse_pointer_2, s(R.string.tool_select), board.tool == TOOL_SELECT && !board.drawing) { board.tool = TOOL_SELECT; if (board.drawing) board.stopDrawing(); renderRail() }
        tool(R.drawable.ic_hand, s(R.string.tool_hand), board.tool == TOOL_HAND) { board.tool = if (board.tool == TOOL_HAND) TOOL_SELECT else TOOL_HAND; if (board.tool == TOOL_HAND) toast(s(R.string.tool_hand)); renderRail() }
        tool(R.drawable.ic_waypoints, s(R.string.tool_connect), board.tool == TOOL_CONNECT) {
            board.tool = if (board.tool == TOOL_CONNECT) TOOL_SELECT else TOOL_CONNECT
            if (board.tool == TOOL_CONNECT) toast(s(R.string.connect_hint))
            renderRail()
        }
        tool(R.drawable.ic_pencil, s(R.string.draw), board.drawing) { if (board.drawing) board.stopDrawing() else board.startDrawing() }
        tool(R.drawable.ic_lock_keyhole, s(R.string.lock_layers), board.scene.lockVisuals || board.scene.lockNodes) { lockMenu(it) }
    }

    /** "Verrouillage": visuals or nodes can't be selected or moved (the other layer stays editable). */
    private fun lockMenu(anchor: View) {
        val sc = board.scene
        popover(anchor, s(R.string.lock_layers), listOf(
            Row(s(R.string.lock_visuals), check = { sc.lockVisuals }) { sc.lockVisuals = !sc.lockVisuals; sc.save(); board.clearSelection(); renderRail() },
            Row(s(R.string.lock_nodes), check = { sc.lockNodes }) { sc.lockNodes = !sc.lockNodes; sc.save(); board.clearSelection(); renderRail() },
        ))
    }

    private val tools = listOf(
        Triple(T_PEN, R.drawable.ic_pen_line, R.string.pen), Triple(T_LINE, R.drawable.ic_minus, R.string.line),
        Triple(T_RECT, R.drawable.ic_square, R.string.rectangle), Triple(T_ELLIPSE, R.drawable.ic_circle, R.string.ellipse),
        Triple(T_ERASER, R.drawable.ic_eraser, R.string.eraser),
    )

    private fun drawBar() {
        barIcon(R.drawable.ic_check, s(R.string.done)) { board.stopDrawing() }
        barIcon(R.drawable.ic_undo_2, s(R.string.undo)) { board.undo() }
        for ((t, icon, name) in tools) barIcon(icon, s(name), selected = Prefs.penTool == t) { Prefs.penTool = t; selectionChanged() }
        barIcon(0, s(R.string.color), swatch = Prefs.penColor) { pickColor(s(R.string.color), withNone = false, current = Prefs.penColor) { Prefs.penColor = it; selectionChanged() } }
        barIcon(R.drawable.ic_spline, s(R.string.style)) {
            sheet(s(R.string.style), listOf(
                Section(s(R.string.width), Palette.BLUE, listOf(2 to R.string.small, 4 to R.string.medium, 10 to R.string.large).map { (w, n) ->
                    Tile(R.drawable.ic_minus, s(n), Prefs.penWidth == w) { Prefs.penWidth = w }
                }),
                Section(s(R.string.style), Palette.PURPLE, listOf(
                    Tile(R.drawable.ic_minus, s(R.string.solid), Prefs.penStyle == S_SOLID) { Prefs.penStyle = S_SOLID },
                    Tile(R.drawable.ic_ellipsis, s(R.string.dotted), Prefs.penStyle == S_DOTTED) { Prefs.penStyle = S_DOTTED },
                    Tile(R.drawable.ic_arrow_left_right, s(R.string.arrow), Prefs.penStyle == S_ARROW) { Prefs.penStyle = S_ARROW },
                )),
            ), light)
        }
    }

    private fun slideBar() {
        barIcon(R.drawable.ic_skip_back, s(R.string.prev)) { board.slideStep(-1) }
        if (board.slidePaused) barIcon(R.drawable.ic_play, s(R.string.play)) { board.toggleSlidePause() }
        else barIcon(R.drawable.ic_pause, s(R.string.pause)) { board.toggleSlidePause() }
        barIcon(R.drawable.ic_skip_forward, s(R.string.next)) { board.slideStep(1) }
        label("${board.slideIndex + 1} / ${board.slides.size}")
        barIcon(R.drawable.ic_circle_stop, s(R.string.stop)) { board.stopSlideshow() }
    }

    /** At most a handful of icons: the obvious actions for what's selected, then "more" and delete. */
    private fun selectionBar() {
        val sel = board.selected()
        val one = sel.singleOrNull()
        if (one?.type != GIF && one?.type != VIDEO) barIcon(R.drawable.ic_info, s(R.string.info_title)) { infoMenu(it) }
        when {
            one?.type == NOTE -> {
                barIcon(R.drawable.ic_pencil, s(R.string.edit)) { noteEditor(one) }
                barIcon(R.drawable.ic_palette, s(R.string.colors)) { noteColors() }
            }
            one?.type == DRAW -> barIcon(R.drawable.ic_pencil, s(R.string.edit)) { board.startDrawing(one) }
            one?.type == GIF -> board.gifOf(one)?.let { gif ->
                if (gif.playing) barIcon(R.drawable.ic_pause, s(R.string.pause)) { gif.playing = false; board.invalidate(); selectionChanged() }
                else barIcon(R.drawable.ic_play, s(R.string.play)) { gif.playing = true; board.invalidate(); selectionChanged() }
                barIcon(R.drawable.ic_step_back, s(R.string.frame)) { gif.step(-1); board.invalidate(); selectionChanged() }
                if (!gif.playing) label("${gif.frame() + 1}/${gif.frames}")
                barIcon(R.drawable.ic_step_forward, s(R.string.frame)) { gif.step(1); board.invalidate(); selectionChanged() }
            }
            one?.type == VIDEO -> board.vidOf(one)?.let { vid ->
                if (vid.playing) barIcon(R.drawable.ic_pause, s(R.string.pause)) { vid.playing = false; selectionChanged() }
                else barIcon(R.drawable.ic_play, s(R.string.play)) { vid.playing = true; selectionChanged() }
                barIcon(R.drawable.ic_repeat, s(R.string.loop)) { loopEditor(one, vid) }
            }
            one?.type == GROUP -> {
                // a real lock: the group and its content stop moving; long-press it to unlock
                barIcon(R.drawable.ic_lock, s(R.string.lock_group)) { board.toggleLock(); toast(s(R.string.group_locked)) }
                barIcon(R.drawable.ic_ungroup, s(R.string.ungroup)) { board.ungroup() }
            }
            one?.isMedia == true -> barIcon(R.drawable.ic_crop, s(R.string.crop)) { board.startCrop() }
            sel.size > 1 -> {
                barIcon(R.drawable.ic_layout_dashboard, s(R.string.arrange)) { board.arrange("optimal") }
                barIcon(R.drawable.ic_group, s(R.string.group)) { board.group(s(R.string.group)) }
            }
        }
        // a playing GIF or video already fills the bar: its rotation and size are in "more"
        if (one?.type != GIF && one?.type != VIDEO) {
            barIcon(R.drawable.ic_rotate_cw, s(R.string.rotation)) { rotateMenu(it) }
            barIcon(R.drawable.ic_scaling, s(R.string.size)) { sizeMenu(it) }
        }
        barIcon(R.drawable.ic_ellipsis, s(R.string.more)) { moreMenu(it) }
        barIcon(R.drawable.ic_trash, s(R.string.delete)) { board.delete() }
    }

    /** Everything that can be done to the selection, grouped: what PureRef has in its right-click menu. */
    private fun moreSections(): List<Section> {
        val sel = board.selected()
        val one = sel.singleOrNull()
        val media = sel.any { it.isMedia }
        val many = sel.size > 1 || sel.any { it.type == GROUP }
        val out = ArrayList<Section>()
        val special = ArrayList<Tile>()
        when (one?.type) {
            NOTE -> {
                special += Tile(R.drawable.ic_pencil, s(R.string.edit)) { noteEditor(one) }
                special += Tile(R.drawable.ic_palette, s(R.string.colors)) { noteColors() }
                val widths = listOf(0f to R.string.auto_size, 240f to R.string.narrow, 480f to R.string.medium, 800f to R.string.wide)
                special += widths.map { (w, n) -> Tile(R.drawable.ic_move_horizontal, s(n), one.noteWidth == w) { board.edit { if (it.type == NOTE) it.copy(noteWidth = w).withText(it.text) else it } } }
            }
            GIF -> board.gifOf(one)?.let { gif ->
                special += Tile(R.drawable.ic_gauge, s(R.string.speed)) { speed { gif.speed = it } }
                special += Tile(R.drawable.ic_sliders_horizontal, s(R.string.scrub)) { scrub(gif.time.toFloat() / gif.duration) { f -> gif.playing = false; gif.seek(f); board.invalidate() } }
                special += Tile(R.drawable.ic_image_plus, s(R.string.frame_to_image)) { board.frameToImage(one) }
            }
            VIDEO -> board.vidOf(one)?.let { vid ->
                special += Tile(R.drawable.ic_gauge, s(R.string.speed)) { speed { vid.speed = it } }
                special += Tile(R.drawable.ic_repeat, s(R.string.loop)) { loopEditor(one, vid) }
                special += Tile(R.drawable.ic_image_plus, s(R.string.frame_to_image)) { board.frameToImage(one) }
            }
            GROUP -> {
                special += Tile(R.drawable.ic_paint_bucket, s(R.string.group_color)) { pickColor(s(R.string.group_color), withNone = true, current = one.bgColor) { c -> board.edit { if (it.type == GROUP) it.copy(bgColor = c) else it } } }
                special += Tile(R.drawable.ic_ungroup, s(R.string.ungroup)) { board.ungroup() }
            }
        }
        if (special.isNotEmpty()) out += Section(label(one!!), Palette.PINK, special)
        out += Section(s(R.string.transform), Palette.BLUE, listOfNotNull(
            if (one?.isMedia == true) Tile(R.drawable.ic_crop, s(R.string.crop)) { board.startCrop() } else null,
            if (media) Tile(R.drawable.ic_rotate_ccw, s(R.string.reset_crop)) { board.resetCrop() } else null,
            Tile(R.drawable.ic_square_split_horizontal, s(R.string.flip_h)) { board.edit { it.copy(flipX = !it.flipX) } },
            Tile(R.drawable.ic_square_split_vertical, s(R.string.flip_v)) { board.edit { it.copy(flipY = !it.flipY) } },
            Tile(R.drawable.ic_rotate_ccw, s(R.string.reset)) { board.edit { it.copy(scale = 1f, rot = 0f, flipX = false, flipY = false) } },
            Tile(R.drawable.ic_contrast, s(R.string.gray), sel.all { it.gray }) { board.edit { it.copy(gray = !it.gray) } },
            Tile(R.drawable.ic_blend, s(R.string.opacity)) { opacity() },
            if (media) sel.filter { it.isMedia }.all { it.nearest }.let { px -> Tile(R.drawable.ic_grid_2x2, s(R.string.pixelated), px) { board.edit { if (it.isMedia) it.copy(nearest = !px) else it } } } else null,
            Tile(R.drawable.ic_crosshair, s(R.string.focus)) { board.focus(board.selected()) },
        ))
        out += Section(s(R.string.arrange), Palette.GREEN, listOf(
            Tile(R.drawable.ic_layout_dashboard, s(R.string.optimal)) { board.arrange("optimal") },
            Tile(R.drawable.ic_arrow_down_a_z, s(R.string.by_name)) { board.arrange("name") },
            Tile(R.drawable.ic_clock, s(R.string.by_addition)) { board.arrange("addition") },
            Tile(R.drawable.ic_list_ordered, s(R.string.by_order)) { board.arrange("order") },
            Tile(R.drawable.ic_folder_tree, s(R.string.by_path)) { board.arrange("path") },
            Tile(R.drawable.ic_shuffle, s(R.string.randomly)) { board.arrange("random") },
        ))
        if (many) {
            out += Section(s(R.string.align), Palette.TEAL, listOf(
                Tile(R.drawable.ic_align_horizontal_justify_start, s(R.string.left)) { board.align(0) },
                Tile(R.drawable.ic_align_horizontal_justify_end, s(R.string.right)) { board.align(1) },
                Tile(R.drawable.ic_align_vertical_justify_start, s(R.string.top)) { board.align(2) },
                Tile(R.drawable.ic_align_vertical_justify_end, s(R.string.bottom)) { board.align(3) },
                Tile(R.drawable.ic_align_horizontal_space_between, s(R.string.distribute_h)) { board.distribute(true) },
                Tile(R.drawable.ic_align_vertical_space_between, s(R.string.distribute_v)) { board.distribute(false) },
                Tile(R.drawable.ic_layers, s(R.string.stack)) { board.stack() },
            ))
            out += Section(s(R.string.normalize), Palette.YELLOW, listOf(
                Tile(R.drawable.ic_move_vertical, s(R.string.n_height)) { board.normalize("height") },
                Tile(R.drawable.ic_move_horizontal, s(R.string.n_width)) { board.normalize("width") },
                Tile(R.drawable.ic_maximize_2, s(R.string.n_size)) { board.normalize("size") },
                Tile(R.drawable.ic_square, s(R.string.n_area)) { board.normalize("area") },
                Tile(R.drawable.ic_scaling, s(R.string.n_scale)) { board.normalize("scale") },
                Tile(R.drawable.ic_arrow_left_right, s(if (Prefs.normalizeFirst) R.string.source_first else R.string.source_average), Prefs.normalizeFirst) {
                    Prefs.normalizeFirst = !Prefs.normalizeFirst
                },
            ))
        }
        out += Section(s(R.string.layers), Palette.PURPLE, listOfNotNull(
            Tile(R.drawable.ic_bring_to_front, s(R.string.front)) { board.toFront() },
            Tile(R.drawable.ic_send_to_back, s(R.string.back)) { board.toBack() },
            Tile(R.drawable.ic_group, s(R.string.group)) { board.group(s(R.string.group)) },
            if (sel.any { it.type == GROUP }) Tile(R.drawable.ic_ungroup, s(R.string.ungroup)) { board.ungroup() } else null,
            if (sel.size > 1) Tile(R.drawable.ic_link, s(R.string.parent)) { board.parentToFirst() } else null,
            if (sel.any { it.parent != 0L }) Tile(R.drawable.ic_unlink, s(R.string.unparent)) { board.edit { it.copy(parent = 0) } } else null,
            Tile(R.drawable.ic_lock, s(R.string.lock)) { board.toggleLock() },
        ))
        out += Section(s(R.string.item_section), Palette.ORANGE, listOfNotNull(
            if (one != null) Tile(R.drawable.ic_text_cursor_input, s(R.string.rename)) { editText(s(R.string.name), one.name) { board.rename(one, it) } } else null,
            if (one != null) Tile(R.drawable.ic_message_square, s(R.string.comment), one.comment.isNotEmpty()) {
                editText(s(R.string.comment), one.comment, allowEmpty = true) { c -> board.apply(listOf(one.copy(comment = c.trim()))) }
            } else null,
            if (one?.type == IMAGE || one?.type == GIF) Tile(R.drawable.ic_replace, s(R.string.replace)) { pickImages(REQ_REPLACE) } else null,
            if (one != null && one.source.startsWith("http")) Tile(R.drawable.ic_external_link, s(R.string.source)) { open(one.source) } else null,
            Tile(R.drawable.ic_copy_plus, s(R.string.duplicate)) { board.duplicate() },
            Tile(R.drawable.ic_tag, s(R.string.color_tag), sel.any { it.tag != 0 }) {
                pickColor(s(R.string.color_tag), withNone = true, current = one?.tag?.takeIf { it != 0 }) { c -> board.edit { it.copy(tag = c) } }
            },
            Tile(R.drawable.ic_link, s(R.string.item_link), one?.url?.isNotEmpty() == true) {
                editText(s(R.string.item_link).removeSuffix("…"), one?.url?.ifEmpty { "https://" } ?: "https://", allowEmpty = true) { u ->
                    val url = u.trim().takeIf { it.isNotEmpty() && it != "https://" }.orEmpty()
                    board.edit { it.copy(url = url) }
                }
            },
            one?.url?.takeIf { it.isNotEmpty() }?.let { u -> Tile(R.drawable.ic_external_link, s(R.string.open_link)) { openUrl(u) } },
            Tile(R.drawable.ic_copy, s(R.string.copy)) { copyItems() },
            Tile(R.drawable.ic_scissors, s(R.string.cut)) { if (board.copySelection()) { markClipboard(); board.delete() } },
            Tile(R.drawable.ic_presentation, s(R.string.slideshow)) { board.startSlideshow() },
            Tile(R.drawable.ic_image_down, s(R.string.as_one_png)) { exportItems = selectionWithChildren(); create(REQ_PNG, "image/png", "${board.scene.name}.png") },
            Tile(R.drawable.ic_images, s(R.string.as_separate)) { exportItems = selectionWithChildren(); exportFiles() },
            Tile(R.drawable.ic_trash, s(R.string.delete), color = Palette.RED) { board.delete() },
        ))
        return out
    }

    private fun selectionWithChildren() = board.withDescendants(board.selection).let { ids -> board.scene.items.filter { it.id in ids } }

    private fun moreSheet() = sheet(s(R.string.more), moreSections(), light)

    // ---- notes

    private fun noteColors() {
        val presets = listOf(
            Triple(R.string.light_on_dark, 0xFFFFFFFF.toInt(), 0xCC202020.toInt()),
            Triple(R.string.dark_on_light, 0xFF111111.toInt(), 0xEEF2F2F2.toInt()),
            Triple(R.string.sticky, 0xFF111111.toInt(), 0xFFFFE680.toInt()),
            Triple(R.string.white_no_bg, 0xFFFFFFFF.toInt(), 0),
            Triple(R.string.black_no_bg, 0xFF000000.toInt(), 0),
        )
        val cur = board.selected().firstOrNull()
        sheet(s(R.string.colors), listOf(Section(s(R.string.colors), Palette.PINK, presets.map { (n, fg, bgc) ->
            // the swatch shows the background, or the text color when there's none
            Tile(0, s(n), cur?.textColor == fg && cur.bgColor == bgc, swatch = if (bgc != 0) bgc or 0xFF000000.toInt() else fg) {
                board.edit { it.copy(textColor = fg, bgColor = bgc) }
            }
        })), light)
    }

    /** Rich-text note editor: bold, italic, underline, strike, size, color, highlight, font, alignment, bullets, checklists, links. */
    private fun noteEditor(item: Item?, node: Boolean = item?.node == true) {
        val et = EditText(this).apply {
            setText(Rich.build(item?.text ?: "", item?.spans ?: ""), TextView.BufferType.SPANNABLE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            // a node just dropped at the end of a link still has its placeholder name: typing replaces it
            if (item?.node == true && item.text == s(R.string.node)) selectAll() else setSelection(text.length)
        }
        // icon buttons on a few rows that wrap to the dialog width, no sideways scrolling
        val tools = GridLayout(this).apply { columnCount = 7 }
        fun tool(iconId: Int, desc: Int, f: (Editable, Int, Int) -> Unit) = tools.addView(ImageView(this).apply {
            setImageDrawable(ic(iconId, textColor))
            scaleType = ImageView.ScaleType.CENTER
            tip(this, s(desc))
            background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).run { getDrawable(0).also { recycle() } }
            setOnClickListener { f(et.text, min(et.selectionStart, et.selectionEnd), max(et.selectionStart, et.selectionEnd)) }
        }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = px(44) })
        tool(R.drawable.ic_bold, R.string.bold) { e, a, b -> toggleSpan(e, a, b, "b") }
        tool(R.drawable.ic_italic, R.string.italic) { e, a, b -> toggleSpan(e, a, b, "i") }
        tool(R.drawable.ic_underline, R.string.underline) { e, a, b -> toggleSpan(e, a, b, "u") }
        tool(R.drawable.ic_strikethrough, R.string.strikethrough) { e, a, b -> toggleSpan(e, a, b, "s") }
        tool(R.drawable.ic_a_arrow_up, R.string.bigger) { e, a, b -> resize(e, a, b, 1.25f) }
        tool(R.drawable.ic_a_arrow_down, R.string.smaller) { e, a, b -> resize(e, a, b, 0.8f) }
        tool(R.drawable.ic_type, R.string.font) { e, a, b ->
            val fonts = listOf("sans-serif", "serif", "monospace", "cursive")
            choose(s(R.string.font), fonts) { setSpan(e, a, b, "font", fonts[it]) }
        }
        tool(R.drawable.ic_baseline, R.string.color) { e, a, b -> pickColor(s(R.string.color), false) { c -> setSpan(e, a, b, "fg", c.toString()) } }
        tool(R.drawable.ic_highlighter, R.string.highlight) { e, a, b -> pickColor(s(R.string.highlight), true) { c -> setSpan(e, a, b, "hl", c.toString()) } }
        tool(R.drawable.ic_text_align_start, R.string.align_start) { e, a, b -> paragraphSpan(e, a, b, "align", Layout.Alignment.ALIGN_NORMAL.ordinal.toString()) }
        tool(R.drawable.ic_text_align_center, R.string.align_center) { e, a, b -> paragraphSpan(e, a, b, "align", Layout.Alignment.ALIGN_CENTER.ordinal.toString()) }
        tool(R.drawable.ic_text_align_end, R.string.align_end) { e, a, b -> paragraphSpan(e, a, b, "align", Layout.Alignment.ALIGN_OPPOSITE.ordinal.toString()) }
        tool(R.drawable.ic_link, R.string.link) { e, a, b -> editText(s(R.string.link), "https://") { setSpan(e, a, b, "url", it) } }
        tool(R.drawable.ic_remove_formatting, R.string.clear) { e, a, b -> clearSpans(e, a, b) }
        tool(R.drawable.ic_list, R.string.bullets) { e, a, b -> paragraphSpan(e, a, b, "bullet", "") }
        tool(R.drawable.ic_list_ordered, R.string.numbered) { e, a, b -> numbered(e, a, b) }
        tool(R.drawable.ic_list_checks, R.string.checklist) { e, a, b -> checklist(e, a, b) }
        tool(R.drawable.ic_list_indent_increase, R.string.indent_more) { e, a, b -> indent(e, a, b, 1) }
        tool(R.drawable.ic_list_indent_decrease, R.string.indent_less) { e, a, b -> indent(e, a, b, -1) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, px(12), 0)
            addView(tools, LinearLayout.LayoutParams(-1, -2))
            addView(et)
        }
        AlertDialog.Builder(this).setTitle(if (item == null) (if (node) R.string.add_node else R.string.add_note) else if (node) R.string.node else R.string.note).setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                BaseInputConnection.removeComposingSpans(et.text) // the keyboard's underline under the word being typed
                val text = et.text.toString()
                if (text.isBlank()) return@setPositiveButton
                val spans = Rich.toJson(et.text)
                if (item == null) board.addNote(text, spans, node = node) else board.editNote(item.id, text, spans)
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

    /** Color swatches. For backgrounds, colors come translucent and "none" is offered. */
    private fun pickColor(title: String, withNone: Boolean, current: Int? = null, f: (Int) -> Unit) {
        val list = palette.map { (n, c) -> s(n) to if (withNone) (c and 0xFFFFFF) or 0x50000000 else c } + if (withNone) listOf(s(R.string.c_none) to 0) else emptyList()
        sheet(title, listOf(Section(title, Palette.PINK, list.map { (n, c) -> Tile(0, n, c == current, swatch = c) { f(c) } })), light)
    }

    private fun speed(f: (Float) -> Unit) {
        val v = floatArrayOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)
        sheet(s(R.string.speed), listOf(Section(s(R.string.speed), Palette.BLUE, v.map { x -> Tile(R.drawable.ic_gauge, "$x×") { f(x) } })), light)
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

    private fun typeIcon(it: Item) = when (it.type) {
        NOTE -> R.drawable.ic_sticky_note; GROUP -> R.drawable.ic_group; DRAW -> R.drawable.ic_pen_line
        GIF -> R.drawable.ic_image_play; VIDEO -> R.drawable.ic_film; else -> R.drawable.ic_image
    }

    /** Tree of everything in the scene. Tap: select and focus. Long-press: rename, reorder, unparent, lock, delete. */
    private fun hierarchy() {
        val items = board.ordered()
        if (items.isEmpty()) return
        val dlg = choose(s(R.string.hierarchy), items.map(::label), items.map { ic(typeIcon(it), textColor) },
            ends = items.map { if (it.locked) ic(R.drawable.ic_lock, textColor) else if (it.comment.isNotEmpty()) ic(R.drawable.ic_message_square, textColor) else null },
            indent = items.map { board.depth(it) }) { i -> board.select(listOf(items[i])); board.focus(listOf(items[i])) }
        dlg.listView.setOnItemLongClickListener { _, _, i, _ ->
            dlg.dismiss()
            val it = items[i]
            sheet(label(it), listOf(Section(s(R.string.hierarchy), Palette.PURPLE, listOf(
                Tile(R.drawable.ic_text_cursor_input, s(R.string.rename)) { editText(s(R.string.name), it.name) { n -> board.rename(it, n) } },
                Tile(R.drawable.ic_bring_to_front, s(R.string.move_up)) { board.moveAmongSiblings(it, -1); hierarchy() },
                Tile(R.drawable.ic_send_to_back, s(R.string.move_down)) { board.moveAmongSiblings(it, 1); hierarchy() },
                Tile(R.drawable.ic_link, s(R.string.parent_to)) {
                    val others = items.filter { o -> o.id !in board.withDescendants(listOf(it.id)) }
                    choose(s(R.string.parent_to), others.map(::label), others.map { o -> ic(typeIcon(o), textColor) }) { j -> board.setParent(it, others[j].id) }
                },
                Tile(R.drawable.ic_unlink, s(R.string.unparent)) { board.unparent(it) },
                Tile(if (it.locked) R.drawable.ic_lock_open else R.drawable.ic_lock, s(if (it.locked) R.string.unlock else R.string.lock)) { board.apply(listOf(it.copy(locked = !it.locked))) },
                Tile(R.drawable.ic_trash, s(R.string.delete), color = Palette.RED) { board.select(listOf(it)); board.delete() },
            ))), light)
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
        val m = mainSections()
        sheet(s(R.string.canvas), listOf(m[0], Section(s(R.string.board_section), Palette.BLUE, listOf(
            Tile(R.drawable.ic_scan, s(R.string.select_all)) { board.selectAll() },
            Tile(R.drawable.ic_maximize, s(R.string.focus_canvas)) { board.focusAll() },
            Tile(R.drawable.ic_layout_dashboard, s(R.string.arrange_all)) { board.clearSelection(); board.arrange("optimal") },
            Tile(if (Prefs.canvasLocked) R.drawable.ic_lock_open else R.drawable.ic_lock, s(if (Prefs.canvasLocked) R.string.unlock_canvas else R.string.lock_canvas)) { toggleCanvasLock() },
        ))), light)
    }

    override fun itemMenu() { moreSheet() }

    override fun editItem(item: Item) {
        if (item.type == NOTE) noteEditor(item) else if (item.type == DRAW) board.startDrawing(item)
    }

    override fun picked(info: String, hex: String) {
        this.info.setCompoundDrawablesRelative(null, null, null, null)
        val h = if (Prefs.hexHash) hex else hex.removePrefix("#")
        this.info.text = info + "\n" + s(R.string.tap_to_copy, h)
        infoHex = h
    }

    private fun setPicking(on: Boolean) {
        board.picking = on
        info.visibility = if (on) View.VISIBLE else View.GONE
        info.setCompoundDrawablesRelative(ic(R.drawable.ic_pipette, Color.WHITE).apply { setBounds(0, 0, px(18), px(18)) }, null, null, null)
        info.compoundDrawablePadding = px(8)
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

    private var browser: SceneBrowser? = null

    /** The scene browser: previews in a grid, one level of folders. */
    private fun openScene() {
        browser = SceneBrowser(this, light, { board.scene },
            open = { d -> if (d != board.scene.dir) switchTo(Scene.open(d)) },
            sceneMenu = ::sceneMenu,
            folderMenu = ::folderMenu,
            newScene = { f -> browser?.dismiss(); switchTo(Scene.create(this, s(R.string.untitled)).apply { folder = f; save() }) },
            newFolder = { editText(s(R.string.new_folder), "") { n -> addFolder(n.trim()); browser?.refresh() } },
        ).also { it.show() }
    }

    /** The scene on screen is edited in place, so its next save doesn't undo the change. */
    private fun sceneAt(d: File) = if (d == board.scene.dir) board.scene else Scene.peek(d)

    private fun addFolder(name: String) { if (name.isNotEmpty() && name !in Prefs.folders.lines()) Prefs.folders = (Prefs.folders.lines() + name).filter { it.isNotEmpty() }.joinToString("\n") }

    private fun sceneMenu(d: File) {
        val sc = sceneAt(d)
        sheet(sc.name, listOf(Section(s(R.string.file), Palette.ORANGE, listOf(
            Tile(R.drawable.ic_pencil_line, s(R.string.rename)) {
                editText(s(R.string.scene_name), sc.name) { sc.name = it; sc.saveKeepTime(); if (sc === board.scene) remember(sc); browser?.refresh() }
            },
            Tile(R.drawable.ic_folder_input, s(R.string.move_to_folder)) {
                val folders = (Prefs.folders.lines() + Scene.list(this).map { sceneAt(it).folder }).filter { it.isNotEmpty() }.distinct().sorted()
                val names = listOf(s(R.string.no_folder)) + folders + s(R.string.new_folder)
                choose(s(R.string.move_to_folder), names) { i ->
                    fun move(f: String) { sc.folder = f; sc.saveKeepTime(); browser?.refresh() }
                    when (i) {
                        0 -> move("")
                        names.size - 1 -> editText(s(R.string.new_folder), "") { n -> addFolder(n.trim()); move(n.trim()) }
                        else -> move(folders[i - 1])
                    }
                }
            },
            Tile(R.drawable.ic_copy, s(R.string.duplicate_scene)) { sc.save(); sc.copy(this, s(R.string.copy_of, sc.name)); browser?.refresh() },
            Tile(R.drawable.ic_trash, s(R.string.delete_scene), color = Palette.RED) { deleteScene(d) },
        ))), light)
    }

    private fun folderMenu(name: String) {
        fun each(f: (Scene) -> Unit) = Scene.list(this).map(::sceneAt).filter { it.folder == name }.forEach { f(it); it.saveKeepTime() }
        sheet(name, listOf(Section(s(R.string.edit), Palette.ORANGE, listOf(
            Tile(R.drawable.ic_pencil_line, s(R.string.rename_folder)) {
                editText(s(R.string.folder_name), name) { n ->
                    val t = n.trim()
                    Prefs.folders = Prefs.folders.lines().map { if (it == name) t else it }.joinToString("\n")
                    each { it.folder = t }
                    browser?.refresh()
                }
            },
            Tile(R.drawable.ic_trash, s(R.string.remove_folder), color = Palette.RED) {
                Prefs.folders = Prefs.folders.lines().filter { it != name }.joinToString("\n")
                each { it.folder = "" }
                browser?.refresh()
            },
        ))), light)
    }

    private fun deleteScene(d: File = board.scene.dir) {
        AlertDialog.Builder(this).setMessage(s(R.string.delete_scene_q, sceneAt(d).name))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (d == board.scene.dir) {
                    val next = Scene.list(this).firstOrNull { it != d }?.let { Scene.open(it) } ?: Scene.create(this, s(R.string.untitled))
                    switchTo(next)
                }
                d.deleteRecursively()
                browser?.refresh()
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
        // PureRef has no links: they go in as drawings
        var fake = -1L // ids only used inside the written file
        val items = board.ordered().let { it + linksAsDrawings(board.scene.links, it) { fake-- } }
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
        val links = board.scene.links.toList()
        val dir = board.scene.images
        val gray = Prefs.canvasGray
        toast(s(R.string.exporting))
        thread {
            val r = runCatching {
                val bmp = renderItems(items, dir, 4096, gray, links)
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

    private class Opt(val icon: Int, val label: String, val values: List<String>, val get: () -> Int, val set: (Int) -> Unit)

    private fun settingsOpts(): List<Opt> {
        val onOff = listOf(s(R.string.off), s(R.string.on))
        fun flag(icon: Int, label: Int, get: () -> Boolean, set: (Boolean) -> Unit) = Opt(icon, s(label), onOff, { if (get()) 1 else 0 }) { set(it == 1) }
        fun <T> pick(icon: Int, label: Int, values: List<T>, names: List<String>, get: () -> T, set: (T) -> Unit) =
            Opt(icon, s(label), names, { values.indexOf(get()).coerceAtLeast(0) }) { set(values[it]) }
        val langs = listOf("", "en", "fr", "es", "ja", "ko", "zh-CN", "zh-TW")
        val langNames = listOf(s(R.string.lang_system), "English", "Français", "Español", "日本語", "한국어", "简体中文", "繁體中文")
        return listOf(
            pick(R.drawable.ic_sun, R.string.theme, listOf(0, 1, 2), listOf(s(R.string.theme_dark), s(R.string.theme_light), s(R.string.theme_glass)), { Prefs.theme }) { Prefs.theme = it },
            pick(R.drawable.ic_languages, R.string.language, langs, langNames, { Prefs.language }) { Prefs.language = it },
            pick(R.drawable.ic_undo_2, R.string.undo_size, listOf(50, 200, 500, 1000), listOf("50", "200", "500", "1000"), { Prefs.undoLimit }) { Prefs.undoLimit = it },
            flag(R.drawable.ic_layout_dashboard, R.string.pack_new, { Prefs.packNew }) { Prefs.packNew = it },
            pick(R.drawable.ic_arrow_down_a_z, R.string.pack_by, listOf(0, 1, 2), listOf(s(R.string.optimal), s(R.string.by_name), s(R.string.by_addition)), { Prefs.packBy }) { Prefs.packBy = it },
            flag(R.drawable.ic_bring_to_front, R.string.bring_front, { Prefs.bringToFront }) { Prefs.bringToFront = it },
            flag(R.drawable.ic_crosshair, R.string.dbl_focus, { Prefs.doubleTapFocus }) { Prefs.doubleTapFocus = it },
            pick(R.drawable.ic_square, R.string.borders, listOf(0, 1, 2), listOf(s(R.string.borders_all), s(R.string.borders_selected), s(R.string.off)), { Prefs.borders }) { Prefs.borders = it },
            pick(R.drawable.ic_move_horizontal, R.string.padding, listOf(0, 5, 10, 20, 40), listOf("0", "5", "10", "20", "40"), { Prefs.padding }) { Prefs.padding = it },
            pick(R.drawable.ic_grid_2x2, R.string.sampling, listOf(false, true), listOf(s(R.string.smooth), s(R.string.pixelated)), { Prefs.nearest }) { Prefs.nearest = it },
            pick(R.drawable.ic_clock, R.string.slide_interval, listOf(3, 5, 10, 20, 30, 60), listOf(3, 5, 10, 20, 30, 60).map { s(R.string.seconds, it) }, { Prefs.slideSeconds }) { Prefs.slideSeconds = it },
            pick(R.drawable.ic_shuffle, R.string.slide_order, listOf(0, 1, 2), listOf(s(R.string.ordered), s(R.string.shuffle), s(R.string.randomly)), { Prefs.slideOrder }) { Prefs.slideOrder = it },
            flag(R.drawable.ic_group, R.string.drag_groups, { Prefs.dragIntoGroups }) { Prefs.dragIntoGroups = it },
            flag(R.drawable.ic_link, R.string.auto_parent, { Prefs.autoParentNotes }) { Prefs.autoParentNotes = it },
            flag(R.drawable.ic_pipette, R.string.hex_hash, { Prefs.hexHash }) { Prefs.hexHash = it },
            pick(R.drawable.ic_image, R.string.quality, listOf(1024, 2048, 4096), listOf("1024 px", "2048 px", "4096 px"), { Prefs.quality }) {
                Prefs.quality = it
                board.setScene(board.scene) // reload images at the new size
            },
        )
    }

    /** Settings list, plus export / import of all settings as a file (PureRef 2). */
    private fun showSettings() {
        val opts = settingsOpts()
        val extra = listOf(s(R.string.export_settings), s(R.string.import_settings))
        val icons = opts.map { ic(it.icon, textColor) } + listOf(ic(R.drawable.ic_upload, textColor), ic(R.drawable.ic_download, textColor))
        choose(s(R.string.settings), opts.map { "${it.label}: ${it.values[it.get()]}" } + extra, icons) { i ->
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

    /** Search every command: the main panel, what can be done to the selection, settings (PureRef's command palette). */
    private fun palette() {
        val opts = settingsOpts()
        val sections = mainSections() + if (board.selection.isNotEmpty()) moreSections() else emptyList()
        val all = sections.flatMap { sec -> sec.tiles.map { t -> Triple(t.icon, "${sec.title} › ${t.label}", t.f) } }.distinctBy { it.second } +
            listOf(Triple(R.drawable.ic_undo_2, s(R.string.undo), { board.undo() }), Triple(R.drawable.ic_redo_2, s(R.string.redo), { board.redo() })) +
            opts.mapIndexed { i, o -> Triple(o.icon, "${s(R.string.settings)} › ${o.label}", { editSetting(i, o, reopen = false) }) }
        var shown = all
        val adapter = iconAdapter(ArrayList(all.map { it.second }), { shown[it].first.takeIf { id -> id != 0 }?.let { id -> ic(id, textColor) } })
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
                shown = all.filter { it.second.lowercase().contains(q) }
                adapter.clear()
                adapter.addAll(shown.map { it.second })
            }
            override fun beforeTextChanged(t: CharSequence, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence, a: Int, b: Int, c: Int) {}
        })
        input.setOnEditorActionListener { _, _, _ -> shown.firstOrNull()?.let { dlg.dismiss(); it.third() }; true }
        list.setOnItemClickListener { _, _, i, _ -> dlg.dismiss(); shown[i].third() }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        input.requestFocus()
    }

    // ---- keyboard (tablets with a keyboard cover, Chromebooks): PureRef's default shortcuts

    /** A shortcut: key + exactly these modifiers (KeyEvent.META_CTRL_ON / SHIFT / ALT), its name in the shortcut list, what it does. */
    private class Key(val code: Int, val mods: Int, val label: String, val f: () -> Unit)

    private fun flip(h: Boolean) = board.edit { if (h) it.copy(flipX = !it.flipX) else it.copy(flipY = !it.flipY) }

    /** Grouped like PureRef's list. The same table runs the keys and fills Android's shortcut list (Ctrl+H or Meta+/). */
    private fun keys(): List<Pair<String, List<Key>>> {
        val C = KeyEvent.META_CTRL_ON
        val S = KeyEvent.META_SHIFT_ON
        val A = KeyEvent.META_ALT_ON
        val sel = board.selection.isNotEmpty()
        val out = ArrayList<Pair<String, List<Key>>>()
        if (board.drawing) out += s(R.string.drawing) to listOf(
            Key(KeyEvent.KEYCODE_D, 0, s(R.string.pen)) { Prefs.penTool = T_PEN; selectionChanged() },
            Key(KeyEvent.KEYCODE_L, 0, s(R.string.line)) { Prefs.penTool = T_LINE; selectionChanged() },
            Key(KeyEvent.KEYCODE_R, 0, s(R.string.rectangle)) { Prefs.penTool = T_RECT; selectionChanged() },
            Key(KeyEvent.KEYCODE_C, 0, s(R.string.ellipse)) { Prefs.penTool = T_ELLIPSE; selectionChanged() },
            Key(KeyEvent.KEYCODE_E, 0, s(R.string.eraser)) { Prefs.penTool = T_ERASER; selectionChanged() },
            Key(KeyEvent.KEYCODE_1, 0, s(R.string.solid)) { Prefs.penStyle = S_SOLID },
            Key(KeyEvent.KEYCODE_2, 0, s(R.string.dotted)) { Prefs.penStyle = S_DOTTED },
            Key(KeyEvent.KEYCODE_3, 0, s(R.string.arrow)) { Prefs.penStyle = S_ARROW },
            Key(KeyEvent.KEYCODE_T, C, s(R.string.color)) { pickColor(s(R.string.color), withNone = false, current = Prefs.penColor) { Prefs.penColor = it; selectionChanged() } },
            Key(KeyEvent.KEYCODE_D, C or S, s(R.string.done)) { board.stopDrawing() },
        )
        out += s(R.string.file) to listOf(
            Key(KeyEvent.KEYCODE_K, C, s(R.string.new_scene)) { switchTo(Scene.create(this, s(R.string.untitled))) },
            Key(KeyEvent.KEYCODE_L, C or A, s(R.string.my_scenes)) { openScene() },
            Key(KeyEvent.KEYCODE_L, C or S, s(R.string.recent_scene)) { previousScene() },
            Key(KeyEvent.KEYCODE_L, C, s(R.string.open_pur)) { openPur() },
            Key(KeyEvent.KEYCODE_O, C, s(R.string.open_pur)) { openPur() },
            Key(KeyEvent.KEYCODE_S, C, s(R.string.save)) { save() },
            Key(KeyEvent.KEYCODE_S, C or S, s(R.string.save_as)) { saveAs() },
            Key(KeyEvent.KEYCODE_E, C, s(R.string.export_png)) { exportItems = board.scene.items.toList(); create(REQ_PNG, "image/png", "${board.scene.name}.png") },
            Key(KeyEvent.KEYCODE_I, C or A, s(R.string.export_images)) { exportItems = board.ordered(); exportFiles() },
            Key(KeyEvent.KEYCODE_I, C or S, s(R.string.as_separate)) { if (sel) { exportItems = selectionWithChildren(); exportFiles() } },
            Key(KeyEvent.KEYCODE_I, C, s(R.string.add_images)) { pickMedia(REQ_ADD, multiple = true) },
            Key(KeyEvent.KEYCODE_N, C, s(R.string.add_note)) { noteEditor(null) },
            Key(KeyEvent.KEYCODE_V, C, s(R.string.paste)) { paste() },
            Key(KeyEvent.KEYCODE_P, C or S, s(R.string.command_palette)) { palette() },
            Key(KeyEvent.KEYCODE_U, C, s(R.string.settings)) { showSettings() },
            Key(KeyEvent.KEYCODE_H, C, s(R.string.guide)) { keyboardGuide() },
            Key(KeyEvent.KEYCODE_1, A, s(R.string.theme_dark)) { Prefs.theme = 0; recreate() },
            Key(KeyEvent.KEYCODE_2, A, s(R.string.theme_light)) { Prefs.theme = 1; recreate() },
            Key(KeyEvent.KEYCODE_3, A, s(R.string.theme_glass)) { Prefs.theme = 2; recreate() },
        )
        out += s(R.string.edit) to listOf(
            Key(KeyEvent.KEYCODE_Z, C, s(R.string.undo)) { board.undo() },
            Key(KeyEvent.KEYCODE_Z, C or S, s(R.string.redo)) { board.redo() },
            Key(KeyEvent.KEYCODE_Y, C, s(R.string.redo)) { board.redo() },
            Key(KeyEvent.KEYCODE_C, C, s(R.string.copy)) { copyItems() },
            Key(KeyEvent.KEYCODE_X, C, s(R.string.cut)) { if (board.copySelection()) { markClipboard(); board.delete() } },
            Key(KeyEvent.KEYCODE_D, C, s(R.string.duplicate)) { board.duplicate() },
            Key(KeyEvent.KEYCODE_A, C, s(R.string.select_all)) { board.selectAll() },
            Key(KeyEvent.KEYCODE_FORWARD_DEL, 0, s(R.string.delete)) { board.delete() },
            Key(KeyEvent.KEYCODE_DEL, 0, s(R.string.delete)) { board.delete() },
            Key(KeyEvent.KEYCODE_ENTER, 0, s(R.string.edit)) { board.selected().singleOrNull()?.let(::editItem) },
            Key(KeyEvent.KEYCODE_C, A, s(R.string.comment)) {
                board.selected().singleOrNull()?.let { one -> editText(s(R.string.comment), one.comment, allowEmpty = true) { c -> board.apply(listOf(one.copy(comment = c.trim()))) } }
            },
            Key(KeyEvent.KEYCODE_O, C or S, s(R.string.source)) { board.selected().singleOrNull()?.source?.takeIf { it.startsWith("http") }?.let(::open) },
            Key(KeyEvent.KEYCODE_D, C or S, s(R.string.draw)) { board.startDrawing() },
            Key(KeyEvent.KEYCODE_ESCAPE, 0, s(R.string.done)) { back() },
        )
        out += s(R.string.transform) to listOf(
            Key(KeyEvent.KEYCODE_H, A or S, s(R.string.flip_h)) { flip(true) },
            Key(KeyEvent.KEYCODE_V, A or S, s(R.string.flip_v)) { flip(false) },
            Key(KeyEvent.KEYCODE_T, C or S, s(R.string.reset)) { board.edit { it.copy(scale = 1f, rot = 0f, flipX = false, flipY = false) } },
            Key(KeyEvent.KEYCODE_C, C or A or S, s(R.string.crop)) { if (board.selected().singleOrNull()?.isMedia == true) board.startCrop() },
            Key(KeyEvent.KEYCODE_C, C or S, s(R.string.reset_crop)) { board.resetCrop() },
            Key(KeyEvent.KEYCODE_G, A, s(R.string.gray)) { val g = board.selected().all { it.gray }; board.edit { it.copy(gray = !g) } },
            Key(KeyEvent.KEYCODE_T, A, s(R.string.pixelated)) { val px = board.selected().filter { it.isMedia }.all { it.nearest }; board.edit { if (it.isMedia) it.copy(nearest = !px) else it } },
            Key(KeyEvent.KEYCODE_L, A, s(R.string.lock)) { board.toggleLock() },
        )
        out += s(R.string.arrange) to listOf(
            Key(KeyEvent.KEYCODE_P, C, s(R.string.optimal)) { board.arrange("optimal") },
            Key(KeyEvent.KEYCODE_N, C or A, s(R.string.by_name)) { board.arrange("name") },
            Key(KeyEvent.KEYCODE_A, C or A, s(R.string.by_addition)) { board.arrange("addition") },
            Key(KeyEvent.KEYCODE_O, C or A, s(R.string.by_order)) { board.arrange("order") },
            Key(KeyEvent.KEYCODE_D, C or A, s(R.string.by_path)) { board.arrange("path") },
            Key(KeyEvent.KEYCODE_R, C or A, s(R.string.randomly)) { board.arrange("random") },
            Key(KeyEvent.KEYCODE_S, C or A, s(R.string.stack)) { board.stack() },
            Key(KeyEvent.KEYCODE_DPAD_LEFT, C, s(R.string.left)) { board.align(0) },
            Key(KeyEvent.KEYCODE_DPAD_RIGHT, C, s(R.string.right)) { board.align(1) },
            Key(KeyEvent.KEYCODE_DPAD_UP, C, s(R.string.top)) { board.align(2) },
            Key(KeyEvent.KEYCODE_DPAD_DOWN, C, s(R.string.bottom)) { board.align(3) },
            Key(KeyEvent.KEYCODE_DPAD_UP, C or A or S, s(R.string.distribute_h)) { board.distribute(true) },
            Key(KeyEvent.KEYCODE_DPAD_DOWN, C or A or S, s(R.string.distribute_v)) { board.distribute(false) },
            Key(KeyEvent.KEYCODE_DPAD_LEFT, C or A, s(R.string.n_height)) { board.normalize("height") },
            Key(KeyEvent.KEYCODE_DPAD_RIGHT, C or A, s(R.string.n_width)) { board.normalize("width") },
            Key(KeyEvent.KEYCODE_DPAD_UP, C or A, s(R.string.n_size)) { board.normalize("size") },
            Key(KeyEvent.KEYCODE_DPAD_DOWN, C or A, s(R.string.n_scale)) { board.normalize("scale") },
        )
        out += s(R.string.layers) to listOf(
            Key(KeyEvent.KEYCODE_G, C, s(R.string.group)) { board.group(s(R.string.group)) },
            Key(KeyEvent.KEYCODE_G, C or S, s(R.string.ungroup)) { board.ungroup() },
            Key(KeyEvent.KEYCODE_P, 0, s(R.string.parent)) { board.parentToFirst() },
            Key(KeyEvent.KEYCODE_DPAD_UP, 0, s(R.string.front)) { board.toFront() },
            Key(KeyEvent.KEYCODE_DPAD_DOWN, 0, s(R.string.back)) { board.toBack() },
            Key(KeyEvent.KEYCODE_J, C, s(R.string.hierarchy)) { hierarchy() },
        )
        out += s(R.string.view) to listOf(
            Key(KeyEvent.KEYCODE_SPACE, 0, s(R.string.focus)) { board.focus(board.selected()) },
            Key(KeyEvent.KEYCODE_SPACE, C, s(R.string.focus_canvas)) { board.focusAll() },
            Key(KeyEvent.KEYCODE_DPAD_RIGHT, 0, s(R.string.next)) { if (board.slides.isNotEmpty()) board.slideStep(1) else board.focusStep(1) },
            Key(KeyEvent.KEYCODE_DPAD_LEFT, 0, s(R.string.prev)) { if (board.slides.isNotEmpty()) board.slideStep(-1) else board.focusStep(-1) },
            Key(KeyEvent.KEYCODE_EQUALS, C, s(R.string.zoom_in)) { board.zoomBy(1.25f) },
            Key(KeyEvent.KEYCODE_PLUS, C, s(R.string.zoom_in)) { board.zoomBy(1.25f) },
            Key(KeyEvent.KEYCODE_NUMPAD_ADD, C, s(R.string.zoom_in)) { board.zoomBy(1.25f) },
            Key(KeyEvent.KEYCODE_MINUS, C, s(R.string.zoom_out)) { board.zoomBy(0.8f) },
            Key(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, C, s(R.string.zoom_out)) { board.zoomBy(0.8f) },
            Key(KeyEvent.KEYCODE_0, C, s(R.string.reset_zoom)) { board.resetZoom() },
            Key(KeyEvent.KEYCODE_R, C, s(R.string.lock_canvas)) { toggleCanvasLock() },
            Key(KeyEvent.KEYCODE_G, C or A, s(R.string.grayscale_canvas)) { Prefs.canvasGray = !Prefs.canvasGray; board.invalidate() },
            Key(KeyEvent.KEYCODE_G, 0, s(R.string.toggle_grid)) { Prefs.grid = if (Prefs.grid == 0) 2 else 0; board.invalidate() },
            Key(KeyEvent.KEYCODE_S, A, s(R.string.slideshow)) { board.startSlideshow() },
            Key(KeyEvent.KEYCODE_A, C or S, s(R.string.always_on_top)) { alwaysOnTop() },
        )
        return out
    }

    /** How the mouse, touchpad and keys work; Android's own list only shows keys. */
    private fun keyboardGuide() {
        AlertDialog.Builder(this).setTitle(R.string.guide).setMessage(R.string.guide_text)
            .setPositiveButton(R.string.all_shortcuts) { _, _ -> requestShowKeyboardShortcuts() }
            .setNegativeButton(R.string.close, null).show()
    }

    /** Once, the first time a keyboard is plugged in or the cover is attached. */
    private fun keyboardHint() {
        if (resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_QWERTY || Prefs.keyboardHinted) return
        Prefs.keyboardHinted = true
        Toast.makeText(this, s(R.string.keyboard_hint), Toast.LENGTH_LONG).show()
    }

    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        keyboardHint()
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        val mods = (if (e.isCtrlPressed) KeyEvent.META_CTRL_ON else 0) or (if (e.isShiftPressed) KeyEvent.META_SHIFT_ON else 0) or
            (if (e.isAltPressed) KeyEvent.META_ALT_ON else 0)
        val k = keys().firstNotNullOfOrNull { (_, ks) -> ks.firstOrNull { it.code == code && it.mods == mods } } ?: return super.onKeyDown(code, e)
        k.f()
        return true
    }

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        for ((title, ks) in keys()) data += KeyboardShortcutGroup(title, ks.distinctBy { it.label }.map { KeyboardShortcutInfo(it.label, it.code, it.mods) })
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

    /** List rows with an icon at the start (and optionally one at the end, and an indent level). */
    private fun iconAdapter(items: MutableList<String>, start: (Int) -> Drawable?, end: (Int) -> Drawable? = { null }, indent: (Int) -> Int = { 0 }) =
        object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(pos: Int, convert: View?, parent: ViewGroup): View = (super.getView(pos, convert, parent) as TextView).apply {
                setCompoundDrawablesRelative(start(pos)?.apply { setBounds(0, 0, px(22), px(22)) }, null, end(pos)?.apply { setBounds(0, 0, px(18), px(18)) }, null)
                compoundDrawablePadding = px(16)
                setPaddingRelative(px(24 + indent(pos) * 22), paddingTop, px(24), paddingBottom)
            }
        }

    /** A list to pick from; with icons when given. Returns the dialog (the hierarchy adds a long-press). */
    private fun choose(title: String, items: List<String>, icons: List<Drawable?>? = null, ends: List<Drawable?>? = null,
                       indent: List<Int>? = null, f: (Int) -> Unit): AlertDialog {
        val b = AlertDialog.Builder(this).setTitle(title)
        if (icons == null) b.setItems(items.toTypedArray()) { _, w -> f(w) }
        else b.setAdapter(iconAdapter(items.toMutableList(), { icons[it] }, { ends?.get(it) }, { indent?.get(it) ?: 0 })) { _, w -> f(w) }
        return b.show()
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
