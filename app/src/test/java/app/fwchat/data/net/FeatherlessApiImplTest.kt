package app.fwchat.data.net

import app.fwchat.data.DataJson
import app.fwchat.domain.ApiMessage
import app.fwchat.domain.ChatRequest
import app.fwchat.domain.FireworksException
import app.fwchat.domain.GenParams
import app.fwchat.domain.PlanInfo
import app.fwchat.domain.ReasoningEffort
import app.fwchat.domain.StreamEvent
import app.fwchat.domain.effectiveContextLength
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Formats RÉELS de Featherless (vérifiés par appels réels), servis par MockWebServer. */
class FeatherlessApiImplTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: FeatherlessApiImpl

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        api = FeatherlessApiImpl(client, server.url("/").toString().trimEnd('/'), maxAttempts = 1)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val request = ChatRequest("Qwen/Qwen3-4B", listOf(ApiMessage("user", "salut")), GenParams(maxTokens = 50))

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun sse(vararg lines: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(lines.joinToString("") { it + "\n\n" })

    private fun chunk(delta: String, finish: String? = null) =
        """data: {"id":"x","choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}"""

    private suspend fun failureOf(api: FeatherlessApiImpl = this.api): Throwable = try {
        api.streamChat("KEY", request).toList(); fail("exception attendue"); error("unreachable")
    } catch (e: FireworksException) {
        e
    }

    // ------------------------------------------------------------ stream

    @Test
    fun streamsRealFormatWithCommentsReasoningUsageChunkAndDone() = runBlocking {
        server.enqueue(
            sse(
                ": FEATHERLESS PROCESSING",
                ": FEATHERLESS PROCESSING",
                chunk("""{"role":"assistant","content":""}"""),
                chunk("""{"reasoning":"Je "}"""),
                chunk("""{"reasoning":"pense"}"""),
                chunk("""{"content":"Sal"}"""),
                chunk("""{"content":"ut"}"""),
                chunk("{}", "stop"),
                """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":7,"total_tokens":17,"cached_tokens":0}}""",
                "data: [DONE]",
            ),
        )
        val events = api.streamChat("KEY", request).toList()
        assertEquals(
            listOf<StreamEvent>(
                StreamEvent.ReasoningDelta("Je "), StreamEvent.ReasoningDelta("pense"),
                StreamEvent.ContentDelta("Sal"), StreamEvent.ContentDelta("ut"),
                StreamEvent.Usage(10, 7, null),
                StreamEvent.Finish("stop"),
            ),
            events,
        )
        val r = server.takeRequest()
        assertEquals("/v1/chat/completions", r.requestUrl!!.encodedPath)
        assertEquals("Bearer KEY", r.getHeader("Authorization"))
        assertEquals("FW Chat", r.getHeader("X-Title"))
    }

    @Test
    fun acceptsReasoningContentAlias() = runBlocking {
        server.enqueue(sse(chunk("""{"reasoning_content":"R"}"""), chunk("""{"content":"C"}"""), chunk("{}", "length"), "data: [DONE]"))
        val events = api.streamChat("KEY", request).toList()
        assertEquals(StreamEvent.ReasoningDelta("R"), events[0])
        assertEquals(StreamEvent.Finish("length"), events.last())
    }

    // ------------------------------------------------------------ corps de requete

    private fun body(params: GenParams): JsonObject =
        DataJson.parseToJsonElement(FeatherlessApiImpl.buildChatBody(request.copy(params = params))).jsonObject

    @Test
    fun bodyNeverContainsReasoningEffort() {
        for (e in ReasoningEffort.entries) {
            assertFalse(e.name, body(GenParams(reasoningEffort = e)).containsKey("reasoning_effort"))
        }
    }

    @Test
    fun reasoningNoneMapsToChatTemplateKwargs() {
        val kwargs = body(GenParams(reasoningEffort = ReasoningEffort.NONE))["chat_template_kwargs"]!!.jsonObject
        for (k in listOf("enable_thinking", "thinking", "do_reasoning")) assertFalse(k, kwargs.getValue(k).jsonPrimitive.boolean)
    }

    @Test
    fun otherReasoningLevelsSendNothing() {
        for (e in listOf(null, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.MAX)) {
            assertFalse(e.toString(), body(GenParams(reasoningEffort = e)).containsKey("chat_template_kwargs"))
        }
    }

    @Test
    fun bodyCarriesAcceptedSamplingParameters() {
        val b = body(
            GenParams(
                temperature = 0.5, topP = 0.9, topK = 40, minP = 0.05, maxTokens = 123, stop = listOf("a", "b", "c", "d", "e"),
                frequencyPenalty = 0.1, presencePenalty = 0.2, repetitionPenalty = 1.1, seed = 7,
            ),
        )
        for (k in listOf("temperature", "top_p", "top_k", "min_p", "max_tokens", "frequency_penalty", "presence_penalty", "repetition_penalty", "seed")) {
            assertTrue(k, b.containsKey(k))
        }
        assertEquals(5, (b["stop"] as kotlinx.serialization.json.JsonArray).size)
        assertTrue(b["stream"]!!.jsonPrimitive.boolean)
        assertTrue(b["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
    }

    // ------------------------------------------------------------ erreurs

    @Test
    fun maps401ToUnauthorized() = runBlocking {
        server.enqueue(json("""{"error":{"message":"Invalid API key","type":"invalid_request_error","param":null,"code":"unauthorized"}}""", 401))
        assertTrue(failureOf() is FireworksException.Unauthorized)
    }

    @Test
    fun maps404ToModelNotFound() = runBlocking {
        server.enqueue(json("""{"error":{"message":"The model does not exist","type":"invalid_request_error","param":"model","code":"model_not_found"}}""", 404))
        assertTrue(failureOf() is FireworksException.ModelNotFound)
    }

    @Test
    fun maps403GatedToModelGatedWithUrlAndFrenchMessage() = runBlocking {
        server.enqueue(
            json(
                """{"error":{"message":"This model is gated. Connect your HuggingFace account at https://featherless.ai/models/meta-llama/Llama-3.1-8B to use it.","type":"permission_error","param":null,"code":"model_gated_needs_oauth"}}""",
                403,
            ),
        )
        val e = failureOf() as FireworksException.ModelGated
        assertEquals("https://featherless.ai/models/meta-llama/Llama-3.1-8B", e.url)
        assertEquals(
            "Ce modèle est verrouillé: connecte ton compte HuggingFace sur featherless.ai pour y accéder. " +
                "https://featherless.ai/models/meta-llama/Llama-3.1-8B",
            e.toUserMessage(),
        )
    }

    @Test
    fun gatedWithoutUrlInMessageFallsBackToModelPage() = runBlocking {
        server.enqueue(json("""{"error":{"message":"gated","code":"model_gated_needs_oauth"}}""", 403))
        val e = failureOf() as FireworksException.ModelGated
        assertEquals("https://featherless.ai/models/Qwen/Qwen3-4B", e.url)
    }

    @Test
    fun otherForbiddenMeansModelOutsidePlan() = runBlocking {
        server.enqueue(json("""{"error":{"message":"Your plan does not include this model","code":"plan_required"}}""", 403))
        val e = failureOf()
        assertTrue(e is FireworksException.ModelNotInPlan)
        assertTrue((e as FireworksException).toUserMessage().contains("hors de ton plan"))
    }

    @Test
    fun maps429ToConcurrencyRateLimitedWithClearMessage() = runBlocking {
        server.enqueue(json("""{"error":{"message":"Concurrency limit exceeded","code":"rate_limit_exceeded"}}""", 429))
        val e = failureOf() as FireworksException.RateLimited
        assertTrue(e.concurrency)
        assertEquals("Limite de concurrence atteinte: attends la fin d'une autre réponse.", e.toUserMessage())
    }

    @Test
    fun maps400ColdModelToColdMessage() = runBlocking {
        server.enqueue(json("""{"error":{"message":"The model is cold and not ready yet","code":"bad_request"}}""", 400))
        val e = failureOf()
        assertTrue(e is FireworksException.ModelCold)
        assertEquals(
            "Modèle froid: son chargement peut prendre plusieurs minutes, réessaie plus tard.",
            (e as FireworksException).toUserMessage(),
        )
    }

    @Test
    fun retries503UpToThreeTimesThenNoCapacityMessage() = runBlocking {
        val retrying = FeatherlessApiImpl(client, server.url("/").toString().trimEnd('/'), maxAttempts = 3, retryBaseDelayMs = 1)
        repeat(3) { server.enqueue(json("""{"error":{"message":"No capacity"}}""", 503)) }
        val e = failureOf(retrying)
        assertTrue(e is FireworksException.NoCapacity)
        assertTrue((e as FireworksException).toUserMessage().startsWith("Capacité insuffisante pour ce modèle"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun retries429BeforeTheStreamThenSucceeds() = runBlocking {
        val retrying = FeatherlessApiImpl(client, server.url("/").toString().trimEnd('/'), maxAttempts = 3, retryBaseDelayMs = 1)
        server.enqueue(json("""{"error":{"message":"Concurrency limit","code":"rate_limit_exceeded"}}""", 429))
        server.enqueue(sse(chunk("""{"content":"ok"}"""), chunk("{}", "stop"), "data: [DONE]"))
        val events = retrying.streamChat("KEY", request).toList()
        assertEquals(StreamEvent.ContentDelta("ok"), events.first())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun doesNotRetryAfterDataWasReceived() = runBlocking {
        val retrying = FeatherlessApiImpl(client, server.url("/").toString().trimEnd('/'), maxAttempts = 3, retryBaseDelayMs = 1)
        // Flux qui s'interrompt sans fin logique: erreur réseau, pas de relance.
        server.enqueue(sse(chunk("""{"content":"a"}""")))
        try {
            retrying.streamChat("KEY", request).toList(); fail()
        } catch (e: FireworksException.Network) {
            // attendu
        }
        assertEquals(1, server.requestCount)
    }

    // ------------------------------------------------------------ liste

    private fun fm(
        id: String, gated: Boolean = false, ctx: String? = "32768", cost: Int? = 1, features: String? = """{"tool_use":true}""",
        plan: String? = null,
    ) = buildString {
        append("""{"id":"$id","is_gated":$gated,"created":1751086102,"model_class":"x","owned_by":"Feather",""")
        if (ctx != null) append(""""context_length":$ctx,""")
        if (cost != null) append(""""concurrency_cost":$cost,""")
        append(""""pricing":{"prompt":"0.1","input":0.1,"output":0.2}""")
        if (features != null) append(""","features":$features""")
        if (plan != null) append(""","available_on_current_plan":$plan""")
        append("}")
    }

    private fun page(items: List<String>, cur: Int, total: Int) =
        json("""{"data":[${items.joinToString(",")}],"pagination":{"current_page":$cur,"per_page":1000,"total_items":${items.size},"total_pages":$total}}""")

    @Test
    fun listModelsPaginatesWithExplicitActiveFilterAndSkipsNonChat() = runBlocking {
        server.enqueue(
            page(
                listOf(
                    fm("Qwen/Qwen3-0.6B", plan = "true"),
                    fm("org/Voice", features = """{"audio_output":true}""", plan = "true"),
                    fm("org/Classifier-X", features = """{"classifier":true}"""),
                    fm("BAAI/bge-Embedding-large"),
                    fm("org/vision-model", features = """{"image_input":true,"tool_use":false}""", plan = "false"),
                ),
                1, 2,
            ),
        )
        server.enqueue(page(listOf(fm("meta-llama/Llama-3.1-8B", gated = true, plan = "false", cost = 2)), 2, 2))

        val list = api.listModels("KEY")

        assertEquals(listOf("Qwen/Qwen3-0.6B", "org/vision-model", "meta-llama/Llama-3.1-8B"), list.map { it.id })
        val q = list[0]
        assertEquals("Qwen3-0.6B", q.displayName)
        assertEquals(32768, q.contextLength)
        assertTrue(q.supportsTools)
        assertEquals(true, q.availableOnPlan)
        assertEquals(1, q.concurrencyCost)
        assertFalse(q.gated)
        assertTrue(list[1].supportsImageInput)
        assertFalse(list[1].supportsTools)
        assertEquals(false, list[1].availableOnPlan)
        assertTrue(list[2].gated)
        assertEquals(2, list[2].concurrencyCost)

        val r1 = server.takeRequest()
        assertEquals("/v1/models", r1.requestUrl!!.encodedPath)
        assertEquals("active", r1.requestUrl!!.queryParameter("status"))
        assertEquals("true", r1.requestUrl!!.queryParameter("conversational"))
        assertEquals("1000", r1.requestUrl!!.queryParameter("per_page"))
        assertEquals("1", r1.requestUrl!!.queryParameter("page"))
        assertEquals("Bearer KEY", r1.getHeader("Authorization"))
        val r2 = server.takeRequest()
        assertEquals("2", r2.requestUrl!!.queryParameter("page"))
        assertEquals("active", r2.requestUrl!!.queryParameter("status"))
    }

    @Test
    fun listModelsToleratesMissingFieldsAndWithoutKeyOmitsAuthorization() = runBlocking {
        server.enqueue(
            json(
                """{"data":[{"id":"a/b"},{"id":"c/d","context_length":null,"features":null,"is_gated":null},
                   {"name":"no-id"},{"id":""},{"id":"e/f","context_length":"8192","features":{"tool_use":"oui"}}]}""",
            ),
        )
        val list = api.listModels("")
        assertEquals(listOf("a/b", "c/d", "e/f"), list.map { it.id })
        assertEquals(0, list[0].contextLength)
        assertNull(list[0].availableOnPlan)
        assertNull(list[0].concurrencyCost)
        assertFalse(list[0].gated)
        assertEquals(8192, list[2].contextLength)
        assertFalse(list[2].supportsTools)
        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals(1, server.requestCount) // pas de pagination -> une seule page
    }

    @Test
    fun listModelsFallsBackToFullDumpWhenFilterIsRefused() = runBlocking {
        server.enqueue(json("""{"error":{"message":"bad param"}}""", 400))
        server.enqueue(json("""{"data":[${fm("a/b")}]}"""))
        assertEquals(listOf("a/b"), api.listModels("KEY").map { it.id })
        server.takeRequest()
        val r2 = server.takeRequest()
        assertNull(r2.requestUrl!!.queryParameter("status"))
    }

    @Test
    fun listModelsMapsUnauthorizedAndGarbage() = runBlocking {
        server.enqueue(json("""{"error":{"message":"bad key","code":"unauthorized"}}""", 401))
        try {
            api.listModels("bad"); fail()
        } catch (e: FireworksException.Unauthorized) {
            // attendu
        }
        server.enqueue(json("pas du json"))
        try {
            api.listModels("k"); fail()
        } catch (e: FireworksException.Network) {
            // attendu
        }
    }

    // ------------------------------------------------------------ plan

    @Test
    fun planParsesRealFormat() = runBlocking {
        server.enqueue(json("""{"id":"basic","name":"Basic","max_context_length":32768,"max_model_size":null,"concurrency":4,"api_access":true,"credits":null}"""))
        val plan = api.plan("KEY")
        assertEquals(PlanInfo("Basic", 32768, 4, null), plan)
        val r = server.takeRequest()
        assertEquals("/v1/plan", r.requestUrl!!.encodedPath)
        assertEquals("Bearer KEY", r.getHeader("Authorization"))
    }

    @Test
    fun planUnauthorized() = runBlocking {
        server.enqueue(json("""{"error":{"message":"nope","code":"unauthorized"}}""", 401))
        try {
            api.plan("bad"); fail()
        } catch (e: FireworksException.Unauthorized) {
            // attendu
        }
    }

    @Test
    fun effectiveContextIsMinOfModelAndPlan() {
        val plan = PlanInfo("Basic", 32768, 4, null)
        fun m(ctx: Int) = app.fwchat.domain.ModelInfo("a/b", "b", ctx, false, false)
        assertEquals(32768, m(131072).effectiveContextLength(plan))
        assertEquals(8192, m(8192).effectiveContextLength(plan))
        assertEquals(32768, m(0).effectiveContextLength(plan))
        assertEquals(131072, m(131072).effectiveContextLength(null))
    }
}
