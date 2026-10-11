package app.fwchat.data.net

import app.fwchat.domain.ApiMessage
import app.fwchat.domain.ChatRequest
import app.fwchat.domain.GenParams
import app.fwchat.domain.ReasoningEffort
import app.fwchat.domain.StreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Test contre la vraie API Featherless. Ignoré sans la variable d'environnement FL_API_KEY.
 * La clé n'est jamais écrite dans un fichier ni affichée.
 */
class LiveFeatherlessTest {
    private lateinit var key: String
    private val api = FeatherlessApiImpl(OkHttpClient())

    @Before
    fun setUp() {
        val k = System.getenv("FL_API_KEY")
        assumeTrue("FL_API_KEY absente: test live ignoré", !k.isNullOrBlank())
        key = k!!
    }

    @Test
    fun listModelsReturnsChatModelsWithPlanAvailability() = runBlocking {
        val models = api.listModels(key)
        println("LIVE featherless models=${models.size}, onPlan=${models.count { it.availableOnPlan == true }}")
        assertTrue("au moins 1000 modèles attendus, reçu ${models.size}", models.size >= 1000)
        assertTrue(models.any { it.availableOnPlan != null })
        assertTrue(models.none { it.id.contains("embedding", ignoreCase = true) })
    }

    @Test
    fun planIsReadable() = runBlocking {
        val plan = api.plan(key)
        println("LIVE plan=${plan?.name} ctx=${plan?.maxContextLength} concurrency=${plan?.concurrency}")
        assertTrue(plan != null && plan.name.isNotBlank())
    }

    @Test
    fun streamChatYieldsContentAndUsage() = runBlocking {
        val request = ChatRequest(
            "Qwen/Qwen3-0.6B",
            listOf(ApiMessage("user", "Réponds uniquement: ok")),
            GenParams(maxTokens = 200, reasoningEffort = ReasoningEffort.NONE),
        )
        val events = withTimeout(120_000) { api.streamChat(key, request).toList() }
        println("LIVE events=${events.size}")
        assertTrue(events.any { it is StreamEvent.ContentDelta || it is StreamEvent.ReasoningDelta })
        assertTrue(events.last() is StreamEvent.Finish)
    }
}
