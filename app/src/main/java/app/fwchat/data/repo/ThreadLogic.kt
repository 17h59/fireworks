package app.fwchat.data.repo

import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.ThreadItem
import java.text.Normalizer
import java.util.Locale

/**
 * Logique pure (JVM, sans Android ni Room) de l'arbre de messages.
 * Sémantique: voir docs/ARCHITECTURE.md ("Sémantique des conversations").
 */
object ThreadLogic {
    const val TITLE_MAX_CHARS = 40
    const val UNTITLED = "Nouveau chat"

    private val byCreated: Comparator<Message> = compareBy<Message>({ it.createdAt }, { it.id })
    private val combiningMarks = Regex("\\p{M}+")
    private val whitespace = Regex("\\s+")

    /** parentId -> enfants triés par createdAt (puis id). Les racines sont sous la clé null. */
    fun childrenByParent(messages: List<Message>): Map<String?, List<Message>> =
        messages.groupBy { it.parentId }.mapValues { (_, v) -> v.sortedWith(byCreated) }

    /**
     * Chemin actif racine -> feuille. Racine = [selectedRootId] si présente parmi les racines, sinon la plus récente.
     * Ensuite: selectedChildId du nœud s'il existe parmi ses enfants, sinon l'enfant le plus récent.
     */
    fun activePath(messages: List<Message>, selectedRootId: String?): List<Message> =
        activePath(childrenByParent(messages), selectedRootId)

    fun activePath(index: Map<String?, List<Message>>, selectedRootId: String?): List<Message> {
        val roots = index[null].orEmpty()
        if (roots.isEmpty()) return emptyList()
        var current = roots.firstOrNull { it.id == selectedRootId } ?: roots.last()
        val out = ArrayList<Message>()
        val seen = HashSet<String>()
        while (seen.add(current.id)) {
            out += current
            val kids = index[current.id]
            if (kids.isNullOrEmpty()) break
            current = kids.firstOrNull { it.id == current.selectedChildId } ?: kids.last()
        }
        return out
    }

    /** Chemin actif avec, pour chaque nœud, les ids de ses frères (lui compris) triés par createdAt. */
    fun activeThread(messages: List<Message>, selectedRootId: String?): List<ThreadItem> {
        val index = childrenByParent(messages)
        return activePath(index, selectedRootId).map { m ->
            ThreadItem(m, index[m.parentId].orEmpty().map { it.id })
        }
    }

    /** Ids du message et de tous ses descendants. */
    fun subtreeIds(messages: List<Message>, rootId: String): Set<String> {
        val index = childrenByParent(messages)
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(rootId)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!out.add(id)) continue
            index[id]?.forEach { stack.addLast(it.id) }
        }
        return out
    }

    /**
     * Après suppression de [deleted]: frère sur lequel retomber (le précédent, sinon le suivant), ou null s'il n'en reste pas.
     * [siblingsBefore] = frères triés (y compris [deleted]).
     */
    fun fallbackSibling(siblingsBefore: List<Message>, deleted: String): Message? {
        val idx = siblingsBefore.indexOfFirst { it.id == deleted }
        val remaining = siblingsBefore.filter { it.id != deleted }
        if (remaining.isEmpty()) return null
        return if (idx > 0) siblingsBefore[idx - 1] else remaining.first()
    }

    /** Chaîne d'ancêtres racine -> [uptoId] inclus (vide si introuvable). Sur le chemin actif, c'est le chemin actif tronqué. */
    fun ancestorChain(messages: List<Message>, uptoId: String): List<Message> {
        val byId = messages.associateBy { it.id }
        val out = ArrayList<Message>()
        val seen = HashSet<String>()
        var cur: Message? = byId[uptoId]
        while (cur != null && seen.add(cur.id)) {
            out += cur
            cur = cur.parentId?.let { byId[it] }
        }
        out.reverse()
        return out
    }

    /** Clone une chaîne dans [newChatId] avec de nouveaux ids; chaque nœud sélectionne le suivant. STREAMING devient INTERRUPTED. */
    fun cloneChain(chain: List<Message>, newChatId: String, idGenerator: () -> String): List<Message> {
        val ids = chain.map { idGenerator() }
        return chain.mapIndexed { i, m ->
            m.copy(
                id = ids[i],
                chatId = newChatId,
                parentId = if (i == 0) null else ids[i - 1],
                selectedChildId = ids.getOrNull(i + 1),
                status = if (m.status == MessageStatus.STREAMING) MessageStatus.INTERRUPTED else m.status,
            )
        }
    }

    /** Titre auto: 1re ligne non vide, espaces réduits, ~40 caractères (suffixe "…" si coupé). Vide si le texte est vide. */
    fun titleFrom(content: String): String {
        val line = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        val s = line.replace(whitespace, " ")
        if (s.codePointCount(0, s.length) <= TITLE_MAX_CHARS) return s
        val end = s.offsetByCodePoints(0, TITLE_MAX_CHARS)
        return s.substring(0, end).trimEnd() + "…"
    }

    fun forkTitle(title: String): String = "Fork de " + title.ifBlank { UNTITLED }

    /** Minuscule, sans accents (NFD sans marques combinantes), espaces de bord retirés. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase(Locale.ROOT).trim()

    /** Échappe pour LIKE avec '!' comme caractère d'échappement. */
    fun escapeLike(s: String): String =
        s.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    /** Requête de recherche prête pour [app.fwchat.data.db.ChatDao.observeSummaries]. */
    fun searchPattern(query: String): String = escapeLike(normalize(query))
}
