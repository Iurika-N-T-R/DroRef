package org.droref

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** One choice in a sheet: an icon (or a color swatch) with a short label. */
class Tile(
    val icon: Int,
    val label: String,
    val checked: Boolean = false,
    val swatch: Int? = null,
    val keepOpen: Boolean = false,
    val color: Int? = null, // overrides the section color, e.g. red for delete
    val f: () -> Unit,
)

/** A titled group of tiles; the color gives each group its own look so it's found at a glance. */
class Section(val title: String, val color: Int, val tiles: List<Tile>)

object Palette {
    const val GREEN = 0xFF34C77B.toInt()
    const val BLUE = 0xFF4F9DFF.toInt()
    const val ORANGE = 0xFFFF8A3D.toInt()
    const val PURPLE = 0xFFA77BFF.toInt()
    const val TEAL = 0xFF26C6DA.toInt()
    const val PINK = 0xFFFF5C93.toInt()
    const val YELLOW = 0xFFFFB300.toInt()
    const val RED = 0xFFFF5252.toInt()
}

/**
 * Bottom sheet of icon tiles grouped in colored sections, with optional header buttons (search, settings…).
 * Replaces long text menus: actions are grouped by purpose, settings live elsewhere.
 */
fun Activity.sheet(
    title: String,
    sections: List<Section>,
    light: Boolean,
    header: List<Triple<Int, String, () -> Unit>> = emptyList(),
): Dialog {
    val dp = resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    val fg = if (light) 0xFF202124.toInt() else 0xFFECECEC.toInt()
    val bg = if (light) 0xFFFFFFFF.toInt() else 0xFF232325.toInt()
    val dlg = Dialog(this)

    fun tint(id: Int, color: Int) = getDrawable(id)!!.mutate().apply { setTint(color) }

    fun tile(t: Tile, color: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(px(2), px(8), px(2), px(6))
        background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            when {
                t.swatch != null -> {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(t.swatch)
                        setStroke(px(if (t.checked) 3 else 1), if (t.checked) fg else 0x55808080)
                    }
                    if (Color.alpha(t.swatch) == 0) setImageDrawable(tint(R.drawable.ic_x, fg))
                }
                else -> {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (t.checked) color else (color and 0xFFFFFF) or 0x2E000000) }
                    setImageDrawable(tint(t.icon, if (t.checked) Color.WHITE else color))
                }
            }
        }
        addView(icon, LinearLayout.LayoutParams(px(52), px(52)))
        addView(TextView(context).apply {
            text = t.label.removeSuffix("…") // "…" means "opens a dialog" in menus; on a tile it just looks cut off
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(fg)
            setPadding(0, px(6), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // the tile already says it
        }, LinearLayout.LayoutParams(-1, -2))
        contentDescription = t.label
        setOnClickListener {
            if (!t.keepOpen) dlg.dismiss()
            t.f()
        }
    }

    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(10), px(16), px(20))
    }
    col.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = 2 * dp; setColor((fg and 0xFFFFFF) or 0x44000000) } },
        LinearLayout.LayoutParams(px(36), px(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(8) })
    col.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = title.removeSuffix("…")
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        for ((ic, desc, f) in header) addView(ImageView(context).apply {
            setImageDrawable(tint(ic, fg))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = desc
            if (Build.VERSION.SDK_INT >= 26) tooltipText = desc
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((fg and 0xFFFFFF) or 0x14000000) }
            setOnClickListener { dlg.dismiss(); f() }
        }, LinearLayout.LayoutParams(px(42), px(42)).apply { marginStart = px(8) })
    })

    val widthDp = resources.displayMetrics.widthPixels / dp
    val cols = min(6, max(4, (widthDp / 86).toInt()))
    for (sec in sections) {
        if (sec.tiles.isEmpty()) continue
        col.addView(TextView(this).apply {
            text = sec.title.uppercase()
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(sec.color)
            setPadding(px(4), px(18), 0, px(4))
        })
        val grid = GridLayout(this).apply { columnCount = cols }
        fun cell() = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 }
        sec.tiles.forEach { grid.addView(tile(it, it.color ?: sec.color), cell()) }
        repeat((cols - sec.tiles.size % cols) % cols) { grid.addView(Space(this), cell()) } // equal widths on the last row
        col.addView(grid, LinearLayout.LayoutParams(-1, -2))
    }

    val r = 24 * dp
    val scroll = SwipeDownScroll(this) { dlg.dismiss() }.apply {
        addView(col)
        background = GradientDrawable().apply { cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f); setColor(bg) }
    }
    dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dlg.setContentView(scroll)
    dlg.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setGravity(Gravity.BOTTOM)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setWindowAnimations(android.R.style.Animation_InputMethod) // slides up from the bottom
    }
    dlg.show()
    return dlg
}

/**
 * The sheet's scroller: once it's scrolled to the top, dragging down moves the whole sheet,
 * and letting go far enough (or fast) closes it, like a system bottom sheet.
 */
private class SwipeDownScroll(ctx: android.content.Context, val close: () -> Unit) : ScrollView(ctx) {
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private var downY = 0f
    private var dragging = false
    private var velocity: android.view.VelocityTracker? = null

    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downY = e.rawY; dragging = false; velocity?.recycle(); velocity = android.view.VelocityTracker.obtain() }
            android.view.MotionEvent.ACTION_MOVE -> if (!dragging && scrollY == 0 && e.rawY - downY > slop) { dragging = true; downY = e.rawY }
        }
        velocity?.addMovement(e)
        return dragging || super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        velocity?.addMovement(e)
        if (!dragging) {
            // the list itself is at the top and the finger goes down: the sheet follows instead
            if (e.actionMasked == android.view.MotionEvent.ACTION_MOVE && scrollY == 0 && e.rawY - downY > slop) { dragging = true; downY = e.rawY }
            else return super.onTouchEvent(e)
        }
        val dy = (e.rawY - downY).coerceAtLeast(0f)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> translationY = dy
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val v = velocity?.apply { computeCurrentVelocity(1000) }?.yVelocity ?: 0f
                val fast = 1000 * resources.displayMetrics.density
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP && (dy > height / 4f || v > fast))
                    animate().translationY(height.toFloat()).setDuration(160).withEndAction(close).start()
                else animate().translationY(0f).setDuration(180).start()
            }
        }
        return true
    }
}
