package app.fwchat.data.net

import app.fwchat.domain.EngineEvent
import app.fwchat.domain.FireworksException
import app.fwchat.domain.GenParams
import app.fwchat.domain.MessageStatus
import app.fwchat.domain.ReasoningEffort
import app.fwchat.domain.Role
import app.fwchat.domain.StreamEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatEngineImplTest {

    private class Env(val test: TestScope) {
        val chats = FakeChatRepository()
        val models = FakeModelRepository()
        val settings = FakeSettings()
        val api = FakeFireworksApi()
        val engine = ChatEngineImpl(chats, models, settings, api, test)
        val events = mutableListOf<EngineEvent>()

        init {
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { engine.events.collect { events += it } }
        }
    }

    private fun TestScope.env() = Env(this)

    private fun script(vararg ev: StreamEvent) = flow { ev.forEach { emit(it) } }

    private val nominal = arrayOf(
        StreamEvent.ReasoningDelta("Je "), StreamEvent.ReasoningDelta("réfléchis"),
        StreamEvent.ContentDelta("Sal"), StreamEvent.ContentDelta("ut"),
        StreamEvent.Usage(10, 7, 3), StreamEvent.Finish("stop"),
    )

    @Test
    fun nominalFlow() = runTest {
        val e = env()
        val chatId = e.chats.addChat()
        e.api.handler = { script(*nominal) }

        e.engine.send(chatId, "Bonjour")
        advanceUntilIdle()

        val assistant = e.chats.assistants(chatId).single()
        assertEquals("Salut", assistant.content)
        assertEquals("Je réfléchis", assistant.reasoning)
        assertEquals(MessageStatus.COMPLETE, assistant.status)
        assertEquals("stop", assistant.finishReason)
        assertEquals(10, assistant.promptTokens)
        assertEquals(7, assistant.completionTokens)
        assertEquals(3, assistant.reasoningTokens)
        assertNull(assistant.error)
        assertTrue(e.engine.streaming.value.isEmpty())
        assertTrue(e.engine.generatingChats.value.isEmpty())
        assertTrue(e.events.isEmpty())
        val user = e.chats.children(null, chatId).single()
        assertEquals("Bonjour", user.content)
        assertEquals(user.id, assistant.parentId)
    }

    @Test
    fun streamingStateIsPublishedAndConflated() = runTest {
        val e = env()
        val chatId = e.chats.addChat()
        e.api.handler = {
            flow {
                repeat(10) { emit(StreamEvent.ContentDelta("a")); delay(5) }
                delay(1000)
                emit(StreamEvent.Finish("stop"))
            }
        }
        e.engine.send(chatId, "x")
        runCurrent()
        assertEquals(setOf(chatId), e.engine.generatingChats.value)
        advanceTimeBy(100)
        val live = e.engine.streaming.value.values.single()
        assertEquals("aaaaaaaaaa", live.content)
        advanceUntilIdle()
        assertTrue(e.engine.streaming.value.isEmpty())
        assertTrue(e.engine.generatingChats.value.isEmpty())
    }

    @Test
    fun checkpointsAreRoughlyOncePerSecond() = runTest {
        val e = env()
        val chatId = e.chats.addChat()
        e.api.handler = {
            flow {
                repeat(35) { emit(StreamEvent.ContentDelta("x")); delay(100) }
                emit(StreamEvent.Finish("stop"))
            }
        }
        e.engine.send(chatId, "x")
        advanceUntilIdle()
        // ~3,5 s de flux => 3 checkpoints + l'écriture finale.
        assertTrue("checkpoints=${e.chats.updateStreamingCalls}", e.chats.updateStreamingCalls in 3..6)
        assertEquals(35, e.chats.assistants(chatId).single().content.length)
    }

    @Test
    fun stopKeepsTextAndMarksInterrupted() = runTest {
        val e = env()
        val chatId = e.chats.addChat()
        e.api.handler = {
            flow {
                emit(StreamEvent.ReasoningDelta("pense"))
                emit(StreamEvent.ContentDelta("Début"))
                awaitCancellation()
            }
        }
        e.engine.send(chatId, "x")
        advanceTimeBy(200)
        assertEquals(setOf(chatId), e.engine.generatingChats.value)

        e.engine.stop(chatId)
        advanceUntilIdle()

        val a = e.chats.assistants(chatId).single()
        assertEquals(MessageStatus.INTERRUPTED, a.status)
        assertEquals("Début", a.content)
        assertEquals("pense", a.reasoning)
        assertTrue(e.engine.streaming.value.isEmpty())
        assertTrue(e.engine.generatingChats.value.isEmpty())
        assertTrue(e.events.isEmpty())
    }

    @Test
    fun unauthorizedErrorSetsErrorStatusAndEvent() = runTest {
        val e = env()
        val chatId = e.chats.addChat()
        e.api.handler = { flow { throw FireworksException.Unauthorized("bad key") } }
        e.engine.send(chatId, "x")
        advanceUntilIdle()

        val a = e.chats.assistants(chatId).single()
        assertEquals(MessageStatus.ERROR, a.status)
        assertTrue(a.error!!.contains("Clé API"))
        assertEquals(listOf<EngineEvent>(EngineEvent.Unauthorized), e.events)
        assertTrue(e.engine.generatingChats.value.isEmpty())
    }

    @Test
    fun missingApiKeyFailsCleanly() = runTest {
        val e = env()
        e.settings.key = null
        val chatId = e.chats.addChat()
        e.engine.send(chatId, "x")
        advanceUntilIdle()
        assertEquals(0, e.api.requests.size)
        assertEquals(MessageStatus.ERROR, e.chats.assistants(chatId).single().status)
        assertEquals(listOf<EngineEvent>(EngineEvent.Unauthorized), e.events)
    }

    @Test
    fun modelNotFoundTriggersRefresh() = runTest {
        val e = env()
        val chatId = e.chats.addChat()
        e.api.handler = { flow { throw FireworksException.ModelNotFound("nope") } }
        e.engine.send(chatId, "x")
        advanceUntilIdle()
        assertEquals(1, e.models.refreshCount)
        val a = e.chats.assistants(chatId).single()
        assertEquals(MessageStatus.ERROR, a.status)
        assertTrue(a.error!!.contains("Modèle introuvable"))
        assertTrue(e.events.single() is EngineEvent.Error)
    }

    @Test
    fun partialTextIsKeptOnMidStreamFailure() = runTest {
        val e = env()
        val chatId = e.chats.addChat()
        e.api.handler = {
            flow {
                emit(StreamEvent.ContentDelta("Partiel"))
                throw FireworksException.Network(java.net.SocketTimeoutException("timeout"))
            }
        }
        e.engine.send(chatId, "x")
        advanceUntilIdle()
        val a = e.chats.assistants(chatId).single()
        assertEquals(MessageStatus.ERROR, a.status)
        assertEquals("Partiel", a.content)
        assertTrue(a.error!!.contains("Délai"))
    }

    @Test
    fun emptyAnswerWithLengthIsExplicitError() = runTest {
        val e = env()
        val chatId = e.chats.addChat()
        e.api.handler = {
            script(StreamEvent.ReasoningDelta("je pense encore"), StreamEvent.Usage(5, 100, 100), StreamEvent.Finish("length"))
        }
        e.engine.send(chatId, "x")
        advanceUntilIdle()
        val a = e.chats.assistants(chatId).single()
        assertEquals(MessageStatus.ERROR, a.status)
        assertEquals("Coupé pendant la réflexion: augmente les tokens max.", a.error)
        assertEquals("je pense encore", a.reasoning)
        assertEquals("length", a.finishReason)
        assertEquals(100, a.completionTokens)
    }

    @Test
    fun lengthWithContentStaysComplete() = runTest {
        val e = env()
        val chatId = e.chats.addChat()
        e.api.handler = { script(StreamEvent.ContentDelta("Tronqué"), StreamEvent.Finish("length")) }
        e.engine.send(chatId, "x")
        advanceUntilIdle()
        val a = e.chats.assistants(chatId).single()
        assertEquals(MessageStatus.COMPLETE, a.status)
        assertEquals("length", a.finishReason)
    }

    @Test
    fun requestWithSystemPromptAndParams() = runTest {
        val e = env()
        val params = GenParams(temperature = 0.5, maxTokens = 321, stop = listOf("END"), reasoningEffort = ReasoningEffort.LOW)
        val chatId = e.chats.addChat(modelId = "accounts/fireworks/models/glm", systemPrompt = "Tu es utile.", params = params)
        // Historique: user, assistant avec contenu, user.
        val u1 = e.chats.add(chatId, null, Role.USER, "Q1")
        val a1 = e.chats.add(chatId, u1.id, Role.ASSISTANT, "R1")
        e.chats.messages[a1.id] = e.chats.msg(a1.id).copy(reasoning = "ne pas renvoyer")
        e.api.handler = { script(StreamEvent.ContentDelta("ok"), StreamEvent.Finish("stop")) }

        e.engine.send(chatId, "Q2")
        advanceUntilIdle()

        val req = e.api.requests.single()
        assertEquals("accounts/fireworks/models/glm", req.model)
        assertEquals(params, req.params)
        assertEquals(
            listOf("system" to "Tu es utile.", "user" to "Q1", "assistant" to "R1", "user" to "Q2"),
            req.messages.map { it.role to it.content },
        )
    }

    @Test
    fun requestWithoutSystemAndSkippingEmptyMessages() = runTest {
        val e = env()
        val chatId = e.chats.addChat(systemPrompt = "   ")
        val u1 = e.chats.add(chatId, null, Role.USER, "Q1")
        e.chats.add(chatId, u1.id, Role.ASSISTANT, "", MessageStatus.ERROR)
        e.api.handler = { script(StreamEvent.ContentDelta("ok"), StreamEvent.Finish("stop")) }

        e.engine.send(chatId, "Q2")
        advanceUntilIdle()

        val req = e.api.requests.single()
        assertEquals(listOf("user" to "Q1", "user" to "Q2"), req.messages.map { it.role to it.content })
    }

    @Test
    fun regenerateUsesPathUpToParentAndCreatesSibling() = runTest {
        val e = env()
        val chatId = e.chats.addChat(systemPrompt = "SYS")
        val u1 = e.chats.add(chatId, null, Role.USER, "Q1")
        val a1 = e.chats.add(chatId, u1.id, Role.ASSISTANT, "R1")
        val u2 = e.chats.add(chatId, a1.id, Role.USER, "Q2")
        val a2 = e.chats.add(chatId, u2.id, Role.ASSISTANT, "R2 ancienne")
        e.api.handler = { script(StreamEvent.ContentDelta("R2 nouvelle"), StreamEvent.Finish("stop")) }

        e.engine.regenerate(a2.id)
        advanceUntilIdle()

        val req = e.api.requests.single()
        assertEquals(listOf("system", "user", "assistant", "user"), req.messages.map { it.role })
        assertEquals("Q2", req.messages.last().content)
        val siblings = e.chats.children(u2.id, chatId)
        assertEquals(2, siblings.size)
        assertEquals("R2 ancienne", e.chats.msg(a2.id).content)
        val fresh = siblings.last()
        assertEquals("R2 nouvelle", fresh.content)
        assertEquals(MessageStatus.COMPLETE, fresh.status)
        assertTrue(e.engine.generatingChats.value.isEmpty())
    }

    @Test
    fun editAndResendBranchesAndExcludesOldTail() = runTest {
        val e = env()
        val chatId = e.chats.addChat()
        val u1 = e.chats.add(chatId, null, Role.USER, "Q1")
        val a1 = e.chats.add(chatId, u1.id, Role.ASSISTANT, "R1")
        val u2 = e.chats.add(chatId, a1.id, Role.USER, "Q2 faute")
        e.chats.add(chatId, u2.id, Role.ASSISTANT, "R2")
        e.api.handler = { script(StreamEvent.ContentDelta("R2bis"), StreamEvent.Finish("stop")) }

        e.engine.editAndResend(u2.id, "Q2 corrigée")
        advanceUntilIdle()

        val req = e.api.requests.single()
        assertEquals(listOf("Q1", "R1", "Q2 corrigée"), req.messages.map { it.content })
        val branches = e.chats.children(a1.id, chatId)
        assertEquals(2, branches.size)
        val newUser = branches.last()
        assertEquals("Q2 corrigée", newUser.content)
        val reply = e.chats.children(newUser.id, chatId).single()
        assertEquals("R2bis", reply.content)
        assertEquals(MessageStatus.COMPLETE, reply.status)
    }

    @Test
    fun onlyOneGenerationPerChatButOtherChatsRunInParallel() = runTest {
        val e = env()
        val c1 = e.chats.addChat()
        val c2 = e.chats.addChat()
        val gate = CompletableDeferred<Unit>()
        e.api.handler = {
            flow {
                gate.await()
                emit(StreamEvent.ContentDelta("ok"))
                emit(StreamEvent.Finish("stop"))
            }
        }
        e.engine.send(c1, "premier")
        e.engine.send(c1, "second (ignoré)")
        e.engine.send(c2, "autre chat")
        runCurrent()

        assertEquals(setOf(c1, c2), e.engine.generatingChats.value)
        assertEquals(2, e.api.requests.size)
        assertEquals(1, e.chats.children(null, c1).size)
        assertTrue(e.events.single() is EngineEvent.Error)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(e.engine.generatingChats.value.isEmpty())

        // Une fois terminé, un nouvel envoi est possible.
        e.engine.send(c1, "troisième")
        runCurrent()
        assertEquals(3, e.api.requests.size)
        advanceUntilIdle()
        assertEquals(2, e.chats.assistants(c1).size)
    }
}
