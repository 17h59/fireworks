package app.fwchat.domain

import kotlinx.coroutines.flow.StateFlow

/** Erreur lisible de la dernière lecture des dépenses. */
enum class BillingError { UNAUTHORIZED, NETWORK, OTHER }

data class BillingState(
    /** Dépenses du mois en cours (USD), null si jamais lues. */
    val monthSpendUsd: Double? = null,
    /** Plafond mensuel du compte, null s'il est absent ou absurdement grand (>= 1 000 000). */
    val monthlyCapUsd: Double? = null,
    /** Crédit estimé = solde saisi - dépenses depuis la saisie; null sans solde saisi. */
    val estimatedCreditUsd: Double? = null,
    /** Solde saisi par l'utilisateur (USD). */
    val enteredBalance: Double? = null,
    /** Instant (ms epoch) de la dernière lecture réussie. */
    val lastUpdated: Long? = null,
    val loading: Boolean = false,
    val error: BillingError? = null,
)

/** Dépenses du compte Fireworks (lecture seule) et estimation du crédit restant. */
interface BillingRepository {
    val state: StateFlow<BillingState>

    /** Relit les dépenses; ne lève jamais (l'échec est dans [BillingState.error]). */
    suspend fun refresh()

    /** Enregistre le solde actuel (null = l'oublier) puis rafraîchit. */
    suspend fun setBalance(usd: Double?)
}
