package app.fwchat.ui.markdown

/**
 * Parseur de blocs (pur Kotlin), basé sur les lignes. Les conteneurs (citations, items de liste) sont
 * traités récursivement sur les lignes "dépouillées" de leur préfixe ; la profondeur est plafonnée
 * ([MAX_DEPTH]) donc le coût reste O(taille × MAX_DEPTH) même sur des entrées pathologiques.
 *
 * `tail` décrit si une plage de lignes touche la fin du document (flux en cours) :
 *  0 = non / mode statique, 1 = oui (dernière ligne complète), 2 = oui et dernière ligne encore partielle.
 * Il ne sert qu'aux heuristiques de streaming (marqueurs inlines non fermés, soulignement setext partiel…).
 */
internal class BlockParser(private val streaming: Boolean) {

    private val inline = InlineParser()

    internal class Top(val blocks: ArrayList<MdBlock>, val startLines: IntList)

    private class Sink(val out: MutableList<MdBlock>, val starts: IntList?) {
        fun add(b: MdBlock, line: Int) {
            out.add(b)
            starts?.add(line)
        }
    }

    private var next = 0
    private var blankBetween = false

    fun parseDocument(lines: List<String>, endsWithNewline: Boolean): Top {
        val blocks = ArrayList<MdBlock>()
        val starts = IntList()
        val tail = if (!streaming) 0 else if (endsWithNewline) 1 else 2
        parseBlocks(lines, 0, lines.size, 0, tail, Sink(blocks, starts))
        return Top(blocks, starts)
    }

    // ------------------------------------------------------------------ boucle principale

    private fun parseBlocks(lines: List<String>, lo: Int, hi: Int, depth: Int, tail: Int, sink: Sink) {
        var i = lo
        var blankSeen = false
        var between = false
        val canNest = depth < MAX_DEPTH
        while (i < hi) {
            val line = lines[i]
            val ind = indentOf(line)
            if (ind >= line.length) {
                i++
                blankSeen = true
                continue
            }
            val sizeBefore = sink.out.size
            var handled = false
            if (ind >= 4) {
                parseIndentedCode(lines, i, hi, sink)
                handled = true
            } else {
                val c = line[ind]
                when {
                    (c == '`' || c == '~') && tryFence(lines, i, hi, ind, tail, sink) -> handled = true
                    c == '#' && tryAtx(line, ind, i, hi, tail, sink) -> handled = true
                    (c == '-' || c == '*' || c == '_') && isThematic(line, ind) -> {
                        sink.add(MdRule, i); next = i + 1; handled = true
                    }
                    c == '>' && canNest -> {
                        parseQuote(lines, i, hi, depth, tail, sink); handled = true
                    }
                    canNest && markerAt(line, ind) != null -> {
                        parseList(lines, i, hi, depth, tail, sink); handled = true
                    }
                    (c == '$' || c == '\\') && tryMath(lines, i, hi, ind, sink) -> handled = true
                }
            }
            if (!handled) parseParagraph(lines, i, hi, depth, tail, sink)
            if (next <= i) next = i + 1 // garde-fou : toujours progresser
            if (blankSeen && sizeBefore > 0 && sink.out.size > sizeBefore) between = true
            blankSeen = false
            i = next
        }
        blankBetween = between
    }

    // ------------------------------------------------------------------ code

    private fun parseIndentedCode(lines: List<String>, i: Int, hi: Int, sink: Sink) {
        var j = i
        var lastNonBlank = i
        while (j < hi) {
            val l = lines[j]
            if (indentOf(l) >= l.length) {
                j++
                continue
            }
            if (indentOf(l) >= 4) {
                lastNonBlank = j
                j++
            } else break
        }
        val sb = StringBuilder()
        for (k in i..lastNonBlank) {
            if (k > i) sb.append('\n')
            val l = lines[k]
            sb.append(l, minOf(4, l.length), l.length)
        }
        sink.add(MdCodeBlock(null, sb.toString(), closed = true, fenced = false), i)
        next = lastNonBlank + 1
    }

    private fun fenceRun(line: String, ind: Int): Int {
        val ch = line[ind]
        var e = ind
        while (e < line.length && line[e] == ch) e++
        val run = e - ind
        if (run < 3) return 0
        if (ch == '`' && line.indexOf('`', e) >= 0) return 0
        return run
    }

    private fun tryFence(lines: List<String>, i: Int, hi: Int, ind: Int, tail: Int, sink: Sink): Boolean {
        val line = lines[i]
        val run = fenceRun(line, ind)
        if (run == 0) return false
        val ch = line[ind]
        val info = line.substring(ind + run).trim()
        var lang: String? = null
        if (info.isNotEmpty()) {
            var e = 0
            while (e < info.length && !info[e].isWhitespace()) e++
            lang = info.substring(0, e)
        }
        val sb = StringBuilder()
        var first = true
        var closed = false
        var j = i + 1
        while (j < hi) {
            val l = lines[j]
            val li = indentOf(l)
            if (li <= 3 && li < l.length && l[li] == ch) {
                var k = li
                while (k < l.length && l[k] == ch) k++
                if (k - li >= run && isBlankFrom(l, k)) {
                    closed = true
                    j++
                    break
                }
                // fermeture partielle en cours de streaming ("``" sur la dernière ligne) : on ne l'affiche pas
                if (tail == 2 && j == hi - 1 && k == l.length && isBlankFrom(l, k)) {
                    j++
                    break
                }
            }
            if (!first) sb.append('\n')
            first = false
            sb.append(l, minOf(li, ind), l.length)
            j++
        }
        sink.add(MdCodeBlock(lang, sb.toString(), closed, fenced = true), i)
        next = j
        return true
    }

    private fun tryMath(lines: List<String>, i: Int, hi: Int, ind: Int, sink: Sink): Boolean {
        val line = lines[i]
        val closeStr: String = when {
            line.startsWith("$$", ind) -> "$$"
            line.startsWith("\\[", ind) -> "\\]"
            else -> return false
        }
        val after = line.substring(ind + 2)
        val idx = after.indexOf(closeStr)
        if (idx >= 0) {
            if (!isBlankFrom(after, idx + 2)) return false
            sink.add(MdMathBlock(after.substring(0, idx).trim(), true), i)
            next = i + 1
            return true
        }
        if (!isBlankFrom(after, 0)) return false
        val sb = StringBuilder()
        var j = i + 1
        var closed = false
        while (j < hi) {
            val l = lines[j]
            val q = l.indexOf(closeStr)
            if (q >= 0) {
                if (q > 0) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(l, 0, q)
                }
                closed = true
                j++
                break
            }
            if (sb.isNotEmpty() || l.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(l)
            }
            j++
        }
        sink.add(MdMathBlock(sb.toString().trimEnd(), closed), i)
        next = j
        return true
    }

    // ------------------------------------------------------------------ titres

    private fun tryAtx(line: String, ind: Int, i: Int, hi: Int, tail: Int, sink: Sink): Boolean {
        var e = ind
        while (e < line.length && line[e] == '#') e++
        val level = e - ind
        if (level > 6) return false
        if (e < line.length && line[e] != ' ') return false
        var content = line.substring(e).trim()
        var k = content.length
        while (k > 0 && content[k - 1] == '#') k--
        if (k == 0) content = "" else if (content[k - 1] == ' ') content = content.substring(0, k).trimEnd()
        val open = tail != 0 && i + 1 == hi
        sink.add(MdHeading(level, inline.parse(content, open)), i)
        next = i + 1
        return true
    }

    // ------------------------------------------------------------------ citations

    private fun parseQuote(lines: List<String>, i: Int, hi: Int, depth: Int, tail: Int, sink: Sink) {
        val inner = ArrayList<String>()
        var j = i
        var fenceCh = 0.toChar()
        var fenceLen = 0
        var lastLazyOk = false
        while (j < hi) {
            val l = lines[j]
            val ind = indentOf(l)
            if (ind <= 3 && ind < l.length && l[ind] == '>') {
                var st = ind + 1
                if (st < l.length && l[st] == ' ') st++
                val rest = l.substring(st)
                inner.add(rest)
                // suivi de l'état de fence
                val ri = indentOf(rest)
                if (ri <= 3 && ri < rest.length) {
                    val rc = rest[ri]
                    if (fenceCh == 0.toChar()) {
                        if (rc == '`' || rc == '~') {
                            val run = fenceRun(rest, ri)
                            if (run > 0) { fenceCh = rc; fenceLen = run }
                        }
                    } else if (rc == fenceCh) {
                        var k = ri
                        while (k < rest.length && rest[k] == fenceCh) k++
                        if (k - ri >= fenceLen && isBlankFrom(rest, k)) fenceCh = 0.toChar()
                    }
                }
                lastLazyOk = fenceCh == 0.toChar() && ri < rest.length && ri < 4 && !isThematic(rest, ri) &&
                    rest[ri] != '#' && !(rest[ri] == '`' || rest[ri] == '~')
                j++
            } else if (ind < l.length && lastLazyOk && markerAt(l, ind) == null && !interrupts(l, ind, depth + 1)) {
                inner.add(l.substring(ind)) // continuation paresseuse
                j++
            } else break
        }
        val sub = ArrayList<MdBlock>()
        val subTail = if (tail != 0 && j == hi) tail else 0
        parseBlocks(inner, 0, inner.size, depth + 1, subTail, Sink(sub, null))
        sink.add(MdQuote(sub), i)
        next = j
    }

    // ------------------------------------------------------------------ listes

    private class Marker(val kind: Char, val ordered: Boolean, val number: Int, val contentOffset: Int)

    private fun markerAt(l: String, ind: Int): Marker? {
        if (ind >= l.length) return null
        val c = l[ind]
        var kind: Char
        var ordered = false
        var number = 0
        var w: Int
        if (c == '-' || c == '+' || c == '*') {
            kind = c
            w = 1
        } else if (c in '0'..'9') {
            var e = ind
            while (e < l.length && l[e] in '0'..'9' && e - ind < 10) e++
            if (e - ind > 9 || e >= l.length) return null
            val d = l[e]
            if (d != '.' && d != ')') return null
            kind = d
            ordered = true
            number = l.substring(ind, e).toInt()
            w = e - ind + 1
        } else return null
        val after = ind + w
        if (after >= l.length) return Marker(kind, ordered, number, after + 1)
        if (l[after] != ' ') return null
        var sp = 0
        while (after + sp < l.length && l[after + sp] == ' ') sp++
        val contentOffset = if (after + sp >= l.length || sp >= 5) after + 1 else after + sp
        return Marker(kind, ordered, number, contentOffset)
    }

    private fun parseList(lines: List<String>, i: Int, hi: Int, depth: Int, tail: Int, sink: Sink) {
        val first = markerAt(lines[i], indentOf(lines[i]))!!
        val items = ArrayList<MdListItem>()
        var loose = false
        var j = i
        var lastEnd = i
        while (j < hi) {
            val l = lines[j]
            val ind = indentOf(l)
            if (ind > 3 || ind >= l.length) break
            val m = markerAt(l, ind) ?: break
            if (m.kind != first.kind) break
            if (items.isNotEmpty() && lastEnd < j) loose = true // ligne(s) vide(s) entre deux items

            val itemLines = ArrayList<String>()
            itemLines.add(if (m.contentOffset >= l.length) "" else l.substring(m.contentOffset))
            val contentIndent = m.contentOffset
            var k = j + 1
            var pendingBlanks = 0
            var fenceCh = 0.toChar()
            var fenceLen = 0
            val st0 = trackFence(itemLines[0], fenceCh, fenceLen)
            fenceCh = st0.first; fenceLen = st0.second
            var lazyOk = fenceCh == 0.toChar() && lazyAllowed(itemLines[0])
            while (k < hi) {
                val nl = lines[k]
                val nind = indentOf(nl)
                if (nind >= nl.length) {
                    if (itemLines.size == 1 && itemLines[0].isEmpty() && k == j + 1) break
                    pendingBlanks++
                    k++
                    continue
                }
                val strip: Int = if (nind >= contentIndent) contentIndent
                else if (nind >= ind + 2 && markerAt(nl, nind) != null) nind
                else -1
                if (strip >= 0) {
                    for (b in 0 until pendingBlanks) itemLines.add("")
                    pendingBlanks = 0
                    val sl = nl.substring(strip)
                    itemLines.add(sl)
                    val st = trackFence(sl, fenceCh, fenceLen)
                    fenceCh = st.first; fenceLen = st.second
                    lazyOk = fenceCh == 0.toChar() && lazyAllowed(sl)
                    k++
                    continue
                }
                if (pendingBlanks == 0 && lazyOk && nind < 4 && markerAt(nl, nind) == null && !interrupts(nl, nind, depth + 1)) {
                    itemLines.add(nl.substring(nind))
                    k++
                    continue
                }
                break
            }
            val itemEnd = k - pendingBlanks
            // case à cocher GFM
            var checked: Boolean? = null
            val f0 = itemLines[0]
            if (f0.length >= 3 && f0[0] == '[' && f0[2] == ']' && (f0[1] == ' ' || f0[1] == 'x' || f0[1] == 'X') &&
                (f0.length == 3 || f0[3] == ' ')
            ) {
                checked = f0[1] != ' '
                itemLines[0] = if (f0.length > 4) f0.substring(4) else ""
            }
            val blocks = ArrayList<MdBlock>()
            val subTail = if (tail != 0 && itemEnd == hi) tail else 0
            parseBlocks(itemLines, 0, itemLines.size, depth + 1, subTail, Sink(blocks, null))
            if (blankBetween) loose = true
            items.add(MdListItem(blocks, checked))
            lastEnd = itemEnd
            j = k
        }
        sink.add(MdList(first.ordered, first.number, !loose, items), i)
        next = maxOf(lastEnd, i + 1)
    }

    /** Met à jour l'état de fence après une ligne. */
    private fun trackFence(l: String, fenceCh: Char, fenceLen: Int): Pair<Char, Int> {
        val ind = indentOf(l)
        if (ind > 3 || ind >= l.length) return fenceCh to fenceLen
        val c = l[ind]
        if (fenceCh == 0.toChar()) {
            if (c == '`' || c == '~') {
                val run = fenceRun(l, ind)
                if (run > 0) return c to run
            }
        } else if (c == fenceCh) {
            var k = ind
            while (k < l.length && l[k] == fenceCh) k++
            if (k - ind >= fenceLen && isBlankFrom(l, k)) return 0.toChar() to 0
        }
        return fenceCh to fenceLen
    }

    /** La ligne peut-elle être suivie d'une continuation paresseuse de paragraphe ? */
    private fun lazyAllowed(l: String): Boolean {
        val ind = indentOf(l)
        if (ind >= l.length || ind >= 4) return false
        val c = l[ind]
        if (c == '#' || c == '`' || c == '~') return false
        return !isThematic(l, ind)
    }

    // ------------------------------------------------------------------ paragraphes / setext / tableaux

    private fun parseParagraph(lines: List<String>, i: Int, hi: Int, depth: Int, tail: Int, sink: Sink) {
        var j = i + 1
        var setextLevel = 0
        var tableAt = -1
        var delimCells: List<MdAlign>? = null
        while (j < hi) {
            val l = lines[j]
            val li = indentOf(l)
            if (li >= l.length) break
            if (li < 4) {
                val c = l[li]
                val pendingLine = tail == 2 && j == hi - 1
                if ((c == '=' || c == '-') && setextUnderline(l, li, pendingLine)) {
                    setextLevel = if (c == '=') 1 else 2
                    break
                }
                if ((c == '|' || c == '-' || c == ':') && lines[j - 1].indexOf('|') >= 0) {
                    val d = tableDelimiter(l, lines[j - 1], pendingLine)
                    if (d != null) {
                        tableAt = j - 1
                        delimCells = d
                        break
                    }
                }
                if (interrupts(l, li, depth)) break
            }
            j++
        }
        if (setextLevel != 0) {
            val content = joinLines(lines, i, j)
            val open = false // une ligne de soulignement vient d'être vue : le titre est clos
            sink.add(MdHeading(setextLevel, inline.parse(content, open)), i)
            next = j + 1
            return
        }
        if (tableAt >= 0) {
            if (tableAt > i) {
                sink.add(MdParagraph(inline.parse(joinLines(lines, i, tableAt), false)), i)
            }
            parseTable(lines, tableAt, hi, depth, tail, delimCells!!, sink)
            return
        }
        val open = tail != 0 && j == hi
        sink.add(MdParagraph(inline.parse(joinLines(lines, i, j), open)), i)
        next = j
    }

    private fun joinLines(lines: List<String>, from: Int, to: Int): String {
        if (to - from == 1) return lines[from].trim()
        val sb = StringBuilder()
        for (k in from until to) {
            val l = lines[k]
            if (k > from) sb.append('\n')
            sb.append(l, indentOf(l), l.length)
        }
        // supprime les espaces de fin (la dernière ligne ne peut pas porter de saut forcé)
        var e = sb.length
        while (e > 0 && (sb[e - 1] == ' ' || sb[e - 1] == '\t')) e--
        sb.setLength(e)
        return sb.toString()
    }

    private fun setextUnderline(l: String, ind: Int, pending: Boolean): Boolean {
        val c = l[ind]
        var e = ind
        while (e < l.length && l[e] == c) e++
        if (!isBlankFrom(l, e)) return false
        if (pending && e - ind < 3) return false
        return true
    }

    private fun parseTable(
        lines: List<String>, hdrIdx: Int, hi: Int, depth: Int, tail: Int,
        aligns: List<MdAlign>, sink: Sink,
    ) {
        val cols = aligns.size
        val header = splitRow(lines[hdrIdx])
        val headerCells = normalizeCells(header, cols, false)
        val rows = ArrayList<List<List<MdInline>>>()
        var j = hdrIdx + 2
        while (j < hi) {
            val l = lines[j]
            val li = indentOf(l)
            if (li >= l.length) break
            if (li < 4 && (interrupts(l, li, depth) || l.indexOf('|') < 0)) break
            val last = tail != 0 && j == hi - 1
            rows.add(normalizeCells(splitRow(l), cols, last))
            j++
        }
        sink.add(MdTable(aligns, headerCells, rows), hdrIdx)
        next = j
    }

    private fun normalizeCells(raw: List<String>, cols: Int, open: Boolean): List<List<MdInline>> {
        val out = ArrayList<List<MdInline>>(cols)
        for (c in 0 until cols) {
            if (c < raw.size) out.add(inline.parse(raw[c], open && c == raw.size - 1)) else out.add(emptyList())
        }
        return out
    }

    /** Si [l] est une ligne de délimiteurs de tableau compatible avec [header], renvoie les alignements. */
    private fun tableDelimiter(l: String, header: String, pending: Boolean): List<MdAlign>? {
        // pré-filtre rapide : uniquement | - : espaces, au moins un '-' et un '|'
        var hasDash = false
        var hasPipe = false
        for (ch in l) {
            when (ch) {
                '-' -> hasDash = true
                '|' -> hasPipe = true
                ':', ' ', '\t' -> {}
                else -> return null
            }
        }
        if (!hasDash || !hasPipe) return null
        val cells = splitRow(l)
        val hcount = splitRow(header).size
        if (cells.size != hcount && !(pending && cells.size < hcount && cells.isNotEmpty())) return null
        val aligns = ArrayList<MdAlign>(hcount)
        for (cell in cells) {
            val t = cell.trim()
            if (t.isEmpty()) return null
            val left = t[0] == ':'
            val right = t.length > 1 && t[t.length - 1] == ':'
            val core = t.substring(if (left) 1 else 0, if (right) t.length - 1 else t.length)
            if (core.isEmpty() || core.any { it != '-' }) return null
            aligns.add(if (left && right) MdAlign.Center else if (left) MdAlign.Left else if (right) MdAlign.Right else MdAlign.None)
        }
        while (aligns.size < hcount) aligns.add(MdAlign.None)
        return aligns
    }

    // ------------------------------------------------------------------ interruptions

    /** Cette ligne (indent < 4) démarre-t-elle un bloc qui interrompt un paragraphe ? */
    private fun interrupts(l: String, ind: Int, depth: Int): Boolean {
        if (ind >= 4 || ind >= l.length) return false
        val c = l[ind]
        when (c) {
            '`', '~' -> return fenceRun(l, ind) > 0
            '#' -> {
                var e = ind
                while (e < l.length && l[e] == '#') e++
                return e - ind <= 6 && (e == l.length || l[e] == ' ')
            }
            '>' -> return depth < MAX_DEPTH
            '-', '*', '_', '+' -> {
                if (isThematic(l, ind)) return true
                if (c != '_' && depth < MAX_DEPTH && ind + 1 < l.length && l[ind + 1] == ' ' && !isBlankFrom(l, ind + 2)) return true
                return false
            }
            in '0'..'9' -> {
                if (depth >= MAX_DEPTH) return false
                val m = markerAt(l, ind) ?: return false
                return m.ordered && m.number == 1 && m.contentOffset < l.length && !isBlankFrom(l, m.contentOffset)
            }
            '$' -> return l.startsWith("$$", ind) && mathStarts(l, ind, "$$")
            '\\' -> return l.startsWith("\\[", ind) && mathStarts(l, ind, "\\]")
        }
        return false
    }

    private fun mathStarts(l: String, ind: Int, close: String): Boolean {
        val after = l.substring(ind + 2)
        val idx = after.indexOf(close)
        return if (idx >= 0) isBlankFrom(after, idx + 2) else isBlankFrom(after, 0)
    }

    companion object {
        const val MAX_DEPTH = 16
    }
}

// ---------------------------------------------------------------------- helpers de lignes

internal fun indentOf(l: String): Int {
    var i = 0
    while (i < l.length && l[i] == ' ') i++
    return i
}

internal fun isBlankFrom(l: String, from: Int): Boolean {
    for (k in from until l.length) {
        val c = l[k]
        if (c != ' ' && c != '\t') return false
    }
    return true
}

internal fun isThematic(l: String, ind: Int): Boolean {
    if (ind >= l.length) return false
    val c = l[ind]
    if (c != '-' && c != '*' && c != '_') return false
    var count = 0
    for (k in ind until l.length) {
        val ch = l[k]
        if (ch == c) count++ else if (ch != ' ' && ch != '\t') return false
    }
    return count >= 3
}

/** Découpe une ligne de tableau en cellules (pipes non échappés), `\|` devient `|`. */
internal fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
    val cells = ArrayList<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') {
            sb.append('|')
            i += 2
        } else if (c == '|') {
            cells.add(sb.toString().trim())
            sb.setLength(0)
            i++
        } else {
            sb.append(c)
            i++
        }
    }
    cells.add(sb.toString().trim())
    return cells
}

/** Résultat du découpage en lignes : [offsets] = index (dans le texte) du début de chaque ligne. */
internal class SplitLines(val lines: ArrayList<String>, val offsets: IntList, val endsWithNewline: Boolean)

internal fun splitLines(text: String, from: Int = 0): SplitLines {
    val lines = ArrayList<String>()
    val offsets = IntList()
    val n = text.length
    var pos = from
    while (pos < n) {
        var e = text.indexOf('\n', pos)
        if (e < 0) e = n
        var end = e
        if (end > pos && text[end - 1] == '\r') end--
        offsets.add(pos)
        lines.add(expandLeadingTabs(text.substring(pos, end)))
        pos = e + 1
    }
    return SplitLines(lines, offsets, n == from || text[n - 1] == '\n')
}

internal fun expandLeadingTabs(l: String): String {
    var i = 0
    var col = 0
    var tabs = false
    while (i < l.length) {
        val c = l[i]
        if (c == ' ') col++
        else if (c == '\t') { col = (col / 4 + 1) * 4; tabs = true }
        else break
        i++
    }
    if (!tabs) return l
    val sb = StringBuilder(col + l.length - i)
    for (k in 0 until col) sb.append(' ')
    sb.append(l, i, l.length)
    return sb.toString()
}
