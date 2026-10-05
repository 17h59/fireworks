package app.fwchat.data.net

import app.fwchat.domain.GenParams
import app.fwchat.domain.PromptFamily
import app.fwchat.domain.ReasoningEffort

/** Adaptation pure des paramètres globaux au modèle ciblé (testable sans réseau). */
object GenParamsCompat {
    /** GLM et gpt-oss refusent `reasoning_effort = none` (HTTP 400). */
    fun supportsNoReasoning(modelId: String?): Boolean {
        if (modelId == null) return true
        val family = PromptFamily.fromModelId(modelId)
        return family != PromptFamily.GLM && family != PromptFamily.GPT_OSS
    }

    /** Si l'effort est NONE et que le modèle ne le supporte pas, il n'est pas envoyé (défaut du modèle). */
    fun forModel(params: GenParams, modelId: String?): GenParams =
        if (params.reasoningEffort == ReasoningEffort.NONE && !supportsNoReasoning(modelId)) {
            params.copy(reasoningEffort = null)
        } else {
            params
        }
}
