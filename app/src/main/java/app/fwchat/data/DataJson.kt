package app.fwchat.data

import app.fwchat.domain.GenParams
import kotlinx.serialization.json.Json

/**
 * Unique configuration JSON de la couche data (réseau, base, sauvegarde, réglages): tolérante en lecture
 * (clés inconnues, valeurs nulles ou énumérations inconnues ramenées aux défauts), complète en écriture.
 */
internal val DataJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    coerceInputValues = true
}

internal fun encodeParams(params: GenParams): String = DataJson.encodeToString(GenParams.serializer(), params)

/** null si le JSON est illisible (à l'appelant de choisir le repli). */
internal fun decodeParamsOrNull(json: String): GenParams? =
    try {
        DataJson.decodeFromString(GenParams.serializer(), json)
    } catch (_: Exception) {
        null
    }
