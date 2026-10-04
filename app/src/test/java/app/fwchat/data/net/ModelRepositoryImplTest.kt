package app.fwchat.data.net

import app.fwchat.domain.FireworksException
import app.fwchat.domain.ModelInfo
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

    @Test
    fun emitsCacheFirstThenRefreshedList() = runTest {
        val cache = InMemoryModelCache(listOf(m("old", "Old")))
        val api = FakeFireworksApi().apply { models = listOf(m("b", "B"), m("a", "A")) }
        val repo = ModelRepositoryImpl(api, FakeSettings(), cache)

        assertEquals(listOf("Old"), repo.models.first().map { it.displayName })
        assertTrue(repo.refresh().isSuccess)
        assertEquals(listOf("A", "B"), repo.models.first().map { it.displayName })
        assertEquals(listOf("A", "B"), cache.stored!!.map { it.displayName })
    }

    @Test
    fun emptyListWhenNoCacheAndNoRefresh() = runTest {
        val repo = ModelRepositoryImpl(FakeFireworksApi(), FakeSettings(), InMemoryModelCache(null))
        assertEquals(emptyList<ModelInfo>(), repo.models.first())
    }

    @Test
    fun refreshFailsWithoutKey() = runTest {
        val api = FakeFireworksApi()
        val repo = ModelRepositoryImpl(api, FakeSettings(key = null), InMemoryModelCache())
        val r = repo.refresh()
        assertTrue(r.exceptionOrNull() is FireworksException.Unauthorized)
        assertEquals(0, api.listCalls)
    }

    @Test
    fun refreshFailureKeepsCacheAndReturnsFailure() = runTest {
        val cache = InMemoryModelCache(listOf(m("old", "Old")))
        val api = FakeFireworksApi().apply { listError = FireworksException.Network(java.io.IOException("off")) }
        val repo = ModelRepositoryImpl(api, FakeSettings(), cache)
        assertTrue(repo.refresh().exceptionOrNull() is FireworksException.Network)
        assertEquals(listOf("Old"), repo.models.first().map { it.displayName })
        assertEquals(0, cache.writes)
    }

    @Test
    fun updatesAreEmittedToCollectors() = runTest {
        val api = FakeFireworksApi().apply { models = listOf(m("a", "A")) }
        val repo = ModelRepositoryImpl(api, FakeSettings(), InMemoryModelCache(listOf(m("old", "Old"))))
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
}
