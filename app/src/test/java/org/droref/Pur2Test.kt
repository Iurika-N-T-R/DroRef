package org.droref

import android.graphics.Bitmap
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Round-trips PureRef 2 native .pur files. Run: ./gradlew testDebugUnitTest -PpurDir=/some/dir
 * Writes kt2.pur there, to check with real PureRef 2: PureRef -c 'load;kt2.pur' -c 'exportScene;kt2.png' -c exit
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Pur2Test {
    private val ctx = RuntimeEnvironment.getApplication()
    private val dir = File(System.getProperty("purDir").orEmpty()).also { it.mkdirs() }

    private fun near(a: Float, b: Float) = assertEquals(a, b, 0.01f)

    private fun image(s: Scene, w: Int, h: Int, color: Int, jpeg: Boolean = false): Item {
        val f = File(s.images, "img${w}x$h")
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            .compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 90, f.outputStream())
        return Item(s.newId(), IMAGE, w.toFloat(), h.toFloat(), file = f.name, name = "img${w}x$h")
    }

    @Test
    fun roundTrip() {
        val s = Scene.create(ctx, "test")
        val group = Item(s.newId(), GROUP, 1f, 1f, name = "refs", bgColor = 0x40FF8800)
        val a = image(s, 400, 300, 0xFFFF0000.toInt()).copy(x = 100f, y = 50f, scale = 0.5f, rot = 30f, parent = group.id,
            opacity = 0.5f, gray = true, comment = "hi")
        val b = image(s, 200, 500, 0xFF00FF00.toInt(), jpeg = true).copy(x = 600f, y = 0f, flipY = true,
            crop = RectF(20f, 40f, 180f, 400f), parent = group.id, nearest = true)
        val dup = a.copy(id = s.newId(), x = 400f, y = 400f, rot = 0f, flipX = true, parent = 0, gray = false) // same image twice
        val note = Item(s.newId(), NOTE, 0f, 0f, x = 300f, y = -200f, scale = 2f, rot = -15f, textColor = 0xFF112233.toInt()).withText("hello\nnote")
        val draw = Item(s.newId(), DRAW, 0f, 0f, x = -300f, y = 300f)
            .withStrokes(listOf(Stroke(T_PEN, 0xFF2040C0.toInt(), 6f, S_DOTTED, floatArrayOf(0f, 0f, 50f, 20f, 100f, 0f))))
        s.items += listOf(group, a, b, dup, note, draw)

        val out = File(dir.takeIf { it.path.isNotEmpty() } ?: ctx.cacheDir, "kt2.pur")
        out.outputStream().use { Pur2.write(drawOrder(s.items), s.images, it, ctx.cacheDir) }
        val bytes = out.readBytes()
        assertTrue(Pur2.isPur2(bytes))

        val r = Scene.create(ctx, "back")
        assertEquals(0, Pur2.read(bytes, r, ctx.cacheDir))
        assertEquals(listOf(GROUP, IMAGE, IMAGE, IMAGE, NOTE, DRAW), r.items.map { it.type })
        val (rg, ra, rb, rdup, rnote) = r.items
        val rdraw = r.items[5]

        for ((x, y) in listOf(a to ra, b to rb, dup to rdup)) {
            near(x.x, y.x); near(x.y, y.y); near(x.scale, y.scale); near(x.w, y.w); near(x.h, y.h)
            assertEquals(x.crop, y.crop)
        }
        near(30f, ra.rot)
        assertEquals(true, rb.flipY)
        // a horizontal flip comes back as vertical flip + 180° (same picture)
        assertEquals(true, rdup.flipY); near(180f, Math.abs(rdup.rot))
        assertEquals(ra.file, rdup.file) // one image resource, used twice
        assertEquals("image/jpeg", imageMime(File(r.images, rb.file))) // originals kept, not re-encoded to PNG

        // item settings
        near(0.5f, ra.opacity); assertEquals(true, ra.gray); assertEquals("hi", ra.comment)
        assertEquals(true, rb.nearest); assertEquals(false, rdup.gray)

        // groups and parenting
        assertEquals("refs", rg.name); assertEquals(0x40FF8800, rg.bgColor)
        assertEquals(rg.id, ra.parent); assertEquals(rg.id, rb.parent); assertEquals(0L, rdup.parent)

        // notes
        assertEquals("hello\nnote", rnote.text)
        near(note.x, rnote.x); near(note.y, rnote.y); near(-15f, rnote.rot); near(2f, rnote.scale)
        assertEquals(note.textColor, rnote.textColor)

        // drawings stay vector strokes
        assertEquals(1, rdraw.strokes.size)
        val st = rdraw.strokes[0]
        assertEquals(0xFF2040C0.toInt(), st.color); near(6f, st.width); assertEquals(S_DOTTED, st.style)
        near(draw.bounds().centerX(), rdraw.bounds().centerX()); near(draw.bounds().centerY(), rdraw.bounds().centerY())
    }

    @Test
    fun legacyFilesStillGoToLegacyReader() {
        val s = Scene.create(ctx, "legacy")
        s.items += image(s, 64, 32, 0xFF0000FF.toInt())
        val out = File(ctx.cacheDir, "legacy.pur")
        out.outputStream().use { Pur.write(s.items, s.images, it) }
        assertEquals(false, Pur2.isPur2(out.readBytes()))
    }
}
