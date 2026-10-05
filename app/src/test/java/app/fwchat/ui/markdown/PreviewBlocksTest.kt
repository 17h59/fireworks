package app.fwchat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewBlocksTest {
    private fun doc(n: Int) = parseMarkdown((1..n).joinToString("\n\n") { "Paragraphe $it" })

    @Test
    fun previewKeepsOnlyTheFirstBlocks() {
        val head = previewBlocks(doc(200), 5)
        assertTrue(head.size < 20)
        assertEquals("P(Paragraphe 1)", dump(head.first()))
    }

    @Test
    fun previewDoesNotChangeWhenTheTextKeepsGrowing() {
        assertEquals(previewBlocks(doc(30), 5), previewBlocks(doc(500), 5))
    }

    @Test
    fun singleBlockIsKept() {
        val one = parseMarkdown("seul")
        assertEquals(one, previewBlocks(one, 5))
    }
}
