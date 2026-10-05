package app.fwchat.data.net

import app.fwchat.domain.BillingError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class BillingRepositoryImplTest {

    private class MemStore(var cache: BillingCache = BillingCache()) : BillingStore {
        override suspend fun read() = cache
        override suspend fun write(cache: BillingCache) { this.cache = cache }
    }

    private lateinit var server: MockWebServer
    private val seen = mutableListOf<RecordedRequest>()
    private var monthSpend = "12.5"
    private var accountsCode = 200
    private var summaryBody = """{"lineItems":[]}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/v1/accounts") && !path.contains("/quotas") && !path.contains("/billing") ->
                        if (accountsCode != 200) MockResponse().setResponseCode(accountsCode).setBody("""{"error":"nope"}""")
                        else MockResponse().setBody("""{"accounts":[{"name":"accounts/abc123","email":"secret@example.com","displayName":"x"}]}""")
                    path.contains("/quotas/monthly-spend-usd") ->
                        MockResponse().setBody("""{"name":"q","value":1e12,"usage":$monthSpend}""")
                    path.contains("/billing/summary") -> MockResponse().setBody(summaryBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun repo(
        store: MemStore = MemStore(),
        key: String? = "fw_test_key",
        now: () -> Long = { System.currentTimeMillis() },
    ) = BillingRepositoryImpl(OkHttpClient(), server.url("/").toString().trimEnd('/'), FakeSettings(key), store, now)

    @Test fun depenses_du_mois_et_plafond_ignore() = runBlocking {
        val r = repo()
        r.refresh()
        val s = r.state.value
        assertEquals(12.5, s.monthSpendUsd!!, 1e-9)
        assertNull(s.monthlyCapUsd)
        assertNull(s.error)
        assertNotNull(s.lastUpdated)
        assertEquals("Bearer fw_test_key", seen.first().getHeader("Authorization"))
    }

    @Test fun email_jamais_stocke() = runBlocking {
        val store = MemStore()
        repo(store).refresh()
        assertEquals("accounts/abc123", store.cache.accountName)
        assertFalse(store.cache.toString().contains("secret@example.com"))
        assertFalse(store.cache.toString().contains("example.com"))
    }

    @Test fun id_de_compte_mis_en_cache() = runBlocking {
        val r = repo()
        r.refresh()
        r.refresh()
        assertEquals(1, seen.count { it.path == "/v1/accounts" })
        assertEquals(2, seen.count { (it.path ?: "").contains("quotas") })
    }

    @Test fun estimation_du_credit_meme_mois() = runBlocking {
        val r = repo()
        monthSpend = "10.0"
        r.setBalance(50.0)
        // Référence = 10, aucune dépense depuis la saisie.
        assertEquals(50.0, r.state.value.estimatedCreditUsd!!, 1e-9)
        monthSpend = "12.25"
        r.refresh()
        assertEquals(47.75, r.state.value.estimatedCreditUsd!!, 1e-9)
        assertEquals(50.0, r.state.value.enteredBalance!!, 1e-9)
    }

    @Test fun estimation_autre_mois_via_billing_summary() = runBlocking {
        val lastMonth = LocalDate.now(ZoneOffset.UTC).minusMonths(1).withDayOfMonth(15)
            .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val store = MemStore(
            BillingCache(accountName = "accounts/abc123", enteredBalance = 20.0, balanceEnteredAt = lastMonth, baselineSpend = 3.0),
        )
        summaryBody = """{"lineItems":[
            {"groupingValue":"m1","totalCost":{"units":"1","nanos":500000000}},
            {"groupingValue":"m2","totalCost":{"nanos":250000000}}]}"""
        val r = repo(store)
        r.refresh()
        assertEquals(18.25, r.state.value.estimatedCreditUsd!!, 1e-9)
        val summary = seen.first { (it.path ?: "").contains("billing/summary") }
        assertTrue(summary.path!!.contains("granularity=DAILY"))
        assertTrue(summary.path!!.contains("startTime="))
    }

    @Test fun sans_solde_pas_de_credit() = runBlocking {
        val r = repo()
        r.refresh()
        assertNull(r.state.value.estimatedCreditUsd)
        assertNull(r.state.value.enteredBalance)
    }

    @Test fun oublier_le_solde() = runBlocking {
        val r = repo()
        r.setBalance(5.0)
        r.setBalance(null)
        assertNull(r.state.value.enteredBalance)
        assertNull(r.state.value.estimatedCreditUsd)
    }

    @Test fun erreur_401_sans_crash_et_valeurs_conservees() = runBlocking {
        val r = repo()
        r.refresh()
        accountsCode = 401
        val store = MemStore(BillingCache(monthSpend = 7.0))
        val r2 = repo(store)
        r2.refresh()
        assertEquals(BillingError.UNAUTHORIZED, r2.state.value.error)
        assertEquals(7.0, r2.state.value.monthSpendUsd!!, 1e-9)
        assertFalse(r2.state.value.loading)
    }

    @Test fun cle_absente_erreur() = runBlocking {
        val r = repo(key = null)
        r.refresh()
        assertEquals(BillingError.UNAUTHORIZED, r.state.value.error)
        assertEquals(0, server.requestCount)
    }

    @Test fun erreur_reseau() = runBlocking {
        val r = repo()
        server.shutdown()
        r.refresh()
        assertEquals(BillingError.NETWORK, r.state.value.error)
    }

    @Test fun cache_persistant_relu_au_demarrage() = runBlocking {
        val store = MemStore()
        repo(store).also { it.setBalance(30.0) }
        val again = repo(store)
        // Lecture asynchrone du cache: attend la première opération.
        server.shutdown()
        again.refresh()
        assertEquals(30.0, again.state.value.enteredBalance!!, 1e-9)
        assertEquals(12.5, again.state.value.monthSpendUsd!!, 1e-9)
    }

    @Test fun money_units_et_nanos() {
        val o = kotlinx.serialization.json.Json.parseToJsonElement("""{"units":"3","nanos":140000000}""")
            as kotlinx.serialization.json.JsonObject
        assertEquals(3.14, BillingRepositoryImpl.money(o), 1e-9)
    }
}
