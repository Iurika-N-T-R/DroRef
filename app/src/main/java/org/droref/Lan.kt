package org.droref

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** One scene as a device sees it: content hash now, hash at the last sync, when its content last changed, its image files. */
class SyncMeta(val h: String, val b: String, val t: Long, val imgs: List<String> = emptyList()) {
    val changed get() = h != b
}

/** Every scene of a device, plus the ones it deleted (id → when), plus its folder list (My scenes). */
class SyncMan(val scenes: Map<String, SyncMeta>, val del: Map<String, Long>, val folders: List<String> = emptyList())

/**
 * Folder lists of both devices against the list both had at the last sync: a folder added on either side is kept,
 * one removed on either side goes (a rename is both). Order: mine first, then theirs.
 */
fun mergeFolders(base: List<String>, mine: List<String>, theirs: List<String>): List<String> {
    val gone = (base - mine.toSet()) + (base - theirs.toSet())
    return (mine + theirs).filter { it.isNotEmpty() && it !in gone }.distinct()
}

class SyncAct(val op: String, val id: String, val t: Long = 0)

/**
 * What to do so both devices end up alike, from the asking device's side.
 * Only one changed a scene since the last sync → it wins. Both did → the newer wins, the other is kept as a copy (fork).
 * A delete wins over a copy that wasn't edited after it.
 * ponytail: "newer" trusts both clocks; fine for my own devices, add a logical clock if they ever drift by minutes.
 */
fun planSync(mine: SyncMan, theirs: SyncMan): List<SyncAct> {
    val out = mutableListOf<SyncAct>()
    for (id in (mine.scenes.keys + theirs.scenes.keys).sorted()) {
        val l = mine.scenes[id]
        val r = theirs.scenes[id]
        when {
            l != null && r != null -> if (l.h != r.h) {
                val mineWins = if (l.changed != r.changed) l.changed else l.t >= r.t
                if (l.changed && r.changed) out += SyncAct(if (mineWins) "forkRemote" else "forkLocal", id)
                out += SyncAct(if (mineWins) "push" else "pull", id)
            }
            l != null -> { val d = theirs.del[id]; out += if (d == null || l.t > d) SyncAct("push", id) else SyncAct("delLocal", id, d) }
            r != null -> { val d = mine.del[id]; out += if (d == null || r.t > d) SyncAct("pull", id) else SyncAct("delRemote", id, d) }
        }
    }
    return out
}

/**
 * LAN sync of the scenes between my devices, like iurikaFlows / OnlyFlows: a 6-digit code to pair (found by sweeping
 * the Wi-Fi /24), then one shared key; every frame after the hello is sealed with it (AES-256-GCM). Manual: "Sync now"
 * finds the paired DroRefs and syncs with each one, whole scenes, images streamed in chunks (only the ones the other lacks).
 * The server only listens while paired or while a code is shown, and only while DroRef's process is alive.
 * ponytail: the key crosses the LAN once, in clear, at pairing, and sealed frames could be replayed; add PAKE / nonces if that matters.
 */
object Lan {
    const val PORT = 47895 // 47891 OnlyFlows, 47893 iurikaFlows / AffineFlows
    private const val CODE_TTL = 5 * 60_000L
    private const val CHUNK = 512 * 1024
    private const val MAX_FRAME = 16 * 1024 * 1024
    private val ID = Regex("[0-9a-f-]{36}")
    private val random = SecureRandom()
    private val main = Handler(Looper.getMainLooper())
    private val lock = ReentrantLock() // one sync at a time, served or asked
    @Volatile private var server: ServerSocket? = null

    @Volatile var code: String? = null; private set
    private var codeUntil = 0L
    private var misses = 0

    // Main thread hooks, set by MainActivity
    var onSave: (() -> Unit)? = null        // write the open scene to disk before it's compared
    var onChanged: ((File) -> Unit)? = null // a scene folder was replaced or deleted by a sync
    var onPaired: (() -> Unit)? = null

    class Report(val devices: Int, val got: Int, val sent: Int, val busy: Boolean = false)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("lan", Context.MODE_PRIVATE)
    fun key(ctx: Context): String? = sp(ctx).getString("key", null)
    /** Random id of this install: a device reachable at two addresses is synced once. */
    private fun me(ctx: Context): String = sp(ctx).getString("me", null)
        ?: java.util.UUID.randomUUID().toString().also { sp(ctx).edit().putString("me", it).apply() }
    fun lastSync(ctx: Context) = sp(ctx).getLong("seen", 0)
    fun unpair(ctx: Context) {
        sp(ctx).edit().remove("key").remove("seen").apply()
        if (code == null) stop()
    }

    // ---- deleted scenes

    private fun tombs(ctx: Context): MutableMap<String, Long> {
        val o = runCatching { JSONObject(sp(ctx).getString("del", "{}")!!) }.getOrElse { JSONObject() }
        return o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap()
    }
    private fun setTombs(ctx: Context, m: Map<String, Long>) = sp(ctx).edit().putString("del", JSONObject(m).toString()).commit()

    /** The user deleted a scene: tell the other devices at the next sync. */
    fun deleted(ctx: Context, id: String) = setTombs(ctx, tombs(ctx).apply { put(id, System.currentTimeMillis()) })

    // ---- sealing

    private fun cipher(mode: Int, key: String, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "AES"), GCMParameterSpec(128, iv))
    }
    private fun seal(key: String, b: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        return iv + cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(b)
    }
    private fun open(key: String, b: ByteArray): ByteArray? =
        runCatching { cipher(Cipher.DECRYPT_MODE, key, b.copyOf(12)).doFinal(b, 12, b.size - 12) }.getOrNull()

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** Length-prefixed frames; clear until [key] is set (the hello), sealed after. */
    private class Conn(val sock: Socket) : Closeable {
        private val inp = DataInputStream(BufferedInputStream(sock.getInputStream()))
        private val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
        var key: String? = null
        fun send(b: ByteArray) { val p = key?.let { seal(it, b) } ?: b; out.writeInt(p.size); out.write(p); out.flush() }
        fun recv(): ByteArray {
            val n = inp.readInt()
            if (n !in 0..MAX_FRAME) throw IOException("frame size")
            val p = ByteArray(n).also(inp::readFully)
            return key?.let { open(it, p) ?: throw IOException("not my key") } ?: p
        }
        fun send(o: JSONObject) = send(o.toString().toByteArray())
        fun json() = JSONObject(String(recv()))
        fun call(o: JSONObject): JSONObject { send(o); return json() }
        override fun close() = sock.close()
    }

    /** Runs [f] on the main thread and waits for it (bounded: a stuck UI can't hang the sync). */
    private fun onMain(f: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { f(); return }
        val done = CountDownLatch(1)
        main.post { try { f() } finally { done.countDown() } }
        done.await(5, TimeUnit.SECONDS)
    }

    // ---- scenes on disk

    private fun dir(ctx: Context, id: String): File {
        require(ID.matches(id)) { "bad id" }
        return File(Scene.root(ctx), id)
    }

    /** sync.json keeps the content hash, the hash at the last sync and when the content last changed. */
    private fun meta(d: File): SyncMeta {
        val f = File(d, "sync.json")
        val st = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
        val sc = Scene.peek(d)
        val h = sha(sc.content().toString())
        if (st.optString("h") != h) { st.put("h", h).put("t", sc.changedAt); f.writeText(st.toString()) }
        return SyncMeta(h, st.optString("b"), st.getLong("t"), File(d, "images").list().orEmpty().filter { !it.endsWith(".part") })
    }

    private fun manifest(ctx: Context) = SyncMan(
        Scene.list(ctx).filter { ID.matches(it.name) }.associate { it.name to meta(it) },
        tombs(ctx),
        folders(),
    )

    private fun folders() = Prefs.folders.lines().filter { it.isNotEmpty() }
    private fun folderBase(ctx: Context) = sp(ctx).getString("fbase", "")!!.lines().filter { it.isNotEmpty() }

    /** The merged folder list becomes both the list and the base for the next merge. */
    private fun setFolders(ctx: Context, list: List<String>) {
        val text = list.joinToString("\n")
        sp(ctx).edit().putString("fbase", text).apply()
        if (text != Prefs.folders) onMain { Prefs.folders = text; onChanged?.invoke(Scene.root(ctx)) } // root ≠ a scene: just refreshes My scenes
    }

    private fun SyncMan.json() = JSONObject()
        .put("scenes", JSONObject().apply { scenes.forEach { (id, m) -> put(id, JSONObject().put("h", m.h).put("b", m.b).put("t", m.t).put("i", JSONArray(m.imgs))) } })
        .put("del", JSONObject(del))
        .put("folders", JSONArray(folders))

    private fun man(o: JSONObject): SyncMan {
        val s = o.getJSONObject("scenes")
        val d = o.getJSONObject("del")
        return SyncMan(
            s.keys().asSequence().associateWith { id ->
                val m = s.getJSONObject(id); val i = m.getJSONArray("i")
                SyncMeta(m.getString("h"), m.getString("b"), m.getLong("t"), List(i.length()) { i.getString(it) })
            },
            d.keys().asSequence().associateWith { d.getLong(it) },
            o.optJSONArray("folders")?.let { f -> List(f.length()) { f.getString(it) } }.orEmpty(),
        )
    }

    /** Scenes both sides hold alike: that's now their common base. */
    private fun settle(ctx: Context, ids: JSONObject) {
        for (id in ids.keys()) runCatching {
            val f = File(dir(ctx, id), "sync.json")
            val st = JSONObject(f.readText())
            if (st.getString("h") == ids.getString(id) && st.optString("b") != st.getString("h")) f.writeText(st.put("b", st.getString("h")).toString())
        }
    }

    /** Header (files and sizes) then each file in chunks; images the other side already has are skipped (their names are random, never reused). */
    private fun sendScene(ctx: Context, c: Conn, id: String, have: Collection<String>, header: JSONObject) {
        val d = dir(ctx, id)
        val sceneJson = JSONObject(File(d, "scene.json").readText()).put("purUri", "").toString().toByteArray() // a .pur path is per device
        val files = mutableListOf<Triple<String, Long, () -> InputStream>>(Triple("scene.json", sceneJson.size.toLong()) { sceneJson.inputStream() })
        File(d, "thumb.jpg").takeIf { it.exists() }?.let { f -> files += Triple("thumb.jpg", f.length()) { f.inputStream() } }
        File(d, "images").listFiles().orEmpty().filter { it.name !in have && !it.name.endsWith(".part") }
            .forEach { f -> files += Triple("images/${f.name}", f.length()) { f.inputStream() } }
        c.send(header.put("id", id).put("t", meta(d).t).put("files", JSONArray(files.map { JSONObject().put("p", it.first).put("n", it.second) })))
        val buf = ByteArray(CHUNK)
        for ((_, n, open) in files) open().use { inp ->
            var left = n
            while (left > 0) {
                val k = inp.read(buf, 0, minOf(CHUNK.toLong(), left).toInt())
                if (k <= 0) throw IOException("file shrank")
                c.send(buf.copyOf(k)); left -= k
            }
        }
    }

    private val SAFE_PATH = Regex("scene\\.json|thumb\\.jpg|images/[A-Za-z0-9._-]+")

    /** Receives into a temporary folder, then swaps files in; scene.json last, so a cut connection leaves the old scene. */
    private fun recvScene(ctx: Context, c: Conn, header: JSONObject) {
        val id = header.getString("id")
        val d = dir(ctx, id)
        val tmp = File(ctx.cacheDir, "lan-$id").apply { deleteRecursively(); mkdirs() }
        try {
            val files = header.getJSONArray("files")
            for (i in 0 until files.length()) {
                val p = files.getJSONObject(i).getString("p")
                if (!SAFE_PATH.matches(p) || p.substringAfter('/').all { it == '.' }) throw IOException("bad path")
                val n = files.getJSONObject(i).getLong("n")
                File(tmp, p).apply { parentFile!!.mkdirs() }.outputStream().use { out ->
                    var left = n
                    while (left > 0) { val b = c.recv(); if (b.size > left) throw IOException("too long"); out.write(b); left -= b.size }
                }
            }
            File(d, "images").mkdirs()
            File(tmp, "images").listFiles().orEmpty().forEach { it.renameTo(File(d, "images/${it.name}")) }
            File(tmp, "thumb.jpg").takeIf { it.exists() }?.renameTo(File(d, "thumb.jpg"))
            val got = File(tmp, "scene.json")
            val mine = runCatching { JSONObject(File(d, "scene.json").readText()).optString("purUri") }.getOrDefault("")
            got.writeText(JSONObject(got.readText()).put("purUri", mine).toString())
            if (!got.renameTo(File(d, "scene.json"))) throw IOException("rename")
            val t = header.getLong("t")
            File(d, "scene.json").setLastModified(t)
            val h = sha(Scene.peek(d).content().toString())
            File(d, "sync.json").writeText(JSONObject().put("h", h).put("b", h).put("t", t).toString())
            setTombs(ctx, tombs(ctx).apply { remove(id) })
        } finally { tmp.deleteRecursively() }
        onMain { onChanged?.invoke(d) }
    }

    /** Keeps this device's version as a new scene before the other one overwrites it. */
    private fun fork(ctx: Context, id: String) {
        val d = dir(ctx, id)
        if (!File(d, "scene.json").exists()) return
        val sc = Scene.peek(d)
        File(sc.copy(ctx, ctx.getString(R.string.lan_conflict, sc.name)).dir, "sync.json").delete()
        onMain { onChanged?.invoke(d) }
    }

    private fun delete(ctx: Context, id: String, t: Long) {
        val d = dir(ctx, id)
        d.deleteRecursively()
        setTombs(ctx, tombs(ctx).apply { put(id, t) })
        onMain { onChanged?.invoke(d) }
    }

    // ---- pairing and serving

    fun newCode(ctx: Context): String = synchronized(this) {
        misses = 0; codeUntil = System.currentTimeMillis() + CODE_TTL
        start(ctx, force = true)
        val app = ctx.applicationContext
        main.postDelayed({ synchronized(this) { if (System.currentTimeMillis() >= codeUntil) { code = null; if (key(app) == null) stop() } } }, CODE_TTL)
        (100000 + random.nextInt(900000)).toString().also { code = it }
    }

    @Synchronized fun start(ctx: Context, force: Boolean = false) {
        if (server != null || (!force && key(ctx) == null)) return
        val app = ctx.applicationContext
        val sock = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }.getOrNull() ?: return
        server = sock
        // one peer at a time: a flood can't pile up threads
        thread(isDaemon = true, name = "lan") {
            while (!sock.isClosed) runCatching { Conn(sock.accept().apply { soTimeout = 15_000 }).use { serve(app, it) } }
                .onFailure { if (it !is java.io.EOFException) android.util.Log.w("Lan", "serve", it) }
        }
    }

    @Synchronized private fun stop() { runCatching { server?.close() }; server = null }

    private fun pairReply(ctx: Context, given: String): JSONObject = synchronized(this) {
        val c = code
        val key = key(ctx)
        if (c != null && System.currentTimeMillis() < codeUntil && same(given, c)) {
            code = null
            val k = key ?: ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            sp(ctx).edit().putString("key", k).apply()
            main.post { onPaired?.invoke() }
            JSONObject().put("key", k)
        } else {
            // 5 wrong guesses burn the code: 6 digits can't be brute-forced in 5 tries
            if (c != null && ++misses >= 5) { code = null; if (key == null) stop() }
            JSONObject()
        }
    }

    private fun serve(ctx: Context, c: Conn) {
        val hello = c.json()
        if (hello.has("pair")) { c.send(pairReply(ctx, hello.getString("pair"))); return }
        c.key = key(ctx) ?: return
        var locked = false
        try {
            while (true) {
                val req = c.json() // throws on a frame not sealed with my key, or when the peer is done
                val op = req.getString("op")
                if (op != "ping" && !locked) {
                    if (!lock.tryLock()) { c.send(JSONObject().put("busy", true)); return }
                    locked = true
                }
                val ok = JSONObject().put("ok", true)
                when (op) {
                    "ping" -> c.send(ok.put("me", me(ctx)))
                    "man" -> { onMain { onSave?.invoke() }; c.send(manifest(ctx).json()) }
                    "get" -> { val h = req.getJSONArray("have"); sendScene(ctx, c, req.getString("id"), List(h.length()) { h.getString(it) }, JSONObject()) }
                    "put" -> { recvScene(ctx, c, req); c.send(ok) }
                    "fork" -> { fork(ctx, req.getString("id")); c.send(ok) }
                    "del" -> { delete(ctx, req.getString("id"), req.getLong("t")); c.send(ok) }
                    "done" -> { settle(ctx, req.getJSONObject("ids")); req.optJSONArray("folders")?.let { f -> setFolders(ctx, List(f.length()) { f.getString(it) }) }; sp(ctx).edit().putLong("seen", System.currentTimeMillis()).apply(); c.send(ok) }
                    else -> return
                }
            }
        } finally { if (locked) lock.unlock() }
    }

    private fun connect(ip: String, timeout: Int) = Socket().apply { connect(InetSocketAddress(ip, PORT), timeout); soTimeout = 15_000 }

    // ponytail: /24 sweep, no mDNS; enough for a home Wi-Fi, add NsdManager if a bigger network ever needs it
    private fun <T> sweep(probe: (String) -> T?): List<T> {
        val mine = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress!! }
        val ips = mine.flatMap { me -> (1..254).map { "${me.substringBeforeLast('.')}.$it" } }.distinct() - mine.toSet()
        val pool = Executors.newFixedThreadPool(64)
        try {
            return pool.invokeAll(ips.map { ip -> Callable { runCatching { probe(ip) }.getOrNull() } }).mapNotNull { it.get() }
        } finally { pool.shutdown() }
    }

    /** Background thread. Joins whichever device shows [code]. */
    fun pair(ctx: Context, code: String): Boolean {
        val k = sweep { ip -> Conn(connect(ip, 400).apply { soTimeout = 3000 }).use { c -> c.call(JSONObject().put("pair", code)).optString("key").ifEmpty { null } } }
            .firstOrNull() ?: return false
        sp(ctx).edit().putString("key", k).apply()
        start(ctx)
        return true
    }

    /** Background thread: syncs every scene with every paired device on the Wi-Fi. */
    fun sync(ctx: Context): Report {
        val key = key(ctx) ?: return Report(0, 0, 0)
        if (!lock.tryLock()) return Report(0, 0, 0, busy = true)
        try {
            onMain { onSave?.invoke() }
            val ips = sweep { ip ->
                Conn(connect(ip, 400).apply { soTimeout = 3000 }).use { c ->
                    c.send(JSONObject().put("sync", 1)); c.key = key
                    c.call(JSONObject().put("op", "ping")).optString("me").takeIf { it.isNotEmpty() && it != me(ctx) }?.let { it to ip }
                }
            }.distinctBy { it.first }.map { it.second }
            var got = 0; var sent = 0; var busy = false; var reached = 0
            for (ip in ips) runCatching {
                Conn(connect(ip, 2000)).use { c ->
                    c.send(JSONObject().put("sync", 1)); c.key = key
                    for (round in 1..3) { // a round can make conflict copies, the next one sends them
                        val r = c.call(JSONObject().put("op", "man"))
                        if (r.optBoolean("busy")) { busy = true; return@use }
                        val theirs = man(r)
                        val mine = manifest(ctx)
                        val acts = planSync(mine, theirs)
                        if (acts.isEmpty()) {
                            val ids = JSONObject().apply { mine.scenes.forEach { (id, m) -> if (theirs.scenes[id]?.h == m.h) put(id, m.h) } }
                            val folders = mergeFolders(folderBase(ctx), mine.folders, theirs.folders)
                            c.call(JSONObject().put("op", "done").put("ids", ids).put("folders", JSONArray(folders)))
                            settle(ctx, ids)
                            setFolders(ctx, folders)
                            reached++
                            return@use
                        }
                        for (a in acts) when (a.op) {
                            "pull" -> {
                                c.send(JSONObject().put("op", "get").put("id", a.id).put("have", JSONArray(mine.scenes[a.id]?.imgs.orEmpty())))
                                recvScene(ctx, c, c.json()); got++
                            }
                            "push" -> { sendScene(ctx, c, a.id, theirs.scenes[a.id]?.imgs.orEmpty(), JSONObject().put("op", "put")); c.json(); sent++ }
                            "forkLocal" -> fork(ctx, a.id)
                            "forkRemote" -> c.call(JSONObject().put("op", "fork").put("id", a.id))
                            "delLocal" -> { delete(ctx, a.id, a.t); got++ }
                            "delRemote" -> { c.call(JSONObject().put("op", "del").put("id", a.id).put("t", a.t)); sent++ }
                        }
                    }
                }
            }.onFailure { android.util.Log.w("Lan", "sync with $ip", it) }
            if (reached > 0) sp(ctx).edit().putLong("seen", System.currentTimeMillis()).apply()
            return Report(reached, got, sent, busy && reached == 0)
        } finally { lock.unlock() }
    }
}
