package app.fwchat.ui.common

import app.fwchat.domain.ModelInfo
import app.fwchat.domain.PlanInfo
import app.fwchat.domain.Provider
import app.fwchat.domain.effectiveContextLength

/** Nombre maximal de lignes affichées dans le sélecteur (la liste Featherless en compte ~7 500). */
const val MODEL_LIST_LIMIT = 200

/** Page de résultats: au plus [MODEL_LIST_LIMIT] modèles à afficher et le nombre total de correspondances. */
data class ModelPage(val items: List<ModelInfo>, val total: Int) {
    val truncated: Boolean get() = total > items.size

    companion object {
        val Empty = ModelPage(emptyList(), 0)
    }
}

/**
 * Filtre pur (à appeler hors du thread principal): tous les mots de [query] doivent figurer dans l'id ou le nom
 * (insensible à la casse). L'ordre du dépôt est conservé; [ModelPage.total] compte toutes les correspondances.
 */
fun pageModels(models: List<ModelInfo>, query: String, limit: Int = MODEL_LIST_LIMIT): ModelPage {
    val terms = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    if (terms.isEmpty()) {
        return ModelPage(if (models.size > limit) models.subList(0, limit) else models, models.size)
    }
    val out = ArrayList<ModelInfo>(minOf(limit, 64))
    var total = 0
    for (m in models) {
        val key = m.searchKey
        if (terms.all { it in key }) {
            if (out.size < limit) out += m
            total++
        }
    }
    return ModelPage(out, total)
}

/** Organisation (préfixe de l'id « org/nom ») d'un modèle Featherless; null pour Fireworks ou sans préfixe. */
fun modelOrg(m: ModelInfo): String? =
    if (m.provider == Provider.FEATHERLESS) m.id.substringBeforeLast('/', "").ifEmpty { null } else null

/** Contexte utilisable formaté (« 32k »), null si inconnu. Borné par le plan pour Featherless. */
fun effectiveContextLabel(m: ModelInfo, plan: PlanInfo?): String? =
    formatContext(m.effectiveContextLength(if (m.provider == Provider.FEATHERLESS) plan else null))

/** Sous-texte d'une ligne du sélecteur: « organisation · Contexte 32k » (cadenas et hors plan sont gérés à part). */
fun modelSubtitle(m: ModelInfo, plan: PlanInfo?): String {
    val ctx = effectiveContextLabel(m, plan)?.let { "Contexte $it" } ?: "Contexte inconnu"
    val org = modelOrg(m)
    return if (org != null) "$org · $ctx" else ctx
}

const val GATED_HINT = "Connecte HuggingFace sur featherless.ai"
const val NOT_IN_PLAN_LABEL = "Hors de ton plan"

/** Avertissement à afficher quand on choisit un modèle verrouillé ou hors plan (l'API renverra l'erreur détaillée). */
fun modelWarning(m: ModelInfo?): String? = when {
    m == null -> null
    m.gated -> "Modèle verrouillé: connecte ton compte HuggingFace sur featherless.ai avant de l'utiliser."
    m.availableOnPlan == false -> "Ce modèle est hors de ton plan Featherless: l'envoi sera probablement refusé."
    else -> null
}
