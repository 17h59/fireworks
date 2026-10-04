package app.fwchat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class IncrementalMarkdownTest {

    private val corpus = """
# Titre principal

Un paragraphe avec du **gras**, de l'*italique*, du ~~barré~~ et du `code`. Un [lien](https://exemple.org "titre")
et une URL nue https://exemple.org/page?x=1. Formule $${'$'}x_i^2${'$'} et \(a+b\) ok.

Sous-titre
----------

- item un
- item deux
  - sous-item a
  - sous-item b
    1. profond
    2. profond bis
- [ ] à faire
- [x] fait

1. premier
2. deuxième

   suite du deuxième

> Une citation
> sur deux lignes
>
> > imbriquée avec **gras**

| Nom | Valeur | Note |
|:----|-------:|:----:|
| a   | 1      | ok   |
| b   | 22     | `x`  |

```kotlin
fun main() {
    println("# pas un titre")
}
```

Texte après code.

${'$'}${'$'}
\int_0^1 x\,dx
${'$'}${'$'}

---

    bloc indenté
    deux lignes

Dernier paragraphe avec une ligne
coupée et un saut forcé
puis la fin.
""".trimIndent()

    private val fragments = listOf(
        "#", "# ", "# titre\n", "## x ##\n", "-", "- ", "- a\n", "  - b\n", "* c\n", "1.", "1. ", "2. z\n", "> ", ">q\n",
        "```", "```py\n", "~~~\n", "``\n", "|", "| a | b |\n", "|---|---|\n", "|--", "---\n", "===\n", "***\n", "\n", "\n\n",
        "text ", "**", "*", "_", "__", "~~", "`", "[", "](", "](http://x)", "![", "$$", "$", "\\", "\\(", "\\)", "\\[", "\\]",
        "    ", "  ", "\t", " ", "x\n", "é", "<br>", "<http://a.b>", "http://a.b/c", "&amp;", "[ ]", "[x] ", "a  \n",
    )

    private fun checkStream(text: String, chunk: (Random) -> Int, seed: Long, checkEveryStep: Boolean = true) {
        val rnd = Random(seed)
        val inc = IncrementalMarkdown()
        var pos = 0
        while (pos < text.length) {
            pos = minOf(text.length, pos + chunk(rnd))
            val prefix = text.substring(0, pos)
            if (checkEveryStep || pos == text.length) {
                val got = inc.update(prefix, streaming = true)
                assertEquals("streaming prefix len=$pos seed=$seed\n$prefix", dump(parseMarkdown(prefix, true)), dump(got))
            } else {
                inc.update(prefix, streaming = true)
            }
        }
        val fin = inc.update(text, streaming = false)
        assertEquals("final seed=$seed", dump(parseMarkdown(text, false)), dump(fin))
        assertEquals(parseMarkdown(text, false), fin)
    }

    @Test fun corpusCharByChar() = checkStream(corpus, { 1 }, 0)

    @Test fun corpusRandomChunks() {
        for (seed in 1L..40L) checkStream(corpus, { it.nextInt(25) + 1 }, seed)
    }

    @Test fun corpusLargeChunks() {
        for (seed in 100L..120L) checkStream(corpus, { it.nextInt(300) + 1 }, seed)
    }

    @Test(timeout = 120_000) fun fuzzFragments() {
        for (seed in 1000L..1400L) {
            val rnd = Random(seed)
            val sb = StringBuilder()
            repeat(rnd.nextInt(60) + 5) { sb.append(fragments[rnd.nextInt(fragments.size)]) }
            checkStream(sb.toString(), { it.nextInt(6) + 1 }, seed)
        }
    }

    @Test(timeout = 120_000) fun fuzzFragmentsStaticMode() {
        // même propriété avec streaming=false à chaque étape
        for (seed in 2000L..2200L) {
            val rnd = Random(seed)
            val sb = StringBuilder()
            repeat(rnd.nextInt(50) + 5) { sb.append(fragments[rnd.nextInt(fragments.size)]) }
            val text = sb.toString()
            val inc = IncrementalMarkdown(defaultStreaming = false)
            var pos = 0
            while (pos < text.length) {
                pos = minOf(text.length, pos + rnd.nextInt(8) + 1)
                val prefix = text.substring(0, pos)
                assertEquals("seed=$seed\n$prefix", dump(parseMarkdown(prefix)), dump(inc.update(prefix)))
            }
        }
    }

    @Test fun closedBlocksKeepIdentity() {
        val inc = IncrementalMarkdown()
        val before = inc.update("# T\n\npara un\n\n- a\n- b\n\n```\ncode\n```\n\ndernier", true)
        assertTrue(before.size >= 5)
        val after = inc.update("# T\n\npara un\n\n- a\n- b\n\n```\ncode\n```\n\ndernier paragraphe qui grandit", true)
        for (i in 0 until before.size - 1) assertSame("bloc $i", before[i], after[i])
        // et encore plus loin
        val later = inc.update("# T\n\npara un\n\n- a\n- b\n\n```\ncode\n```\n\ndernier paragraphe qui grandit\n\nnouveau", true)
        for (i in 0 until after.size - 1) assertSame("bloc $i", after[i], later[i])
    }

    @Test fun streamingKeepsEarlierBlocksWhileTailChanges() {
        val inc = IncrementalMarkdown()
        val text = (1..200).joinToString("\n\n") { "Paragraphe numéro $it avec du **gras** et du texte." }
        var prev: List<MdBlock> = emptyList()
        var pos = 0
        while (pos < text.length) {
            pos = minOf(text.length, pos + 7)
            val cur = inc.update(text.substring(0, pos), true)
            // tout sauf les 2 derniers blocs est identique à l'état précédent
            val common = minOf(prev.size, cur.size) - 2
            for (i in 0 until common) assertSame(prev[i], cur[i])
            prev = cur
        }
    }

    @Test fun unchangedTextReturnsSameList() {
        val inc = IncrementalMarkdown()
        val a = inc.update("abc **d", true)
        assertSame(a, inc.update("abc **d", true))
    }

    @Test fun editResetsToFullParse() {
        val inc = IncrementalMarkdown()
        inc.update("# A\n\nun deux trois\n\nquatre", true)
        val edited = "# B\n\nun deux trois\n\nquatre cinq"
        assertEquals(dump(parseMarkdown(edited, true)), dump(inc.update(edited, true)))
        val shorter = "# B"
        assertEquals(dump(parseMarkdown(shorter, true)), dump(inc.update(shorter, true)))
        assertEquals("", dump(inc.update("", true)))
    }

    @Test fun unclosedCodeBlockIsShownAsOpenCode() {
        val inc = IncrementalMarkdown()
        val r = inc.update("Voici:\n\n```kotlin\nfun a() {\n  1", true)
        val code = r.last() as MdCodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("fun a() {\n  1", code.code)
        assertTrue(!code.closed)
        val r2 = inc.update("Voici:\n\n```kotlin\nfun a() {\n  1\n}\n```\n", true)
        assertTrue((r2.last() as MdCodeBlock).closed)
    }

    @Test fun tableInProgress() {
        val inc = IncrementalMarkdown()
        var r = inc.update("| a | b |\n|---|---|\n| 1", true)
        val t = r.last() as MdTable
        assertEquals(1, t.rows.size)
        assertEquals(2, t.rows[0].size)
        r = inc.update("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n\nfin", true)
        assertEquals(2, (r[0] as MdTable).rows.size)
    }

    @Test fun listInProgress() {
        val inc = IncrementalMarkdown()
        var r = inc.update("- a\n- b\n- ", true)
        assertEquals(1, r.size)
        r = inc.update("- a\n- b\n- c\n\nfin", true)
        assertEquals("UL(P(a),P(b),P(c)) P(fin)", dump(r))
    }

    @Test fun openBoldDoesNotFlickerRawAsterisks() {
        val inc = IncrementalMarkdown()
        for (cut in listOf("**", "**g", "**gr", "**gras", "**gras*", "**gras**", "**gras** et")) {
            val t = "Le $cut"
            val out = dump(inc.update(t, true))
            if (cut.length in 3..6) assertTrue("$t -> $out", out.contains("<b>")) // pas de "**" brut
        }
    }
}
