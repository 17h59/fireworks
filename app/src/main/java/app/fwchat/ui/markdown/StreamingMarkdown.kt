package app.fwchat.ui.markdown

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ----------------------------------------------------------------------------------------------
// Rendu paresseux d'un message EN COURS de génération, et parse hors thread principal des gros messages.
//
// Principe (voir docs/MARKDOWN_API.md) : la LazyColumn n'émet en items que les blocs FERMÉS ([StreamStable]) ; elle
// ne relit donc ce lot que lorsqu'un bloc se ferme (rare : un paragraphe, pas un token). Le dernier bloc, encore
// ouvert, est rendu dans UN item « vivant » via [MarkdownTail], qui lit le texte courant à chaque publication.
// Un gros bloc ouvert (code de milliers de lignes…) est découpé : ses tranches complètes passent côté « stable »
// (une tranche = 80 lignes de code, 6000 caractères de paragraphe…), seule la dernière tranche reste vivante.
// ----------------------------------------------------------------------------------------------

/**
 * Partie « stable » d'un message en streaming : ce que la LazyColumn émet avec
 * `markdownItems(key, stable.blocks, lastBlockSlices = stable.lastBlockSlices)`.
 * L'égalité ne dépend que de la structure fermée (nombre de blocs, identité du dernier, tranches gelées) :
 * un `derivedStateOf` dessus ne change donc pas à chaque token.
 */
class StreamStable internal constructor(
    /** Blocs fermés + (si [frozenSlices] > 0) le bloc ouvert, dont seules les [frozenSlices] premières tranches sont émises. */
    val blocks: List<MdBlock>,
    /** Tranches du dernier élément de [blocks] à émettre (Int.MAX_VALUE = toutes : le dernier bloc est alors fermé). */
    val lastBlockSlices: Int,
    /** Nombre de blocs fermés (les premiers de [blocks]). */
    val closedCount: Int,
    /** Tranches complètes du bloc ouvert (0 s'il est petit ou absent). */
    val frozenSlices: Int,
) {
    private val anchor: MdBlock? = if (closedCount > 0) blocks[closedCount - 1] else null

    override fun equals(other: Any?): Boolean =
        other is StreamStable && other.closedCount == closedCount && other.frozenSlices == frozenSlices &&
            other.anchor === anchor

    override fun hashCode(): Int =
        (closedCount * 31 + frozenSlices) * 31 + (if (anchor == null) 0 else System.identityHashCode(anchor))
}

/** Découpage d'un état du texte en streaming. */
class StreamParts internal constructor(
    /** Tous les blocs (le dernier est rendu de façon optimiste). */
    val blocks: List<MdBlock>,
    val stable: StreamStable,
) {
    /** Bloc ouvert à rendre dans l'item vivant (null si le texte est vide). */
    val liveBlock: MdBlock? get() = blocks.lastOrNull()

    /** Première tranche de [liveBlock] à rendre (les précédentes sont dans [stable]). */
    val liveFromSlice: Int get() = stable.frozenSlices
}

/**
 * Découpe [blocks] (résultat de [IncrementalMarkdown]) en partie stable / partie vivante.
 * Fonction pure, testable sur JVM.
 */
fun splitStreamingBlocks(blocks: List<MdBlock>): StreamParts {
    if (blocks.isEmpty()) return StreamParts(blocks, StreamStable(emptyList(), Int.MAX_VALUE, 0, 0))
    val closed = blocks.size - 1
    val tail = blocks[closed]
    val frozen = (lazySliceCount(tail) - 1).coerceAtLeast(0)
    val stable = if (frozen > 0) {
        StreamStable(blocks, frozen, closed, frozen)
    } else {
        StreamStable(if (closed == 0) emptyList() else blocks.subList(0, closed), Int.MAX_VALUE, closed, 0)
    }
    return StreamParts(blocks, stable)
}

/**
 * Parseur d'UN message en streaming. Mémoïse le dernier texte : appeler [parts] avec le même texte depuis la
 * LazyColumn (partie stable) et depuis l'item vivant ne parse qu'une fois. Thread principal uniquement.
 *
 * @param parser parseur incrémental, idéalement `cache.incremental(messageId, streaming = true)` pour que
 *   [MarkdownBlocksCache.cachedBlocks] le reprenne à la fin du flux.
 */
@Stable
class StreamingMarkdown(private val parser: IncrementalMarkdown = IncrementalMarkdown(true)) {
    private var lastText: String? = null
    private var lastParts: StreamParts = splitStreamingBlocks(emptyList())

    fun parts(text: String): StreamParts {
        val prev = lastText
        if (prev != null && (prev === text || prev == text)) return lastParts
        val parts = splitStreamingBlocks(parser.update(text, streaming = true))
        lastText = text
        lastParts = parts
        return parts
    }

    /**
     * État dérivé de [text] (lu dans le lambda de la LazyColumn) : sa valeur ne change que quand un bloc se ferme
     * ou qu'une tranche d'un gros bloc se complète, jamais à chaque token.
     */
    fun stableState(text: () -> String): State<StreamStable> =
        derivedStateOf(structuralEqualityPolicy()) { parts(text()).stable }
}

/**
 * Parse de gros textes statiques HORS du thread principal, avec le cache LRU : [blocks] renvoie les blocs s'ils sont
 * prêts, sinon null (afficher un texte brut) et lance le parse sur `Dispatchers.Default`. L'état « version » lu dans
 * [blocks] relance le lambda de la LazyColumn quand un parse se termine.
 *
 * [blocks] et [prefetch] s'appellent depuis le thread principal (lambda de la LazyColumn / composition).
 */
@Stable
class MarkdownBlocksLoader(
    private val cache: MarkdownBlocksCache,
    private val scope: CoroutineScope,
) {
    private var version by mutableIntStateOf(0)
    private val inFlight = HashMap<Any, String>()

    /** Blocs définitifs de [text] ou null s'ils ne sont pas encore prêts (le parse est alors demandé). */
    fun blocks(key: Any, text: String): List<MdBlock>? {
        if (version < 0) return null // lecture d'état: relance le lambda de la LazyColumn à la fin d'un parse
        cache.cachedBlocks(key, text)?.let { return it }
        prefetch(key, text)
        return null
    }

    /** Demande le parse (sans lire d'état) ; sans effet s'il est déjà en cours pour ce texte. */
    fun prefetch(key: Any, text: String) {
        val running = inFlight[key]
        if (running != null && (running === text || running == text)) return
        inFlight[key] = text
        scope.launch {
            val parser = IncrementalMarkdown(false)
            withContext(Dispatchers.Default) { parser.update(text, false) }
            // Un texte plus récent a été demandé entre-temps: ce résultat est périmé.
            if (inFlight[key] === text) {
                inFlight.remove(key)
                cache.put(key, parser)
                version++
            }
        }
    }
}
