package org.droref

import org.junit.Assert.assertEquals
import org.junit.Test

class LanPlanTest {
    private fun m(vararg s: Pair<String, SyncMeta>, del: Map<String, Long> = emptyMap()) = SyncMan(s.toMap(), del)
    private fun plan(a: SyncMan, b: SyncMan) = planSync(a, b).map { "${it.op}:${it.id}" }

    @Test fun onlyTheChangedSideWins() {
        // B changed it, even though A's copy looks newer by clock: A didn't touch it since the last sync
        assertEquals(listOf("pull:s"), plan(m("s" to SyncMeta("h1", "h1", 900)), m("s" to SyncMeta("h2", "h1", 100))))
        assertEquals(listOf("push:s"), plan(m("s" to SyncMeta("h2", "h1", 100)), m("s" to SyncMeta("h1", "h1", 900))))
    }

    @Test fun bothChangedKeepsTheOlderAsACopy() {
        assertEquals(listOf("forkRemote:s", "push:s"), plan(m("s" to SyncMeta("a", "h", 200)), m("s" to SyncMeta("b", "h", 100))))
        assertEquals(listOf("forkLocal:s", "pull:s"), plan(m("s" to SyncMeta("a", "h", 100)), m("s" to SyncMeta("b", "h", 200))))
    }

    @Test fun sameContentDoesNothing() = assertEquals(emptyList<String>(), plan(m("s" to SyncMeta("h", "", 1)), m("s" to SyncMeta("h", "x", 2))))

    @Test fun newScenesTravel() {
        assertEquals(listOf("push:a", "pull:b"), plan(m("a" to SyncMeta("h", "", 1)), m("b" to SyncMeta("h", "", 1))))
    }

    @Test fun deleteWinsUnlessEditedAfter() {
        assertEquals(listOf("delLocal:s"), plan(m("s" to SyncMeta("h", "h", 100)), m(del = mapOf("s" to 200L))))
        assertEquals(listOf("push:s"), plan(m("s" to SyncMeta("h2", "h", 300)), m(del = mapOf("s" to 200L))))
        assertEquals(listOf("delRemote:s"), plan(m(del = mapOf("s" to 200L)), m("s" to SyncMeta("h", "h", 100))))
    }

    @Test fun foldersMergeAgainstTheLastSync() {
        val base = listOf("Persos", "Décors")
        // renamed on mine (Persos → Characters), deleted on theirs (Décors), a new one on theirs (Props)
        assertEquals(listOf("Characters", "Props"), mergeFolders(base, listOf("Characters", "Décors"), listOf("Persos", "Props")))
        // nothing changed on theirs: mine's rename and delete go through, no empty leftovers
        assertEquals(listOf("Characters"), mergeFolders(base, listOf("Characters"), base))
        // first sync (no base): everything from both sides
        assertEquals(listOf("A", "B"), mergeFolders(emptyList(), listOf("A"), listOf("B", "A")))
    }
}
