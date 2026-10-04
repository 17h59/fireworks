package app.fwchat.data.net

import app.fwchat.domain.ApiMessage
import app.fwchat.domain.ChatRequest
import app.fwchat.domain.FireworksException
import app.fwchat.domain.GenParams
import app.fwchat.domain.StreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Test contre la vraie API Fireworks. Ignoré sans la variable d'environnement FW_API_KEY.
 * Consomme quelques centimes: ne pas multiplier les appels.
 */
class LiveFireworksTest {
    private lateinit var key: String
    private val api = FireworksApiImpl(OkHttpClient())

    @Before
    fun setUp() {
        val k = System.getenv("FW_API_KEY")
        assumeTrue("FW_API_KEY absente: test live ignoré", !k.isNullOrBlank())
        key = k!!
    }

    @Test
    fun listModelsReturnsServerlessChatModels() = runBlocking {
        val models = api.listModels(key)
        println("LIVE models=${models.size}: " + models.joinToString { it.shortId })
        assertTrue("au moins 5 modèles attendus, reçu ${models.size}", models.size >= 5)
        assertTrue(models.any { it.shortId == "glm-5p3-flash" })
        assertTrue(models.none { it.id.contains("embedding") || it.id.contains("reranker") })
        assertEquals(models.sortedBy { it.displayName.lowercase() }.map { it.id }, models.map { it.id })
    }

    @Test
    fun streamChatYieldsReasoningContentAndUsage() = runBlocking {
        val request = ChatRequest(
            "accounts/fireworks/models/glm-5p3-flash",
            listOf(ApiMessage("user", "Réponds uniquement: ok")),
            GenParams(maxTokens = 1500),
        )
        val events = withTimeout(90_000) { api.streamChat(key, request).toList() }
        val reasoning = events.filterIsInstance<StreamEvent.ReasoningDelta>().joinToString("") { it.text }
        val content = events.filterIsInstance<StreamEvent.ContentDelta>().joinToString("") { it.text }
        val usage = events.filterIsInstance<StreamEvent.Usage>().single()
        val finish = events.filterIsInstance<StreamEvent.Finish>().single()
        println("LIVE reasoning=${reasoning.length} chars content='$content' usage=$usage finish=${finish.reason}")
        assertTrue("reasoning vide", reasoning.isNotEmpty())
        assertTrue("content vide", content.isNotEmpty())
        assertTrue(usage.completionTokens != null && usage.promptTokens != null)
        assertEquals(finish, events.last())
        assertTrue(usage.reasoningTokens != null && usage.reasoningTokens!! > 0)
    }

    @Test
    fun unknownModelIsModelNotFound() = runBlocking {
        val request = ChatRequest(
            "accounts/fireworks/models/ce-modele-nexiste-pas",
            listOf(ApiMessage("user", "x")), GenParams(maxTokens = 5),
        )
        try {
            api.streamChat(key, request).toList()
            throw AssertionError("ModelNotFound attendu")
        } catch (e: FireworksException.ModelNotFound) {
            println("LIVE 404 message=${e.message}")
        }
    }

    @Test
    fun invalidKeyIsUnauthorized() = runBlocking {
        try {
            api.listModels("fw_invalid_key_for_test")
            throw AssertionError("Unauthorized attendu")
        } catch (e: FireworksException.Unauthorized) {
            println("LIVE 401 message=${e.message}")
        }
    }
}
