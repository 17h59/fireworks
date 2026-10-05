package app.fwchat.ui.settings

import app.fwchat.domain.BillingError
import java.text.NumberFormat
import java.util.Locale

/** « 12,34 $ » (format français, 2 décimales; 3 pour les très petits montants non nuls). */
fun formatUsd(amount: Double): String {
    val digits = if (amount != 0.0 && amount < 0.01) 3 else 2
    val nf = NumberFormat.getNumberInstance(Locale.FRANCE).apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }
    return nf.format(amount).replace(' ', ' ').replace(' ', ' ') + " $"
}

/** Lit un solde saisi (« 12,5 », « 12.50 », « 1 234,5 $ »); null si invalide ou négatif. */
fun parseBalanceInput(text: String): Double? {
    val cleaned = text.trim().replace("$", "").filterNot { it.isWhitespace() }.replace(',', '.')
    if (cleaned.isEmpty()) return null
    val v = cleaned.toDoubleOrNull() ?: return null
    return v.takeIf { it.isFinite() && it >= 0.0 }
}

/** Message d'erreur lisible pour la carte « Dépenses & crédit ». */
fun billingErrorMessage(error: BillingError): String = when (error) {
    BillingError.UNAUTHORIZED -> "Impossible de lire les dépenses : clé API refusée."
    BillingError.NETWORK -> "Impossible de lire les dépenses : pas de connexion."
    BillingError.OTHER -> "Impossible de lire les dépenses."
}
