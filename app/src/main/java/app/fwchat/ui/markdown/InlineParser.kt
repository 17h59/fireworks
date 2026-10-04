package app.fwchat.ui.markdown

/**
 * Parseur d'inlines (pur Kotlin). Algorithme CommonMark "delimiter stack" en temps linéaire :
 *  - les marqueurs sont des jetons dans une liste doublement chaînée (pas de copie de tableau) ;
 *  - la recherche d'ouvrant utilise `openers_bottom` (pas de quadratique sur `*a *a *a …`) ;
 *  - les spans de code utilisent un index des séries de backticks (recherche amortie O(1)) ;
 *  - l'imbrication est plafonnée (MAX_DEPTH) : au-delà le style est simplement ignoré.
 * Jamais d'exception pour une entrée quelconque.
 *
 * [open] = true : le texte est la fin d'un flux en cours. Les marqueurs non fermés (`**gras`, `` `code``,
 * `[lien](ur`) sont rendus comme s'ils étaient fermés en fin de bloc, pour éviter le clignotement.
 */
internal class InlineParser {

    private class Tok(@JvmField var kind: Int) {
        @JvmField var text: String = ""
        @JvmField var node: MdInline? = null
        @JvmField var depth = 0
        @JvmField var prev: Tok? = null
        @JvmField var next: Tok? = null

        // délimiteurs d'emphase
        @JvmField var ch: Char = ' '
        @JvmField var count = 0
        @JvmField var orig = 0
        @JvmField var canOpen = false
        @JvmField var canClose = false
        @JvmField var dprev: Tok? = null
        @JvmField var dnext: Tok? = null

        // crochets
        @JvmField var image = false
        @JvmField var active = true
        @JvmField var bprev: Tok? = null
        @JvmField var lprev: Tok? = null
        @JvmField var delimBottom: Tok? = null
    }

    private var s: String = ""
    private var n = 0
    private var open = false

    private var head = Tok(T_HEAD)
    private var last = head
    private var dRoot = Tok(T_HEAD)
    private var dTail = dRoot
    private var bTop: Tok? = null
    private var lTop: Tok? = null

    // caches de recherche (un échec est définitif car les ouvrants avancent de gauche à droite)
    private var aborted = false
    private var noCloseParen = false
    private var noCloseBracket = false
    private var runsByLen: HashMap<Int, IntList>? = null
    private var runPtr: HashMap<Int, Int>? = null

    fun parse(src: String, open: Boolean): List<MdInline> {
        if (src.isEmpty()) return emptyList()
        s = src
        n = src.length
        this.open = open
        head = Tok(T_HEAD)
        last = head
        dRoot = Tok(T_HEAD)
        dTail = dRoot
        bTop = null
        lTop = null
        noCloseParen = false
        noCloseBracket = false
        aborted = false
        runsByLen = null
        runPtr = null
        try {
            scan()
            processEmphasis(dRoot, open)
            return collect(head.next, null)
        } finally {
            head = Tok(T_HEAD); last = head; dRoot = Tok(T_HEAD); dTail = dRoot
            bTop = null; lTop = null; runsByLen = null; runPtr = null; s = ""
        }
    }

    // ------------------------------------------------------------------ scan

    private fun scan() {
        val s = this.s
        val n = this.n
        var i = 0
        var textStart = 0
        while (i < n) {
            val c = s[i]
            if (c.code >= 128 || !SPECIAL[c.code]) {
                i++
                continue
            }
            when (c) {
                '\\' -> {
                    if (i + 1 >= n) {
                        i++
                    } else {
                        val d = s[i + 1]
                        var done = false
                        if (d == '\n') {
                            emitText(textStart, i)
                            appendNode(MdLineBreak)
                            i += 2; textStart = i
                            done = true
                        } else if (d == '(' || d == '[') {
                            val close = if (d == '(') "\\)" else "\\]"
                            val q = findMathClose(close, i + 2, d == '(')
                            if (q >= 0) {
                                emitText(textStart, i)
                                appendNode(MdMath(s.substring(i, q + 2)))
                                i = q + 2; textStart = i
                                done = true
                            }
                        }
                        if (!done) {
                            if (isAsciiPunct(d)) {
                                emitText(textStart, i)
                                appendText(d.toString())
                                i += 2; textStart = i
                            } else {
                                i++
                            }
                        }
                    }
                }
                '`' -> {
                    var e = i
                    while (e < n && s[e] == '`') e++
                    val len = e - i
                    val close = findBacktickClose(e, len)
                    if (close >= 0) {
                        emitText(textStart, i)
                        appendNode(MdCode(normalizeCode(s.substring(e, close))))
                        i = close + len; textStart = i
                    } else if (open && e < n) {
                        emitText(textStart, i)
                        appendNode(MdCode(normalizeCode(s.substring(e))))
                        i = n; textStart = n
                    } else {
                        i = e
                    }
                }
                '*', '_', '~' -> {
                    var e = i
                    while (e < n && s[e] == c) e++
                    val len = e - i
                    if (c == '~' && len != 2) {
                        i = e
                    } else {
                        emitText(textStart, i)
                        pushDelim(c, len, i, e)
                        i = e; textStart = e
                    }
                }
                '[' -> {
                    emitText(textStart, i)
                    pushBracket(false)
                    i++; textStart = i
                }
                '!' -> {
                    if (i + 1 < n && s[i + 1] == '[') {
                        emitText(textStart, i)
                        pushBracket(true)
                        i += 2; textStart = i
                    } else {
                        i++
                    }
                }
                ']' -> {
                    val op = bTop
                    if (op == null) {
                        i++
                    } else if (!op.active) {
                        popBracket(op)
                        i++
                    } else {
                        var consumedTo = -1
                        var url = ""
                        var title: String? = null
                        if (i + 1 < n && s[i + 1] == '(') {
                            val r = parseDest(i + 2)
                            if (r != null) {
                                url = r.url; title = r.title; consumedTo = r.end
                            } else if (open && destIncomplete) {
                                consumedTo = n
                            }
                        }
                        if (consumedTo < 0) {
                            popBracket(op)
                            i++
                        } else {
                            emitText(textStart, i)
                            processEmphasis(op.delimBottom!!, false)
                            val children = collect(op.next, null)
                            val depth = lastDepth
                            val node: MdInline = if (op.image) {
                                MdImage(url, plainText(children), title)
                            } else {
                                MdLink(url, title, children)
                            }
                            val before = op.prev!!
                            val t = Tok(T_NODE)
                            t.node = node
                            t.depth = if (op.image) 1 else depth + 1
                            before.next = t; t.prev = before; t.next = null
                            last = t
                            popBracket(op)
                            if (!op.image) {
                                var b = lTop
                                while (b != null && b.active) {
                                    b.active = false
                                    b = b.lprev
                                }
                            }
                            i = consumedTo; textStart = i
                        }
                    }
                }
                '<' -> {
                    val end = scanAngle(i)
                    if (end > 0) {
                        emitText(textStart, i)
                        val inner = s.substring(i + 1, end - 1)
                        val low = inner.lowercase()
                        if (low == "br" || low == "br/" || low == "br /") {
                            appendNode(MdLineBreak)
                        } else if (inner.indexOf('@') > 0 && !inner.contains(':')) {
                            appendNode(MdLink("mailto:$inner", null, listOf(MdText(inner))))
                        } else {
                            appendNode(MdLink(inner, null, listOf(MdText(inner))))
                        }
                        i = end; textStart = i
                    } else {
                        i++
                    }
                }
                '&' -> {
                    val r = decodeEntity(i)
                    if (r != null) {
                        emitText(textStart, i)
                        appendText(r.first)
                        i = r.second; textStart = i
                    } else {
                        i++
                    }
                }
                '$' -> {
                    if (i + 1 < n && s[i + 1] == '$') {
                        val q = s.indexOf("$$", i + 2)
                        if (q > i + 2) {
                            emitText(textStart, i)
                            appendNode(MdMath(s.substring(i, q + 2)))
                            i = q + 2; textStart = i
                        } else {
                            i += 2
                        }
                    } else if (i + 1 < n && !isWs(s[i + 1])) {
                        var q = s.indexOf('$', i + 1)
                        while (q > 0 && s[q - 1] == '\\') q = s.indexOf('$', q + 1)
                        if (q > i + 1 && !isWs(s[q - 1]) && !(q + 1 < n && s[q + 1].isDigit()) && !(q + 1 < n && s[q + 1] == '$')) {
                            emitText(textStart, i)
                            appendNode(MdMath(s.substring(i, q + 1)))
                            i = q + 1; textStart = i
                        } else {
                            i++
                        }
                    } else {
                        i++
                    }
                }
                '\n' -> {
                    var j = i
                    while (j > textStart && s[j - 1] == ' ') j--
                    val spaces = i - j
                    emitText(textStart, j)
                    appendNode(if (spaces >= 2) MdLineBreak else MdSoftBreak)
                    i++
                    while (i < n && s[i] == ' ') i++
                    textStart = i
                }
                'h', 'w' -> {
                    val end = scanBareUrl(i)
                    if (end > 0) {
                        emitText(textStart, i)
                        val raw = s.substring(i, end)
                        val url = if (c == 'w') "https://$raw" else raw
                        appendNode(MdLink(url, null, listOf(MdText(raw))))
                        i = end; textStart = i
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
        emitText(textStart, n)
    }

    // ------------------------------------------------------------------ jetons

    private fun emitText(from: Int, to: Int) {
        if (to > from) appendText(s.substring(from, to))
    }

    private fun appendText(t: String) {
        val tok = Tok(T_TEXT)
        tok.text = t
        link(tok)
    }

    private fun appendNode(node: MdInline) {
        val tok = Tok(T_NODE)
        tok.node = node
        link(tok)
    }

    private fun link(tok: Tok) {
        last.next = tok
        tok.prev = last
        last = tok
    }

    private fun pushDelim(c: Char, len: Int, start: Int, end: Int) {
        val before = if (start == 0) '\n' else s[start - 1]
        val after = if (end >= n) '\n' else s[end]
        val beforeWs = isWs(before)
        val afterWs = isWs(after)
        val beforeP = isPunct(before)
        val afterP = isPunct(after)
        val left = !afterWs && (!afterP || beforeWs || beforeP)
        val right = !beforeWs && (!beforeP || afterWs || afterP)
        val tok = Tok(T_DELIM)
        tok.ch = c
        tok.count = len
        tok.orig = len
        if (c == '_') {
            tok.canOpen = left && (!right || beforeP)
            tok.canClose = right && (!left || afterP)
        } else {
            tok.canOpen = left
            tok.canClose = right
        }
        link(tok)
        tok.dprev = dTail
        dTail.dnext = tok
        dTail = tok
    }

    private fun pushBracket(image: Boolean) {
        val tok = Tok(T_BRACKET)
        tok.text = if (image) "![" else "["
        tok.image = image
        tok.delimBottom = dTail
        tok.bprev = bTop
        bTop = tok
        if (!image) {
            tok.lprev = lTop
            lTop = tok
        }
        link(tok)
    }

    private fun popBracket(op: Tok) {
        bTop = op.bprev
        if (!op.image) lTop = op.lprev
    }

    private fun removeDelim(d: Tok) {
        val p = d.dprev!!
        val nx = d.dnext
        p.dnext = nx
        if (nx != null) nx.dprev = p else dTail = p
    }

    // ------------------------------------------------------------------ emphase

    private var lastDepth = 0

    /** Aplati les jetons de [from] (inclus) à [to] (exclu) en inlines ; fusionne les textes adjacents. */
    private fun collect(from: Tok?, to: Tok?): List<MdInline> {
        val out = ArrayList<MdInline>()
        var sb: StringBuilder? = null
        var maxDepth = 0
        var t = from
        while (t != null && t !== to) {
            when (t.kind) {
                T_TEXT, T_BRACKET -> {
                    if (sb == null) sb = StringBuilder()
                    sb.append(t.text)
                }
                T_DELIM -> {
                    if (t.count > 0) {
                        if (sb == null) sb = StringBuilder()
                        for (k in 0 until t.count) sb.append(t.ch)
                    }
                }
                T_NODE -> {
                    if (sb != null && sb.isNotEmpty()) {
                        out.add(MdText(sb.toString()))
                        sb.setLength(0)
                    }
                    out.add(t.node!!)
                    if (t.depth > maxDepth) maxDepth = t.depth
                }
            }
            t = t.next
        }
        if (sb != null && sb.isNotEmpty()) out.add(MdText(sb.toString()))
        lastDepth = maxDepth
        return out
    }

    private fun keyOf(d: Tok): Int {
        val ci = when (d.ch) { '*' -> 0; '_' -> 1; else -> 2 }
        return ci * 6 + (if (d.canOpen) 3 else 0) + (d.orig % 3)
    }

    private fun processEmphasis(bottom: Tok, autoClose: Boolean) {
        val ob = arrayOfNulls<Tok>(18)
        java.util.Arrays.fill(ob, bottom)
        var closer = bottom.dnext
        while (closer != null && !aborted) {
            if (!closer.canClose) {
                closer = closer.dnext
                continue
            }
            val key = keyOf(closer)
            var opener = closer.dprev
            var found = false
            while (opener != null && opener !== bottom && opener !== ob[key]) {
                if (opener.ch == closer.ch && opener.canOpen) {
                    val odd = closer.ch != '~' &&
                        (closer.canOpen || opener.canClose) &&
                        closer.orig % 3 != 0 &&
                        (opener.orig + closer.orig) % 3 == 0
                    if (!odd) {
                        found = true
                        break
                    }
                }
                opener = opener.dprev
            }
            if (!found || opener == null) {
                ob[key] = closer.dprev
                val nx = closer.dnext
                if (!closer.canOpen) removeDelim(closer)
                closer = nx
            } else {
                val use = if (closer.ch == '~') 2 else if (closer.count >= 2 && opener.count >= 2) 2 else 1
                opener.count -= use
                closer.count -= use
                wrap(opener, closer, use)
                opener.dnext = closer
                closer.dprev = opener
                if (opener.count == 0) removeDelim(opener)
                if (closer.count == 0) {
                    val nx = closer.dnext
                    removeDelim(closer)
                    closer = nx
                }
            }
        }
        if (autoClose && !aborted) {
            var d: Tok? = dTail
            while (d != null && d !== bottom && !aborted) {
                val p = d.dprev
                if (d.canOpen && d.count > 0 && !(d.ch == '~' && d.count < 2)) {
                    while (d.count > 0 && !aborted) {
                        val use = if (d.ch == '~') 2 else if (d.count >= 2) 2 else 1
                        d.count -= use
                        wrapToEnd(d, use)
                    }
                }
                d = p
            }
        }
        bottom.dnext = null
        dTail = bottom
    }

    private fun makeNode(ch: Char, use: Int, children: List<MdInline>): MdInline = when {
        ch == '~' -> MdStrike(children)
        use == 2 -> MdStrong(children)
        else -> MdEmphasis(children)
    }

    /** Enveloppe les jetons entre [opener] et [closer] (exclus) dans un nœud d'emphase. */
    private fun wrap(opener: Tok, closer: Tok, use: Int) {
        val inner = opener.next
        if (inner === closer) return
        val children = collect(inner, closer)
        if (lastDepth >= MAX_DEPTH) {
            // imbrication pathologique : on arrête tout traitement d'emphase (reste du texte littéral), ce qui garde le coût linéaire
            aborted = true
            return
        }
        if (children.isEmpty()) return
        val t = Tok(T_NODE)
        t.node = makeNode(opener.ch, use, children)
        t.depth = lastDepth + 1
        opener.next = t; t.prev = opener
        t.next = closer; closer.prev = t
    }

    private fun wrapToEnd(d: Tok, use: Int) {
        val inner = d.next ?: return
        val children = collect(inner, null)
        if (lastDepth >= MAX_DEPTH) {
            aborted = true
            return
        }
        if (children.isEmpty()) return
        val t = Tok(T_NODE)
        t.node = makeNode(d.ch, use, children)
        t.depth = lastDepth + 1
        d.next = t; t.prev = d; t.next = null
        last = t
    }

    // ------------------------------------------------------------------ code span

    private fun buildRuns() {
        val map = HashMap<Int, IntList>()
        var i = 0
        while (i < n) {
            if (s[i] == '`') {
                var e = i
                while (e < n && s[e] == '`') e++
                map.getOrPut(e - i) { IntList() }.add(i)
                i = e
            } else {
                i++
            }
        }
        runsByLen = map
        runPtr = HashMap()
    }

    /** Début de la prochaine série d'exactement [len] backticks à partir de [from], ou -1. */
    private fun findBacktickClose(from: Int, len: Int): Int {
        if (runsByLen == null) buildRuns()
        val list = runsByLen!![len] ?: return -1
        var p = runPtr!![len] ?: 0
        while (p < list.size && list[p] < from) p++
        runPtr!![len] = p
        return if (p < list.size) list[p] else -1
    }

    private fun normalizeCode(raw: String): String {
        var t = if (raw.indexOf('\n') >= 0) raw.replace('\n', ' ') else raw
        if (t.length >= 2 && t[0] == ' ' && t[t.length - 1] == ' ' && t.any { it != ' ' }) {
            t = t.substring(1, t.length - 1)
        }
        return t
    }

    // ------------------------------------------------------------------ maths

    private fun findMathClose(close: String, from: Int, paren: Boolean): Int {
        if (paren) {
            if (noCloseParen) return -1
        } else if (noCloseBracket) return -1
        val q = s.indexOf(close, from)
        if (q < 0) {
            if (paren) noCloseParen = true else noCloseBracket = true
        }
        return q
    }

    // ------------------------------------------------------------------ liens

    private class Dest(val url: String, val title: String?, val end: Int)

    private var destIncomplete = false

    /** Analyse `(dest "titre")` à partir de [from] (juste après `(`). Renvoie null si invalide. */
    private fun parseDest(from: Int): Dest? {
        destIncomplete = false
        var p = from
        val limit = minOf(n, from + MAX_DEST)
        while (p < n && isWsOrNl(s[p])) p++
        if (p >= n) { destIncomplete = true; return null }
        val url: String
        if (s[p] == '<') {
            val st = p + 1
            var q = st
            while (q < limit && s[q] != '>' && s[q] != '\n' && s[q] != '<') q++
            if (q >= n) { destIncomplete = true; return null }
            if (q >= limit || s[q] != '>') return null
            url = unescape(s.substring(st, q))
            p = q + 1
        } else {
            val st = p
            var depth = 0
            while (p < limit) {
                val ch = s[p]
                if (ch == '\\' && p + 1 < n && isAsciiPunct(s[p + 1])) { p += 2; continue }
                if (ch.code <= 32) break
                if (ch == '(') {
                    depth++
                    if (depth > 32) return null
                } else if (ch == ')') {
                    if (depth == 0) break
                    depth--
                }
                p++
            }
            if (p >= n) { destIncomplete = true; return null }
            if (p >= limit) return null
            url = unescape(s.substring(st, p))
        }
        while (p < n && isWsOrNl(s[p])) p++
        if (p >= n) { destIncomplete = true; return null }
        var title: String? = null
        val tc = s[p]
        if (tc == '"' || tc == '\'' || tc == '(') {
            val closeCh = if (tc == '(') ')' else tc
            val st = p + 1
            var q = st
            val tl = minOf(n, st + MAX_TITLE)
            while (q < tl && s[q] != closeCh) {
                if (s[q] == '\\' && q + 1 < n) q++
                q++
            }
            if (q >= n) { destIncomplete = true; return null }
            if (q >= tl) return null
            title = unescape(s.substring(st, q))
            p = q + 1
            while (p < n && isWsOrNl(s[p])) p++
            if (p >= n) { destIncomplete = true; return null }
        }
        if (s[p] != ')') return null
        return Dest(url, title, p + 1)
    }

    private fun unescape(t: String): String {
        if (t.indexOf('\\') < 0) return t
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length && isAsciiPunct(t[i + 1])) {
                sb.append(t[i + 1]); i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /** `<...>` : renvoie l'index après `>` si c'est un autolien / `<br>`, sinon -1. */
    private fun scanAngle(i: Int): Int {
        var j = i + 1
        val lim = minOf(n, i + 2048)
        if (j >= n) return -1
        val first = s[j]
        if (!first.isLetter()) return -1
        while (j < lim) {
            val ch = s[j]
            if (ch == '>') break
            if (ch.isWhitespace() || ch == '<') {
                // "<br />" : seul cas avec espace
                if (ch == ' ' && s.startsWith("<br />", i, ignoreCase = true)) return i + 6
                return -1
            }
            j++
        }
        if (j >= lim || s[j] != '>') return -1
        val inner = s.substring(i + 1, j)
        if (inner.equals("br", true) || inner.equals("br/", true)) return j + 1
        val colon = inner.indexOf(':')
        if (colon in 2..32 && colon + 1 < inner.length &&
            inner.substring(0, colon).all { it.isLetterOrDigit() || it == '+' || it == '.' || it == '-' }
        ) return j + 1
        val at = inner.indexOf('@')
        if (at > 0 && at < inner.length - 3 && inner.indexOf('.', at) > at + 1 && !inner.contains(':')) return j + 1
        return -1
    }

    /** URL nue (http://, https://, www.) : renvoie l'index de fin, ou -1. */
    private fun scanBareUrl(i: Int): Int {
        if (i > 0 && s[i - 1].isLetterOrDigit()) return -1
        val prefix = when {
            s.startsWith("https://", i) -> 8
            s.startsWith("http://", i) -> 7
            s.startsWith("www.", i) -> 4
            else -> return -1
        }
        var e = i + prefix
        while (e < n && !s[e].isWhitespace() && s[e] != '<') e++
        var opens = 0
        var closes = 0
        for (k in i until e) {
            if (s[k] == '(') opens++ else if (s[k] == ')') closes++
        }
        while (e > i + prefix) {
            val ch = s[e - 1]
            if (ch == ')' && closes > opens) {
                closes--; e--
            } else if (ch == '?' || ch == '!' || ch == '.' || ch == ',' || ch == ':' || ch == '*' || ch == '_' ||
                ch == '~' || ch == '\'' || ch == '"' || ch == ';' || ch == ')'
            ) {
                if (ch == ')') break
                e--
            } else break
        }
        return if (e > i + prefix) e else -1
    }

    // ------------------------------------------------------------------ entités

    private fun decodeEntity(i: Int): Pair<String, Int>? {
        val lim = minOf(n, i + 12)
        var j = i + 1
        if (j >= lim) return null
        if (s[j] == '#') {
            j++
            var hex = false
            if (j < lim && (s[j] == 'x' || s[j] == 'X')) { hex = true; j++ }
            val st = j
            while (j < lim && s[j] != ';') j++
            if (j >= lim || s[j] != ';' || j == st) return null
            val cp = s.substring(st, j).toIntOrNull(if (hex) 16 else 10) ?: return null
            if (cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return null
            return String(Character.toChars(cp)) to j + 1
        }
        val st = j
        while (j < lim && s[j].isLetterOrDigit()) j++
        if (j >= lim || s[j] != ';' || j == st) return null
        val v = ENTITIES[s.substring(st, j)] ?: return null
        return v to j + 1
    }

    companion object {
        const val MAX_DEPTH = 32
        private const val MAX_DEST = 4096
        private const val MAX_TITLE = 1024
        private const val T_HEAD = 0
        private const val T_TEXT = 1
        private const val T_DELIM = 2
        private const val T_NODE = 3
        private const val T_BRACKET = 4

        private val SPECIAL = BooleanArray(128).also { t ->
            for (c in "\\`*_~[]!<&$\nhw") t[c.code] = true
        }

        private val ENTITIES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
            "copy" to "\u00A9", "reg" to "\u00AE", "hellip" to "\u2026", "mdash" to "\u2014", "ndash" to "\u2013",
            "laquo" to "\u00AB", "raquo" to "\u00BB", "rarr" to "\u2192", "larr" to "\u2190", "times" to "\u00D7",
            "deg" to "\u00B0", "plusmn" to "\u00B1", "euro" to "\u20AC", "bull" to "\u2022", "middot" to "\u00B7",
            "trade" to "\u2122", "ne" to "\u2260", "le" to "\u2264", "ge" to "\u2265",
        )
    }
}

internal class IntList {
    var a = IntArray(16)
    var size = 0
    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }
    operator fun get(i: Int) = a[i]
}

internal fun isWs(c: Char): Boolean = c == ' ' || c == '\n' || c == '\t' || c == '\r' || c.isWhitespace() || c == '\u00A0'

private fun isWsOrNl(c: Char): Boolean = c == ' ' || c == '\n' || c == '\t'

internal fun isAsciiPunct(c: Char): Boolean =
    c.code in 33..47 || c.code in 58..64 || c.code in 91..96 || c.code in 123..126

internal fun isPunct(c: Char): Boolean {
    if (c.code < 128) return isAsciiPunct(c)
    return when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        -> true
        else -> false
    }
}
