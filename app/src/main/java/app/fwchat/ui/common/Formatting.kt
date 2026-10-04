package app.fwchat.ui.common

import java.text.NumberFormat
import java.util.Locale

/**
 * Formate une taille de contexte: 131072 -> "128k", 1048576 -> "1M", 200000 -> "200k".
 * 0 (ou négatif) = inconnu -> null.
 */
fun formatContext(tokens: Int): String? {
    if (tokens <= 0) return null
    if (tokens >= 1_000_000) {
        if (tokens % 1_048_576 == 0) return "${tokens / 1_048_576}M"
        val rounded = Math.round(tokens / 100_000.0) / 10.0
        return if (rounded == Math.floor(rounded)) "${rounded.toInt()}M" else "${rounded}M".replace('.', ',')
    }
    if (tokens < 1000) return tokens.toString()
    if (tokens % 1024 == 0) {
        val k = tokens / 1024
        if (k and (k - 1) == 0) return "${k}k"
    }
    return "${Math.round(tokens / 1000.0)}k"
}

/** Entier avec séparateur de milliers français: 1240 -> "1 240". */
fun formatInt(n: Int): String = NumberFormat.getIntegerInstance(Locale.FRANCE).format(n)
