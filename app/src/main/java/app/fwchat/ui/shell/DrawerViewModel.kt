package app.fwchat.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fwchat.domain.ChatEngine
import app.fwchat.domain.ChatRepository
import app.fwchat.domain.ChatSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class DateBucket { TODAY, YESTERDAY, LAST_7_DAYS, LAST_30_DAYS, OLDER }

/** Ligne du tiroir: en-tête de groupe ou chat. Les clés sont stables pour la LazyColumn. */
sealed interface DrawerRow {
    val key: String

    data class Header(val bucket: DateBucket) : DrawerRow {
        override val key: String get() = "header_${bucket.name}"
    }

    data class Item(val chat: ChatSummary) : DrawerRow {
        override val key: String get() = "chat_${chat.id}"
    }
}

/** Jours calendaires écoulés: 0 = aujourd'hui, 1 = hier, 2..7 = 7 derniers jours, 8..30 = 30 derniers jours. */
fun dateBucketOf(updatedAt: Long, nowMillis: Long, zone: ZoneId): DateBucket {
    val day = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0 -> DateBucket.TODAY
        days == 1L -> DateBucket.YESTERDAY
        days <= 7 -> DateBucket.LAST_7_DAYS
        days <= 30 -> DateBucket.LAST_30_DAYS
        else -> DateBucket.OLDER
    }
}

/** Groupe une liste déjà triée (updatedAt décroissant) en lignes: en-tête puis chats, en conservant l'ordre. */
fun groupByDate(chats: List<ChatSummary>, nowMillis: Long, zone: ZoneId): List<DrawerRow> {
    val rows = ArrayList<DrawerRow>(chats.size + DateBucket.entries.size)
    var current: DateBucket? = null
    for (chat in chats) {
        val bucket = dateBucketOf(chat.updatedAt, nowMillis, zone)
        if (bucket != current) {
            rows += DrawerRow.Header(bucket)
            current = bucket
        }
        rows += DrawerRow.Item(chat)
    }
    return rows
}

/** [loaded] false tant que la base n'a pas répondu (évite de clignoter l'état vide). */
data class DrawerUiState(val rows: List<DrawerRow> = emptyList(), val loaded: Boolean = false)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DrawerViewModel(
    private val chats: ChatRepository,
    private val engine: ChatEngine,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    debounceMillis: Long = 150,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    /** Texte du champ de recherche (flux brut, sans latence: lié directement au TextField). */
    val query: StateFlow<String> = _query.asStateFlow()

    val state: StateFlow<DrawerUiState> = _query
        .debounce { if (it.isEmpty()) 0L else debounceMillis }
        .flatMapLatest { q -> chats.observeChatSummaries(q.trim()) }
        .map { list -> DrawerUiState(groupByDate(list, clock(), zone()), loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DrawerUiState())

    /** Chats en cours de génération (indicateur dans le tiroir). */
    val generating: StateFlow<Set<String>> get() = engine.generatingChats

    fun setQuery(q: String) {
        _query.value = q
    }

    fun rename(chatId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { chats.renameChat(chatId, clean) }
    }

    fun delete(chatId: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            engine.stop(chatId)
            chats.deleteChat(chatId)
            onDeleted()
        }
    }

    /** Dupliquer = fork jusqu'au dernier message du chemin actif. Sans message: rien. */
    fun duplicate(chatId: String, onForked: (String) -> Unit = {}) {
        viewModelScope.launch {
            val last = chats.getActivePath(chatId).lastOrNull() ?: return@launch
            onForked(chats.fork(chatId, last.id))
        }
    }

    companion object {
        val Factory = containerFactory { c -> DrawerViewModel(c.chats, c.engine) }
    }
}
