package app.fwchat.ui.chat

import androidx.compose.runtime.saveable.Saver
import app.fwchat.domain.EngineEvent
import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.Role
import app.fwchat.domain.ThreadItem

// ------------------------------------------------------------------------------------------------
// Fil de messages: éviter de tout reconstruire à chaque checkpoint de la base.

object ThreadDiff {

    /**
     * Deux items sont équivalents pour l'affichage si tout est égal, sauf — pour un message en STREAMING —
     * content / reasoning / updatedAt: le texte live vient de `engine.streaming`, la base ne l'écrit qu'en
     * checkpoints (1/s) et ne doit pas relancer la construction de la liste.
     */
    fun sameForUi(old: ThreadItem, new: ThreadItem): Boolean {
        if (old === new) return true
        if (old.siblingIds != new.siblingIds) return false
        val a = old.message
        val b = new.message
        if (a.status == MessageStatus.STREAMING && b.status == MessageStatus.STREAMING && a.id == b.id) {
            return a.copy(content = "", reasoning = null, updatedAt = 0) ==
                b.copy(content = "", reasoning = null, updatedAt = 0)
        }
        return a == b
    }

    /**
     * Réutilise les instances inchangées de [previous] (par id de message). Renvoie [previous] lui-même (même
     * instance) si rien n'a changé pour l'affichage: un `distinctUntilChanged` en aval supprime alors l'émission.
     */
    fun stabilize(previous: List<ThreadItem>, next: List<ThreadItem>): List<ThreadItem> {
        if (previous.isEmpty()) return next
        val byId = HashMap<String, ThreadItem>(previous.size * 2)
        for (p in previous) byId[p.message.id] = p
        var identical = previous.size == next.size
        val out = ArrayList<ThreadItem>(next.size)
        for (i in next.indices) {
            val n = next[i]
            val p = byId[n.message.id]
            if (p != null && sameForUi(p, n)) {
                out += p
                if (previous.getOrNull(i) !== p) identical = false
            } else {
                out += n
                identical = false
            }
        }
        return if (identical) previous else out
    }
}

// ------------------------------------------------------------------------------------------------
// Sauvegarde d'état (Bundle limité à ~1 Mo pour TOUTE l'activité): ne jamais y mettre un gros texte.

/** Au-delà, un texte n'est pas sauvegardé dans le Bundle (TransactionTooLargeException au onSaveInstanceState). */
const val SAVED_TEXT_MAX_CHARS = 50_000

/** Saver de texte qui ne sauvegarde rien au-delà de [SAVED_TEXT_MAX_CHARS] (restauré alors par la valeur initiale). */
val BoundedTextSaver: Saver<String, String> = Saver(
    save = { if (it.length <= SAVED_TEXT_MAX_CHARS) it else null },
    restore = { it },
)

// ------------------------------------------------------------------------------------------------
// Messages utilisateur très longs: aperçu replié + rendu par tranches.

object LongText {
    /** Au-delà, le message est replié par défaut (aperçu). */
    const val THRESHOLD_CHARS = 6_000
    const val PREVIEW_CHARS = 1_500
    const val PREVIEW_LINES = 12
    const val CHUNK_CHARS = 3_000

    fun isLong(text: String): Boolean = text.length > THRESHOLD_CHARS

    /**
     * Début du texte pour l'aperçu replié: au plus [PREVIEW_CHARS] caractères et [PREVIEW_LINES] lignes, coupé de
     * préférence sur une fin de ligne ou un espace. Le texte entier est renvoyé s'il tient dans ces limites.
     */
    fun preview(text: String): String {
        var end = minOf(text.length, PREVIEW_CHARS)
        var lines = 1
        for (i in 0 until end) {
            if (text[i] == '\n' && ++lines > PREVIEW_LINES) {
                end = i
                break
            }
        }
        if (end >= text.length) return text
        if (end == PREVIEW_CHARS) {
            // coupure en plein mot: reculer jusqu'au dernier espace/saut de ligne proche
            val floor = (end - 200).coerceAtLeast(0)
            var k = end
            while (k > floor && !text[k - 1].isWhitespace()) k--
            if (k > floor) end = k
        }
        return text.substring(0, end).trimEnd()
    }

    /**
     * Découpe [text] en tranches d'au plus [maxChars] caractères, sur les fins de paragraphe (une ligne vide), sinon
     * de ligne, sinon d'espace, sinon à la dure. Invariant: la concaténation des tranches redonne exactement [text].
     */
    fun chunks(text: String, maxChars: Int = CHUNK_CHARS): List<String> {
        require(maxChars >= 2)
        if (text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>(text.length / maxChars + 1)
        var start = 0
        while (start < text.length) {
            val limit = start + maxChars
            if (limit >= text.length) {
                out += text.substring(start)
                break
            }
            val floor = start + maxChars / 2
            var cut = text.lastIndexOf("\n\n", limit - 2).let { if (it >= floor) it + 2 else -1 }
            if (cut < 0) cut = text.lastIndexOf('\n', limit - 1).let { if (it >= floor) it + 1 else -1 }
            if (cut < 0) cut = text.lastIndexOf(' ', limit - 1).let { if (it >= floor) it + 1 else -1 }
            if (cut < 0) cut = limit
            out += text.substring(start, cut)
            start = cut
        }
        return out
    }
}

// ------------------------------------------------------------------------------------------------
// Événements du moteur -> snackbar

/**
 * Notice à afficher pour cet événement sur l'écran du chat [visibleChatId] (null = brouillon), ou null s'il
 * concerne un autre chat. `Unauthorized` et les erreurs sans chat sont globales.
 */
fun EngineEvent.noticeFor(visibleChatId: String?): EngineNotice? = when (this) {
    EngineEvent.Unauthorized -> EngineNotice.Unauthorized
    is EngineEvent.Error -> if (chatId == null || chatId == visibleChatId) EngineNotice.Message(message) else null
}

// ------------------------------------------------------------------------------------------------
// Envoi d'un brouillon

/**
 * Le champ de saisie peut-il être vidé dès que [ChatViewModel.send] accepte le texte ? Oui pour un chat existant;
 * non pour un brouillon (le chat n'est pas encore créé: si `createChat` échoue, le texte ne doit pas être perdu;
 * l'écran vide son champ à l'événement `OpenChat(fromDraft = true)`).
 */
fun clearsDraftImmediately(isDraftChat: Boolean, accepted: Boolean): Boolean = accepted && !isDraftChat

/** Demander POST_NOTIFICATIONS: seulement si elle n'est pas accordée ET qu'on ne l'a jamais demandée (pas de boucle). */
fun shouldAskNotificationPermission(granted: Boolean, alreadyAsked: Boolean): Boolean = !granted && !alreadyAsked

// ------------------------------------------------------------------------------------------------
// « Réessayer »: ne pas empiler les blocs d'erreur.

object RetryCleanup {
    /** Un message assistant en erreur sans contenu utile peut être supprimé une fois remplacé. */
    fun isDroppable(m: Message): Boolean =
        m.role == Role.ASSISTANT && m.status == MessageStatus.ERROR && m.content.isBlank()

    /**
     * Le message qui a remplacé [failedId] (son nouveau frère sélectionné) une fois sa génération terminée, sinon
     * null (pas encore créé, ou encore en STREAMING).
     */
    fun replacement(thread: List<ThreadItem>, failedId: String): Message? =
        thread.firstOrNull { failedId in it.siblingIds && it.message.id != failedId }
            ?.message
            ?.takeIf { it.status != MessageStatus.STREAMING }

    /** On supprime l'ancienne erreur si la nouvelle génération a abouti ou a échoué à son tour; pas si interrompue. */
    fun shouldDropFailed(replacement: Message): Boolean =
        replacement.status == MessageStatus.COMPLETE || replacement.status == MessageStatus.ERROR
}
