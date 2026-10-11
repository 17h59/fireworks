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
        assertFalse(ReasoningEffort.NONE in GenParamsLogic.reasoningOptions("accounts/fireworks/models/glm-5p3-flash"))
        assertTrue(ReasoningEffort.NONE in GenParamsLogic.reasoningOptions("accounts/fireworks/models/kimi-k3"))
    }

    @Test
    fun optionsDeRaisonnementCommencentParDefaut() {
        val o = GenParamsLogic.reasoningOptions("accounts/fireworks/models/kimi-k3")
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
    fun aucunToujoursProposePourFeatherless() {
        // Même pour une famille GLM / gpt-oss: la restriction ne vaut que pour Fireworks.
        assertTrue(GenParamsLogic.supportsNoReasoning("zai-org/GLM-4.5"))
        assertTrue(GenParamsLogic.supportsNoReasoning("openai/gpt-oss-120b"))
        assertTrue(ReasoningEffort.NONE in GenParamsLogic.reasoningOptions("zai-org/GLM-4.5"))
        val ignored = GenParams(reasoningEffort = ReasoningEffort.NONE)
        assertFalse(GenParamsLogic.noReasoningIgnored(ignored, "zai-org/GLM-4.5"))
        assertTrue(GenParamsLogic.noReasoningIgnored(ignored, "accounts/fireworks/models/glm-5p3"))
    }

    @Test
    fun aideDeRaisonnementSeulementPourFeatherless() {
        val hint = GenParamsLogic.featherlessReasoningHint("Qwen/Qwen3-8B")
        assertTrue(hint!!.startsWith("Pour Featherless, seul « Aucun » agit"))
        assertNull(GenParamsLogic.featherlessReasoningHint("accounts/fireworks/models/kimi-k3"))
        assertNull(GenParamsLogic.featherlessReasoningHint(null))
    }

    private fun fl(id: String, name: String = id.substringAfterLast('/')) = ModelInfo(id, name, 0, false, false)

    @Test
    fun filtreDesModeles() {
        fun m(id: String, name: String) = ModelInfo("accounts/fireworks/models/$id", name, 0, false, false)
        val list = listOf(m("glm-5p3", "GLM 5.3"), m("kimi-k3", "Kimi K3"))
        assertEquals(list, pageModels(list, "  ").items)
        assertEquals(listOf(list[0]), pageModels(list, "GLM").items)
        assertEquals(listOf(list[1]), pageModels(list, "kimi").items)
        assertTrue(pageModels(list, "zzz").items.isEmpty())
    }

    @Test
    fun filtreAvecPlusieursMotsDansLIdOuLeNom() {
        val list = listOf(fl("Qwen/Qwen3-8B"), fl("meta-llama/Llama-3.1-8B-Instruct"), fl("Qwen/Qwen3-32B"))
        assertEquals(listOf(list[0]), pageModels(list, "qwen 8b").items)
        assertEquals(listOf(list[1]), pageModels(list, "META llama instruct").items)
        assertEquals(2, pageModels(list, "qwen").total)
    }

    @Test
    fun listeLimiteA200AvecLeTotalExact() {
        val big = (1..7500).map { fl("org$it/modele-$it") }
        val all = pageModels(big, "")
        assertEquals(MODEL_LIST_LIMIT, all.items.size)
        assertEquals(7500, all.total)
        assertTrue(all.truncated)
        // L'ordre du dépôt est conservé.
        assertEquals(big.take(MODEL_LIST_LIMIT), all.items)

        val some = pageModels(big, "modele-1")
        assertEquals(MODEL_LIST_LIMIT, some.items.size)
        assertEquals(1111, some.total) // 1, 10..19, 100..199, 1000..1999
        assertTrue(some.truncated)

        val few = pageModels(big, "modele-7499")
        assertEquals(1, few.items.size)
        assertFalse(few.truncated)
    }

    @Test
    fun recherchePerformanteSur7500Modeles() {
        val big = (1..7500).map { fl("org$it/modele-$it") }
        val start = System.nanoTime()
        repeat(20) { pageModels(big, "modele 9") }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("20 recherches en $ms ms", ms < 2000)
    }

    @Test
    fun sousTexteOrganisationEtContexteEffectif() {
        val plan = app.fwchat.domain.PlanInfo("Premium", 32768, 4, null)
        val big = ModelInfo("Qwen/Qwen3-32B", "Qwen3-32B", 131072, false, false, availableOnPlan = true, concurrencyCost = 2)
        assertEquals("Qwen · Contexte 32k", modelSubtitle(big, plan))
        assertEquals("Qwen · Contexte 128k", modelSubtitle(big, null))
        val fw = ModelInfo("accounts/fireworks/models/kimi-k3", "kimi", 262144, false, false)
        assertEquals("Contexte 256k", modelSubtitle(fw, plan))
        assertNull(modelOrg(fw))
        assertEquals("Qwen", modelOrg(big))
    }

    @Test
    fun avertissementPourModeleVerrouilleOuHorsPlan() {
        assertNull(modelWarning(null))
        assertNull(modelWarning(fl("a/b")))
        assertTrue(modelWarning(ModelInfo("a/b", "b", 0, false, false, gated = true))!!.contains("HuggingFace"))
        assertTrue(modelWarning(ModelInfo("a/b", "b", 0, false, false, availableOnPlan = false))!!.contains("hors de ton plan"))
        assertNull(modelWarning(ModelInfo("a/b", "b", 0, false, false, availableOnPlan = true)))
    }

    @Test
    fun descriptionAccessibleDesSegments() {
        assertEquals("Fireworks, sélectionné", providerSegmentDescription(app.fwchat.domain.Provider.FIREWORKS, true, true))
        assertEquals("Featherless, sans clé", providerSegmentDescription(app.fwchat.domain.Provider.FEATHERLESS, false, false))
        assertEquals("Featherless", providerSegmentDescription(app.fwchat.domain.Provider.FEATHERLESS, false, true))
    }

    @Test
    fun formatContexteEtEntiers() {
        assertEquals("128k", formatContext(131072))
        assertEquals("1M", formatContext(1048576))
        assertNull(formatContext(0))
        assertEquals("1 240".length, formatInt(1240).length)
    }

    @Test
    fun parametresPersonnalisesSiDifferentDesDefauts() {
        val defaults = GenParams(maxTokens = 16384)
        assertFalse(GenParamsLogic.isCustomized(defaults, defaults.copy()))
        assertTrue(GenParamsLogic.isCustomized(defaults.copy(temperature = 0.2), defaults))
        assertTrue(GenParamsLogic.isCustomized(defaults.copy(stop = listOf("x")), defaults))
        assertTrue(GenParamsLogic.isCustomized(defaults, defaults.copy(reasoningEffort = ReasoningEffort.LOW)))
    }

    @Test
    fun echecDeRechargementDesModelesEnFrancais() {
        assertEquals(
            "Clé API invalide ou manquante. Vérifie-la dans les réglages.",
            refreshErrorMessage(app.fwchat.domain.FireworksException.Unauthorized("401")),
        )
        assertEquals("boom", refreshErrorMessage(IllegalStateException("boom")))
        assertEquals("Échec du rechargement des modèles.", refreshErrorMessage(IllegalStateException()))
    }
}
