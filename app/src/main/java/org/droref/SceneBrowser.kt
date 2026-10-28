package org.droref

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Full-screen grid of scenes with previews, like PureRef's start screen, plus one level of folders.
 * Tap opens; long-press asks the host for the scene's or folder's menu. [refresh] after changing anything.
 */
class SceneBrowser(
    private val a: Activity,
    private val light: Boolean,
    private val current: () -> Scene,
    private val open: (File) -> Unit,
    private val sceneMenu: (File) -> Unit,
    private val folderMenu: (String) -> Unit,
    private val newScene: (folder: String) -> Unit,
    private val newFolder: () -> Unit,
) {
    private class Entry(val dir: File?, val name: String, val folder: String, val edited: Long, val count: Int)

    private val dp = a.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val fg = if (light) 0xFF202124.toInt() else 0xFFECECEC.toInt()
    private val muted = if (light) 0xFF6B6B70.toInt() else 0xFF9A9AA0.toInt()
    private val bg = if (light) 0xFFF2F2F2.toInt() else 0xFF1C1C1E.toInt()
    private val card = if (light) 0xFFFFFFFF.toInt() else 0xFF2A2A2D.toInt()
    private val well = Prefs.canvasColor()

    private val dlg = Dialog(a, if (light) android.R.style.Theme_DeviceDefault_Light_NoActionBar else android.R.style.Theme_DeviceDefault_NoActionBar)
    private val title = TextView(a)
    private val backBtn = ImageView(a)
    private val grid = GridView(a)
    private var folder = "" // the open folder, "" = top level
    private var entries = emptyList<Entry>()
    private val thumbs = HashMap<String, Bitmap?>()
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun ic(id: Int, color: Int = fg) = a.getDrawable(id)!!.mutate().apply { setTint(color) }

    private fun headerButton(icon: Int, desc: String, f: () -> Unit) = ImageView(a).apply {
        setImageDrawable(ic(icon))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = desc
        if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = desc
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((fg and 0xFFFFFF) or 0x14000000) }
        setOnClickListener { f() }
    }

    fun show() {
        backBtn.apply {
            setImageDrawable(ic(R.drawable.ic_arrow_left))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = a.getString(R.string.back)
            setOnClickListener { folder = ""; refresh() }
        }
        title.apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(fg); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val header = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(12), px(12), px(8))
            addView(backBtn, LinearLayout.LayoutParams(px(44), px(44)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(8) })
            addView(headerButton(R.drawable.ic_folder_plus, a.getString(R.string.new_folder)) { newFolder() }, LinearLayout.LayoutParams(px(44), px(44)))
            addView(headerButton(R.drawable.ic_file_plus, a.getString(R.string.new_scene)) { newScene(folder) }, LinearLayout.LayoutParams(px(44), px(44)).apply { marginStart = px(8) })
            addView(headerButton(R.drawable.ic_x, a.getString(R.string.close)) { dlg.dismiss() }, LinearLayout.LayoutParams(px(44), px(44)).apply { marginStart = px(8) })
        }
        grid.apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = px(150)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = px(12)
            verticalSpacing = px(12)
            setPadding(px(16), px(4), px(16), px(24))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(0)
            adapter = Adapter()
            setOnItemClickListener { _, _, i, _ ->
                val e = entries[i]
                if (e.dir == null) { folder = e.folder; refresh() } else { dlg.dismiss(); open(e.dir) }
            }
            setOnItemLongClickListener { _, _, i, _ -> entries[i].let { if (it.dir == null) folderMenu(it.folder) else sceneMenu(it.dir) }; true }
        }
        dlg.setContentView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        dlg.setOnKeyListener { _, code, e ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && e.action == android.view.KeyEvent.ACTION_UP && folder.isNotEmpty()) { folder = ""; refresh(); true } else false
        }
        dlg.setOnDismissListener { loader.shutdownNow() }
        refresh()
        dlg.show()
    }

    fun dismiss() = dlg.dismiss()

    /** Re-reads the scenes; previews that are older than their scene get redrawn in the background. */
    fun refresh() {
        val cur = current()
        val scenes = Scene.list(a).map { d -> if (d == cur.dir) cur else Scene.peek(d) }
        val folders = (Prefs.folders.lines() + scenes.map { it.folder }).filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }
        entries = if (folder.isEmpty()) {
            folders.map { f -> Entry(null, f, f, 0, scenes.count { it.folder == f }) } +
                scenes.filter { it.folder.isEmpty() }.map { Entry(it.dir, it.name, it.folder, it.edited, it.items.size) }
        } else scenes.filter { it.folder == folder }.map { Entry(it.dir, it.name, it.folder, it.edited, it.items.size) }
        title.text = if (folder.isEmpty()) a.getString(R.string.my_scenes).removeSuffix("…") else folder
        backBtn.visibility = if (folder.isEmpty()) View.GONE else View.VISIBLE
        for (sc in scenes) {
            val key = sc.dir.path
            if (sc !== cur && sc.thumb.exists() && sc.thumb.lastModified() >= sc.edited) { if (key !in thumbs) loadThumb(sc) }
            else {
                val items = sc.items.toList()
                val links = sc.links.toList()
                loader.execute { sc.writeThumb(items, links); loadThumb(sc) }
            }
        }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun loadThumb(sc: Scene) {
        val f = sc.thumb
        val run = Runnable {
            val b = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            main.post { thumbs[sc.dir.path] = b; (grid.adapter as BaseAdapter).notifyDataSetChanged() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) loader.execute(run) else run.run()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(i: Int) = entries[i]
        override fun getItemId(i: Int) = i.toLong()

        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val e = entries[i]
            val w = (parent as GridView).columnWidth
            val isCurrent = e.dir == current().dir
            val preview = FrameLayout(a).apply {
                background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(if (e.dir == null) card else well) }
                clipToOutline = true
                val b = e.dir?.let { thumbs[it.path] }
                addView(ImageView(a).apply {
                    if (b != null) { setImageBitmap(b); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(px(4), px(4), px(4), px(4)) } // the whole board
                    else {
                        setImageDrawable(ic(if (e.dir == null) R.drawable.ic_folder else R.drawable.ic_image, if (e.dir == null) Palette.ORANGE else muted))
                        scaleType = ImageView.ScaleType.CENTER
                        scaleX = 1.6f; scaleY = 1.6f
                    }
                }, FrameLayout.LayoutParams(-1, -1))
            }
            return LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(8), px(8), px(8), px(10))
                background = GradientDrawable().apply {
                    cornerRadius = 16 * dp
                    setColor(card)
                    if (isCurrent) setStroke(px(2), Palette.ORANGE)
                }
                addView(preview, LinearLayout.LayoutParams(-1, w - px(16)))
                addView(TextView(a).apply {
                    text = e.name
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(fg)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(px(4), px(8), px(4), 0)
                })
                addView(TextView(a).apply {
                    text = if (e.dir == null) a.getString(R.string.n_scenes, e.count)
                    else DateUtils.getRelativeTimeSpanString(e.edited, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
                    textSize = 12f
                    setTextColor(muted)
                    maxLines = 1
                    setPadding(px(4), px(2), px(4), 0)
                })
            }
        }
    }
}
