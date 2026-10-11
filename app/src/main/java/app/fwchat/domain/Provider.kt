package app.fwchat.domain

/** Fournisseur d'inférence. Celui d'un chat se déduit de son modèle ([fromModelId]), sans migration. */
enum class Provider(val label: String) {
    FIREWORKS("Fireworks"),
    FEATHERLESS("Featherless");

    companion object {
        /** Les ids Fireworks commencent par « accounts/ »; tout le reste (ids HuggingFace « org/nom ») est Featherless. */
        fun fromModelId(id: String): Provider = if (id.startsWith("accounts/")) FIREWORKS else FEATHERLESS
    }
}

/** Plan Featherless (`GET /v1/plan`). */
data class PlanInfo(
    val name: String,
    /** Borne le contexte effectif de tous les modèles. */
    val maxContextLength: Int?,
    /** Unités de concurrence simultanées. */
    val concurrency: Int?,
    val credits: Double?,
)
