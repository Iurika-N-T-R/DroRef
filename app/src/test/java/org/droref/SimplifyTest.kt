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

    @Test fun settingsRoundTripAndClamp() {
        assertEquals(Simp(96, 3, 4), Simp.parse(Simp(96, 3, 4).toString()))
        assertEquals(Simp(16, 8, 0), Simp.parse("1,99,1")) // out of range clamps, 1 tone = off
        assertEquals(null, Simp.parse("2"))                // old level format / garbage = off
    }

    @Test fun blurSpreadsAndOldSettingsLoad() {
        val w = 9; val px = IntArray(w * 3) { i -> if (i % w == 4) rgb(255, 255, 255) else rgb(0, 0, 0) }
        gaussian(px, w, 3, 1)
        val mid = px[w + 4] shr 16 and 255; val side = px[w + 3] shr 16 and 255; val far = px[w] shr 16 and 255
        assertTrue("peak spreads out", mid in 1..254 && side in 1 until mid && far < side)
        assertEquals(Simp(96, 3, 4, 0), Simp.parse("96,3,4"))  // saved before blur existed
        assertEquals(Simp(1024, 0, 0, 6), Simp.parse("1024,0,0,6"))
    }
}
