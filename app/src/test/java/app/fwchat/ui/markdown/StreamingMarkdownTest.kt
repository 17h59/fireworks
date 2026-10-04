package app.fwchat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownTest {

    private fun grow(inc: IncrementalMarkdown, text: String, step: Int = 7): List<Int> {
        val counts = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            i = minOf(text.length, i + step)
            inc.update(text.substring(0, i), streaming = true)
            counts += inc.stableCount
        }
        return counts
    }

    @Test
    fun stableCountCroitSansJamaisDepasserLeNombreDeBlocsMoinsUn() {
        val text = "Premier paragraphe.\n\nSecond paragraphe avec **gras**.\n\n- a\n- b\n\n```kotlin\nfun f() {}\n```\n\nFin du texte"
        val inc = IncrementalMarkdown()
        var previous = 0
        var i = 0
        while (i < text.length) {
            i = minOf(text.length, i + 3)
            inc.update(text.substring(0, i), streaming = true)
            assertTrue("stableCount decroit", inc.stableCount >= previous)
            if (inc.blocks.isNotEmpty()) assertTrue(inc.stableCount < inc.blocks.size)
            previous = inc.stableCount
        }
        assertTrue("des blocs ont ete fermes", previous >= 3)
    }

    @Test
    fun lesBlocsStablesGardentLeurIdentite() {
        val inc = IncrementalMarkdown()
        inc.update("Un.\n\nDeux.\n\nTrois.\n\nQua", streaming = true)
        val before = inc.blocks.take(inc.stableCount)
        assertTrue(before.isNotEmpty())
        inc.update("Un.\n\nDeux.\n\nTrois.\n\nQuatre.\n\nCinq", streaming = true)
        before.forEachIndexed { i, b -> assertSame(b, inc.blocks[i]) }
    }

    @Test
    fun stableCountRepartAZeroApresUneEditionNonAppend() {
        val inc = IncrementalMarkdown()
        inc.update("Un.\n\nDeux.\n\nTrois", streaming = true)
        assertTrue(inc.stableCount > 0)
        inc.update("Autre texte", streaming = true)
        assertEquals(0, inc.stableCount)
        inc.reset()
        assertEquals(0, inc.stableCount)
    }

    @Test
    fun canExtendDistinguePrologementEtEdition() {
        val inc = IncrementalMarkdown()
        assertFalse(inc.canExtend("abc"))
        inc.update("abc", streaming = true)
        assertTrue(inc.canExtend("abc"))
        assertTrue(inc.canExtend("abcdef"))
        assertFalse(inc.canExtend("ab"))
        assertFalse(inc.canExtend("xyz"))
    }

    @Test
    fun splitStreamingVideEtUnBloc() {
        val empty = splitStreamingBlocks(emptyList())
        assertNull(empty.liveBlock)
        assertTrue(empty.stable.blocks.isEmpty())

        val one = splitStreamingBlocks(parseMarkdown("Bonjour", openTail = true))
        assertEquals(0, one.stable.closedCount)
        assertTrue(one.stable.blocks.isEmpty())
        assertNotNull(one.liveBlock)
        assertEquals(0, one.liveFromSlice)
    }

    @Test
    fun splitStreamingNEmetQueLesBlocsFermes() {
        val parts = splitStreamingBlocks(parseMarkdown("Un.\n\nDeux.\n\nTrois en cours", openTail = true))
        assertEquals(2, parts.stable.closedCount)
        assertEquals(2, parts.stable.blocks.size)
        assertEquals(Int.MAX_VALUE, parts.stable.lastBlockSlices)
        assertEquals("Trois en cours", dumpI((parts.liveBlock as MdParagraph).inlines))
    }

    @Test
    fun splitStreamingGeleLesTranchesCompletesDunGrosCodeOuvert() {
        val lines = (1..200).joinToString("\n") { "ligne $it" }
        val parts = splitStreamingBlocks(parseMarkdown("Intro\n\n```\n$lines", openTail = true))
        // 200 lignes = 3 tranches de 80 : 2 sont completes
        assertEquals(1, parts.stable.closedCount)
        assertEquals(2, parts.stable.frozenSlices)
        assertEquals(2, parts.stable.lastBlockSlices)
        assertEquals(2, parts.stable.blocks.size) // intro + code ouvert (tranches gelees seulement)
        assertEquals(2, parts.liveFromSlice)
        val code = parts.liveBlock as MdCodeBlock
        assertFalse(code.closed)
        assertEquals(3, lazySliceCount(code))
    }

    @Test
    fun petitCodeOuvertNeGelePas() {
        val parts = splitStreamingBlocks(parseMarkdown("```\na\nb", openTail = true))
        assertEquals(0, parts.stable.frozenSlices)
        assertTrue(parts.stable.blocks.isEmpty())
    }

    @Test
    fun lEgaliteDeStreamStableIgnoreLeBlocOuvert() {
        val inc = IncrementalMarkdown()
        val a = splitStreamingBlocks(inc.update("Un.\n\nDeux.\n\nTrois en", streaming = true)).stable
        val b = splitStreamingBlocks(inc.update("Un.\n\nDeux.\n\nTrois en cours de", streaming = true)).stable
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val c = splitStreamingBlocks(inc.update("Un.\n\nDeux.\n\nTrois en cours de route.\n\nQuatre", streaming = true)).stable
        assertNotEquals(a, c)
        assertEquals(3, c.closedCount)
    }

    @Test
    fun streamingMarkdownMemoiseLeMemeTexte() {
        val sm = StreamingMarkdown()
        val text = "Un.\n\nDeux"
        val p1 = sm.parts(text)
        val p2 = sm.parts(String(text.toCharArray())) // meme contenu, autre instance
        assertSame(p1, p2)
        val p3 = sm.parts("$text et plus")
        assertNotEquals(p1, p3)
    }

    @Test
    fun cachedBlocksReprendLeParseurDuLiveSansToutReparser() {
        val cache = MarkdownBlocksCache()
        val live = cache.incremental("m1", streaming = true)
        live.update("Un.\n\nDeux.\n\nTrois", streaming = true)
        val closedBlock = live.blocks[0]

        val final = cache.cachedBlocks("m1", "Un.\n\nDeux.\n\nTrois.")
        assertNotNull(final)
        assertSame("le bloc ferme est reutilise", closedBlock, final!![0])
        assertEquals(parseMarkdown("Un.\n\nDeux.\n\nTrois."), final)
    }

    @Test
    fun cachedBlocksRendNullSiAbsentOuSiLeTexteNEstPasUnProlongement() {
        val cache = MarkdownBlocksCache()
        assertNull(cache.cachedBlocks("m", "texte"))
        cache.blocks("m", "texte long")
        assertNull(cache.cachedBlocks("m", "autre"))
        assertNotNull(cache.cachedBlocks("m", "texte long"))
    }

    @Test
    fun putPermetDeReutiliserUnParseurAlimenteAilleurs() {
        val cache = MarkdownBlocksCache()
        val inc = IncrementalMarkdown(false)
        inc.update("# Titre\n\nCorps", streaming = false)
        cache.put("m", inc)
        assertEquals(parseMarkdown("# Titre\n\nCorps"), cache.cachedBlocks("m", "# Titre\n\nCorps"))
    }
}
