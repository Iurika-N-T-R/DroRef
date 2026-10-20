package org.droref

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * PureRef's "always on top", Android style: the board in a floating window over any app.
 * Its little bar moves / resizes it, changes opacity, makes it transparent to touch (tracing),
 * switches the background off (overlay mode: only the images float), or goes back to the app.
 * Two windows, so the bar stays touchable while the board lets touches through.
 */
class OverlayService : Service(), BoardView.Host {
    private lateinit var wm: WindowManager
    private lateinit var board: BoardView
    private lateinit var bar: LinearLayout
    private lateinit var boardLp: WindowManager.LayoutParams
    private lateinit var barLp: WindowManager.LayoutParams
    private lateinit var through: TextView
    private var clickThrough = false
    private val dp by lazy { resources.displayMetrics.density }
    private val barH by lazy { (40 * dp).toInt() }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.localized())
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WindowManager::class.java)
        val scene = Scene.current(this, getString(R.string.untitled))
        board = BoardView(this, scene, floating = true).also { it.host = this; it.background = Prefs.ovBg }
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        boardLp = WindowManager.LayoutParams(Prefs.ovW, Prefs.ovH, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.ovX; y = Prefs.ovY + barH
            alpha = Prefs.ovAlpha
        }
        barLp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, barH, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        bar = LinearLayout(this).apply { setBackgroundColor(0xE0202020.toInt()) }
        button("✥", getString(R.string.ov_move)) { dx, dy -> boardLp.x += dx; boardLp.y += dy; layout() }
        button("◐", getString(R.string.opacity), click = {
            val steps = floatArrayOf(1f, 0.8f, 0.6f, 0.4f)
            val next = steps[(steps.indexOfFirst { it <= boardLp.alpha + 0.01f } + 1) % steps.size]
            boardLp.alpha = if (clickThrough) minOf(next, MAX_THROUGH_ALPHA) else next
            layout()
        })
        through = button("☝", getString(R.string.ov_through), click = { setClickThrough(!clickThrough) })
        button("▣", getString(R.string.ov_background), click = { board.background = (board.background + 1) % 3; Prefs.ovBg = board.background })
        button("⤡", getString(R.string.ov_resize)) { dx, dy ->
            boardLp.width = (boardLp.width + dx).coerceAtLeast((160 * dp).toInt())
            boardLp.height = (boardLp.height + dy).coerceAtLeast((120 * dp).toInt())
            layout()
        }
        button("⛶", getString(R.string.focus_canvas), click = { board.focusAll() })
        button("⌂", getString(R.string.ov_open_app), click = {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            stopSelf()
        })
        button("✕", getString(R.string.close), click = { stopSelf() })
        wm.addView(board, boardLp)
        wm.addView(bar, barLp)
        layout()
        board.post { board.fitAll() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun button(label: String, desc: String, click: (() -> Unit)? = null, drag: ((Int, Int) -> Unit)? = null): TextView {
        val v = TextView(this).apply {
            text = label
            contentDescription = desc
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            minWidth = (44 * dp).toInt()
        }
        if (click != null) v.setOnClickListener { click() }
        if (drag != null) {
            var lx = 0f
            var ly = 0f
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lx = e.rawX; ly = e.rawY }
                    MotionEvent.ACTION_MOVE -> { drag((e.rawX - lx).toInt(), (e.rawY - ly).toInt()); lx = e.rawX; ly = e.rawY }
                }
                true
            }
        }
        bar.addView(v, LinearLayout.LayoutParams(-2, -1))
        return v
    }

    private fun button(label: String, desc: String, drag: (Int, Int) -> Unit) = button(label, desc, null, drag)

    private fun layout() {
        barLp.x = boardLp.x
        barLp.y = boardLp.y - barH
        wm.updateViewLayout(board, boardLp)
        wm.updateViewLayout(bar, barLp)
    }

    /** Touches go to the app underneath. Android 12+ only allows that for windows at most 80% opaque. */
    private fun setClickThrough(on: Boolean) {
        clickThrough = on
        boardLp.flags = if (on) boardLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else boardLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (on && Build.VERSION.SDK_INT >= 31) boardLp.alpha = minOf(boardLp.alpha, MAX_THROUGH_ALPHA)
        through.setBackgroundColor(if (on) 0xFF4FA3FF.toInt() else 0) // ☝ is an emoji on most phones, so tint the background
        layout()
        Toast.makeText(this, if (on) R.string.ov_through_on else R.string.ov_through_off, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        running = false
        Prefs.ovX = boardLp.x; Prefs.ovY = boardLp.y - barH; Prefs.ovW = boardLp.width; Prefs.ovH = boardLp.height
        Prefs.ovAlpha = boardLp.alpha
        board.scene.save()
        runCatching { wm.removeView(board) }
        runCatching { wm.removeView(bar) }
        super.onDestroy()
    }

    // the floating board is for looking and arranging; menus live in the app
    override fun selectionChanged() {}
    override fun lockedPressed(item: Item) = Toast.makeText(this, R.string.locked_item, Toast.LENGTH_SHORT).show()
    override fun canvasMenu() {}
    override fun picked(info: String, hex: String) {}
    override fun editItem(item: Item) {}

    companion object {
        const val MAX_THROUGH_ALPHA = 0.8f
        var running = false
            private set
    }
}
