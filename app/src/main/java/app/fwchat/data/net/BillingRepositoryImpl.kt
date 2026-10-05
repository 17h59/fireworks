package app.fwchat.data.net

import app.fwchat.data.DataJson
import app.fwchat.domain.BillingError
import app.fwchat.domain.BillingRepository
import app.fwchat.domain.BillingState
import app.fwchat.domain.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Valeurs persistées (jamais l'e-mail du compte: seul l'id `accounts/xxx` est gardé).
 * [baselineSpend] = dépenses serveur au moment où le solde a été saisi (null = pas encore mesuré).
 */
@Serializable
data class BillingCache(
    val accountName: String? = null,
    val enteredBalance: Double? = null,
    /** Instant de la saisie du solde (ms epoch). */
    val balanceEnteredAt: Long? = null,
    val baselineSpend: Double? = null,
    val monthSpend: Double? = null,
    val monthlyCap: Double? = null,
    val sinceEntrySpend: Double? = null,
    val lastUpdated: Long? = null,
)

interface BillingStore {
    suspend fun read(): BillingCache
    suspend fun write(cache: BillingCache)
}

/** Cache JSON dans un fichier (typiquement filesDir/billing_cache.json). */
class FileBillingStore(private val file: File) : BillingStore {
    override suspend fun read(): BillingCache = withContext(Dispatchers.IO) {
        try {
            if (!file.isFile) BillingCache() else DataJson.decodeFromString(BillingCache.serializer(), file.readText())
        } catch (e: Exception) {
            BillingCache()
        }
    }

    override suspend fun write(cache: BillingCache) = withContext(Dispatchers.IO) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(DataJson.encodeToString(BillingCache.serializer(), cache))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        Unit
    }
}

/**
 * Dépenses du compte via l'API Fireworks (GET en lecture seule, clé Bearer envoyée uniquement à [baseUrl]).
 * Aucun endpoint ne donne le crédit restant: il est estimé à partir du solde saisi par l'utilisateur.
 */
class BillingRepositoryImpl(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val settings: SettingsRepository,
    private val store: BillingStore,
    private val clock: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BillingRepository {

    private val _state = MutableStateFlow(BillingState())
    override val state: StateFlow<BillingState> = _state.asStateFlow()

    private val lock = Mutex()
    private var cache = BillingCache()

    private val loaded: Deferred<Unit> = scope.async {
        lock.withLock {
            cache = try {
                store.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BillingCache()
            }
            _state.value = _state.value.copy(
                monthSpendUsd = cache.monthSpend,
                monthlyCapUsd = cache.monthlyCap,
                enteredBalance = cache.enteredBalance,
                estimatedCreditUsd = estimate(cache),
                lastUpdated = cache.lastUpdated,
            )
        }
    }

    override suspend fun setBalance(usd: Double?) {
        loaded.await()
        lock.withLock {
            cache = if (usd == null || !usd.isFinite()) {
                cache.copy(enteredBalance = null, balanceEnteredAt = null, baselineSpend = null, sinceEntrySpend = null)
            } else {
                cache.copy(
                    enteredBalance = usd,
                    balanceEnteredAt = clock(),
                    // Mesuré par le refresh qui suit, avec la même source que les dépenses actuelles.
                    baselineSpend = null,
                    sinceEntrySpend = null,
                )
            }
            persist()
            publish(error = null)
        }
        refresh()
    }

    override suspend fun refresh() {
        loaded.await()
        lock.withLock {
            _state.value = _state.value.copy(loading = true)
            try {
                val key = settings.apiKey()
                if (key.isNullOrBlank()) {
                    publish(error = BillingError.UNAUTHORIZED)
                    return
                }
                fetchAll(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BillingHttpException) {
                publish(error = if (e.code == 401 || e.code == 403) BillingError.UNAUTHORIZED else BillingError.OTHER)
            } catch (e: IOException) {
                publish(error = BillingError.NETWORK)
            } catch (e: Exception) {
                publish(error = BillingError.OTHER)
            } finally {
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    private suspend fun fetchAll(key: String) {
        val account = cache.accountName ?: fetchAccountName(key).also {
            cache = cache.copy(accountName = it)
        }
        val quota = getJson(key, "/v1/$account/quotas/monthly-spend-usd")
        val monthSpend = quota.number("usage") ?: throw IllegalStateException("usage manquant")
        val cap = quota.number("value")?.takeIf { it < CAP_IGNORED_FROM }

        var next = cache.copy(monthSpend = monthSpend, monthlyCap = cap)
        val enteredAt = next.enteredBalance?.let { next.balanceEnteredAt }
        if (enteredAt != null) {
            val now = clock()
            val sameMonth = monthOf(enteredAt) == monthOf(now)
            if (next.baselineSpend == null) {
                // Première mesure après la saisie: les dépenses serveur actuelles servent de référence.
                next = next.copy(baselineSpend = monthSpend)
            }
            val since = if (sameMonth) {
                (monthSpend - (next.baselineSpend ?: monthSpend)).coerceAtLeast(0.0)
            } else {
                // Saisie un autre mois: somme des dépenses entre la saisie et demain (inclus aujourd'hui).
                billingSum(key, account, dayOf(enteredAt), dayOf(now).plusDays(1))
            }
            next = next.copy(sinceEntrySpend = since)
        }
        cache = next.copy(lastUpdated = clock())
        persist()
        publish(error = null)
    }

    private suspend fun fetchAccountName(key: String): String {
        val root = getJson(key, "/v1/accounts")
        // Ne lit que `name`: la réponse contient aussi un e-mail qui ne doit jamais être gardé.
        val name = ((root["accounts"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("name")
        if (name == null || !ACCOUNT_NAME.matches(name)) throw IllegalStateException("compte introuvable")
        return name
    }

    private suspend fun billingSum(key: String, account: String, from: LocalDate, endExclusive: LocalDate): Double {
        val root = getJson(
            key, "/v1/$account/billing/summary",
            "startTime" to "${from}T00:00:00Z", "endTime" to "${endExclusive}T00:00:00Z", "granularity" to "DAILY",
        )
        val items = root["lineItems"] as? JsonArray ?: return 0.0
        return items.sumOf { (it as? JsonObject)?.obj("totalCost")?.let(::money) ?: 0.0 }
    }

    private suspend fun getJson(key: String, path: String, vararg query: Pair<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/').plus(path).toHttpUrl().newBuilder().apply {
                query.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            val request = Request.Builder().url(url).get()
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) throw BillingHttpException(resp.code)
                DataJson.parseToJsonElement(body) as? JsonObject ?: throw IllegalStateException("réponse inattendue")
            }
        }

    private suspend fun persist() {
        try {
            store.write(cache)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Cache non inscriptible: sans gravité.
        }
    }

    private fun publish(error: BillingError?) {
        _state.value = _state.value.copy(
            monthSpendUsd = cache.monthSpend,
            monthlyCapUsd = cache.monthlyCap,
            enteredBalance = cache.enteredBalance,
            estimatedCreditUsd = estimate(cache),
            lastUpdated = cache.lastUpdated,
            error = error,
        )
    }

    private fun estimate(c: BillingCache): Double? {
        val balance = c.enteredBalance ?: return null
        val spent = c.sinceEntrySpend ?: if (c.baselineSpend != null) 0.0 else return balance
        return (balance - spent).coerceAtLeast(0.0)
    }

    private class BillingHttpException(val code: Int) : IOException("HTTP $code")

    companion object {
        /** Plafonds mensuels à partir de cette valeur: considérés comme « illimités », non affichés. */
        const val CAP_IGNORED_FROM = 1_000_000.0
        private val ACCOUNT_NAME = Regex("^accounts/[A-Za-z0-9_.-]+$")

        private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
        private fun monthOf(ms: Long) = dayOf(ms).let { it.year * 100 + it.monthValue }

        /** Money Google: `{units: "12", nanos: 340000000}` = 12,34. */
        internal fun money(o: JsonObject): Double {
            val units = (o["units"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            val nanos = (o["nanos"] as? JsonPrimitive)?.longOrNull ?: 0L
            return units + nanos / 1e9
        }

        private fun JsonObject.number(key: String): Double? {
            val p = this[key] as? JsonPrimitive ?: return null
            return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
        }
    }
}
