package app.fwchat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    // ------------------------------------------------------------ blocs simples

    @Test fun empty() = assertEquals("", md(""))

    @Test fun paragraphs() {
        assertEquals("P(a) P(b)", md("a\n\nb"))
        assertEquals("P(a\\nb)", md("a\nb"))
        assertEquals("P(a<br>b)", md("a  \nb"))
        assertEquals("P(a<br>b)", md("a\\\nb"))
    }

    @Test fun crlf() = assertEquals("P(a) P(b)", md("a\r\n\r\nb\r\n"))

    @Test fun atxHeadings() {
        assertEquals("H1(Titre)", md("# Titre"))
        assertEquals("H3(Titre)", md("### Titre ###"))
        assertEquals("H6(x)", md("###### x"))
        assertEquals("P(####### x)", md("####### x"))
        assertEquals("P(#hashtag)", md("#hashtag"))
        assertEquals("H2(<b>g</b>)", md("## **g**"))
    }

    @Test fun setextHeadings() {
        assertEquals("H1(Titre)", md("Titre\n====="))
        assertEquals("H2(Titre)", md("Titre\n---"))
        assertEquals("H2(a\\nb)", md("a\nb\n---"))
    }

    @Test fun thematicBreaks() {
        assertEquals("P(a) HR P(b)", md("a\n\n---\n\nb"))
        assertEquals("HR", md("***"))
        assertEquals("HR", md("_ _ _"))
        assertEquals("HR", md("- - -"))
    }

    @Test fun fencedCode() {
        assertEquals("CODE[kotlin](val x = 1\nval y = 2)", md("```kotlin\nval x = 1\nval y = 2\n```"))
        assertEquals("CODE[](a)", md("~~~\na\n~~~"))
        assertEquals("CODE[](a\n```\nb)", md("````\na\n```\nb\n````"))
        assertEquals("CODE[]()", md("```\n```"))
    }

    @Test fun unclosedFence() {
        assertEquals("CODE[py!](print(1)\nx)", md("```py\nprint(1)\nx"))
        // ligne de fermeture partielle en streaming : masquée
        val b = parseMarkdown("```py\nprint(1)\n``", openTail = true).single() as MdCodeBlock
        assertEquals("print(1)", b.code)
        assertTrue(!b.closed)
    }

    @Test fun fenceKeepsInnerMarkdown() {
        assertEquals("CODE[](# not heading\n- not list)", md("```\n# not heading\n- not list\n```"))
    }

    @Test fun indentedCode() {
        assertEquals("CODE[~](a\n\nb)", md("    a\n\n    b"))
        assertEquals("P(a\\nb)", md("a\n    b"))
    }

    @Test fun mathBlocks() {
        assertEquals("MATH(x^2 + y^2)", md("$$\nx^2 + y^2\n$$"))
        assertEquals("MATH(a=b)", md("$$ a=b $$"))
        assertEquals("MATH(\\frac{a}{b})", md("\\[\n\\frac{a}{b}\n\\]"))
        assertEquals("MATH!(x)", md("$$\nx"))
        assertEquals("P(\$\$100 is a lot)", md("\$\$100 is a lot"))
    }

    // ------------------------------------------------------------ listes

    @Test fun bulletList() {
        assertEquals("UL(P(a),P(b),P(c))", md("- a\n- b\n- c"))
        assertEquals("UL(P(a),P(b))", md("* a\n* b"))
        assertEquals("UL(P(a)) UL(P(b))", md("- a\n* b"))
    }

    @Test fun orderedList() {
        assertEquals("OL1(P(a),P(b))", md("1. a\n2. b"))
        assertEquals("OL3(P(a),P(b))", md("3) a\n4) b"))
    }

    @Test fun nestedLists() {
        assertEquals("UL(P(a) UL(P(b) UL(P(c))),P(d))", md("- a\n  - b\n    - c\n- d"))
        assertEquals("OL1(P(a) UL(P(x),P(y)),P(b))", md("1. a\n   - x\n   - y\n2. b"))
    }

    @Test fun lenientNestedUnderOrdered() {
        // 2 espaces sous "1. " : imbriqué (tolérance, comme la plupart des rendus de chat)
        assertEquals("OL1(P(a) UL(P(x)),P(b))", md("1. a\n  - x\n2. b"))
    }

    @Test fun looseList() {
        assertEquals("UL~(P(a),P(b))", md("- a\n\n- b"))
        assertEquals("UL~(P(a) P(b))", md("- a\n\n  b"))
    }

    @Test fun listLazyContinuation() {
        assertEquals("UL(P(a\\nb))", md("- a\nb"))
    }

    @Test fun listInterruptsParagraph() {
        assertEquals("P(Texte:) UL(P(a),P(b))", md("Texte:\n- a\n- b"))
        assertEquals("P(Texte\\n2. non)", md("Texte\n2. non"))
    }

    @Test fun listWithCode() {
        assertEquals("UL(P(a) CODE[sh](ls),P(b))", md("- a\n  ```sh\n  ls\n  ```\n- b"))
    }

    @Test fun taskList() {
        assertEquals("UL([ ]P(a),[x]P(b),[x]P(c))", md("- [ ] a\n- [x] b\n- [X] c"))
        assertEquals("UL(P([x]b))", md("- [x]b"))
    }

    @Test fun emptyListItem() {
        assertEquals("UL(,P(a))", md("-\n- a"))
    }

    @Test fun listEndsAtNonIndented() {
        assertEquals("UL(P(a)) P(para)", md("- a\n\npara"))
    }

    // ------------------------------------------------------------ citations

    @Test fun quotes() {
        assertEquals("Q(P(a))", md("> a"))
        assertEquals("Q(P(a\\nb))", md("> a\n> b"))
        assertEquals("Q(P(a\\nb))", md("> a\nb"))
        assertEquals("Q(P(a) P(b))", md("> a\n>\n> b"))
        assertEquals("Q(P(a) Q(P(b)))", md("> a\n>\n> > b"))
        assertEquals("Q(H1(t) UL(P(x),P(y)))", md("> # t\n> - x\n> - y"))
        assertEquals("Q(CODE[](a\nb))", md("> ```\n> a\n> b\n> ```"))
    }

    @Test fun quoteEndsAtBlank() = assertEquals("Q(P(a)) P(b)", md("> a\n\nb"))

    // ------------------------------------------------------------ tableaux

    @Test fun table() {
        val t = "| a | b |\n|---|:-:|\n| 1 | 2 |\n| 3 | 4 |"
        assertEquals("TABLE[NC](a|b;1|2;3|4)", md(t))
    }

    @Test fun tableAlignments() {
        assertEquals("TABLE[LCRN](a|b|c|d;1|2|3|4)", md("a|b|c|d\n:--|:-:|--:|---\n1|2|3|4"))
    }

    @Test fun tableRowsNormalized() {
        assertEquals("TABLE[NN](a|b;1|;1|2)", md("| a | b |\n|-|-|\n| 1 |\n| 1 | 2 | 3 |"))
    }

    @Test fun tableInlineAndEscapedPipe() {
        assertEquals("TABLE[NN](<b>a</b>|b;x | y|z)", md("| **a** | b |\n|-|-|\n| x \\| y | z |"))
    }

    @Test fun tableAfterParagraph() {
        assertEquals("P(Intro) TABLE[NN](a|b;1|2)", md("Intro\n| a | b |\n|-|-|\n| 1 | 2 |"))
    }

    @Test fun tableEndsAtBlankOrText() {
        assertEquals("TABLE[NN](a|b;1|2) P(fin)", md("| a | b |\n|-|-|\n| 1 | 2 |\n\nfin"))
        assertEquals("TABLE[NN](a|b;1|2) P(fin)", md("| a | b |\n|-|-|\n| 1 | 2 |\nfin"))
    }

    @Test fun notATable() {
        assertEquals("P(a | b\\n--- x)", md("a | b\n--- x"))
        assertEquals("H2(a | b)", md("a | b\n---"))
    }

    @Test fun tablePartialDelimiterWhileStreaming() {
        val streamed = parseMarkdown("| a | b |\n|---|", openTail = true)
        assertTrue(streamed.single() is MdTable)
        assertTrue(parseMarkdown("| a | b |\n|---|").single() is MdParagraph)
    }

    // ------------------------------------------------------------ HTML / divers

    @Test fun rawHtmlIsText() {
        assertEquals("P(<div>hello</div>)", md("<div>hello</div>"))
        assertEquals("P(a<br>b)", md("a<br>b"))
    }

    @Test fun deepContainersDoNotOverflow() {
        val s = "> ".repeat(500) + "x\n" + "- ".repeat(500) + "y"
        val blocks = parseMarkdown(s)
        assertTrue(blocks.isNotEmpty())
    }
}
