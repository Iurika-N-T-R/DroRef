package org.droref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimplifyTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun posterizeKeepsColor() {
        val px = intArrayOf(rgb(200, 40, 40), rgb(100, 100, 100))
        posterizeValue(px, 3)
        val r = px[0] shr 16 and 255; val g = px[0] shr 8 and 255
        assertTrue("red stays red", r > g + 100)
        assertEquals(rgb(128, 128, 128), px[1]) // gray snaps to the middle band
    }

    @Test fun kuwaharaKeepsEdgeAndFlattensNoise() {
        val w = 12; val h = 4
        val px = IntArray(w * h) { i -> val x = i % w; if (x < 6) rgb(20 + (i % 3) * 10, 30, 30) else rgb(230, 230, 230) }
        val out = kuwahara(px, w, h, 2)
        for (y in 0 until h) {
            assertTrue("dark side stays dark", (out[y * w + 5] shr 16 and 255) < 60)
            assertEquals("light side stays light", rgb(230, 230, 230), out[y * w + 6])
        }
    }

    @Test fun settingsRoundTripOrderAndToggles() {
        val p = Simp.of(96, 2, 3, 4)
        assertEquals(p, Simp.parse(p.toString()))
        val q = p.up('v').with(Step('f', 3, on = false))     // values now run before flat shapes, flat is off
        assertEquals("d96 b2 v4 -f3", q.toString())
        assertEquals(q, Simp.parse(q.toString()))
        assertEquals(q, q.up('b'))                              // detail stays first
        assertEquals(Simp.of(16, 50, 8, 0), Simp.parse("d1 b99 f99 -v2").let { it!!.with(Step('v', 2, false)) })
        assertEquals(null, Simp.parse("d96 b2"))                // a filter missing = not ours
    }

    @Test fun blurSpreadsAndOldSettingsLoad() {
        val w = 9; val px = IntArray(w * 3) { i -> if (i % w == 4) rgb(255, 255, 255) else rgb(0, 0, 0) }
        gaussian(px, w, 3, 1)
        val mid = px[w + 4] shr 16 and 255; val side = px[w + 3] shr 16 and 255; val far = px[w] shr 16 and 255
        assertTrue("peak spreads out", mid in 1..254 && side in 1 until mid && far < side)
        assertEquals(Simp.of(96, 0, 3, 4), Simp.parse("96,3,4"))       // 1.4.0 / 1.4.1, no blur
        assertEquals(Simp.of(1024, 30, 0, 0), Simp.parse("1024,0,0,6")) // 1.4.2 blur was 5x coarser
    }
}
