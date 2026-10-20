package org.droref

import android.graphics.Bitmap
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Round-trips .pur files. Run: ./gradlew testDebugUnitTest -PpurDir=/some/dir
 * Reads [purDir]/py.pur if present (made by FyorDev/PureRef-format), writes kt.pur and kt_edit.pur there
 * so they can be checked with real PureRef: PureRef -c 'load;kt.pur' -c 'exportScene;kt.png' -c exit
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val dir = File(System.getProperty("purDir").orEmpty()).also { it.mkdirs() }

    private fun near(a: Float, b: Float) = assertEquals(a, b, 0.01f)

    private fun image(s: Scene, w: Int, h: Int, color: Int): Item {
        val f = File(s.images, "img${w}x$h")
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.PNG, 100, f.outputStream())
        return Item(s.newId(), IMAGE, w.toFloat(), h.toFloat(), file = f.name, name = "img${w}x$h")
    }

    @Test
    fun roundTrip() {
        val s = Scene.create(ctx, "test")
        val a = image(s, 400, 300, 0xFFFF0000.toInt()).copy(x = 100f, y = 50f, scale = 0.5f, rot = 30f)
        val b = image(s, 200, 500, 0xFF00FF00.toInt()).copy(x = 600f, y = 0f, flipY = true, crop = RectF(20f, 40f, 180f, 400f))
        val dup = a.copy(id = s.newId(), x = 400f, y = 400f, rot = 0f, flipX = true) // same image twice
        val note = Item(s.newId(), NOTE, 0f, 0f, x = 300f, y = -200f, scale = 2f, rot = -15f, textColor = 0xFF112233.toInt()).withText("hello\nnote")
        s.items += listOf(a, b, dup, note)

        val out = File(dir.takeIf { it.path.isNotEmpty() } ?: ctx.cacheDir, "kt_edit.pur")
        out.outputStream().use { Pur.write(s.items, s.images, it) }

        val r = Scene.create(ctx, "back")
        assertEquals(0, Pur.read(out.readBytes(), r))
        assertEquals(4, r.items.size)
        val (ra, rb, rdup, rnote) = r.items
        for ((x, y) in listOf(a to ra, b to rb, dup to rdup)) {
            near(x.x, y.x); near(x.y, y.y); near(x.scale, y.scale); near(x.w, y.w)
            assertEquals(x.crop, y.crop)
        }
        near(30f, ra.rot)
        assertEquals(true, rb.flipY)
        // a horizontal flip comes back as vertical flip + 180° (same picture)
        assertEquals(true, rdup.flipY); near(180f, Math.abs(rdup.rot))
        assertEquals(ra.file, rdup.file)
        assertEquals("hello\nnote", rnote.text)
        near(note.x, rnote.x); near(note.y, rnote.y); near(-15f, rnote.rot); near(2f, rnote.scale)
        assertEquals(note.textColor, rnote.textColor)
    }

    @Test
    fun readsPythonMadeFile() {
        val py = File(dir, "py.pur")
        assumeTrue(py.exists())
        val s = Scene.create(ctx, "py")
        assertEquals(0, Pur.read(py.readBytes(), s))
        assertEquals(listOf(400f to 300f, 200f to 500f, 640f to 360f), s.items.map { it.w to it.h })
        File(dir, "kt.pur").outputStream().use { Pur.write(s.items, s.images, it) }
    }
}
