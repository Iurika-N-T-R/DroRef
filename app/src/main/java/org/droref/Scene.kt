package org.droref

import android.content.Context
import android.graphics.Matrix
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The scene on screen, shared by the main screen and the floating window (same process, same object). */
object Boards {
    var scene: Scene? = null

    // DroRef's own clipboard for board items (copy/cut/paste, also between scenes)
    var clip: List<Item> = emptyList()
    var clipLinks: List<Link> = emptyList()
    var clipImages: java.io.File? = null
}

/** A board: items, camera, undo history. Lives in its own folder: scene.json + images/. */
class Scene private constructor(val dir: File) {
    var name = "Untitled"
    var purUri = "" // the .pur file "Save" writes to
    var folder = "" // "" = not in a folder (scene browser)
    val items = mutableListOf<Item>()
    val links = mutableListOf<Link>() // arrows between items (nodes)
    var lockVisuals = false // "Verrouillage": images, videos and drawings can't be selected or moved
    var lockNodes = false   // notes, nodes and links can't be selected or moved
    val cam = Matrix() // board -> screen
    val images = File(dir, "images").apply { mkdirs() }
    private var nextId = 1L
    private val json = AtomicFile(File(dir, "scene.json"))
    private class Snap(val items: List<Item>, val links: List<Link>)
    private val undos = ArrayDeque<Snap>()
    private val redos = ArrayDeque<Snap>()

    fun newId() = nextId++

    /** Call before a change to make it undoable. */
    fun checkpoint() {
        undos.addLast(Snap(items.toList(), links.toList()))
        while (undos.size > Prefs.undoLimit) undos.removeFirst()
        redos.clear()
    }

    fun undo() = swap(undos, redos)
    fun redo() = swap(redos, undos)

    private fun swap(from: ArrayDeque<Snap>, to: ArrayDeque<Snap>): Boolean {
        val s = from.removeLastOrNull() ?: return false
        to.addLast(Snap(items.toList(), links.toList()))
        items.clear()
        items += s.items
        links.clear()
        links += s.links
        return true
    }

    fun save() {
        val v = FloatArray(9).also(cam::getValues)
        val o = JSONObject().put("name", name).put("nextId", nextId).put("purUri", purUri).put("folder", folder)
            .put("camera", JSONArray(v.map { it.toDouble() }))
            .put("items", JSONArray(items.map { it.toJson() }))
            .put("links", JSONArray(links.map { it.toJson() }))
            .put("lockVisuals", lockVisuals).put("lockNodes", lockNodes)
        val out = json.startWrite()
        try {
            out.write(o.toString().toByteArray())
            json.finishWrite(out)
        } catch (e: Exception) {
            json.failWrite(out) // keep the previous good file
        }
    }

    /** Saves without looking edited (moving to a folder isn't an edit). */
    fun saveKeepTime() {
        val t = File(dir, "scene.json").lastModified()
        save()
        File(dir, "scene.json").setLastModified(t)
    }

    val thumb get() = File(dir, "thumb.jpg")
    val edited get() = File(dir, "scene.json").lastModified()

    /** Small preview for the scene browser; [items] copied on the UI thread, this runs on a background one. */
    fun writeThumb(items: List<Item>, links: List<Link> = emptyList()) {
        if (items.isEmpty()) { thumb.delete(); return }
        val b = renderItems(items, images, 640, false, links)
        runCatching { thumb.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }
        b.recycle()
    }

    fun copy(ctx: Context, newName: String): Scene {
        val d = File(root(ctx), UUID.randomUUID().toString())
        dir.copyRecursively(d)
        return open(d).apply { name = newName; purUri = ""; save() }
    }

    private fun load(clean: Boolean) {
        val o = runCatching { JSONObject(String(json.readFully())) }.getOrNull() ?: return
        name = o.optString("name", name)
        purUri = o.optString("purUri")
        folder = o.optString("folder")
        nextId = o.optLong("nextId", 1)
        o.optJSONArray("camera")?.let { c -> cam.setValues(FloatArray(9) { c.getDouble(it).toFloat() }) }
        val a = o.getJSONArray("items")
        for (i in 0 until a.length()) runCatching { items += Item.fromJson(a.getJSONObject(i)) }
        o.optJSONArray("links")?.let { l -> for (i in 0 until l.length()) runCatching { links += Link.fromJson(l.getJSONObject(i)) } }
        lockVisuals = o.optBoolean("lockVisuals")
        lockNodes = o.optBoolean("lockNodes")
        if (clean) {
            // images deleted in an earlier session (undo history is gone, so nothing can bring them back)
            val used = items.map { it.file }.toSet()
            images.listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
        }
    }

    companion object {
        fun root(ctx: Context): File {
            Prefs.init(ctx)
            return File(ctx.filesDir, "scenes").apply { mkdirs() }
        }
        fun open(dir: File) = Scene(dir).apply { load(clean = true) }
        /** Read-only look, e.g. for listing names. */
        fun peek(dir: File) = Scene(dir).apply { load(clean = false) }
        fun create(ctx: Context, name: String) = Scene(File(root(ctx), UUID.randomUUID().toString())).apply { this.name = name; save() }
        fun list(ctx: Context): List<File> = root(ctx).listFiles().orEmpty()
            .filter { File(it, "scene.json").exists() }
            .sortedByDescending { File(it, "scene.json").lastModified() }

        /** The shared scene, or the last one used, or a new one. */
        fun current(ctx: Context, untitled: String): Scene = Boards.scene ?: run {
            val last = Prefs.scene.takeIf { it.isNotEmpty() }?.let { File(root(ctx), it) }
            (if (last != null && File(last, "scene.json").exists()) open(last) else create(ctx, untitled)).also { Boards.scene = it }
        }
    }
}
