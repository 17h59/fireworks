package app.fwchat.data.net

import app.fwchat.domain.GenParams
import app.fwchat.domain.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenParamsCompatTest {
    private val none = GenParams(maxTokens = 5, reasoningEffort = ReasoningEffort.NONE)

    @Test
    fun noneIsOmittedForGlmAndGptOss() {
        assertNullEffort(GenParamsCompat.forModel(none, "accounts/fireworks/models/glm-5p3"))
        assertNullEffort(GenParamsCompat.forModel(none, "accounts/fireworks/models/gpt-oss-120b"))
        assertEquals(5, GenParamsCompat.forModel(none, "accounts/fireworks/models/glm-5p3").maxTokens)
    }

    @Test
    fun noneIsKeptForOtherModels() {
        assertEquals(ReasoningEffort.NONE, GenParamsCompat.forModel(none, "accounts/fireworks/models/kimi-k3").reasoningEffort)
        assertEquals(ReasoningEffort.NONE, GenParamsCompat.forModel(none, null).reasoningEffort)
    }

    @Test
    fun otherEffortsAreNeverTouched() {
        val low = GenParams(reasoningEffort = ReasoningEffort.LOW)
        assertSame(low, GenParamsCompat.forModel(low, "accounts/fireworks/models/glm-5p3"))
        val dflt = GenParams()
        assertSame(dflt, GenParamsCompat.forModel(dflt, "accounts/fireworks/models/gpt-oss-120b"))
    }

    @Test
    fun supportsNoReasoningByFamily() {
        assertFalse(GenParamsCompat.supportsNoReasoning("accounts/fireworks/models/glm-5p3-flash"))
        assertTrue(GenParamsCompat.supportsNoReasoning("accounts/fireworks/models/qwen3p8-max"))
    }

    private fun assertNullEffort(p: GenParams) = assertEquals(null, p.reasoningEffort)
}
