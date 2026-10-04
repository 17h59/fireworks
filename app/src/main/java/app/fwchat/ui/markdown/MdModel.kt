package app.fwchat.ui.markdown

/*
 * Modèle de document markdown. Pur Kotlin (aucune dépendance Android), immuable.
 * Les classes sont des `data class` : l'égalité est structurelle (utile pour les tests et pour
 * réutiliser les blocs inchangés), mais Compose compare surtout par identité d'objet : les blocs
 * "fermés" renvoyés par IncrementalMarkdown sont les MÊMES instances d'un appel à l'autre.
 */

// ---------------------------------------------------------------- Inlines

sealed class MdInline

/** Texte brut (entités et échappements déjà résolus). */
data class MdText(val text: String) : MdInline()

data class MdStrong(val children: List<MdInline>) : MdInline()

data class MdEmphasis(val children: List<MdInline>) : MdInline()

data class MdStrike(val children: List<MdInline>) : MdInline()

/** Code en ligne (contenu brut, sans les backticks). */
data class MdCode(val code: String) : MdInline()

/** Lien. [url] vide = lien incomplet (en cours de streaming) : à afficher stylé mais non cliquable. */
data class MdLink(val url: String, val title: String?, val children: List<MdInline>) : MdInline()

/** Image : jamais chargée en v1, affichée comme lien/alt. */
data class MdImage(val url: String, val alt: String, val title: String?) : MdInline()

/** Formule LaTeX en ligne, [raw] contient les délimiteurs ($..$, $$..$$, \(..\), \[..\]) : affichée telle quelle. */
data class MdMath(val raw: String) : MdInline()

/** Saut de ligne forcé (2 espaces + fin de ligne, `\` + fin de ligne, `<br>`). */
data object MdLineBreak : MdInline()

/** Retour à la ligne simple au sein d'un paragraphe. */
data object MdSoftBreak : MdInline()

// ---------------------------------------------------------------- Blocs

sealed class MdBlock

data class MdParagraph(val inlines: List<MdInline>) : MdBlock() {
    /** Découpage en morceaux pour le rendu paresseux (calculé une fois, hors égalité). */
    @Transient private var chunksCache: List<List<MdInline>>? = null
    internal val chunks: List<List<MdInline>>
        get() = chunksCache ?: splitInlinesForLazy(inlines, PARAGRAPH_CHUNK_CHARS).also { chunksCache = it }
}

data class MdHeading(val level: Int, val inlines: List<MdInline>) : MdBlock()

/**
 * Bloc de code. [closed] = false tant que la clôture n'est pas arrivée (streaming).
 * [language] est null s'il n'y a pas d'info string (ou pour un bloc indenté).
 */
data class MdCodeBlock(
    val language: String?,
    val code: String,
    val closed: Boolean = true,
    val fenced: Boolean = true,
) : MdBlock() {
    /** Offsets de début de chaque ligne de [code] (calculé une fois, hors égalité). */
    @Transient private var lineStartsCache: IntArray? = null
    internal val lineStarts: IntArray
        get() = lineStartsCache ?: computeLineStarts(code).also { lineStartsCache = it }
}

/** Bloc de formule `$$ .. $$` / `\[ .. \]` (affiché tel quel, en monospace). */
data class MdMathBlock(val text: String, val closed: Boolean = true) : MdBlock()

data class MdQuote(val blocks: List<MdBlock>) : MdBlock()

/** [tight] : liste serrée (pas de ligne vide entre items). [start] : premier numéro si [ordered]. */
data class MdList(
    val ordered: Boolean,
    val start: Int,
    val tight: Boolean,
    val items: List<MdListItem>,
) : MdBlock()

/** [checked] : null = pas de case à cocher, sinon état de la case GFM `[ ]` / `[x]`. */
data class MdListItem(val blocks: List<MdBlock>, val checked: Boolean? = null)

enum class MdAlign { None, Left, Center, Right }

/** Tableau GFM. Toutes les lignes ont exactement `aligns.size` cellules. */
data class MdTable(
    val aligns: List<MdAlign>,
    val header: List<List<MdInline>>,
    val rows: List<List<List<MdInline>>>,
) : MdBlock() {
    /** Largeur estimée (en caractères, plafonnée) de chaque colonne : sert à aligner les morceaux d'un gros tableau. */
    @Transient private var colCharsCache: IntArray? = null
    internal val colChars: IntArray
        get() = colCharsCache ?: IntArray(aligns.size) { c ->
            var m = inlineLength(header[c])
            for (r in rows) {
                val l = inlineLength(r[c])
                if (l > m) m = l
            }
            m.coerceAtMost(MAX_COL_CHARS)
        }.also { colCharsCache = it }
}

/** Séparateur horizontal (`---`). */
data object MdRule : MdBlock()

// ---------------------------------------------------------------- Utilitaires

internal const val PARAGRAPH_CHUNK_CHARS = 6000
internal const val CODE_CHUNK_LINES = 80
internal const val TABLE_CHUNK_ROWS = 40
internal const val MAX_COL_CHARS = 32

internal fun computeLineStarts(s: String): IntArray {
    var count = 1
    for (ch in s) if (ch == '\n') count++
    val out = IntArray(count)
    var k = 1
    for (i in s.indices) if (s[i] == '\n') out[k++] = i + 1
    return out
}

/** Longueur approximative (en caractères) du texte d'une liste d'inlines. */
internal fun inlineLength(list: List<MdInline>): Int {
    var n = 0
    for (x in list) n += inlineLength(x)
    return n
}

internal fun inlineLength(x: MdInline): Int = when (x) {
    is MdText -> x.text.length
    is MdStrong -> inlineLength(x.children)
    is MdEmphasis -> inlineLength(x.children)
    is MdStrike -> inlineLength(x.children)
    is MdCode -> x.code.length
    is MdLink -> inlineLength(x.children)
    is MdImage -> x.alt.length + 8
    is MdMath -> x.raw.length
    MdLineBreak, MdSoftBreak -> 1
}

/** Texte brut aplati (sans mise en forme) : utile pour les alt d'images et les tests. */
fun plainText(list: List<MdInline>): String {
    val sb = StringBuilder()
    appendPlain(list, sb)
    return sb.toString()
}

private fun appendPlain(list: List<MdInline>, sb: StringBuilder) {
    for (x in list) when (x) {
        is MdText -> sb.append(x.text)
        is MdStrong -> appendPlain(x.children, sb)
        is MdEmphasis -> appendPlain(x.children, sb)
        is MdStrike -> appendPlain(x.children, sb)
        is MdCode -> sb.append(x.code)
        is MdLink -> appendPlain(x.children, sb)
        is MdImage -> sb.append(x.alt)
        is MdMath -> sb.append(x.raw)
        MdLineBreak, MdSoftBreak -> sb.append('\n')
    }
}

/**
 * Découpe un très long paragraphe en morceaux (aux sauts de ligne de premier niveau, ou aux espaces
 * pour un texte géant sans saut) afin de ne composer que les morceaux visibles.
 */
internal fun splitInlinesForLazy(inlines: List<MdInline>, maxChars: Int): List<List<MdInline>> {
    if (inlineLength(inlines) <= maxChars) return listOf(inlines)
    val out = ArrayList<List<MdInline>>()
    var cur = ArrayList<MdInline>()
    var curLen = 0
    fun flush() {
        if (cur.isNotEmpty()) out.add(cur)
        cur = ArrayList()
        curLen = 0
    }
    for (x in inlines) {
        if (x is MdText && x.text.length > maxChars) {
            var s = x.text
            while (s.length > maxChars) {
                var cut = s.lastIndexOf(' ', maxChars)
                if (cut < maxChars / 2) cut = maxChars
                if (curLen > 0) flush()
                out.add(listOf(MdText(s.substring(0, cut))))
                s = s.substring(cut)
            }
            if (s.isNotEmpty()) {
                cur.add(MdText(s)); curLen += s.length
            }
            continue
        }
        if ((x === MdSoftBreak || x === MdLineBreak) && curLen >= maxChars) {
            flush()
            continue // le saut de ligne est absorbé : les morceaux sont des items séparés
        }
        cur.add(x)
        curLen += inlineLength(x)
    }
    flush()
    return if (out.isEmpty()) listOf(inlines) else out
}
