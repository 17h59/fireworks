package app.fwchat.ui.shell

import app.cash.turbine.test
import app.fwchat.ui.FakeChatEngine
import app.fwchat.ui.FakeChatRepoForDrawer
import app.fwchat.ui.message
import app.fwchat.ui.summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class DrawerViewModelTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val now = ZonedDateTime.of(2026, 10, 4, 14, 0, 0, 0, zone)
    private val nowMs = now.toInstant().toEpochMilli()
    private fun ago(days: Long, hours: Long = 0) = now.minusDays(days).minusHours(hours).toInstant().toEpochMilli()

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `buckets par jour calendaire`() {
        fun b(ms: Long) = dateBucketOf(ms, nowMs, zone)
        assertEquals(DateBucket.TODAY, b(now.withHour(0).withMinute(1).toInstant().toEpochMilli()))
        assertEquals(DateBucket.TODAY, b(nowMs + 3_600_000)) // futur: aujourd'hui
        // 00:30 hier, même s'il y a moins de 24 h
        assertEquals(DateBucket.YESTERDAY, b(now.minusDays(1).withHour(0).withMinute(30).toInstant().toEpochMilli()))
        assertEquals(DateBucket.LAST_7_DAYS, b(ago(2)))
        assertEquals(DateBucket.LAST_7_DAYS, b(ago(7)))
        assertEquals(DateBucket.LAST_30_DAYS, b(ago(8)))
        assertEquals(DateBucket.LAST_30_DAYS, b(ago(30)))
        assertEquals(DateBucket.OLDER, b(ago(31)))
    }

    @Test
    fun `groupByDate insere un en-tete par groupe et conserve l ordre`() {
        val list = listOf(
            summary("a", updatedAt = ago(0, 1)),
            summary("b", updatedAt = ago(0, 2)),
            summary("c", updatedAt = ago(1)),
            summary("d", updatedAt = ago(5)),
            summary("e", updatedAt = ago(20)),
            summary("f", updatedAt = ago(100)),
            summary("g", updatedAt = ago(200)),
        )
        val rows = groupByDate(list, nowMs, zone)
        val shape = rows.map {
            when (it) {
                is DrawerRow.Header -> "H:${it.bucket}"
                is DrawerRow.Item -> it.chat.id
            }
        }
        assertEquals(
            listOf(
                "H:TODAY", "a", "b", "H:YESTERDAY", "c", "H:LAST_7_DAYS", "d",
                "H:LAST_30_DAYS", "e", "H:OLDER", "f", "g",
            ),
            shape,
        )
        assertEquals(rows.size, rows.map { it.key }.toSet().size) // clés uniques
    }

    @Test
    fun `liste vide ne produit aucune ligne`() {
        assertTrue(groupByDate(emptyList(), nowMs, zone).isEmpty())
    }

    private fun vm(repo: FakeChatRepoForDrawer, engine: FakeChatEngine = FakeChatEngine()) =
        DrawerViewModel(repo, engine, clock = { nowMs }, zone = { zone }, debounceMillis = 150)

    @Test
    fun `etat charge puis filtre avec debounce`() = runTest(dispatcher) {
        val repo = FakeChatRepoForDrawer(
            listOf(
                summary("1", "Recette de crêpes", updatedAt = ago(0, 1)),
                summary("2", "Voyage à Lisbonne", updatedAt = ago(3)),
            ),
        )
        val vm = vm(repo)
        vm.state.test {
            assertFalse(awaitItem().loaded)
            val all = awaitItem()
            assertTrue(all.loaded)
            assertEquals(4, all.rows.size) // 2 en-têtes + 2 chats

            vm.setQuery("lisb")
            assertEquals("lisb", vm.query.value) // le champ n'est jamais retardé
            advanceTimeBy(100)
            runCurrent()
            expectNoEvents() // debounce pas encore écoulé
            advanceTimeBy(60)
            runCurrent()
            val filtered = awaitItem()
            assertEquals(listOf("Voyage à Lisbonne"), filtered.rows.filterIsInstance<DrawerRow.Item>().map { it.chat.title })

            vm.setQuery("")
            advanceUntilIdle()
            assertEquals(4, awaitItem().rows.size) // retour immédiat sans debounce
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(repo.queries.containsAll(listOf("", "lisb")))
    }

    @Test
    fun `renommer ignore un titre vide et nettoie les espaces`() = runTest(dispatcher) {
        val repo = FakeChatRepoForDrawer()
        val vm = vm(repo)
        vm.rename("1", "   ")
        vm.rename("1", "  Nouveau titre ")
        advanceUntilIdle()
        assertEquals(listOf("1" to "Nouveau titre"), repo.renamed)
    }

    @Test
    fun `supprimer arrete la generation puis supprime et notifie`() = runTest(dispatcher) {
        val repo = FakeChatRepoForDrawer()
        val engine = FakeChatEngine()
        var notified = false
        vm(repo, engine).delete("1") { notified = true }
        advanceUntilIdle()
        assertEquals(listOf("1"), engine.stopped)
        assertEquals(listOf("1"), repo.deleted)
        assertTrue(notified)
    }

    @Test
    fun `dupliquer fork jusqu au dernier message du chemin actif`() = runTest(dispatcher) {
        val repo = FakeChatRepoForDrawer().apply { activePath = listOf(message("m1"), message("m2")) }
        var forked: String? = null
        vm(repo).duplicate("chat") { forked = it }
        advanceUntilIdle()
        assertEquals(listOf("chat" to "m2"), repo.forks)
        assertEquals("fork-of-chat", forked)
    }

    @Test
    fun `dupliquer un chat vide ne fait rien`() = runTest(dispatcher) {
        val repo = FakeChatRepoForDrawer()
        var forked: String? = null
        vm(repo).duplicate("chat") { forked = it }
        advanceUntilIdle()
        assertTrue(repo.forks.isEmpty())
        assertEquals(null, forked)
    }

    @Test
    fun `generating reflete le moteur`() = runTest(dispatcher) {
        val engine = FakeChatEngine()
        val vm = vm(FakeChatRepoForDrawer(), engine)
        vm.generating.test {
            assertEquals(emptySet<String>(), awaitItem())
            engine.generating.value = setOf("x")
            assertEquals(setOf("x"), awaitItem())
        }
    }
}
