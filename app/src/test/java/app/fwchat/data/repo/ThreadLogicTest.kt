package app.fwchat.data.repo

import app.fwchat.domain.Message
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadLogicTest {
    private fun msg(
        id: String,
        parent: String?,
        t: Long,
        selected: String? = null,
        role: Role = Role.USER,
        status: MessageStatus = MessageStatus.COMPLETE,
    ) = Message(
        id = id, chatId = "c", parentId = parent, role = role, content = id, reasoning = null,
        selectedChildId = selected, createdAt = t, updatedAt = t, modelId = null, status = status,
        finishReason = null, error = null, edited = false, promptTokens = null, completionTokens = null,
        reasoningTokens = null,
    )

    private fun ids(l: List<Message>) = l.map { it.id }

    @Test
    fun emptyTreeHasEmptyPath() {
        assertTrue(ThreadLogic.activePath(emptyList(), null).isEmpty())
    }

    @Test
    fun defaultSelectionIsMostRecentChild() {
        val all = listOf(
            msg("r", null, 1), msg("a", "r", 2), msg("b", "r", 3), msg("a1", "a", 4), msg("b1", "b", 5),
        )
        assertEquals(listOf("r", "b", "b1"), ids(ThreadLogic.activePath(all, null)))
    }

    @Test
    fun selectedChildIsFollowedAndRemembersBranchBelow() {
        val all = listOf(
            msg("r", null, 1, selected = "a"), msg("a", "r", 2, selected = "a2"), msg("b", "r", 3),
            msg("a1", "a", 4), msg("a2", "a", 5), msg("a2x", "a2", 6),
        )
        assertEquals(listOf("r", "a", "a2", "a2x"), ids(ThreadLogic.activePath(all, null)))
    }

    @Test
    fun staleSelectedChildFallsBackToMostRecent() {
        val all = listOf(msg("r", null, 1, selected = "ghost"), msg("a", "r", 2), msg("b", "r", 3))
        assertEquals(listOf("r", "b"), ids(ThreadLogic.activePath(all, null)))
    }

    @Test
    fun multipleRootsUseSelectedRootElseMostRecent() {
        val all = listOf(msg("r1", null, 1), msg("r1a", "r1", 2), msg("r2", null, 3), msg("r2a", "r2", 4))
        assertEquals(listOf("r1", "r1a"), ids(ThreadLogic.activePath(all, "r1")))
        assertEquals(listOf("r2", "r2a"), ids(ThreadLogic.activePath(all, null)))
        assertEquals(listOf("r2", "r2a"), ids(ThreadLogic.activePath(all, "inconnu")))
    }

    @Test
    fun cycleDoesNotLoopForever() {
        val all = listOf(msg("r", null, 1, selected = "a"), msg("a", "r", 2, selected = "r"))
        assertEquals(listOf("r", "a"), ids(ThreadLogic.activePath(all, null)))
    }

    @Test
    fun threadItemsCarrySiblingsSortedByCreatedAt() {
        val all = listOf(
            msg("r", null, 1, selected = "b"), msg("c", "r", 30), msg("a", "r", 10), msg("b", "r", 20),
            msg("r2", null, 5),
        )
        val thread = ThreadLogic.activeThread(all, "r")
        assertEquals(listOf("r", "b"), thread.map { it.message.id })
        assertEquals(listOf("r", "r2").sorted(), thread[0].siblingIds.sorted())
        assertEquals(listOf("a", "b", "c"), thread[1].siblingIds)
        assertEquals(1, thread[1].siblingIndex)
        assertEquals(3, thread[1].siblingCount)
        assertEquals(listOf("r", "r2"), thread[0].siblingIds)
    }

    @Test
    fun equalTimestampsAreOrderedByIdDeterministically() {
        val all = listOf(msg("r", null, 1), msg("y", "r", 5), msg("x", "r", 5))
        assertEquals(listOf("r", "y"), ids(ThreadLogic.activePath(all, null)))
    }

    @Test
    fun subtreeIncludesAllDescendants() {
        val all = listOf(msg("r", null, 1), msg("a", "r", 2), msg("b", "r", 3), msg("a1", "a", 4), msg("a11", "a1", 5))
        assertEquals(setOf("a", "a1", "a11"), ThreadLogic.subtreeIds(all, "a"))
        assertEquals(setOf("r", "a", "b", "a1", "a11"), ThreadLogic.subtreeIds(all, "r"))
    }

    @Test
    fun fallbackSiblingPrefersPreviousThenNext() {
        val sibs = listOf(msg("a", "r", 1), msg("b", "r", 2), msg("c", "r", 3))
        assertEquals("a", ThreadLogic.fallbackSibling(sibs, "b")?.id)
        assertEquals("b", ThreadLogic.fallbackSibling(sibs, "c")?.id)
        assertEquals("b", ThreadLogic.fallbackSibling(sibs, "a")?.id)
        assertNull(ThreadLogic.fallbackSibling(listOf(msg("a", "r", 1)), "a"))
    }

    @Test
    fun ancestorChainGoesFromRootToTarget() {
        val all = listOf(msg("r", null, 1), msg("a", "r", 2), msg("b", "r", 3), msg("a1", "a", 4))
        assertEquals(listOf("r", "a", "a1"), ids(ThreadLogic.ancestorChain(all, "a1")))
        assertEquals(listOf("r"), ids(ThreadLogic.ancestorChain(all, "r")))
        assertTrue(ThreadLogic.ancestorChain(all, "zzz").isEmpty())
    }

    @Test
    fun cloneChainRelinksWithNewIds() {
        val chain = listOf(
            msg("r", null, 1, selected = "a"),
            msg("a", "r", 2, role = Role.ASSISTANT, status = MessageStatus.STREAMING),
        )
        var n = 0
        val clones = ThreadLogic.cloneChain(chain, "new") { "n${++n}" }
        assertEquals(listOf("n1", "n2"), ids(clones))
        assertTrue(clones.all { it.chatId == "new" })
        assertNull(clones[0].parentId)
        assertEquals("n1", clones[1].parentId)
        assertEquals("n2", clones[0].selectedChildId)
        assertNull(clones[1].selectedChildId)
        assertEquals(MessageStatus.INTERRUPTED, clones[1].status)
        assertEquals(1L, clones[0].createdAt)
    }

    @Test
    fun titleUsesFirstNonBlankLineTruncatedTo40() {
        assertEquals("Bonjour", ThreadLogic.titleFrom("\n  Bonjour  \nsuite"))
        assertEquals("", ThreadLogic.titleFrom("  \n \t "))
        assertEquals("a b c", ThreadLogic.titleFrom("a   b\tc"))
        val long = "x".repeat(60)
        assertEquals("x".repeat(40) + "…", ThreadLogic.titleFrom(long))
        assertEquals("x".repeat(40), ThreadLogic.titleFrom("x".repeat(40)))
    }

    @Test
    fun titleTruncationDoesNotSplitSurrogatePairs() {
        val t = ThreadLogic.titleFrom("😀".repeat(50))
        assertEquals("😀".repeat(40) + "…", t)
    }

    @Test
    fun forkTitleFallsBackToNewChat() {
        assertEquals("Fork de Recette", ThreadLogic.forkTitle("Recette"))
        assertEquals("Fork de Nouveau chat", ThreadLogic.forkTitle(""))
    }

    @Test
    fun normalizeStripsAccentsAndCase() {
        assertEquals("ecole ete", ThreadLogic.normalize("  École ÉTÉ "))
        assertEquals("cafe", ThreadLogic.normalize("Café"))
        assertEquals("naive", ThreadLogic.normalize("naïve"))
    }

    @Test
    fun likeEscapingProtectsWildcards() {
        assertEquals("100!%!_!!", ThreadLogic.escapeLike("100%_!"))
        assertEquals("a!%", ThreadLogic.searchPattern("A%"))
    }
}
