package org.droref

import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArrangeTest {
    @Test fun masonryKeepsSizesAndSpreadsPortraits() {
        Prefs.init(RuntimeEnvironment.getApplication())
        val sizes = listOf(1080f to 1920f, 1920f to 1080f, 1000f to 1000f, 540f to 960f, 1600f to 900f, 800f to 800f, 1080f to 1920f, 1280f to 720f)
        val items = sizes.mapIndexed { i, (w, h) -> Item(i + 1L, IMAGE, w, h, x = i * 50f, y = i * 70f) }
        val out = Arrange.masonry(items, 16f / 9f, 0f, 0f)
        val rects = out.map { it.bounds() }
        out.zip(items).forEach { (o, i) -> assertEquals("size kept", i.scale, o.scale, 0f) }
        val lefts = rects.map { it.left.toInt() }.distinct()
        assertTrue("not one column", lefts.size > 1)
        val portraits = rects.filter { it.height() > it.width() }.map { it.left.toInt() }.distinct()
        assertTrue("portraits spread side by side, not stacked", portraits.size > 1)
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val a = rects[i]; val b = rects[j]
            assertTrue("no overlap $i/$j", a.right <= b.left + 0.5f || b.right <= a.left + 0.5f || a.bottom <= b.top + 0.5f || b.bottom <= a.top + 0.5f)
        }
    }
}
