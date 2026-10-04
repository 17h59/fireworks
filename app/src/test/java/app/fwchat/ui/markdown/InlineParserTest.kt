package app.fwchat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class InlineParserTest {

    @Test fun plain() = assertEquals("hello world", inl("hello world"))

    @Test fun strongAndEmphasis() {
        assertEquals("a <b>b</b> c", inl("a **b** c"))
        assertEquals("a <b>b</b> c", inl("a __b__ c"))
        assertEquals("a <i>b</i> c", inl("a *b* c"))
        assertEquals("a <i>b</i> c", inl("a _b_ c"))
        assertEquals("<i><b>x</b></i>", inl("***x***"))
        assertEquals("<b>a <i>b</i> c</b>", inl("**a *b* c**"))
        assertEquals("<i>a <b>b</b> c</i>", inl("*a **b** c*"))
    }

    @Test fun intrawordUnderscore() {
        assertEquals("snake_case_name", inl("snake_case_name"))
        assertEquals("a<i>b</i>c", inl("a*b*c"))
    }

    @Test fun spacedStarsAreLiteral() {
        assertEquals("2 * 3 * 4", inl("2 * 3 * 4"))
        assertEquals("a ** b", inl("a ** b"))
    }

    @Test fun strike() {
        assertEquals("a <s>b</s> c", inl("a ~~b~~ c"))
        assertEquals("a ~b~ c", inl("a ~b~ c"))
    }

    @Test fun codeSpans() {
        assertEquals("a <c>b</c> c", inl("a `b` c"))
        assertEquals("<c>a`b</c>", inl("``a`b``"))
        assertEquals("<c>*x*</c>", inl("`*x*`"))
        assertEquals("<c>a</c>", inl("` a `"))
        assertEquals("a `b", inl("a `b"))
    }

    @Test fun links() {
        assertEquals("<a http://x.io>txt</a>", inl("[txt](http://x.io)"))
        assertEquals("<a http://x.io \"t\">txt</a>", inl("[txt](http://x.io \"t\")"))
        assertEquals("<a http://x.io><b>b</b></a>", inl("[**b**](http://x.io)"))
        assertEquals("[txt] (x)", inl("[txt] (x)"))
        assertEquals("[a](", inl("[a]("))
        assertEquals("<a http://a.io/(x)>t</a>", inl("[t](http://a.io/(x))"))
    }

    @Test fun autolinks() {
        assertEquals("voir <a https://a.io/x>https://a.io/x</a>.", inl("voir https://a.io/x."))
        assertEquals("<a http://a.io>http://a.io</a>", inl("<http://a.io>"))
        assertEquals("<a mailto:a@b.io>a@b.io</a>", inl("<a@b.io>"))
        assertEquals("(<a https://a.io>https://a.io</a>)", inl("(https://a.io)"))
        assertEquals("<a https://www.a.io>www.a.io</a>", inl("www.a.io"))
        assertEquals("whttp", inl("whttp"))
    }

    @Test fun linksDoNotNest() {
        assertEquals("[a <a http://b>b</a>](<a http://c>http://c</a>)", inl("[a [b](http://b)](http://c)"))
    }

    @Test fun images() {
        assertEquals("<img http://x/i.png alt>", inl("![alt](http://x/i.png)"))
        assertEquals("a <img u b> c", inl("a ![b](u) c"))
    }

    @Test fun escapes() {
        assertEquals("*a* b", inl("\\*a\\* b"))
        assertEquals("a\\b", inl("a\\b"))
        assertEquals("a <m>\\[x\\]</m> b", inl("a \\[x\\] b"))
        assertEquals("# a", inl("\\# a"))
    }

    @Test fun entities() {
        assertEquals("a & b < c", inl("a &amp; b &lt; c"))
        assertEquals("A", inl("&#65;"))
        assertEquals("A", inl("&#x41;"))
        assertEquals("&unknown; &", inl("&unknown; &"))
    }

    @Test fun latex() {
        assertEquals("soit <m>\$x_i * y_i\$</m> ok", inl("soit \$x_i * y_i\$ ok"))
        assertEquals("soit <m>\$\$a*b\$\$</m> ok", inl("soit \$\$a*b\$\$ ok"))
        assertEquals("<m>\\(a_1\\)</m>", inl("\\(a_1\\)"))
        assertEquals("coûte \$5 et \$10", inl("coûte \$5 et \$10"))
    }

    @Test fun hardBreakTrailingSpacesAndBr() {
        assertEquals("a<br>b", inl("a  \nb"))
        assertEquals("a\\nb", inl("a \nb"))
        assertEquals("a<br>b", inl("a<br/>b"))
    }

    @Test fun unmatchedMarkersStayLiteral() {
        assertEquals("**a", inl("**a"))
        assertEquals("a*", inl("a*"))
        assertEquals("[a", inl("[a"))
    }

    @Test fun streamingAutoClose() {
        fun s(t: String) = dumpI((parseMarkdown(t, openTail = true).single() as MdParagraph).inlines)
        assertEquals("a <b>gras en cou</b>", s("a **gras en cou"))
        assertEquals("a <i>it</i>", s("a *it"))
        assertEquals("a <c>cod</c>", s("a `cod"))
        assertEquals("<a >text</a>", s("[text](http://ex"))
        assertEquals("a <b>b <i>c</i></b>", s("a **b *c"))
        assertEquals("a **gras en cou", dumpI((parseMarkdown("a **gras en cou").single() as MdParagraph).inlines))
    }
}
