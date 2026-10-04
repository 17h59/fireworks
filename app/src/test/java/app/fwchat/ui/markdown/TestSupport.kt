package app.fwchat.ui.markdown

/** Représentation compacte (type S-expression) d'un document, pour des assertions lisibles. */
fun dump(blocks: List<MdBlock>): String = blocks.joinToString(" ") { dump(it) }

fun dump(b: MdBlock): String = when (b) {
    is MdParagraph -> "P(${dumpI(b.inlines)})"
    is MdHeading -> "H${b.level}(${dumpI(b.inlines)})"
    is MdCodeBlock -> "CODE[${b.language ?: ""}${if (b.closed) "" else "!"}${if (b.fenced) "" else "~"}](${b.code})"
    is MdMathBlock -> "MATH${if (b.closed) "" else "!"}(${b.text})"
    is MdQuote -> "Q(${dump(b.blocks)})"
    is MdList -> (if (b.ordered) "OL${b.start}" else "UL") + (if (b.tight) "" else "~") + "(" +
        b.items.joinToString(",") { it.checked.let { c -> if (c == null) "" else if (c) "[x]" else "[ ]" } + dump(it.blocks) } + ")"
    is MdTable -> "TABLE[${b.aligns.joinToString("") { it.name.first().toString() }}](" +
        dumpRow(b.header) + ";" + b.rows.joinToString(";") { dumpRow(it) } + ")"
    MdRule -> "HR"
}

private fun dumpRow(r: List<List<MdInline>>) = r.joinToString("|") { dumpI(it) }

fun dumpI(l: List<MdInline>): String = l.joinToString("") { dumpI(it) }

fun dumpI(i: MdInline): String = when (i) {
    is MdText -> i.text
    is MdStrong -> "<b>${dumpI(i.children)}</b>"
    is MdEmphasis -> "<i>${dumpI(i.children)}</i>"
    is MdStrike -> "<s>${dumpI(i.children)}</s>"
    is MdCode -> "<c>${i.code}</c>"
    is MdLink -> "<a ${i.url}${if (i.title != null) " \"${i.title}\"" else ""}>${dumpI(i.children)}</a>"
    is MdImage -> "<img ${i.url} ${i.alt}>"
    is MdMath -> "<m>${i.raw}</m>"
    MdLineBreak -> "<br>"
    MdSoftBreak -> "\\n"
}

/** Parse un texte et renvoie le dump (rendu définitif). */
fun md(text: String) = dump(parseMarkdown(text))

fun inl(text: String): String {
    val b = parseMarkdown(text).single() as MdParagraph
    return dumpI(b.inlines)
}
