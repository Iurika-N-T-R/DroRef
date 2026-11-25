package org.droref

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.text.Html
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * PureRef 2.x native .pur, read and write.
 *
 * Implemented from the format specification in https://github.com/Vacyyyy/pur-2-file-format (FORMAT.md),
 * verified there against PureRef 2.0.3 and 2.1.3. In short:
 *  - A .pur is a SQLite database whose first H bytes were replaced by a PureRef header and moved to the
 *    end of the file: file = header(H) + db[H:N] + db[0:H]. Header (big-endian, Qt strings = u32 byte
 *    length + UTF-16BE): format "2.1", u32 0, u64 N, app version, md5 checksum, thumbnail QByteArray
 *    (the "2.0" envelope has no thumbnail). Checksum = md5 of everything after the checksum string.
 *  - Serialized Qt values (transforms, paths, BigRational) sit in TEXT cells whose code points are the
 *    raw bytes (Latin-1 style). We read them with CAST(.. AS BLOB) and write them with CAST(? AS TEXT).
 *  - QTransform: m11 m12 m13 m21 m22 m23 m31 m32 m33; x' = m11 x + m21 y + m31, y' = m12 x + m22 y + m32.
 *    Item transforms are relative to the parent. Image pixels go through image_transform first.
 */
object Pur2 {
    private val UTF16 = Charsets.UTF_16BE
    private const val FORMAT_VERSION = "2.1"
    private const val APP_VERSION = "2.1.3" // never claim a newer app than exists: PureRef refuses those
    private const val USER_VERSION = 200101
    private const val APPLICATION_ID = 940753918
    private const val CHUNK = 512 * 1024 // a CursorWindow holds about 2 MB, so big cells are read in pieces

    private const val T_TRANSFORM = 80
    private const val T_SIZEF = 22
    private const val T_CUSTOM = 1024

    private val SCHEMA = listOf(
        "CREATE TABLE images (id INTEGER PRIMARY KEY,source_type INTEGER,origin TEXT,source TEXT,format TEXT,checksum TEXT,data BLOB,width INTEGER,height INTEGER)",
        "CREATE TABLE metadata (id INTEGER PRIMARY KEY,scene_rect TEXT,application_version TEXT,view_transform TEXT,thumbnail BLOB,horizontal_scroll INTEGER,vertical_scroll INTEGER,last_save_path TEXT,last_load_path TEXT,last_load_checksum TEXT,saved INTEGER)",
        "CREATE TABLE items (parent INTEGER,id INTEGER PRIMARY KEY,name TEXT,transform BLOB,sort_order BLOB,z REAL,opacity REAL,locked INTEGER,comment INTEGER)",
        "CREATE TABLE items_images (image INTEGER,playback_speed REAL,id INTEGER PRIMARY KEY,playback_state INTEGER,image_transform BLOB,image_bounds BLOB,playback_frame INTEGER,flags INTEGER)",
        "CREATE TABLE items_drawings (id INTEGER PRIMARY KEY,strokes BLOB)",
        "CREATE TABLE items_notes (text_color TEXT,id INTEGER PRIMARY KEY,fixed_size TEXT,background_color TEXT,text TEXT,style INTEGER)",
        "CREATE TABLE items_groups (id INTEGER PRIMARY KEY,background_color TEXT,lock_mode INTEGER)",
    )

    /** True for a PureRef 2.x (or later) file; legacy 1.x files go to [Pur]. */
    fun isPur2(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val n = ByteBuffer.wrap(bytes).getInt(0)
        if (n !in 2..64 || 4 + n > bytes.size) return false
        val major = String(bytes, 4, n, UTF16).substringBefore('.').toIntOrNull() ?: return false
        return major >= 2
    }

    // ------------------------------------------------------------------ reading

    /** Fills [scene] from a 2.x file. [cache] holds a temporary copy of the database. Returns how many images were skipped. */
    fun read(bytes: ByteArray, scene: Scene, cache: File): Int {
        val dbFile = File(cache, "pur2-read-${UUID.randomUUID()}.sqlite")
        dbFile.writeBytes(unwrap(bytes))
        try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                .use { return build(it, scene) }
        } finally {
            listOf("", "-journal", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }
        }
    }

    /** The SQLite database inside a .pur. */
    private fun unwrap(f: ByteArray): ByteArray {
        val b = ByteBuffer.wrap(f)
        var p = 0
        fun need(n: Long) = require(n >= 0 && p + n <= f.size) { "Broken .pur header" }
        fun qstr(): String {
            need(4); val n = b.getInt(p); p += 4
            if (n == -1) return ""
            need(n.toLong()); return String(f, p, n, UTF16).also { p += n }
        }
        val version = qstr()
        require(version.substringBefore('.') == "2") { "Unsupported PureRef format $version" }
        need(12); p += 4 // reserved
        val n = b.getLong(p); p += 8
        qstr() // application version
        qstr() // checksum (not checked: PureRef itself only warns on a mismatch)
        if (version != "2.0") { // 2.1 and later carry a thumbnail
            need(4); val t = b.getInt(p); p += 4
            if (t > 0) { need(t.toLong()); p += t }
        }
        val h = p
        require(n > h && n + h == f.size.toLong()) { "Broken .pur container" }
        val ni = n.toInt()
        return f.copyOfRange(ni, f.size) + f.copyOfRange(h, ni)
    }

    private class Node(
        val id: Long, val parent: Long, val name: String, val m: Matrix, val order: Double, val z: Double,
        val opacity: Float, val locked: Boolean, val comment: String,
    )
    private class Res(val format: String, val w: Int, val h: Int, val source: String)
    private class Inst(val res: Long, val pixels: Matrix, val bounds: RectF?, val flags: Int)
    private class Note(val html: String, val textColor: String?, val bg: String?)
    private class Group(val bg: String?, val closed: Boolean)
    private class PStroke(val color: Int, val width: Float, val dashed: Boolean, val paths: List<FloatArray>)

    private fun build(db: SQLiteDatabase, scene: Scene): Int {
        val res = HashMap<Long, Res>()
        db.rawQuery("SELECT id, format, width, height, source FROM images", null).use { c ->
            while (c.moveToNext()) res[c.getLong(0)] = Res(c.str(1), c.getInt(2), c.getInt(3), c.str(4))
        }

        val nodes = LinkedHashMap<Long, Node>()
        db.rawQuery(
            "SELECT id, parent, name, typeof(transform), CAST(transform AS BLOB), typeof(sort_order), CAST(sort_order AS BLOB)," +
                " z, opacity, locked, comment FROM items", null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                nodes[id] = Node(
                    id, if (c.isNull(1)) -1 else c.getLong(1), c.str(2), transform(cell(c, 3, 4)), rational(cell(c, 5, 6)),
                    if (c.isNull(7)) 0.0 else c.getDouble(7), if (c.isNull(8)) 1f else c.getFloat(8),
                    !c.isNull(9) && c.getInt(9) != 0, c.str(10),
                )
            }
        }

        val imgs = HashMap<Long, Inst>()
        db.rawQuery(
            "SELECT id, image, typeof(image_transform), CAST(image_transform AS BLOB), typeof(image_bounds), CAST(image_bounds AS BLOB), flags FROM items_images",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val bounds = cell(c, 4, 5)?.let { b -> runCatching { pathBounds(b) }.getOrNull() }
                imgs[c.getLong(0)] = Inst(c.getLong(1), transform(cell(c, 2, 3)), bounds, if (c.isNull(6)) 1 else c.getInt(6))
            }
        }
        val notes = HashMap<Long, Note>()
        db.rawQuery("SELECT id, text, text_color, background_color FROM items_notes", null).use { c ->
            while (c.moveToNext()) notes[c.getLong(0)] = Note(c.str(1), c.getString(2), c.getString(3))
        }
        val groups = HashMap<Long, Group>()
        db.rawQuery("SELECT id, background_color, lock_mode FROM items_groups", null).use { c ->
            while (c.moveToNext()) groups[c.getLong(0)] = Group(c.getString(1), c.isNull(2) || c.getInt(2) != 0)
        }
        val drawings = HashSet<Long>()
        db.rawQuery("SELECT id FROM items_drawings", null).use { c -> while (c.moveToNext()) drawings += c.getLong(0) }

        // board transform and opacity of each item: its own, then its parents'
        val world = HashMap<Long, Matrix>()
        val alpha = HashMap<Long, Float>()
        fun worldOf(id: Long, depth: Int = 0): Matrix {
            world[id]?.let { return it }
            val n = nodes[id] ?: return Matrix()
            val m = Matrix(n.m)
            var a = n.opacity
            if (n.parent != id && n.parent in nodes && depth < 64) {
                m.postConcat(worldOf(n.parent, depth + 1))
                a *= alpha[n.parent] ?: 1f
            }
            alpha[id] = a
            return m.also { world[id] = it }
        }

        // drawing order: parents first, siblings by sort order (then z)
        val kids = nodes.values.groupBy { if (it.parent in nodes && it.parent != it.id) it.parent else -1L }
            .mapValues { (_, v) -> v.sortedWith(compareBy<Node>({ it.order }, { it.z })) }

        val ids = HashMap<Long, Long>() // PureRef id -> our id (or the nearest added ancestor's)
        val files = HashMap<Long, String?>() // resource -> file in scene.images (null: unreadable)
        var skipped = 0

        fun fileFor(rid: Long): String? = files.getOrPut(rid) {
            val data = runCatching { bytesOf(db, "images", "data", rid) }.getOrNull() ?: return@getOrPut null
            if (imageSize(data) == null) return@getOrPut null // TIFF, JPEG XL...: Android can't show it
            UUID.randomUUID().toString().also { File(scene.images, it).writeBytes(data) }
        }

        fun add(n: Node) {
            val parent = ids[n.parent] ?: 0L
            val w = worldOf(n.id)
            val a = alpha[n.id] ?: n.opacity
            val id = scene.newId()
            val img = imgs[n.id]
            val note = notes[n.id]
            val group = groups[n.id]
            val item: Item? = when {
                img != null -> {
                    val r = res[img.res]
                    val file = fileFor(img.res)
                    if (r == null || file == null) { skipped++; null } else {
                        val f = File(scene.images, file)
                        val (dw, dh) = imageSize(f)!!
                        val pw = if (r.w > 0) r.w.toFloat() else dw.toFloat() // the file's pixel space
                        val ph = if (r.h > 0) r.h.toFloat() else dh.toFloat()
                        // our content coords (decoded pixels) -> file pixels -> item coords -> board
                        val full = Matrix().apply { setScale(pw / dw, ph / dh); postConcat(img.pixels); postConcat(w) }
                        val crop = img.bounds?.let { b ->
                            val inv = Matrix(); img.pixels.invert(inv)
                            RectF(b).also { inv.mapRect(it) }.apply { left *= dw / pw; right *= dw / pw; top *= dh / ph; bottom *= dh / ph }
                        }?.takeIf { it.intersect(0f, 0f, dw.toFloat(), dh.toFloat()) && it.width() > 1 && it.height() > 1 }
                            ?: RectF(0f, 0f, dw.toFloat(), dh.toFloat())
                        val name = n.name.ifEmpty { r.source.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.') }
                        Item(
                            id, if (isAnimatedGif(f)) GIF else IMAGE, dw.toFloat(), dh.toFloat(), crop = crop, file = file,
                            name = name, source = r.source, opacity = a, gray = img.flags and 2 != 0, nearest = img.flags and 1 == 0,
                            locked = n.locked, comment = n.comment, parent = parent,
                        ).placed(full, dw / 2f, dh / 2f)
                    }
                }
                note != null -> {
                    // Qt writes a full document; its <head><style> would otherwise show up as text
                    val html = note.html.replace(Regex("(?is)<head.*?</head>"), "").replace(Regex("(?is)<style.*?</style>"), "")
                    val sp = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
                    val text = sp.toString().trimEnd('\n', ' ')
                    var nt = Item(id, NOTE, 0f, 0f, name = n.name, opacity = a, locked = n.locked, comment = n.comment, parent = parent)
                    color(note.textColor)?.let { c -> nt = nt.copy(textColor = c) }
                    color(note.bg)?.let { c -> nt = nt.copy(bgColor = c) }
                    // ponytail: note size uses our font, so imported notes are only roughly the PureRef size
                    nt = nt.withText(text, Rich.toJson(sp))
                    nt.placed(w, nt.w / 2, nt.h / 2) // Qt text origin is top-left
                }
                group != null -> Item(
                    id, GROUP, 1f, 1f, name = n.name, bgColor = color(group.bg) ?: 0x30808080, closed = group.closed,
                    opacity = a, locked = n.locked, comment = n.comment, parent = parent,
                ).placed(w, 0.5f, 0.5f) // sized to its children below
                n.id in drawings -> {
                    val list = runCatching { strokes(bytesOf(db, "items_drawings", "strokes", n.id)) }.getOrDefault(emptyList())
                        .flatMap { s -> s.paths.map { p -> Stroke(T_PEN, s.color, s.width, if (s.dashed) S_DOTTED else S_SOLID, p) } }
                    if (list.isEmpty()) null
                    else Item(id, DRAW, 0f, 0f, name = n.name, opacity = a, locked = n.locked, comment = n.comment, parent = parent)
                        .placed(w, 0f, 0f).withStrokes(list)
                }
                else -> null // a type this app doesn't know
            }
            if (item != null) { scene.items += item; ids[n.id] = id } else ids[n.id] = parent
        }

        val seen = HashSet<Long>()
        fun walk(pid: Long) { kids[pid]?.forEach { if (seen.add(it.id)) { add(it); walk(it.id) } } }
        walk(-1L)
        nodes.values.filter { it.id !in seen }.forEach(::add) // parent cycles: keep them anyway

        // groups wrap their children, as on the board
        val byId = scene.items.associateBy { it.id }
        fun depth(it: Item): Int { var d = 0; var p = byId[it.parent]; while (p != null && d < 64) { d++; p = byId[p.parent] }; return d }
        for (gr in scene.items.filter { it.type == GROUP }.sortedByDescending(::depth)) {
            val idx = scene.items.indexOfFirst { it.id == gr.id }
            val children = scene.items.filter { it.parent == gr.id }
            if (children.isEmpty()) { scene.items.removeAt(idx); continue }
            val r = children.union().apply { inset(-GROUP_PAD, -GROUP_PAD) }
            scene.items[idx] = scene.items[idx].copy(x = r.centerX(), y = r.centerY(), w = r.width(), h = r.height(),
                crop = RectF(0f, 0f, r.width(), r.height()), scale = 1f, rot = 0f, flipX = false, flipY = false)
        }
        return skipped
    }

    private fun Cursor.str(i: Int): String = if (isNull(i)) "" else getString(i)

    private fun color(s: String?): Int? = s?.takeIf { it.isNotBlank() }?.let { runCatching { Color.parseColor(it.trim()) }.getOrNull() }

    /** Text cells hold one byte per code point; other writers may have used real blobs. */
    private fun cell(c: Cursor, typeCol: Int, dataCol: Int): ByteArray? {
        if (c.isNull(dataCol)) return null
        val raw = c.getBlob(dataCol)
        return if (c.getString(typeCol) == "text") latin1(raw) else raw
    }

    private fun latin1(utf8: ByteArray): ByteArray {
        val s = String(utf8, Charsets.UTF_8)
        return ByteArray(s.length) { s[it].code.toByte() }
    }

    /** A cell that may be bigger than a CursorWindow, read in pieces. */
    private fun bytesOf(db: SQLiteDatabase, table: String, col: String, id: Long): ByteArray? {
        var type = ""
        var len = -1L
        db.rawQuery("SELECT typeof($col), length(CAST($col AS BLOB)) FROM $table WHERE id=$id", null).use { c ->
            if (c.moveToFirst() && !c.isNull(1)) { type = c.getString(0); len = c.getLong(1) }
        }
        if (len < 0) return null
        val out = ByteArrayOutputStream(len.toInt())
        var pos = 1L
        while (pos <= len) {
            db.rawQuery("SELECT substr(CAST($col AS BLOB), $pos, $CHUNK) FROM $table WHERE id=$id", null).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) out.write(c.getBlob(0))
            }
            pos += CHUNK
        }
        val raw = out.toByteArray()
        return if (type == "text") latin1(raw) else raw
    }

    private class In(val a: ByteArray) {
        private val b = ByteBuffer.wrap(a)
        var p = 0
        fun i8() = a[p++].toInt()
        fun u16() = (b.getShort(p).toInt() and 0xFFFF).also { p += 2 }
        fun i32() = b.getInt(p).also { p += 4 }
        fun i64() = b.getLong(p).also { p += 8 }
        fun f64() = b.getDouble(p).also { p += 8 }
        /** QVariant header: type id, null flag, and for custom types the registered name. */
        fun variant(): Int {
            val t = i32(); p++
            if (t == T_CUSTOM) { val n = i32(); if (n > 0) p += n }
            return t
        }
    }

    private fun transform(bytes: ByteArray?): Matrix {
        val m = Matrix()
        if (bytes == null) return m
        runCatching {
            val r = In(bytes)
            if (r.variant() != T_TRANSFORM) return m
            val v = DoubleArray(9) { r.f64() }
            m.setValues(floatArrayOf(v[0].toFloat(), v[3].toFloat(), v[6].toFloat(), v[1].toFloat(), v[4].toFloat(), v[7].toFloat(), 0f, 0f, 1f))
        }
        return m
    }

    /** BigRational sort order as a double (good enough to sort by). */
    private fun rational(bytes: ByteArray?): Double {
        if (bytes == null) return 0.0
        return runCatching {
            val r = In(bytes)
            if (r.variant() != T_CUSTOM) return 0.0
            fun big(): Double {
                val sign = r.i32()
                val n = r.i64()
                require(n in 0..64)
                var v = 0.0
                for (k in 0 until n.toInt()) v += (r.i32().toLong() and 0xFFFFFFFFL) * 2.0.pow(32 * k)
                return when (sign) { 0 -> 0.0; -1 -> -v; else -> v }
            }
            val num = big()
            val den = big()
            if (den == 0.0) num else num / den
        }.getOrDefault(0.0)
    }

    /** A QPainterPath payload as polylines, one per subpath, cubics flattened. */
    private fun readPath(r: In): List<FloatArray> {
        val n = r.i32()
        require(n in 0..(r.a.size / 20))
        val types = IntArray(n); val xs = FloatArray(n); val ys = FloatArray(n)
        for (k in 0 until n) { types[k] = r.i32(); xs[k] = r.f64().toFloat(); ys[k] = r.f64().toFloat() }
        if (n > 0) r.p += 8 // subpath start, fill rule
        val out = ArrayList<FloatArray>()
        var cur = ArrayList<Float>()
        fun flush() { if (cur.size >= 2) out += cur.toFloatArray(); cur = ArrayList() }
        var i = 0
        while (i < n) {
            when (types[i]) {
                0 -> { flush(); cur += xs[i]; cur += ys[i]; i++ }
                2 -> if (i + 2 < n && cur.size >= 2) {
                    val x0 = cur[cur.size - 2]; val y0 = cur[cur.size - 1]
                    for (s in 1..12) {
                        val t = s / 12f; val u = 1 - t
                        cur += u * u * u * x0 + 3 * u * u * t * xs[i] + 3 * u * t * t * xs[i + 1] + t * t * t * xs[i + 2]
                        cur += u * u * u * y0 + 3 * u * u * t * ys[i] + 3 * u * t * t * ys[i + 1] + t * t * t * ys[i + 2]
                    }
                    i += 3
                } else { cur += xs[i]; cur += ys[i]; i++ }
                else -> { cur += xs[i]; cur += ys[i]; i++ }
            }
        }
        flush()
        return out
    }

    private fun pathBounds(bytes: ByteArray): RectF? {
        val r = In(bytes)
        if (r.variant() != T_CUSTOM) return null
        val pts = readPath(r).flatMap { it.asList() }
        if (pts.size < 4) return null
        val xs = pts.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = pts.filterIndexed { i, _ -> i % 2 == 1 }
        return RectF(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun strokes(bytes: ByteArray?): List<PStroke> {
        if (bytes == null) return emptyList()
        val r = In(bytes)
        if (r.variant() != T_CUSTOM) return emptyList()
        val n = r.i32()
        val out = ArrayList<PStroke>()
        repeat(n) {
            val version = r.i8()
            if (version <= 99) r.p-- // older strokes start straight with the color
            if (r.i8() != 1) return out // color not stored as RGB: stop here
            val a = r.u16(); val red = r.u16(); val g = r.u16(); val bl = r.u16(); r.u16()
            val width = r.f64().toFloat()
            val paths = readPath(r)
            r.p += 16 // QPointF, always (0, 0)
            val style = if (version > 99) r.i32() else 0
            out += PStroke(Color.argb(a / 257, red / 257, g / 257, bl / 257), width, style == 1, paths)
        }
        return out
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes [items] (in drawing order) as a native PureRef 2 .pur. Call off the UI thread.
     * Images keep their original files (PNG, JPEG, GIF, WebP, BMP); videos go in as their first frame.
     * Groups, parenting, notes, drawings, comments, opacity, grayscale and sampling are kept.
     */
    fun write(items: List<Item>, images: File, out: OutputStream, cache: File) {
        val dbFile = File(cache, "pur2-write-${UUID.randomUUID()}.sqlite")
        try {
            SQLiteDatabase.openDatabase(
                dbFile.path, null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { db ->
                db.disableWriteAheadLogging() // one self-contained file, no -wal next to it
                pragma(db, "PRAGMA journal_mode=DELETE")
                pragma(db, "PRAGMA page_size=4096")
                pragma(db, "PRAGMA auto_vacuum=FULL") // only works before the first table
                pragma(db, "PRAGMA application_id=$APPLICATION_ID")
                db.version = USER_VERSION
                db.beginTransaction()
                try {
                    SCHEMA.forEach(db::execSQL)
                    fill(db, items, images)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            wrap(dbFile.readBytes(), thumbnail(items, images), out)
        } finally {
            listOf("", "-journal", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }
        }
    }

    private fun pragma(db: SQLiteDatabase, sql: String) = db.rawQuery(sql, null).use { it.moveToFirst() }

    /** An image resource as it goes in the file. */
    private class Pix(val data: ByteArray, val format: String, val w: Int, val h: Int, val md5: String)

    private fun pix(it: Item, images: File): Pix? {
        val f = File(images, it.file)
        if (!f.exists()) return null
        val mime = imageMime(f)
        val format = when (mime) { "image/png" -> "PNG"; "image/jpeg" -> "JPG"; "image/gif" -> "GIF"; "image/webp" -> "WEBP"; "image/bmp" -> "BMP"; else -> null }
        val data = if (it.type != VIDEO && format != null) f.readBytes() else {
            val bmp = (if (it.type == VIDEO) videoFrame(f) else decode(f, 8192)) ?: return null
            ByteArrayOutputStream().also { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o); bmp.recycle() }.toByteArray()
        }
        val (w, h) = imageSize(data) ?: return null
        return Pix(data, if (it.type != VIDEO && format != null) format else "PNG", w, h, md5(data))
    }

    private fun fill(db: SQLiteDatabase, items: List<Item>, images: File) {
        // resources, one per file
        val pixOf = HashMap<String, Pix?>()
        val resId = HashMap<String, Long>()
        val insImage = db.compileStatement(
            "INSERT INTO images (id,source_type,origin,source,format,checksum,data,width,height) VALUES (?,1,?,?,?,?,?,?,?)")
        val kept = items.filter { it ->
            when {
                it.isMedia -> pixOf.getOrPut(it.file) { pix(it, images) }?.let { p ->
                    if (it.file !in resId) {
                        val rid = resId.size.toLong()
                        resId[it.file] = rid
                        val src = it.source.replace('\\', '/')
                        insImage.clearBindings()
                        insImage.bindLong(1, rid); insImage.bindString(2, src); insImage.bindString(3, src)
                        insImage.bindString(4, p.format); insImage.bindString(5, p.md5); insImage.bindBlob(6, p.data)
                        insImage.bindLong(7, p.w.toLong()); insImage.bindLong(8, p.h.toLong())
                        try { insImage.executeInsert() } catch (e: android.database.SQLException) {
                            throw IllegalStateException("image $rid (${it.file}): ${e.message}", e)
                        }
                    }
                    true
                } ?: false
                it.type == DRAW -> it.strokes.isNotEmpty()
                it.type == NOTE || it.type == GROUP -> true
                else -> false
            }
        }

        // file ids are positions, so two items sharing an id on the board can't collide in the file
        val ids = HashMap<Long, Long>()
        kept.forEachIndexed { i, it -> ids.putIfAbsent(it.id, i.toLong()) }
        // board transform of each item's local coords (what children are relative to)
        val world = kept.map { it ->
            when {
                it.isMedia -> {
                    val p = pixOf.getValue(it.file)!!
                    Matrix(it.matrix()).apply { preScale(it.w / p.w, it.h / p.h); preTranslate(p.w / 2f, p.h / 2f) } // item origin = image center
                }
                it.type == GROUP -> Matrix(it.matrix()).apply { preTranslate(it.w / 2, it.h / 2) }
                else -> it.matrix() // notes: top-left like Qt text; drawings: their own coords
            }
        }
        val siblings = HashMap<Long, Int>()

        val insItem = db.compileStatement(
            "INSERT INTO items (id,parent,name,transform,sort_order,z,opacity,locked,comment) VALUES (?,?,?,CAST(? AS TEXT),CAST(? AS TEXT),?,?,?,?)")
        val insInst = db.compileStatement(
            "INSERT INTO items_images (id,image,playback_speed,playback_state,image_transform,image_bounds,playback_frame,flags) VALUES (?,?,1.0,?,CAST(? AS TEXT),CAST(? AS TEXT),0,?)")
        val insNote = db.compileStatement(
            "INSERT INTO items_notes (id,text_color,fixed_size,background_color,text,style) VALUES (?,?,CAST(? AS TEXT),?,?,0)")
        val insGroup = db.compileStatement("INSERT INTO items_groups (id,background_color,lock_mode) VALUES (?,?,?)")
        val insDraw = db.compileStatement("INSERT INTO items_drawings (id,strokes) VALUES (?,CAST(? AS TEXT))")

        for ((z, it) in kept.withIndex()) try {
            val id = z.toLong()
            val parent = ids[it.parent]?.takeIf { p -> p != id } ?: -1L
            val local = Matrix(world[z])
            if (parent != -1L) {
                val inv = Matrix()
                if (world[parent.toInt()].invert(inv)) local.postConcat(inv)
            }
            val order = siblings.merge(parent, 1, Int::plus)!!
            insItem.clearBindings()
            insItem.bindLong(1, id); insItem.bindLong(2, parent) // same order as the column list
            if (it.name.isEmpty()) insItem.bindNull(3) else insItem.bindString(3, it.name)
            insItem.bindBlob(4, cellBytes(qTransform(local))); insItem.bindBlob(5, cellBytes(bigRational(order.toLong())))
            insItem.bindDouble(6, z + 1.0); insItem.bindDouble(7, it.opacity.toDouble()); insItem.bindLong(8, if (it.locked) 1 else 0)
            if (it.comment.isEmpty()) insItem.bindNull(9) else insItem.bindString(9, it.comment)
            insItem.executeInsert()

            when {
                it.isMedia -> {
                    val p = pixOf.getValue(it.file)!!
                    val kx = p.w / it.w; val ky = p.h / it.h
                    val c = it.crop
                    val l = c.left * kx - p.w / 2f; val t = c.top * ky - p.h / 2f
                    val r = c.right * kx - p.w / 2f; val b = c.bottom * ky - p.h / 2f
                    insInst.clearBindings()
                    insInst.bindLong(1, id); insInst.bindLong(2, resId.getValue(it.file))
                    insInst.bindLong(3, if (it.type == GIF) 3 else 0) // 3 = playing, 0 = not animated
                    insInst.bindBlob(4, cellBytes(qTransform(Matrix().apply { setTranslate(-p.w / 2f, -p.h / 2f) })))
                    insInst.bindBlob(5, cellBytes(painterPath(listOf(floatArrayOf(l, t, r, t, r, b, l, b, l, t)))))
                    insInst.bindLong(6, (if (it.nearest) 0L else 1L) or (if (it.gray) 2L else 0L))
                    insInst.executeInsert()
                }
                it.type == NOTE -> {
                    insNote.clearBindings()
                    insNote.bindLong(1, id); insNote.bindString(2, argb(it.textColor))
                    insNote.bindBlob(3, cellBytes(variant(T_SIZEF) { f64(-1.0); f64(-1.0) })) // auto size
                    insNote.bindString(4, argb(it.bgColor)); insNote.bindString(5, noteHtml(it))
                    insNote.executeInsert()
                }
                it.type == GROUP -> {
                    insGroup.clearBindings()
                    insGroup.bindLong(1, id); insGroup.bindString(2, argb(it.bgColor)); insGroup.bindLong(3, if (it.closed) 1 else 0)
                    insGroup.executeInsert()
                }
                it.type == DRAW -> {
                    insDraw.clearBindings()
                    insDraw.bindLong(1, id); insDraw.bindBlob(2, cellBytes(strokeList(it.strokes)))
                    insDraw.executeInsert()
                }
            }
        } catch (e: android.database.SQLException) {
            throw IllegalStateException("item $z (${it.type}, board id ${it.id}): ${e.message}", e)
        }

        val meta = db.compileStatement(
            "INSERT INTO metadata (id,scene_rect,application_version,view_transform,thumbnail,horizontal_scroll,vertical_scroll," +
                "last_save_path,last_load_path,last_load_checksum,saved) VALUES (0,NULL,?,CAST(? AS TEXT),?,0,0,NULL,NULL,NULL,1)")
        meta.bindString(1, APP_VERSION); meta.bindBlob(2, cellBytes(qTransform(Matrix()))); meta.bindBlob(3, ByteArray(0))
        meta.executeInsert()
    }

    private fun noteHtml(it: Item): String {
        val body = Html.toHtml(Rich.build(it.text, it.spans), Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
        return "<html><body style=\"font-size:${NOTE_TEXT.toInt()}px;color:${argb(it.textColor)};\">$body</body></html>"
    }

    private fun argb(c: Int) = "#%08x".format(c)

    /** Bytes for a TEXT cell that holds one byte per code point (bound as a blob, then CAST to TEXT). */
    private fun cellBytes(raw: ByteArray): ByteArray = String(CharArray(raw.size) { (raw[it].toInt() and 0xFF).toChar() }).toByteArray(Charsets.UTF_8)

    private class Out {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) = o.write(v)
        fun u16(v: Int) { o.write(v shr 8 and 0xFF); o.write(v and 0xFF) }
        fun i32(v: Int) = o.write(ByteBuffer.allocate(4).putInt(v).array())
        fun i64(v: Long) = o.write(ByteBuffer.allocate(8).putLong(v).array())
        fun f64(v: Double) = i64(java.lang.Double.doubleToRawLongBits(v))
        fun bytes(b: ByteArray) = o.write(b)
    }

    private fun variant(type: Int, name: String? = null, payload: Out.() -> Unit): ByteArray = Out().apply {
        i32(type); u8(0)
        if (name != null) { val n = (name + "\u0000").toByteArray(Charsets.US_ASCII); i32(n.size); bytes(n) }
        payload()
    }.o.toByteArray()

    private fun qTransform(m: Matrix): ByteArray {
        val v = FloatArray(9).also(m::getValues)
        return variant(T_TRANSFORM) {
            f64(v[Matrix.MSCALE_X].toDouble()); f64(v[Matrix.MSKEW_Y].toDouble()); f64(0.0)
            f64(v[Matrix.MSKEW_X].toDouble()); f64(v[Matrix.MSCALE_Y].toDouble()); f64(0.0)
            f64(v[Matrix.MTRANS_X].toDouble()); f64(v[Matrix.MTRANS_Y].toDouble()); f64(1.0)
        }
    }

    private fun bigRational(n: Long): ByteArray = variant(T_CUSTOM, "BigRational") {
        i32(1); i64(1); i32(n.toInt()) // numerator n (orders start at 1)
        i32(1); i64(1); i32(1)         // denominator 1
    }

    /** QPainterPath payload: each polyline becomes a move-to then line-tos. */
    private fun Out.path(lines: List<FloatArray>) {
        val n = lines.sumOf { it.size / 2 }
        i32(n)
        for (p in lines) for (k in 0 until p.size / 2) { i32(if (k == 0) 0 else 1); f64(p[2 * k].toDouble()); f64(p[2 * k + 1].toDouble()) }
        if (n > 0) { i32(0); i32(0) }
    }

    private fun painterPath(lines: List<FloatArray>) = variant(T_CUSTOM, "QPainterPath") { path(lines) }

    private fun strokeList(list: List<Stroke>): ByteArray = variant(T_CUSTOM, "QList<GraphicsDrawItem::Stroke>") {
        val drawn = list.filter { it.tool != T_ERASER && it.pts.size >= 2 }
        i32(drawn.size)
        for (s in drawn) {
            u8(100); u8(1) // stroke version, QColor spec RGB
            u16(Color.alpha(s.color) * 257); u16(Color.red(s.color) * 257); u16(Color.green(s.color) * 257); u16(Color.blue(s.color) * 257); u16(0)
            f64(s.width.toDouble())
            val line = s.outline()
            path(if (s.style == S_ARROW) listOf(line) + arrowHead(line, s.width) else listOf(line))
            f64(0.0); f64(0.0)
            i32(if (s.style == S_DOTTED) 1 else 0) // 1 = dashed
        }
    }

    /** PureRef strokes have no arrows: the head goes in as a little "v" (same shape the board draws). */
    private fun arrowHead(p: FloatArray, w: Float): List<FloatArray> {
        if (p.size < 4) return emptyList()
        val ex = p[p.size - 2]; val ey = p[p.size - 1]
        var i = p.size - 4
        while (i > 0 && kotlin.math.hypot(p[i] - ex, p[i + 1] - ey) < w * 4) i -= 2
        val a = atan2(ey - p[i + 1], ex - p[i])
        val len = w * 4
        return listOf(floatArrayOf(
            ex + len * cos(a + 2.6f), ey + len * sin(a + 2.6f), ex, ey, ex + len * cos(a - 2.6f), ey + len * sin(a - 2.6f),
        ))
    }

    /** 256 px JPEG preview: Windows Explorer shows it for .pur files. */
    private fun thumbnail(items: List<Item>, images: File): ByteArray = runCatching {
        if (items.isEmpty()) return ByteArray(0)
        val b = renderItems(items, images, 256, false)
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it); b.recycle() }.toByteArray()
    }.getOrDefault(ByteArray(0))

    private fun qstr(s: String): ByteArray { val b = s.toByteArray(UTF16); return ByteBuffer.allocate(4).putInt(b.size).array() + b }

    /** Writes header + db[H:] + db[:H], the displaced-prefix container. */
    private fun wrap(db: ByteArray, thumb0: ByteArray, out: OutputStream) {
        val fixed = qstr(FORMAT_VERSION).size + 4 + 8 + qstr(APP_VERSION).size + 4 + 64 + 4
        val thumb = if (fixed + thumb0.size < db.size) thumb0 else ByteArray(0)
        val h = fixed + thumb.size
        require(h < db.size) { "Database too small" }
        val thumbLen = ByteBuffer.allocate(4).putInt(thumb.size).array()
        val md = MessageDigest.getInstance("MD5")
        md.update(thumbLen); md.update(thumb); md.update(db, h, db.size - h); md.update(db, 0, h)
        val checksum = md.digest().joinToString("") { "%02x".format(it) }
        out.write(qstr(FORMAT_VERSION))
        out.write(ByteArray(4))
        out.write(ByteBuffer.allocate(8).putLong(db.size.toLong()).array())
        out.write(qstr(APP_VERSION))
        out.write(qstr(checksum))
        out.write(thumbLen); out.write(thumb)
        out.write(db, h, db.size - h)
        out.write(db, 0, h)
    }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
}
