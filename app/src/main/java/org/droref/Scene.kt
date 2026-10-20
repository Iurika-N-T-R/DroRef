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
}

/** A board: items, camera, undo history. Lives in its own folder: scene.json + images/. */
class Scene private constructor(val dir: File) {
    var name = "Untitled"
    var purUri = "" // the .pur file "Save" writes to
    val items = mutableListOf<Item>()
    val cam = Matrix() // board -> screen
    val images = File(dir, "images").apply { mkdirs() }
    private var nextId = 1L
    private val json = AtomicFile(File(dir, "scene.json"))
    private val undos = ArrayDeque<List<Item>>()
    private val redos = ArrayDeque<List<Item>>()

    fun newId() = nextId++

    /** Call before a change to make it undoable. */
    fun checkpoint() {
        undos.addLast(items.toList())
        while (undos.size > Prefs.undoLimit) undos.removeFirst()
        redos.clear()
    }

    fun undo() = swap(undos, redos)
    fun redo() = swap(redos, undos)

    private fun swap(from: ArrayDeque<List<Item>>, to: ArrayDeque<List<Item>>): Boolean {
        val s = from.removeLastOrNull() ?: return false
        to.addLast(items.toList())
        items.clear()
        items += s
        return true
    }

    fun save() {
        val v = FloatArray(9).also(cam::getValues)
        val o = JSONObject().put("name", name).put("nextId", nextId).put("purUri", purUri)
            .put("camera", JSONArray(v.map { it.toDouble() }))
            .put("items", JSONArray(items.map { it.toJson() }))
        val out = json.startWrite()
        try {
            out.write(o.toString().toByteArray())
            json.finishWrite(out)
        } catch (e: Exception) {
            json.failWrite(out) // keep the previous good file
        }
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
        nextId = o.optLong("nextId", 1)
        o.optJSONArray("camera")?.let { c -> cam.setValues(FloatArray(9) { c.getDouble(it).toFloat() }) }
        val a = o.getJSONArray("items")
        for (i in 0 until a.length()) runCatching { items += Item.fromJson(a.getJSONObject(i)) }
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
        fun list(ctx: Context) = root(ctx).listFiles().orEmpty()
            .filter { File(it, "scene.json").exists() }
            .sortedByDescending { File(it, "scene.json").lastModified() }

        /** The shared scene, or the last one used, or a new one. */
        fun current(ctx: Context, untitled: String): Scene = Boards.scene ?: run {
            val last = Prefs.scene.takeIf { it.isNotEmpty() }?.let { File(root(ctx), it) }
            (if (last != null && File(last, "scene.json").exists()) open(last) else create(ctx, untitled)).also { Boards.scene = it }
        }
    }
}
