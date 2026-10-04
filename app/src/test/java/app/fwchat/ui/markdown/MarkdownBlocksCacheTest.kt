package app.fwchat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MarkdownBlocksCacheTest {
    @Test fun sameTextReturnsSameListAndStreamingGrows() {
        val cache = MarkdownBlocksCache(maxEntries = 2)
        val a = cache.blocks("m1", "# T\n\npara", streaming = true)
        assertSame(a, cache.blocks("m1", "# T\n\npara", streaming = true))
        val b = cache.blocks("m1", "# T\n\npara qui grandit", streaming = true)
        assertSame(a[0], b[0])
        assertEquals("H1(T) P(para qui grandit)", dump(cache.blocks("m1", "# T\n\npara qui grandit", streaming = false)))
    }

    @Test fun lruEvictsOldest() {
        val cache = MarkdownBlocksCache(maxEntries = 2)
        val a = cache.blocks("a", "x")
        cache.blocks("b", "y")
        cache.blocks("c", "z")
        val a2 = cache.blocks("a", "x") // "a" a été évincé : re-parse, même contenu
        assertEquals(dump(a), dump(a2))
    }
}
