package app.fwchat.ui.common

import app.fwchat.data.net.GenParamsCompat
import app.fwchat.domain.GenParams
import app.fwchat.domain.ReasoningEffort
import java.util.Locale

/** Logique pure de l'éditeur de paramètres (testable sans Compose). */
object GenParamsLogic {
    const val MAX_STOP = 4

    // Valeurs proposées quand l'utilisateur quitte l'état « Défaut du modèle » d'un paramètre.
    const val INITIAL_TEMPERATURE = 1.0
    const val INITIAL_TOP_P = 1.0
    const val INITIAL_TOP_K = 40
    const val INITIAL_MIN_P = 0.05
    const val INITIAL_MAX_TOKENS = 16384
    const val INITIAL_PENALTY = 0.0
    const val INITIAL_REPETITION = 1.0
    const val INITIAL_SEED = 0L

    /** Certains modèles refusent reasoning_effort = none. */
    fun supportsNoReasoning(modelId: String?): Boolean = GenParamsCompat.supportsNoReasoning(modelId)

    /** Valeur globale « Aucun » ignorée par le modèle (glm / gpt-oss): aide discrète dans l'éditeur. */
    fun noReasoningIgnored(params: GenParams, modelId: String?): Boolean =
        params.reasoningEffort == ReasoningEffort.NONE && !supportsNoReasoning(modelId)

    /** Options de la rangée de chips (null = « Défaut », non envoyé), dans l'ordre d'affichage. */
    fun reasoningOptions(modelId: String?): List<ReasoningEffort?> = buildList {
        add(null)
        if (supportsNoReasoning(modelId)) add(ReasoningEffort.NONE)
        add(ReasoningEffort.LOW)
        add(ReasoningEffort.MEDIUM)
        add(ReasoningEffort.HIGH)
        add(ReasoningEffort.MAX)
    }

    fun reasoningLabel(effort: ReasoningEffort?): String = when (effort) {
        null -> "Défaut"
        ReasoningEffort.NONE -> "Aucun"
        ReasoningEffort.LOW -> "Bas"
        ReasoningEffort.MEDIUM -> "Moyen"
        ReasoningEffort.HIGH -> "Élevé"
        ReasoningEffort.MAX -> "Max"
    }

    /** Ajoute un mot d'arrêt (trim, non vide, sans doublon, 4 max). */
    fun addStop(params: GenParams, word: String): GenParams {
        val w = word.trim()
        if (w.isEmpty() || w in params.stop || params.stop.size >= MAX_STOP) return params
        return params.copy(stop = params.stop + w)
    }

    fun removeStop(params: GenParams, word: String): GenParams = params.copy(stop = params.stop - word)

    /** Les paramètres du chat diffèrent-ils des défauts globaux (pastille sur l'icône des paramètres)? */
    fun isCustomized(params: GenParams, defaults: GenParams): Boolean = params != defaults

    /** Texte tapé -> entier positif, null si vide ou invalide. */
    fun parseInt(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it >= 0 }

    fun parseLong(text: String): Long? = text.trim().toLongOrNull()

    /** Arrondit au pas donné (curseurs): évite 0.30000000000000004. */
    fun snap(value: Double, step: Double): Double {
        val snapped = Math.round(value / step) * step
        return Math.round(snapped * 1000.0) / 1000.0
    }

    /** Valeur décimale affichée (2 décimales, virgule française). */
    fun formatDecimal(v: Double): String = String.format(Locale.FRANCE, "%.2f", v)
}
