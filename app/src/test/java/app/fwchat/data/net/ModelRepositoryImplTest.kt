package app.fwchat.data.net

import app.fwchat.domain.FireworksException
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.PlanInfo
import app.fwchat.domain.Provider
import app.fwchat.domain.ProviderApis
import app.fwchat.domain.searchModels

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelRepositoryImplTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun m(id: String, name: String) = ModelInfo("accounts/fireworks/models/$id", name, 1000, false, true)

    private fun mkRepo(
        api: FakeChatApi,
        settings: FakeSettings,
        cache: ModelCache,
        flApi: FakeChatApi = FakeChatApi(Provider.FEATHERLESS),
        flCache: ModelCache = InMemoryModelCache(),
        clock: () -> Long = { 0L },
    ) = ModelRepositoryImpl(
        ProviderApis(mapOf(Provider.FIREWORKS to api, Provider.FEATHERLESS to flApi)), settings,
        mapOf(Provider.FIREWORKS to cache, Provider.FEATHERLESS to flCache), clock = clock,
    )

    @Test
    fun emitsCacheFirstThenRefreshedList() = runTest {
        val cache = InMemoryModelCache(listOf(m("old", "Old")))
        val api = FakeChatApi().apply { models = listOf(m("b", "B"), m("a", "A")) }
        val repo = mkRepo(api, FakeSettings(), cache)

        assertEquals(listOf("Old"), repo.models.first().map { it.displayName })
        assertTrue(repo.refresh().isSuccess)
        assertEquals(listOf("A", "B"), repo.models.first().map { it.displayName })
        assertEquals(listOf("A", "B"), cache.stored!!.map { it.displayName })
    }

    @Test
    fun emptyListWhenNoCacheAndNoRefresh() = runTest {
        val repo = mkRepo(FakeChatApi(), FakeSettings(), InMemoryModelCache(null))
        assertEquals(emptyList<ModelInfo>(), repo.models.first())
    }

    @Test
    fun refreshFailsWithoutKey() = runTest {
        val api = FakeChatApi()
        val repo = mkRepo(api, FakeSettings(key = null), InMemoryModelCache())
        val r = repo.refresh()
        assertTrue(r.exceptionOrNull() is FireworksException.Unauthorized)
        assertEquals(0, api.listCalls)
    }

    @Test
    fun refreshFailureKeepsCacheAndReturnsFailure() = runTest {
        val cache = InMemoryModelCache(listOf(m("old", "Old")))
        val api = FakeChatApi().apply { listError = FireworksException.Network(java.io.IOException("off")) }
        val repo = mkRepo(api, FakeSettings(), cache)
        assertTrue(repo.refresh().exceptionOrNull() is FireworksException.Network)
        assertEquals(listOf("Old"), repo.models.first().map { it.displayName })
        assertEquals(0, cache.writes)
    }

    @Test
    fun updatesAreEmittedToCollectors() = runTest {
        val api = FakeChatApi().apply { models = listOf(m("a", "A")) }
        val repo = mkRepo(api, FakeSettings(), InMemoryModelCache(listOf(m("old", "Old"))))
        val collected = mutableListOf<List<String>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.models.take(2).toList().forEach { collected += it.map { x -> x.displayName } }
        }
        repo.refresh()
        job.join()
        assertEquals(listOf(listOf("Old"), listOf("A")), collected)
    }

    @Test
    fun fileCacheRoundTripAndCorruption() = runTest {
        val file = tmp.newFolder("sub").resolve("models.json")
        val cache = FileModelCache(file)
        assertNull(cache.read())
        val list = listOf(m("a", "A"), ModelInfo("accounts/fireworks/models/b", "B", 0, true, false))
        cache.write(list)
        assertEquals(list, cache.read())
        file.writeText("{ pas du json")
        assertNull(cache.read())
    }

    private fun fl(id: String, plan: Boolean? = true, ctx: Int = 4096) =
        ModelInfo(id, id.substringAfterLast('/'), ctx, false, false, availableOnPlan = plan)

    @Test
    fun modelsFollowActiveProvider() = runTest {
        val api = FakeChatApi().apply { models = listOf(m("a", "A")) }
        val flApi = FakeChatApi(Provider.FEATHERLESS).apply { models = listOf(fl("org/x")) }
        val settings = FakeSettings(key = "fw", flKey = "fl")
        val repo = mkRepo(api, settings, InMemoryModelCache(), flApi)
        assertTrue(repo.refresh(Provider.FIREWORKS).isSuccess)
        assertTrue(repo.refresh(Provider.FEATHERLESS).isSuccess)
        assertEquals(listOf("A"), repo.models.first().map { it.displayName })
        settings.setActiveProvider(Provider.FEATHERLESS)
        assertEquals(listOf("org/x"), repo.models.first().map { it.id })
        assertEquals(listOf("A"), repo.modelsFor(Provider.FIREWORKS).first().map { it.displayName })
    }

    @Test
    fun refreshDefaultsToActiveProviderAndUsesItsKey() = runTest {
        val api = FakeChatApi()
        val flApi = FakeChatApi(Provider.FEATHERLESS)
        val repo = mkRepo(api, FakeSettings(key = "fw", flKey = "fl", active = Provider.FEATHERLESS), InMemoryModelCache(), flApi)
        assertTrue(repo.refresh().isSuccess)
        assertEquals(1, flApi.listCalls)
        assertEquals(0, api.listCalls)
    }

    @Test
    fun featherlessSortsPlanAvailableFirstThenById() = runTest {
        val flApi = FakeChatApi(Provider.FEATHERLESS).apply {
            models = listOf(fl("z/b", false), fl("Z/a", true), fl("a/c", null), fl("b/a", true))
        }
        val repo = mkRepo(FakeChatApi(), FakeSettings(key = null, flKey = "fl", active = Provider.FEATHERLESS), InMemoryModelCache(), flApi)
        repo.refresh()
        assertEquals(listOf("b/a", "Z/a", "a/c", "z/b"), repo.models.first().map { it.id })
    }

    @Test
    fun refreshFeatherlessStoresPlanAndPropagatesUnauthorized() = runTest {
        val flApi = FakeChatApi(Provider.FEATHERLESS).apply { planResult = PlanInfo("Basic", 32768, 4, null) }
        val repo = mkRepo(FakeChatApi(), FakeSettings(key = null, flKey = "fl", active = Provider.FEATHERLESS), InMemoryModelCache(), flApi)
        assertNull(repo.plan.first())
        assertTrue(repo.refresh().isSuccess)
        assertEquals(PlanInfo("Basic", 32768, 4, null), repo.plan.first())

        val bad = object : app.fwchat.domain.ChatApi {
            override val provider = Provider.FEATHERLESS
            override suspend fun plan(apiKey: String): PlanInfo? = throw FireworksException.Unauthorized("401")
            override suspend fun listModels(apiKey: String): List<ModelInfo> = emptyList()
            override fun streamChat(apiKey: String, request: app.fwchat.domain.ChatRequest) = kotlinx.coroutines.flow.emptyFlow<app.fwchat.domain.StreamEvent>()
        }
        val repo2 = ModelRepositoryImpl(
            ProviderApis(mapOf(Provider.FIREWORKS to FakeChatApi(), Provider.FEATHERLESS to bad)),
            FakeSettings(key = null, flKey = "fl", active = Provider.FEATHERLESS),
            mapOf(Provider.FIREWORKS to InMemoryModelCache(), Provider.FEATHERLESS to InMemoryModelCache()),
        )
        assertTrue(repo2.refresh().exceptionOrNull() is FireworksException.Unauthorized)
    }

    @Test
    fun refreshIfStaleSkipsFreshCacheAndRefreshesOldOrEmpty() = runTest {
        var now = 1_000_000L
        val flApi = FakeChatApi(Provider.FEATHERLESS).apply { models = listOf(fl("o/m")) }
        val repo = mkRepo(
            FakeChatApi(), FakeSettings(key = null, flKey = "fl", active = Provider.FEATHERLESS), InMemoryModelCache(), flApi,
            clock = { now },
        )
        assertTrue(repo.refreshIfStale(Provider.FEATHERLESS, 1000).isSuccess) // vide: charge
        assertEquals(1, flApi.listCalls)
        now += 500
        repo.refreshIfStale(Provider.FEATHERLESS, 1000) // frais: rien
        assertEquals(1, flApi.listCalls)
        now += 2000
        repo.refreshIfStale(Provider.FEATHERLESS, 1000) // ancien: recharge
        assertEquals(2, flApi.listCalls)
        // Sans cle: aucun appel.
        val noKey = FakeChatApi(Provider.FEATHERLESS)
        val repo2 = mkRepo(FakeChatApi(), FakeSettings(key = "fw", flKey = null), InMemoryModelCache(), noKey)
        assertTrue(repo2.refreshIfStale(Provider.FEATHERLESS).exceptionOrNull() is FireworksException.Unauthorized)
        assertEquals(0, noKey.listCalls)
    }

    @Test
    fun compactCacheRoundTripKeepsFeatherlessFields() = runTest {
        val file = tmp.newFolder("fl").resolve("c.json")
        val cache = CompactModelCache(file)
        assertNull(cache.read())
        val list = listOf(
            ModelInfo("Qwen/Qwen3-4B", "Qwen3-4B", 32768, false, true, gated = false, availableOnPlan = true, concurrencyCost = 1),
            ModelInfo("meta-llama/Llama", "Llama", 0, true, false, gated = true, availableOnPlan = false, concurrencyCost = null),
            ModelInfo("a/b", "b", 8192, false, false),
        )
        cache.write(list)
        assertEquals(list, cache.read())
        assertTrue(file.length() < 400)
        file.writeText("{ pas du json")
        assertNull(cache.read())
    }

    @Test
    fun searchModelsFiltersByAllTermsAndHonorsLimit() {
        val list = listOf(fl("Qwen/Qwen3-4B"), fl("Qwen/Qwen3-8B"), fl("meta/Llama-3-8B"))
        assertEquals(listOf("Qwen/Qwen3-8B"), list.searchModels("qwen 8b").map { it.id })
        assertEquals(3, list.searchModels("  ").size)
        assertEquals(1, list.searchModels("", limit = 1).size)
    }
}
