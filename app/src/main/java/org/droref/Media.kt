package org.droref

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.annotation.TargetApi
import java.io.File
import kotlin.math.max

/** Frame delays of a GIF in ms (browsers' rule: 0-10 ms means 100 ms). */
fun gifDelays(b: ByteArray): IntArray {
    if (b.size < 13 || b[0] != 'G'.code.toByte() || b[1] != 'I'.code.toByte() || b[2] != 'F'.code.toByte()) return IntArray(0)
    fun u8(i: Int) = b[i].toInt() and 0xFF
    fun skipBlocks(start: Int): Int {
        var p = start
        while (p < b.size) { val n = u8(p); p += 1 + n; if (n == 0) break }
        return p
    }
    var p = 13
    if (u8(10) and 0x80 != 0) p += 3 * (1 shl ((u8(10) and 7) + 1))
    val out = ArrayList<Int>()
    var delay = 100
    while (p < b.size) {
        when (u8(p)) {
            0x21 -> {
                if (p + 6 < b.size && u8(p + 1) == 0xF9) delay = (u8(p + 4) or (u8(p + 5) shl 8)) * 10
                p = skipBlocks(p + 2)
            }
            0x2C -> {
                if (p + 10 >= b.size) break
                val f = u8(p + 9)
                p += 10
                if (f and 0x80 != 0) p += 3 * (1 shl ((f and 7) + 1))
                p = skipBlocks(p + 1)
                out += if (delay <= 10) 100 else delay
                delay = 100
            }
            else -> break
        }
    }
    return out.toIntArray()
}

fun isAnimatedGif(f: File) = imageMime(f) == "image/gif" && runCatching { gifDelays(f.readBytes()).size > 1 }.getOrDefault(false)

/** Animated GIF with frame-exact play, pause, step, scrub and speed (PureRef 2's GIF toolbar). */
@Suppress("DEPRECATION") // Movie is the only platform decoder that can seek; AnimatedImageDrawable can't
class Gif(f: File) {
    private val movie: Movie? = runCatching { Movie.decodeFile(f.path) }.getOrNull()
    private val starts: IntArray
    val duration: Int
    var time = 0
    var playing = true
        set(v) { field = v; lastTick = 0 }
    var speed = 1f
    private var lastTick = 0L

    init {
        val d = runCatching { gifDelays(f.readBytes()) }.getOrDefault(IntArray(0))
        starts = IntArray(d.size)
        var t = 0
        d.forEachIndexed { i, x -> starts[i] = t; t += x }
        duration = max(1, movie?.duration()?.takeIf { it > 0 } ?: t)
    }

    val frames get() = max(1, starts.size)
    fun frame() = starts.indexOfLast { it <= time }.coerceAtLeast(0)

    fun step(d: Int) {
        playing = false
        time = starts.getOrElse(Math.floorMod(frame() + d, frames)) { 0 }
    }

    fun seek(fraction: Float) { time = (fraction * duration).toInt().coerceIn(0, duration - 1) }

    /** Advances the clock. True while it needs more frames. */
    fun tick(now: Long): Boolean {
        if (!playing) return false
        if (lastTick > 0) time = ((time + (now - lastTick) * speed).toLong() % duration).toInt()
        lastTick = now
        return true
    }

    fun draw(c: Canvas, w: Float, h: Float, paint: Paint) {
        val m = movie ?: return
        m.setTime(time)
        c.save()
        c.scale(w / m.width(), h / m.height())
        m.draw(c, 0f, 0f, paint)
        c.restore()
    }

    fun bitmap(): Bitmap? {
        val m = movie ?: return null
        val b = Bitmap.createBitmap(m.width(), m.height(), Bitmap.Config.ARGB_8888)
        m.setTime(time)
        m.draw(Canvas(b), 0f, 0f)
        return b
    }
}

/** Video size as displayed (portrait phone videos come out portrait). */
fun videoInfo(f: File): Pair<Int, Int>? = runCatching {
    MediaMetadataRetriever().run {
        try {
            setDataSource(f.path)
            if (extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val r = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (r % 180 == 0) w to h else h to w
        } finally {
            release()
        }
    }
}.getOrNull()

fun videoFrame(f: File, ms: Long = 0): Bitmap? = runCatching {
    MediaMetadataRetriever().run {
        try {
            setDataSource(f.path)
            getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            release()
        }
    }
}.getOrNull()

/**
 * Muted video looping between A and B (AnimRef's trimmed loops).
 * Decoder -> GL texture -> GPU renders RGBA into an ImageReader -> HardwareBuffer bitmap the board draws like any image,
 * so videos crop, rotate, layer and fade like images. Decoders output YUV, hence the GL step.
 */
@TargetApi(29)
class Vid(f: File, private val w: Int, private val h: Int, private val redraw: () -> Unit) {
    private val worker = HandlerThread("video").apply { start() }
    private val gl = Handler(worker.looper)
    private val main = Handler(android.os.Looper.getMainLooper())
    private val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 5, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    private val held = ArrayDeque<Image>()
    private val mp = MediaPlayer()
    private var ready = false
    private var released = false
    @Volatile var frame: Bitmap? = null
        private set
    var duration = 1
        private set
    var loopA = 0f
    var loopB = 1f
    var playing = true
        set(v) {
            field = v
            if (!ready) return
            if (v) { mp.start(); applySpeed() } else mp.pause()
        }
    var speed = 1f
        set(v) { field = v; if (ready && playing) applySpeed() }

    val position get() = if (ready) mp.currentPosition else 0

    // GL state, worker thread only
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var surface = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var program = 0
    private val stMatrix = FloatArray(16)

    init {
        reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val hb = img.hardwareBuffer
            if (hb == null) { img.close(); return@setOnImageAvailableListener }
            val b = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))
            hb.close()
            synchronized(held) {
                held.addLast(img)
                while (held.size > 2) held.removeFirst().close() // the frame on screen and the one before stay valid
            }
            frame = b
            redraw()
        }, gl)
        mp.setDataSource(f.path)
        mp.setVolume(0f, 0f)
        mp.setOnPreparedListener {
            ready = true
            duration = max(1, it.duration)
            it.seekTo(a().toLong(), MediaPlayer.SEEK_CLOSEST)
            if (playing) { it.start(); applySpeed() }
        }
        mp.setOnCompletionListener { if (playing) { it.seekTo(a().toLong(), MediaPlayer.SEEK_CLOSEST); it.start() } }
        gl.post {
            val out = setupGl()
            main.post {
                if (released) { out.release(); return@post }
                mp.setSurface(out)
                out.release()
                mp.prepareAsync()
            }
        }
    }

    private fun setupGl(): Surface {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
        ), 0, cfgs, 0, 1, n, 0)
        context = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreateWindowSurface(display, cfgs[0], reader.surface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, context)
        EGL14.eglSwapInterval(display, 0)
        program = program(
            "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uSt; varying vec2 vTex;" +
                "void main() { gl_Position = aPos; vTex = (uSt * aTex).xy; }",
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;" +
                "void main() { gl_FragColor = texture2D(sTex, vTex); }",
        )
        val tex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val st = SurfaceTexture(tex)
        st.setDefaultBufferSize(w, h)
        st.setOnFrameAvailableListener({ drawFrame() }, gl)
        texture = st
        return Surface(st)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)).apply { position(0) }

    private fun drawFrame() {
        val st = texture ?: return
        if (released) return
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val tc = GLES20.glGetAttribLocation(program, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(2)
        GLES20.glVertexAttribPointer(tc, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(tc)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uSt"), 1, false, stMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, surface)
    }

    private fun a() = (loopA * duration).toInt()
    private fun b() = (loopB * duration).toInt()
    private fun applySpeed() = runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }

    /** Keeps playback inside the A–B loop. */
    fun tick() {
        if (!ready || !playing) return
        val p = mp.currentPosition
        if (p >= b() || p < a() - 100) mp.seekTo(a().toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    fun seek(fraction: Float) { if (ready) mp.seekTo((fraction * duration).toLong(), MediaPlayer.SEEK_CLOSEST) }

    fun release() {
        released = true
        runCatching { mp.release() }
        gl.post {
            texture?.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            synchronized(held) { held.forEach { it.close() }; held.clear() }
            reader.close()
            worker.quitSafely()
        }
    }
}
