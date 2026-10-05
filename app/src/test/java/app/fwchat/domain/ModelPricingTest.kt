package app.fwchat.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelPricingTest {
    @Test fun bareme_palier_1() {
        for (id in listOf("nemotron-lightning-3p5-30b-a3b", "glm-5p3-flash", "gpt-oss-120b")) assertEquals(id, 1, costTier(id))
    }

    @Test fun bareme_palier_2() {
        for (id in listOf("deepseek-v4p1-flash", "minimax-m3", "nemotron-3-ultra-nvfp4")) assertEquals(id, 2, costTier(id))
    }

    @Test fun bareme_palier_3() {
        for (id in listOf("inkling", "glm-5p3", "glm-5p2", "qwen3p8-max")) assertEquals(id, 3, costTier(id))
    }

    @Test fun bareme_palier_4() {
        for (id in listOf("kimi-k3", "ember-1")) assertEquals(id, 4, costTier(id))
    }

    @Test fun id_complet_accepte() {
        assertEquals(3, costTier("accounts/fireworks/models/glm-5p3"))
        assertEquals("$$$", costIcons("accounts/fireworks/models/glm-5p3"))
    }

    @Test fun variante_fast_majoree() {
        val base = ModelPricing.priceOf("glm-5p3")!!
        val fast = ModelPricing.priceOf("glm-5p3-fast")!!
        assertEquals(base.inputPerM * 1.5, fast.inputPerM, 1e-9)
        assertEquals(base.outputPerM * 1.5, fast.outputPerM, 1e-9)
        // gpt-oss: mixte 0,49 x 1,5 = 0,73 -> passe de $ a $$
        assertEquals(2, costTier("gpt-oss-120b-fast"))
    }

    @Test fun inconnu_sans_prix() {
        assertNull(costTier("qwen3p8-2p4t-a95b"))
        assertNull(costIcons("modele-inconnu"))
        assertNull(costTier("modele-inconnu-fast"))
        assertNull(costDescription("modele-inconnu"))
    }

    @Test fun description_francaise() {
        assertEquals("Coût : faible", costDescription("gpt-oss-120b"))
        assertEquals("Coût : très élevé", costDescription("kimi-k3"))
    }
}
