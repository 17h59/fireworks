package app.fwchat.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Cache (LRU, par clé de message) de parseurs incrémentaux, utilisable HORS composition, typiquement
 * dans le lambda d'une `LazyColumn` où l'on ne peut pas appeler `remember` :
 *
 * ```
 * val cache = rememberMarkdownBlocksCache()
 * LazyColumn { markdownItems("msg:${m.id}", cache.blocks(m.id, m.text), style) }
 * ```
 *
 * Un appel avec un texte identique à l'appel précédent est quasi gratuit (même liste renvoyée).
 * Un texte qui a seulement grandi ne re-parse que le dernier bloc. Un texte différent => re-parse complet.
 * Thread principal uniquement.
 */
class MarkdownBlocksCache(private val maxEntries: Int = 512) {

    private val map = object : LinkedHashMap<Any, IncrementalMarkdown>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, IncrementalMarkdown>?): Boolean =
            size > maxEntries
    }

    /**
     * @param key identifiant stable du message (le même avant et après la fin du streaming).
     * @param streaming true tant que le texte est en cours de génération ; rappeler avec false à la fin.
     */
    fun blocks(key: Any, text: String, streaming: Boolean = false): List<MdBlock> {
        val inc = map.getOrPut(key) { IncrementalMarkdown(streaming) }
        return inc.update(text, streaming)
    }

    /**
     * Le parseur incrémental de [key] (créé au besoin). Permet de PARTAGER le parseur d'un message entre son rendu
     * en direct ([StreamingMarkdown]) et son rendu final : à la fin du flux, [cachedBlocks] ne re-parse que la fin.
     */
    fun incremental(key: Any, streaming: Boolean = false): IncrementalMarkdown =
        map.getOrPut(key) { IncrementalMarkdown(streaming) }

    /** Range un parseur déjà alimenté (ex. parsé hors du thread principal, voir [MarkdownBlocksLoader]). */
    fun put(key: Any, parser: IncrementalMarkdown) {
        map[key] = parser
    }

    /**
     * Blocs de [key] SANS rien parser de coûteux : renvoie null si le cache n'a pas de parseur pour [key] ou si
     * [text] n'est pas un prolongement de son texte (il faudrait alors tout re-parser).
     * Sinon la mise à jour est incrémentale (quasi gratuite) et définitive (`streaming = false`).
     */
    fun cachedBlocks(key: Any, text: String): List<MdBlock>? {
        val inc = map[key] ?: return null
        if (!inc.canExtend(text)) return null
        return inc.update(text, false)
    }

    /** Oublie un message (ex. supprimé). */
    fun remove(key: Any) {
        map.remove(key)
    }

    fun clear() = map.clear()
}

@Composable
fun rememberMarkdownBlocksCache(maxEntries: Int = 512): MarkdownBlocksCache =
    remember(maxEntries) { MarkdownBlocksCache(maxEntries) }
