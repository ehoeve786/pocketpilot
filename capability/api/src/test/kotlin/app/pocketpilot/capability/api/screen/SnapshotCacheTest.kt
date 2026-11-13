package app.pocketpilot.capability.api.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotCacheTest {
    @Test
    fun `internal snapshots do not push out the ones the caller was shown`() {
        val evicted = mutableListOf<String>()
        val cache = SnapshotCache<String>(keep = 2) { evicted += it }

        cache.put(snapshot("s1"), "s1")
        repeat(5) { cache.put(snapshot("i$it"), "i$it", shown = false) }

        assertTrue(cache.contains("s1"))
        assertEquals(listOf("i0", "i1", "i2"), evicted)
        cache.put(snapshot("s2"), "s2")
        cache.put(snapshot("s3"), "s3")
        assertFalse(cache.contains("s1"))
    }

    private fun snapshot(id: String) = Snapshot(id = id, packageName = null, screen = ScreenSize(1080, 2400), elements = emptyList())
}
