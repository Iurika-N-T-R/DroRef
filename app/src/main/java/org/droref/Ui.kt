package org.droref

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * DroRef's own dialogs, lists, text fields, slider and toast: the same rounded cards and colors as the popovers,
 * so nothing looks like a system widget. [windowType] is for the floating window service (no activity behind it).
 */
class Ui(private val c: Context, private val windowType: Int? = null) {
    private val light get() = Prefs.theme == 1
    private val dp = c.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    val fg get() = if (light) 0xFF222222.toInt() else 0xFFFFFFFF.toInt()
    private val muted get() = if (light) 0xFF6B6B70.toInt() else 0xFFA0A0A6.toInt()
    private val cardBg get() = if (light) 0xFAFFFFFF.toInt() else 0xFA2E2E2E.toInt()
    private val line get() = if (light) 0x22000000 else 0x33FFFFFF
    private val well get() = if (light) 0x0F000000 else 0x14FFFFFF

    fun ic(id: Int, color: Int = fg): Drawable = c.getDrawable(id)!!.mutate().apply { setTint(color) }

    class Btn(val label: String, val primary: Boolean = false, val danger: Boolean = false, val f: () -> Unit = {})

    /** A list line: icon at the start, optional icon at the end, indent level, a check when it's the current value. */
    class Line(val label: String, val icon: Drawable? = null, val end: Drawable? = null, val indent: Int = 0, val checked: Boolean = false)

    /**
     * A rounded card over the canvas: title, message, a body that scrolls when tall, buttons at the bottom.
     * [bottom] + no [dim] = a live panel at the foot of the screen that leaves the board visible.
     */
    fun card(
        title: String?, message: String? = null, body: View? = null, buttons: List<Btn> = emptyList(),
        bottom: Boolean = false, dim: Boolean = true, keyboard: Boolean = false, onClose: () -> Unit = {},
    ): Dialog {
        val dlg = Dialog(c)
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 20 * dp; setColor(cardBg); setStroke(px(1), line) }
            clipToOutline = true
            setPadding(0, px(8), 0, px(8))
        }
        title?.let {
            col.addView(TextView(c).apply {
                text = it.removeSuffix("…"); setTextColor(fg); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
                setPadding(px(22), px(12), px(22), px(8))
            })
        }
        val middle = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        message?.let {
            middle.addView(TextView(c).apply {
                text = it; setTextColor(muted); textSize = 14.5f; setLineSpacing(0f, 1.15f)
                setPadding(px(22), px(4), px(22), px(10)); setTextIsSelectable(false)
            })
        }
        body?.let { middle.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        // the middle shrinks and scrolls when the card would be taller than the screen; title and buttons stay put
        col.addView(ScrollView(c).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; addView(middle) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (buttons.isNotEmpty()) col.addView(LinearLayout(c).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(px(14), px(8), px(14), px(6))
            for (b in buttons) addView(button(b) { dlg.dismiss(); b.f() }, LinearLayout.LayoutParams(-2, px(42)).apply { marginStart = px(8) })
        })
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(col)
        dlg.setOnDismissListener { onClose() }
        dlg.window?.apply {
            windowType?.let(::setType)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = minOf(c.resources.displayMetrics.widthPixels - px(32), px(if (bottom) 520 else 420))
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (bottom) { setGravity(Gravity.BOTTOM); attributes = attributes.apply { y = px(24) } }
            if (!dim) clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) else setDimAmount(0.35f)
            if (keyboard) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.show()
        return dlg
    }

    private fun button(b: Btn, click: () -> Unit) = TextView(c).apply {
        text = b.label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = px(84)
        setPadding(px(18), 0, px(18), 0)
        val accent = if (b.danger) Palette.RED else Palette.BLUE
        setTextColor(if (b.primary) Color.WHITE else if (b.danger) Palette.RED else fg)
        background = GradientDrawable().apply { cornerRadius = 21 * dp; setColor(if (b.primary) accent else well) }
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
    }

    /** Rows to tap; [onLong] adds a long-press. Rebuild with [fill] (the command palette filters in place). */
    fun lines(lines: List<Line>, onPick: (Int) -> Unit, onLong: ((Int) -> Unit)? = null) =
        LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; fill(this, lines, onPick, onLong) }

    fun fill(box: LinearLayout, lines: List<Line>, onPick: (Int) -> Unit, onLong: ((Int) -> Unit)? = null) {
        box.removeAllViews()
        lines.forEachIndexed { i, l ->
            box.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(48)
                setPaddingRelative(px(22 + l.indent * 20), px(6), px(16), px(6))
                background = c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
                l.icon?.let { addView(ImageView(c).apply { setImageDrawable(it); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(px(22), px(22)).apply { marginEnd = px(16) }) }
                addView(TextView(c).apply {
                    text = l.label; textSize = 15f
                    setTextColor(if (l.checked) Palette.BLUE else fg)
                    if (l.checked) typeface = Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val end = if (l.checked) ic(R.drawable.ic_check, Palette.BLUE) else l.end
                end?.let { addView(ImageView(c).apply { setImageDrawable(it) }, LinearLayout.LayoutParams(px(20), px(20)).apply { marginStart = px(12) }) }
                isSelected = l.checked
                setOnClickListener { onPick(i) }
                onLong?.let { f -> setOnLongClickListener { f(i); true } }
            })
        }
    }

    /** Pick one line; closes on the pick. */
    fun list(title: String, lines: List<Line>, onLong: ((Int) -> Unit)? = null, f: (Int) -> Unit): Dialog {
        lateinit var dlg: Dialog
        dlg = card(title, body = lines(lines, { dlg.dismiss(); f(it) }, onLong?.let { l -> { i: Int -> dlg.dismiss(); l(i) } }))
        return dlg
    }

    /** A text field in the cards' style: rounded well, no system underline. */
    fun field(initial: CharSequence = "", hint: String? = null, multiline: Boolean = false) = EditText(c).apply {
        setText(initial, TextView.BufferType.SPANNABLE)
        this.hint = hint
        setTextColor(fg)
        setHintTextColor(muted)
        textSize = 15f
        background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(well); setStroke(px(1), line) }
        setPadding(px(14), px(12), px(14), px(12))
        if (multiline) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; gravity = Gravity.TOP or Gravity.START
        } else isSingleLine = true
    }

    /** One line of text to type, OK / Cancel. */
    fun ask(title: String, initial: String, okLabel: String, cancelLabel: String, allowEmpty: Boolean = false, f: (String) -> Unit) {
        val input = field(initial).apply { setSelection(text.length) }
        lateinit var dlg: Dialog
        fun ok() = input.text.toString().takeIf { allowEmpty || it.isNotBlank() }?.let(f)
        input.setOnEditorActionListener { _, _, _ -> dlg.dismiss(); ok(); true }
        dlg = card(title, body = padded(input), buttons = listOf(Btn(cancelLabel), Btn(okLabel, primary = true) { ok() }), keyboard = true)
        input.requestFocus()
    }

    fun padded(v: View, h: Int = 22) = FrameLayout(c).apply { setPadding(px(h), px(4), px(h), px(8)); addView(v, FrameLayout.LayoutParams(-1, -2)) }

    /** A label over a [Slider]. */
    fun labeled(label: String, slider: Slider) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(22), px(4), px(22), px(4))
        addView(TextView(c).apply { text = label; setTextColor(muted); textSize = 13f })
        addView(slider, LinearLayout.LayoutParams(-1, px(40)))
    }

    fun slider(start: Float, onRelease: () -> Unit = {}, f: (Float) -> Unit) = Slider(c, start, f, onRelease)

    /** A short message in a pill at the bottom, fading out by itself. */
    fun toast(text: String, long: Boolean = false) {
        val v = TextView(c).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            maxWidth = px(320)
            setPadding(px(18), px(11), px(18), px(11))
            background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(0xEE202022.toInt()) }
            elevation = 8 * dp
            alpha = 0f
        }
        val ms = if (long) 3500L else 2000L
        val root = (c as? Activity)?.window?.decorView as? FrameLayout
        if (root != null && windowType == null) {
            root.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(96) })
            v.animate().alpha(1f).setDuration(150).withEndAction {
                v.animate().alpha(0f).setStartDelay(ms).setDuration(250).withEndAction { root.removeView(v) }.start()
            }.start()
        } else {
            val wm = c.getSystemService(WindowManager::class.java)
            val lp = WindowManager.LayoutParams(-2, -2, windowType ?: WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = px(96) }
            runCatching { wm.addView(v, lp) }.onSuccess {
                v.animate().alpha(1f).setDuration(150).withEndAction {
                    v.animate().alpha(0f).setStartDelay(ms).setDuration(250).withEndAction { runCatching { wm.removeView(v) } }.start()
                }.start()
            }
        }
    }
}

/** A flat slider (0…1): blue fill, round thumb; [f] while dragging, [onRelease] when let go. Screen readers can step it. */
class Slider(ctx: Context, start: Float, private val f: (Float) -> Unit, private val onRelease: () -> Unit = {}) : View(ctx) {
    var value = start.coerceIn(0f, 1f)
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val dp = ctx.resources.displayMetrics.density
    private val light get() = Prefs.theme == 1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val r = 9 * dp

    init { isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    override fun onDraw(c: Canvas) {
        val y = height / 2f
        val x0 = r + dp; val x1 = width - r - dp
        val x = x0 + (x1 - x0) * value
        p.strokeWidth = 4 * dp
        p.color = if (light) 0x22000000 else 0x33FFFFFF
        c.drawLine(x0, y, x1, y, p)
        p.color = if (isEnabled) Palette.BLUE else 0x66808080
        c.drawLine(x0, y, x, y, p)
        p.color = Color.WHITE
        c.drawCircle(x, y, r, p)
        p.color = if (isEnabled) Palette.BLUE else 0x66808080
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * dp
        c.drawCircle(x, y, r, p)
        p.style = Paint.Style.FILL
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        val x0 = r + dp; val x1 = width - r - dp
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                value = (e.x - x0) / (x1 - x0)
                f(value)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onRelease()
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, value * 100)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val step = when (action) { AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 0.05f; AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -0.05f; else -> return super.performAccessibilityAction(action, args) }
        value += step; f(value); onRelease()
        return true
    }
}
