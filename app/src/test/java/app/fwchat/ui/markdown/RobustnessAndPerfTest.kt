package app.fwchat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RobustnessAndPerfTest {

    private inline fun timed(label: String, block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("PERF $label: $ms ms")
        return ms
    }

    private fun realisticText(targetChars: Int, seed: Long = 42): String {
        val rnd = Random(seed)
        val words = "le la les un une de des et ou mais donc car avec sans pour dans sur sous vers chez entre analyse modèle donnée fonction variable résultat important exemple code liste tableau paragraphe".split(" ")
        fun sentence(): String {
            val n = rnd.nextInt(12) + 5
            val sb = StringBuilder()
            repeat(n) {
                val w = words[rnd.nextInt(words.size)]
                sb.append(
                    when (rnd.nextInt(30)) {
                        0 -> "**$w**"
                        1 -> "*$w*"
                        2 -> "`$w()`"
                        3 -> "[$w](https://exemple.org/$w)"
                        else -> w
                    },
                ).append(' ')
            }
            return sb.toString().trim() + "."
        }
        val sb = StringBuilder()
        while (sb.length < targetChars) {
            when (rnd.nextInt(10)) {
                0 -> sb.append("## ").append(sentence()).append("\n\n")
                1, 2, 3, 4 -> sb.append(sentence()).append(' ').append(sentence()).append("\n\n")
                5 -> {
                    repeat(rnd.nextInt(5) + 2) { sb.append("- ").append(sentence()).append('\n') }
                    sb.append('\n')
                }
                6 -> {
                    sb.append("```kotlin\n")
                    repeat(rnd.nextInt(15) + 3) { sb.append("    val x$it = compute($it) // ").append(sentence()).append('\n') }
                    sb.append("```\n\n")
                }
                7 -> {
                    sb.append("| a | b | c |\n|---|:-:|--:|\n")
                    repeat(rnd.nextInt(6) + 2) { sb.append("| ").append(it).append(" | ").append(words[it]).append(" | **x** |\n") }
                    sb.append('\n')
                }
                8 -> sb.append("> ").append(sentence()).append("\n> ").append(sentence()).append("\n\n")
                else -> {
                    repeat(rnd.nextInt(4) + 2) { sb.append(it + 1).append(". ").append(sentence()).append('\n') }
                    sb.append('\n')
                }
            }
        }
        return sb.toString()
    }

    @Test(timeout = 60_000) fun parse1MbRealisticText() {
        val text = realisticText(1_000_000)
        println("PERF corpus size = ${text.length} chars")
        parseMarkdown(realisticText(50_000, 1)) // échauffement JIT
        var blocks = 0
        val ms = timed("parse 1 Mo realiste") { blocks = parseMarkdown(text).size }
        println("PERF blocks = $blocks")
        assertTrue("trop lent: $ms ms", ms < 10_000)
        assertTrue(blocks > 1000)
    }

    @Test(timeout = 120_000) fun incrementalStreamCostIsLocal() {
        val text = realisticText(300_000, 7)
        val inc = IncrementalMarkdown()
        var pos = 0
        var steps = 0
        var worst = 0L
        var updateNanos = 0L
        while (pos < text.length) {
            pos = minOf(text.length, pos + 5)
            val prefix = text.substring(0, pos) // (hors mesure : le moteur fournit déjà un nouveau String à chaque token)
            val t0 = System.nanoTime()
            inc.update(prefix, true)
            val d = System.nanoTime() - t0
            updateNanos += d
            if (steps > 2000 && d > worst) worst = d
            steps++
        }
        val total = updateNanos / 1_000_000
        println("PERF incremental: $steps updates sur ${text.length} chars en $total ms cumulés (${updateNanos / 1000 / steps} us/update, pire après 2000 pas: ${worst / 1000} us)")
        // un re-parse complet à chaque pas coûterait ~steps * 30 ms ; ici on exige très nettement moins
        assertTrue("incrémental trop lent: $total ms", total < 60_000)
        assertEquals(dump(parseMarkdown(text, true)), dump(inc.blocks))
    }

    @Test(timeout = 120_000) fun incrementalHugeCodeBlock() {
        val sb = StringBuilder("```text\n")
        repeat(3000) { sb.append("ligne numéro $it avec du contenu *non interprété*\n") }
        val text = sb.toString()
        val inc = IncrementalMarkdown()
        var pos = 0
        var nanos = 0L
        var n = 0
        while (pos < text.length) {
            pos = minOf(text.length, pos + 6)
            val prefix = text.substring(0, pos)
            val t0 = System.nanoTime()
            inc.update(prefix, true)
            nanos += System.nanoTime() - t0
            n++
        }
        println("PERF code ${text.length / 1000} Ko en flux: $n updates, ${nanos / 1_000_000} ms au total (${nanos / 1000 / n} us/update)")
        val code = inc.blocks.single() as MdCodeBlock
        assertTrue(!code.closed)
        assertEquals(3000, code.lineStarts.size.coerceAtMost(3000))
    }

    // ------------------------------------------------------------ pathologique

    private fun pathological(label: String, text: String, maxMs: Long = 20_000) {
        val ms = timed("pathologique $label (${text.length} chars)") {
            val b = parseMarkdown(text)
            val b2 = parseMarkdown(text, true)
            assertTrue(b.size >= 0 && b2.size >= 0)
        }
        assertTrue("$label trop lent: $ms ms", ms < maxMs)
        // l'incrémental ne doit pas non plus planter
        val inc = IncrementalMarkdown()
        inc.update(text.take(text.length / 2), true)
        inc.update(text, true)
    }

    @Test(timeout = 60_000) fun megabyteOfAsterisks() = pathological("*", "*".repeat(1_000_000))

    @Test(timeout = 60_000) fun megabyteOfUnderscoresAndTildes() {
        pathological("_", "_".repeat(1_000_000))
        pathological("~", "~".repeat(1_000_000))
    }

    @Test(timeout = 60_000) fun starsWithWords() = pathological("*a ", "*a ".repeat(300_000))

    @Test(timeout = 60_000) fun alternatingEmphasis() = pathological("*a_", "*a_b ".repeat(200_000))

    @Test(timeout = 60_000) fun closersOnly() = pathological("a* ", "a* ".repeat(300_000))

    @Test(timeout = 60_000) fun manyOpenBrackets() {
        pathological("[", "[".repeat(1_000_000))
        pathological("[a](", "[a](".repeat(250_000))
        pathological("![", "![a]".repeat(250_000))
        pathological("]", "]".repeat(1_000_000))
        pathological("[[a]", "[[a]".repeat(250_000))
    }

    @Test(timeout = 60_000) fun manyBackticks() {
        pathological("`", "`".repeat(1_000_000))
        pathological("`a", "`a".repeat(500_000))
        pathological("`a``b```", "`a``b```c````".repeat(100_000))
        pathological("``` inline", "```a ".repeat(200_000))
    }

    @Test(timeout = 60_000) fun manyAngleDollarAmp() {
        pathological("<", "<a".repeat(500_000))
        pathological("$", "$".repeat(1_000_000))
        pathological("\$a", "\$a ".repeat(300_000))
        pathological("\\(", "\\(".repeat(500_000))
        pathological("&", "&".repeat(1_000_000))
        pathological("&#", "&#1".repeat(300_000))
        pathological("http", "http://".repeat(150_000))
        pathological("backslash", "\\".repeat(1_000_000))
    }

    @Test(timeout = 60_000) fun deepNestedQuotes() {
        pathological(">", ">".repeat(1_000_000))
        pathological("> > ", "> ".repeat(300_000) + "x")
        pathological(">lines", (">".repeat(50) + " x\n").repeat(20_000))
    }

    @Test(timeout = 60_000) fun deepNestedLists() {
        val sb = StringBuilder()
        for (d in 0 until 2000) sb.append(" ".repeat(d * 2)).append("- item\n")
        pathological("liste profonde", sb.toString())
        pathological("- - - ", "- ".repeat(300_000) + "x")
        pathological("1. 1. ", "1. ".repeat(300_000) + "x")
        val sb2 = StringBuilder()
        repeat(30_000) { sb2.append("- a\n  - b\n    - c\n      - d\n") }
        pathological("liste en boucle", sb2.toString())
    }

    @Test(timeout = 60_000) fun hugeTables() {
        val sb = StringBuilder("| a | b | c | d |\n|---|---|---|---|\n")
        repeat(50_000) { sb.append("| $it | **x** | `y` | [z](u) |\n") }
        pathological("table 50k lignes", sb.toString())
        val wide = StringBuilder()
        repeat(20_000) { wide.append("| c$it ") }
        wide.append("|\n")
        repeat(20_000) { wide.append("|---") }
        wide.append("|\n")
        repeat(20) { repeat(20_000) { wide.append("| v ") }; wide.append("|\n") }
        pathological("table 20k colonnes", wide.toString())
        pathological("pipes", "|".repeat(1_000_000))
        pathological("|-|", "|-|\n".repeat(200_000))
    }

    @Test(timeout = 60_000) fun manyEmptyAndTinyLines() {
        pathological("\\n", "\n".repeat(1_000_000))
        pathological("- \\n", "-\n".repeat(500_000))
        pathological("#", "#\n".repeat(300_000))
        pathological("```", "```\n".repeat(300_000))
        pathological("setext", "a\n---\n".repeat(200_000))
    }

    @Test(timeout = 60_000) fun veryLongSingleLine() {
        pathological("une ligne", "mot ".repeat(500_000))
        pathological("une ligne + gras", "**mot** ".repeat(150_000))
    }

    @Test(timeout = 120_000) fun randomGarbageNeverThrows() {
        val alphabet = "*_~`[]()!<>&$\\#|-:>+.1 \n\t\r\"'=abc日本é😀"
        for (seed in 0L until 3000L) {
            val rnd = Random(seed)
            val len = rnd.nextInt(200)
            val sb = StringBuilder()
            repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
            val t = sb.toString()
            val b = parseMarkdown(t)
            val b2 = parseMarkdown(t, true)
            dump(b); dump(b2) // la récursion de dump ne doit pas déborder non plus
            val inc = IncrementalMarkdown()
            inc.update(t.take(len / 2), true)
            assertEquals("seed=$seed\n$t", dump(parseMarkdown(t, true)), dump(inc.update(t, true)))
        }
    }

    @Test fun inlineNestingIsBounded() {
        val t = "*a ".repeat(5000) + "b" + "* ".repeat(5000)
        val b = parseMarkdown(t).single() as MdParagraph
        assertTrue(depth(b.inlines) <= 40)
        dump(listOf(b)) // pas de débordement de pile
        val t2 = "**a *b ".repeat(5000) + "c" + "* **".repeat(5000)
        assertTrue(depth((parseMarkdown(t2).single() as MdParagraph).inlines) <= 40)
    }

    private fun depth(l: List<MdInline>): Int {
        var d = 0
        for (x in l) {
            val c = when (x) {
                is MdStrong -> 1 + depth(x.children)
                is MdEmphasis -> 1 + depth(x.children)
                is MdStrike -> 1 + depth(x.children)
                is MdLink -> 1 + depth(x.children)
                else -> 0
            }
            if (c > d) d = c
        }
        return d
    }
}
