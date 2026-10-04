package app.fwchat.ui.markdown

/**
 * Parse un document markdown complet (CommonMark + GFM usuel) en blocs.
 *
 * @param openTail true si [text] est le début d'un flux encore en cours : le dernier bloc est alors
 *   rendu "optimistement" (gras / code non fermés affichés comme fermés, ligne partielle tolérée).
 *   Avec false (défaut) le résultat est le rendu définitif.
 *
 * Ne lève jamais d'exception ; coût linéaire en la taille du texte.
 */
fun parseMarkdown(text: String, openTail: Boolean = false): List<MdBlock> {
    if (text.isEmpty()) return emptyList()
    return try {
        val sl = splitLines(text)
        BlockParser(openTail).parseDocument(sl.lines, sl.endsWithNewline).blocks
    } catch (e: Exception) {
        listOf(MdParagraph(listOf(MdText(text))))
    } catch (e: StackOverflowError) {
        listOf(MdParagraph(listOf(MdText(text))))
    }
}

/**
 * Parseur incrémental pour le streaming. On lui donne à chaque fois le texte COMPLET (qui ne fait que
 * grandir par ajout en fin) ; seul le dernier bloc (et l'avant-dernier si la ligne en cours est partielle)
 * est re-parsé. Les blocs "fermés" gardent la même identité d'objet d'un appel à l'autre.
 * Si le texte n'est pas un simple ajout, re-parse complet automatiquement.
 *
 * Invariant (testé) : `update(t)` == `parseMarkdown(t, openTail = streaming)`, quel que soit le découpage du flux.
 *
 * Non thread-safe : à utiliser depuis un seul thread (typiquement la composition).
 */
class IncrementalMarkdown(private val defaultStreaming: Boolean = true) {

    private var text = ""
    private var lastStreaming = defaultStreaming
    private var initialized = false
    private var all = ArrayList<MdBlock>()
    private var starts = IntList()
    private var stableCount = 0
    private var snapshot: List<MdBlock> = emptyList()

    /** Dernier résultat de [update]. */
    val blocks: List<MdBlock> get() = snapshot

    /** Oublie tout l'état (le prochain [update] re-parse en entier). */
    fun reset() {
        text = ""
        initialized = false
        all = ArrayList()
        starts = IntList()
        stableCount = 0
        snapshot = emptyList()
    }

    /**
     * @param newText texte complet courant.
     * @param streaming true tant que le flux n'est pas terminé (rendu optimiste du dernier bloc) ;
     *   passer false au dernier appel pour obtenir le rendu définitif.
     * @return la liste des blocs ; les éléments fermés sont identiques (===) à ceux de l'appel précédent.
     */
    fun update(newText: String, streaming: Boolean = defaultStreaming): List<MdBlock> {
        if (initialized && streaming == lastStreaming && newText == text) return snapshot
        if (!initialized || !newText.startsWith(text)) {
            reset()
        }
        return try {
            doUpdate(newText, streaming)
        } catch (e: Exception) {
            fallback(newText)
        } catch (e: StackOverflowError) {
            fallback(newText)
        }
    }

    private fun fallback(newText: String): List<MdBlock> {
        reset()
        text = newText
        initialized = true
        snapshot = listOf(MdParagraph(listOf(MdText(newText))))
        all = ArrayList(snapshot)
        starts = IntList().also { it.add(0) }
        stableCount = 0
        return snapshot
    }

    private fun doUpdate(newText: String, streaming: Boolean): List<MdBlock> {
        val regionStart = if (stableCount in 0 until all.size) starts[stableCount] else 0
        if (stableCount > 0 && stableCount >= all.size) stableCount = 0
        val sl = splitLines(newText, regionStart)
        val top = BlockParser(streaming).parseDocument(sl.lines, sl.endsWithNewline)

        val newAll = ArrayList<MdBlock>(stableCount + top.blocks.size)
        val newStarts = IntList()
        for (k in 0 until stableCount) {
            newAll.add(all[k])
            newStarts.add(starts[k])
        }
        for (idx in 0 until top.blocks.size) {
            val b = top.blocks[idx]
            val absStart = sl.offsets[top.startLines[idx]]
            val absIdx = stableCount + idx
            val old = if (absIdx < all.size) all[absIdx] else null
            newAll.add(if (old != null && starts[absIdx] == absStart && old == b) old else b)
            newStarts.add(absStart)
        }
        val k = top.blocks.size
        if (k > 0) {
            val lastStartLine = top.startLines[k - 1]
            // Ligne finale incomplète : elle peut encore changer le sens du bloc précédent (en-tête de tableau qui
            // coupe un paragraphe, ligne de délimiteurs qui grandit…) quand le dernier bloc ne fait que 1-2 lignes.
            val pendingFirst = !sl.endsWithNewline && lastStartLine >= sl.lines.size - 2
            val add = if (pendingFirst) maxOf(k - 2, 0) else k - 1
            stableCount += add
        }
        text = newText
        lastStreaming = streaming
        initialized = true
        all = newAll
        starts = newStarts
        snapshot = newAll
        // la liste exposée est une copie : on ne mute jamais un snapshot déjà rendu
        all = ArrayList(newAll)
        return snapshot
    }
}
