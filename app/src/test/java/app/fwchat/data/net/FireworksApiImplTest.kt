package app.fwchat.data.net

import app.fwchat.data.DataJson
import app.fwchat.domain.ApiMessage
import app.fwchat.domain.ChatRequest
import app.fwchat.domain.FireworksException
import app.fwchat.domain.GenParams
import app.fwchat.domain.ReasoningEffort
import app.fwchat.domain.StreamEvent
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class FireworksApiImplTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: FireworksApiImpl

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        api = FireworksApiImpl(client, server.url("/").toString().trimEnd('/'), maxAttempts = 1)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun model(
        name: String, display: String, serverless: Boolean = true, state: String = "READY",
        kind: String = "HF_BASE_MODEL", ctx: Any = 4096,
    ) = """{"name":"accounts/fireworks/models/$name","displayName":"$display","supportsServerless":$serverless,
        "state":"$state","kind":"$kind","contextLength":$ctx,"supportsImageInput":true,"supportsTools":false,
        "baseModelDetails":{"x":1}}"""

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun sse(vararg events: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })

    private fun chunk(delta: String, finish: String? = null) =
        """{"id":"x","choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}],"usage":null}"""

    private val request = ChatRequest(
        "accounts/fireworks/models/m", listOf(ApiMessage("user", "salut")), GenParams(maxTokens = 50),
    )

    private suspend fun collect(): List<StreamEvent> = api.streamChat("KEY", request).toList()

    private suspend fun failureOf(): Throwable = try {
        collect(); fail("exception attendue"); error("unreachable")
    } catch (e: FireworksException) {
        e
    }

    // ------------------------------------------------------------ listModels

    @Test
    fun listModelsPaginatesFiltersAndSorts() = runBlocking {
        server.enqueue(
            json(
                """{"models":[${model("zeta", "Zeta")},${model("embed", "Emb", kind = "EMBEDDING_MODEL")},
                   ${model("alpha", "alpha")},${model("off", "Off", serverless = false)}],"nextPageToken":"p2","totalSize":6}""",
            ),
        )
        server.enqueue(
            json(
                """{"models":[${model("qwen3-reranker-8b", "Rerank")},${model("beta", "Beta", ctx = "\"131072\"")},
                   ${model("notready", "NR", state = "CREATING")}],"nextPageToken":""}""",
            ),
        )

        val list = api.listModels("KEY")

        assertEquals(listOf("alpha", "Beta", "Zeta"), list.map { it.displayName })
        assertEquals("accounts/fireworks/models/alpha", list[0].id)
        assertEquals(131072, list[1].contextLength)
        assertTrue(list[0].supportsImageInput)
        assertFalse(list[0].supportsTools)

        val r1 = server.takeRequest()
        assertEquals("Bearer KEY", r1.getHeader("Authorization"))
        assertEquals("/v1/accounts/fireworks/models", r1.requestUrl!!.encodedPath)
        assertEquals("200", r1.requestUrl!!.queryParameter("pageSize"))
        assertNull(r1.requestUrl!!.queryParameter("pageToken"))
        val r2 = server.takeRequest()
        assertEquals("p2", r2.requestUrl!!.queryParameter("pageToken"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun listModelsRetriesWithoutFilterOn400() = runBlocking {
        server.enqueue(json("""{"error":{"message":"bad filter","code":"INVALID_ARGUMENT"}}""", 400))
        server.enqueue(json("""{"models":[${model("a", "A")}]}"""))
        val list = api.listModels("KEY")
        assertEquals(1, list.size)
        assertEquals("supports_serverless=true", server.takeRequest().requestUrl!!.queryParameter("filter"))
        assertNull(server.takeRequest().requestUrl!!.queryParameter("filter"))
    }

    @Test
    fun listModelsMapsUnauthorized() = runBlocking {
        server.enqueue(json("""{"error":{"message":"The API key you provided is invalid.","code":"UNAUTHORIZED"}}""", 401))
        try {
            api.listModels("bad"); fail()
        } catch (e: FireworksException.Unauthorized) {
            assertTrue(e.message!!.contains("invalid"))
        }
    }

    // ------------------------------------------------------------ streamChat

    @Test
    fun streamsReasoningContentUsageAndFinish() = runBlocking {
        server.enqueue(
            sse(
                chunk("""{"role":"assistant"}"""),
                chunk("""{"reasoning_content":"Je "}"""),
                chunk("""{"reasoning_content":"pense"}"""),
                chunk("""{"content":"Sa"}"""),
                chunk("""{"content":"lut"}"""),
                chunk("{}", "stop"),
                """{"id":"x","choices":[],"usage":{"prompt_tokens":9,"completion_tokens":20,"total_tokens":29,"completion_tokens_details":{"reasoning_tokens":12}}}""",
                "[DONE]",
            ),
        )
        val events = collect()
        assertEquals(
            listOf(
                StreamEvent.ReasoningDelta("Je "), StreamEvent.ReasoningDelta("pense"),
                StreamEvent.ContentDelta("Sa"), StreamEvent.ContentDelta("lut"),
                StreamEvent.Usage(9, 20, 12), StreamEvent.Finish("stop"),
            ),
            events,
        )
        val req = server.takeRequest()
        assertEquals("/inference/v1/chat/completions", req.requestUrl!!.encodedPath)
        assertEquals("Bearer KEY", req.getHeader("Authorization"))
    }

    @Test
    fun usageChunkWithEmptyChoicesAndNoDoneStillFinishes() = runBlocking {
        server.enqueue(
            sse(
                chunk("""{"content":"a"}""", "stop"),
                """{"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}""",
            ),
        )
        assertEquals(
            listOf(StreamEvent.ContentDelta("a"), StreamEvent.Usage(1, 2, null), StreamEvent.Finish("stop")),
            collect(),
        )
    }

    @Test
    fun requestBodyOnlyContainsNonNullParams() = runBlocking {
        server.enqueue(sse(chunk("""{"content":"a"}""", "stop"), "[DONE]"))
        collect()
        val body = DataJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("accounts/fireworks/models/m", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertTrue(body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
        assertEquals(50, body["max_tokens"]!!.jsonPrimitive.int)
        for (k in listOf("temperature", "top_p", "top_k", "min_p", "stop", "frequency_penalty", "presence_penalty",
            "repetition_penalty", "seed", "reasoning_effort")) {
            assertFalse("$k ne doit pas être envoyé", body.containsKey(k))
        }
        val msgs = body["messages"] as JsonArray
        assertEquals("user", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun requestBodyContainsAllSetParams() = runBlocking {
        server.enqueue(sse(chunk("""{"content":"a"}""", "stop"), "[DONE]"))
        val full = GenParams(
            temperature = 0.7, topP = 0.9, topK = 40, minP = 0.05, maxTokens = 99, stop = listOf("A", "B"),
            frequencyPenalty = 0.1, presencePenalty = 0.2, repetitionPenalty = 1.1, seed = 42L,
            reasoningEffort = ReasoningEffort.HIGH,
        )
        api.streamChat("KEY", request.copy(params = full)).toList()
        val body = DataJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(0.7, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(40, body["top_k"]!!.jsonPrimitive.int)
        assertEquals(listOf("A", "B"), body["stop"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(42, body["seed"]!!.jsonPrimitive.int)
        assertEquals(1.1, body["repetition_penalty"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun mapsHttp401() = runBlocking {
        server.enqueue(json("""{"error":{"message":"The API key you provided is invalid.","code":"UNAUTHORIZED","type":"error"}}""", 401))
        assertTrue(failureOf() is FireworksException.Unauthorized)
    }

    @Test
    fun mapsHttp402() = runBlocking {
        server.enqueue(json("""{"error":{"message":"Account suspended, no credit","code":"PAYMENT_REQUIRED"}}""", 402))
        assertTrue(failureOf() is FireworksException.InsufficientCredit)
    }

    @Test
    fun mapsHttp429WithRetryAfter() = runBlocking {
        server.enqueue(json("""{"error":{"message":"slow down","code":"RESOURCE_EXHAUSTED"}}""", 429).setHeader("Retry-After", "7"))
        val e = failureOf() as FireworksException.RateLimited
        assertEquals(7, e.retryAfterSeconds)
    }

    @Test
    fun mapsHttp429WithoutRetryAfter() = runBlocking {
        server.enqueue(json("""{"error":{"message":"slow down"}}""", 429))
        assertNull((failureOf() as FireworksException.RateLimited).retryAfterSeconds)
    }

    @Test
    fun mapsHttp404ToModelNotFound() = runBlocking {
        server.enqueue(json("""{"error":{"message":"Model not found, inaccessible, and/or not deployed","param":"model","code":"NOT_FOUND","type":"error"}}""", 404))
        assertTrue(failureOf() is FireworksException.ModelNotFound)
    }

    @Test
    fun mapsContextLengthExceeded() = runBlocking {
        server.enqueue(json("""{"error":{"message":"The prompt has 2000000 tokens, which exceeds the maximum context length of 131072","code":"INVALID_ARGUMENT"}}""", 400))
        assertTrue(failureOf() is FireworksException.ContextLengthExceeded)
    }

    @Test
    fun mapsOtherHttpErrors() = runBlocking {
        server.enqueue(json("""{"error":{"message":"boom"}}""", 500))
        val e = failureOf() as FireworksException.Http
        assertEquals(500, e.code)
        assertTrue(e.body.contains("boom"))
    }

    @Test
    fun jsonErrorWithStatus200BeforeStreamIsMapped() = runBlocking {
        server.enqueue(json("""{"error":{"message":"Model not found","code":"NOT_FOUND"}}""", 200))
        assertTrue(failureOf() is FireworksException.ModelNotFound)
    }

    @Test
    fun errorEventInsideTheStreamIsMapped() = runBlocking {
        server.enqueue(sse(chunk("""{"content":"a"}"""), """{"error":{"message":"quota","code":"RESOURCE_EXHAUSTED"}}"""))
        val received = mutableListOf<StreamEvent>()
        try {
            api.streamChat("KEY", request).collect { received += it }
            fail()
        } catch (e: FireworksException.RateLimited) {
            assertEquals(listOf<StreamEvent>(StreamEvent.ContentDelta("a")), received)
        }
    }

    @Test
    fun networkFailureBecomesNetworkException() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(failureOf() is FireworksException.Network)
    }

    @Test
    fun streamCutBeforeFinishIsAnError() = runBlocking {
        server.enqueue(sse(chunk("""{"content":"a"}""")))
        assertTrue(failureOf() is FireworksException.Network)
    }

    @Test
    fun idleTimeoutBecomesNetworkException() = runBlocking {
        val fast = FireworksApiImpl(client, server.url("/").toString().trimEnd('/'), streamIdleTimeoutSeconds = 1)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t0 = System.nanoTime()
        try {
            fast.streamChat("KEY", request).toList(); fail()
        } catch (e: FireworksException.Network) {
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is java.io.IOException })
        }
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0) < 10)
    }

    @Test
    fun cancellingTheCollectionCancelsTheHttpCall() = runBlocking {
        // 1er évènement (~50 octets) livré tout de suite, puis un remplissage très lent.
        val first = """data: {"choices":[{"delta":{"content":"A"}}]}""" + "\n\n"
        val filler = ": ping\n".repeat(20000)
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(first + filler)
                .throttleBody(first.length.toLong() + 1, 300, TimeUnit.MILLISECONDS),
        )
        val events = withTimeout(5000) { api.streamChat("KEY", request).take(1).toList() }
        assertEquals(listOf<StreamEvent>(StreamEvent.ContentDelta("A")), events)
        // L'appel doit être annulé côté client peu après.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (client.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(0, client.dispatcher.runningCallsCount())
    }

    // ------------------------------------------------------------ relances (429 / 5xx avant le flux)

    private fun retrying(maxAttempts: Int = 3) = FireworksApiImpl(
        client, server.url("/").toString().trimEnd('/'),
        maxAttempts = maxAttempts, retryBaseDelayMs = 1,
    )

    private val okStream get() = sse(chunk("""{"content":"ok"}"""), chunk("{}", "stop"))

    @Test
    fun retriesOn503ThenSucceeds() = runBlocking {
        server.enqueue(json("""{"error":{"message":"overloaded"}}""", 503))
        server.enqueue(json("""{"error":{"message":"overloaded"}}""", 500))
        server.enqueue(okStream)
        val events = retrying().streamChat("KEY", request).toList()
        assertEquals(StreamEvent.ContentDelta("ok"), events.first())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun retriesOn429ThenSucceedsRespectingRetryAfter() = runBlocking {
        server.enqueue(json("""{"error":{"message":"slow down","code":"RESOURCE_EXHAUSTED"}}""", 429).setHeader("Retry-After", "1"))
        server.enqueue(okStream)
        val t0 = System.nanoTime()
        val events = retrying().streamChat("KEY", request).toList()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertEquals(StreamEvent.ContentDelta("ok"), events.first())
        assertEquals(2, server.requestCount)
        assertTrue("Retry-After ignoré (${elapsedMs} ms)", elapsedMs >= 950)
    }

    @Test
    fun givesUpAfterMaxAttemptsWithLastError() = runBlocking {
        repeat(3) { server.enqueue(json("""{"error":{"message":"down"}}""", 502)) }
        server.enqueue(okStream)
        try {
            retrying().streamChat("KEY", request).toList(); fail()
        } catch (e: FireworksException.Http) {
            assertEquals(502, e.code)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun doesNotRetryOtherErrors() = runBlocking {
        server.enqueue(json("""{"error":{"message":"bad key","code":"UNAUTHORIZED"}}""", 401))
        server.enqueue(okStream)
        try {
            retrying().streamChat("KEY", request).toList(); fail()
        } catch (e: FireworksException.Unauthorized) {
            // attendu
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun doesNotRetryWhenRetryAfterIsTooLong() = runBlocking {
        server.enqueue(json("""{"error":{"message":"slow down"}}""", 429).setHeader("Retry-After", "3600"))
        server.enqueue(okStream)
        try {
            retrying().streamChat("KEY", request).toList(); fail()
        } catch (e: FireworksException.RateLimited) {
            assertEquals(3600, e.retryAfterSeconds)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun neverRetriesAfterADeltaWasReceived() = runBlocking {
        server.enqueue(sse(chunk("""{"content":"a"}"""), """{"error":{"message":"quota","code":"RESOURCE_EXHAUSTED"}}"""))
        server.enqueue(okStream)
        val received = mutableListOf<StreamEvent>()
        try {
            retrying().streamChat("KEY", request).collect { received += it }
            fail()
        } catch (e: FireworksException.RateLimited) {
            assertEquals(listOf<StreamEvent>(StreamEvent.ContentDelta("a")), received)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retriesEmbeddedErrorBeforeFirstEvent() = runBlocking {
        server.enqueue(sse("""{"error":{"message":"quota","code":"RESOURCE_EXHAUSTED"}}"""))
        server.enqueue(okStream)
        val events = retrying().streamChat("KEY", request).toList()
        assertEquals(StreamEvent.ContentDelta("ok"), events.first())
        assertEquals(2, server.requestCount)
    }

    // ------------------------------------------------------------ SSE: régressions de format

    @Test
    fun multiByteUtf8ReceivedByteByByteIsDecoded() = runBlocking {
        val text = "é€😀日本語"
        val body = "data: " + chunk("""{"content":"$text"}""") + "\n\n" + "data: " + chunk("{}", "stop") + "\n\n"
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
                .throttleBody(1, 1, TimeUnit.MILLISECONDS),
        )
        val events = collect()
        assertEquals(StreamEvent.ContentDelta(text), events.first())
        assertEquals(StreamEvent.Finish("stop"), events.last())
    }

    @Test
    fun crlfCommentsAndEventFieldsAreIgnored() = runBlocking {
        val body = ": keep-alive\r\n\r\n" +
            "event: message\r\ndata: " + chunk("""{"content":"a"}""") + "\r\n\r\n" +
            ": ping\r\n" +
            "event: message\r\nid: 7\r\ndata: " + chunk("""{"content":"b"}""") + "\r\n\r\n" +
            "data: " + chunk("{}", "stop") + "\r\n\r\n" +
            "data: [DONE]\r\n\r\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        val events = collect()
        assertEquals(
            listOf<StreamEvent>(StreamEvent.ContentDelta("a"), StreamEvent.ContentDelta("b"), StreamEvent.Finish("stop")),
            events,
        )
    }

    @Test
    fun dataSplitOverSeveralLinesIsJoined() = runBlocking {
        val body = "data: {\"choices\":[{\"index\":0,\n" +
            "data: \"delta\":{\"content\":\"multi\"},\n" +
            "data: \"finish_reason\":null}]}\n\n" +
            "data: " + chunk("{}", "stop") + "\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        val events = collect()
        assertEquals(StreamEvent.ContentDelta("multi"), events.first())
        assertEquals(StreamEvent.Finish("stop"), events.last())
    }
}
