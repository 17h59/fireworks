package app.fwchat.domain

/** Prix d'un modèle en USD par million de tokens. */
data class ModelPrice(val inputPerM: Double, val outputPerM: Double, val cachedInputPerM: Double) {
    /** Coût « mixte » indicatif: 1 part d'entrée pour 3 parts de sortie. */
    val blended: Double get() = (inputPerM + OUTPUT_WEIGHT * outputPerM) / (1 + OUTPUT_WEIGHT)

    private companion object {
        const val OUTPUT_WEIGHT = 3
    }
}

/**
 * Table de prix codée en dur (l'API des modèles ne fournit aucun prix). Valeurs INDICATIVES, vérifiées à la date
 * [PRICES_VERIFIED_ON]; elles servent uniquement à afficher des icônes « $ ».
 */
object ModelPricing {
    const val PRICES_VERIFIED_ON = "2026-10-05"
    const val PRICES_VERIFIED_LABEL = "5 octobre 2026"

    /** Seuils du coût mixte (USD / 1M tokens): < T1 = $, < T2 = $$, < T3 = $$$, sinon $$$$. */
    const val TIER_1_MAX = 0.6
    const val TIER_2_MAX = 2.5
    const val TIER_3_MAX = 8.0

    /** Majoration des variantes « -fast » par rapport au modèle de base. */
    const val FAST_MULTIPLIER = 1.5

    private val table: Map<String, ModelPrice> = mapOf(
        "deepseek-v4p1-flash" to ModelPrice(0.30, 1.20, 0.006),
        "ember-1" to ModelPrice(3.00, 15.00, 0.30),
        "glm-5p2" to ModelPrice(1.40, 4.40, 0.14),
        "glm-5p3" to ModelPrice(1.40, 4.40, 0.26),
        "glm-5p3-flash" to ModelPrice(0.15, 0.50, 0.03),
        "gpt-oss-120b" to ModelPrice(0.15, 0.60, 0.015),
        "inkling" to ModelPrice(1.00, 4.05, 0.17),
        "kimi-k3" to ModelPrice(3.00, 15.00, 0.30),
        "minimax-m3" to ModelPrice(0.30, 1.20, 0.06),
        "nemotron-3-ultra-nvfp4" to ModelPrice(0.60, 2.40, 0.12),
        "nemotron-lightning-3p5-30b-a3b" to ModelPrice(0.05, 0.20, 0.01),
        "qwen3p8-max" to ModelPrice(2.00, 6.00, 0.25),
        // qwen3p8-2p4t-a95b: prix inconnu, volontairement absent.
    )

    private fun shortId(id: String) = id.substringAfterLast('/')

    /** Prix du modèle ([id] complet ou court), ou null s'il est inconnu. Les variantes « -fast » valent ×1,5. */
    fun priceOf(id: String): ModelPrice? {
        val short = shortId(id)
        table[short]?.let { return it }
        if (short.endsWith("-fast")) {
            val base = table[short.removeSuffix("-fast")] ?: return null
            return ModelPrice(
                base.inputPerM * FAST_MULTIPLIER,
                base.outputPerM * FAST_MULTIPLIER,
                base.cachedInputPerM * FAST_MULTIPLIER,
            )
        }
        return null
    }
}

/** Palier de coût de 1 (économique) à 4 (très cher), ou null si le prix est inconnu. */
fun costTier(id: String): Int? {
    val mixed = ModelPricing.priceOf(id)?.blended ?: return null
    return when {
        mixed < ModelPricing.TIER_1_MAX -> 1
        mixed < ModelPricing.TIER_2_MAX -> 2
        mixed < ModelPricing.TIER_3_MAX -> 3
        else -> 4
    }
}

/** « $ » à « $$$$ », ou null si le prix est inconnu. */
fun costIcons(id: String): String? = costTier(id)?.let { "$".repeat(it) }

/** Traduction du palier pour l'accessibilité, ex. « Coût : élevé ». */
fun costDescription(id: String): String? = when (costTier(id)) {
    1 -> "Coût : faible"
    2 -> "Coût : moyen"
    3 -> "Coût : élevé"
    4 -> "Coût : très élevé"
    else -> null
}
