package app.fwchat.ui.common

import app.fwchat.domain.GenParams
import app.fwchat.domain.ModelInfo
import app.fwchat.domain.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenParamsLogicTest {

    @Test
    fun aucunMasquePourGlmEtGptOss() {
        assertFalse(GenParamsLogic.supportsNoReasoning("accounts/fireworks/models/glm-5p3"))
        assertFalse(GenParamsLogic.supportsNoReasoning("accounts/fireworks/models/gpt-oss-120b"))
        assertTrue(GenParamsLogic.supportsNoReasoning("accounts/fireworks/models/kimi-k3"))
        assertTrue(GenParamsLogic.supportsNoReasoning(null))
        assertFalse(ReasoningEffort.NONE in GenParamsLogic.reasoningOptions("glm-5p3-flash"))
        assertTrue(ReasoningEffort.NONE in GenParamsLogic.reasoningOptions("kimi-k3"))
    }

    @Test
    fun optionsDeRaisonnementCommencentParDefaut() {
        val o = GenParamsLogic.reasoningOptions("kimi-k3")
        assertNull(o.first())
        assertEquals(listOf(null, ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.MAX), o)
        assertEquals("Défaut", GenParamsLogic.reasoningLabel(null))
        assertEquals("Aucun", GenParamsLogic.reasoningLabel(ReasoningEffort.NONE))
    }

    @Test
    fun motsDArretLimitesAQuatreSansDoublonNiVide() {
        var p = GenParams()
        p = GenParamsLogic.addStop(p, "  ")
        assertTrue(p.stop.isEmpty())
        p = GenParamsLogic.addStop(p, " FIN ")
        p = GenParamsLogic.addStop(p, "FIN")
        assertEquals(listOf("FIN"), p.stop)
        p = GenParamsLogic.addStop(p, "a")
        p = GenParamsLogic.addStop(p, "b")
        p = GenParamsLogic.addStop(p, "c")
        p = GenParamsLogic.addStop(p, "d")
        assertEquals(listOf("FIN", "a", "b", "c"), p.stop)
        p = GenParamsLogic.removeStop(p, "a")
        assertEquals(listOf("FIN", "b", "c"), p.stop)
    }

    @Test
    fun analyseDesChampsNumeriques() {
        assertEquals(12, GenParamsLogic.parseInt(" 12 "))
        assertNull(GenParamsLogic.parseInt(""))
        assertNull(GenParamsLogic.parseInt("-3"))
        assertNull(GenParamsLogic.parseInt("abc"))
        assertEquals(-5L, GenParamsLogic.parseLong("-5"))
        assertNull(GenParamsLogic.parseLong("-"))
    }

    @Test
    fun arrondiDesCurseurs() {
        assertEquals(0.3, GenParamsLogic.snap(0.30000000000000004, 0.05), 0.0)
        assertEquals(0.75, GenParamsLogic.snap(0.74, 0.05), 0.0)
        assertEquals("1,05", GenParamsLogic.formatDecimal(1.05))
    }

    @Test
    fun filtreDesModeles() {
        fun m(id: String, name: String) = ModelInfo("accounts/fireworks/models/$id", name, 0, false, false)
        val list = listOf(m("glm-5p3", "GLM 5.3"), m("kimi-k3", "Kimi K3"))
        assertEquals(list, filterModels(list, "  "))
        assertEquals(listOf(list[0]), filterModels(list, "GLM"))
        assertEquals(listOf(list[1]), filterModels(list, "kimi"))
        assertTrue(filterModels(list, "zzz").isEmpty())
    }

    @Test
    fun formatContexteEtEntiers() {
        assertEquals("128k", formatContext(131072))
        assertEquals("1M", formatContext(1048576))
        assertNull(formatContext(0))
        assertEquals("1 240".length, formatInt(1240).length)
    }
}
