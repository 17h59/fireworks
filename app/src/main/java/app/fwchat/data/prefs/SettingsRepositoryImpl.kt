package app.fwchat.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.fwchat.domain.AppDefaults
import app.fwchat.domain.AppSettings
import app.fwchat.domain.GenParams
import app.fwchat.domain.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Réglages dans un DataStore Preferences; la clé API est chiffrée par [secretStore]
 * (le DataStore ne contient que le drapeau `hasApiKey`).
 */
class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: SecretStore,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            AppSettings(
                hasApiKey = p[HAS_API_KEY] ?: false,
                defaultModelId = p[DEFAULT_MODEL_ID],
                defaultSystemPromptId = p[DEFAULT_SYSTEM_PROMPT_ID],
                defaultParams = p[DEFAULT_PARAMS]?.let(::decodeParams) ?: AppDefaults.GEN_PARAMS,
            )
        }

    override suspend fun apiKey(): String? = try {
        secretStore.read()?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override suspend fun setApiKey(key: String?) {
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        secretStore.write(clean)
        dataStore.edit { it[HAS_API_KEY] = clean != null }
    }

    override suspend fun setDefaultModel(modelId: String?) {
        dataStore.edit { if (modelId == null) it.remove(DEFAULT_MODEL_ID) else it[DEFAULT_MODEL_ID] = modelId }
    }

    override suspend fun setDefaultSystemPrompt(promptId: String?) {
        dataStore.edit {
            if (promptId == null) it.remove(DEFAULT_SYSTEM_PROMPT_ID) else it[DEFAULT_SYSTEM_PROMPT_ID] = promptId
        }
    }

    override suspend fun setDefaultParams(params: GenParams) {
        dataStore.edit { it[DEFAULT_PARAMS] = json.encodeToString(GenParams.serializer(), params) }
    }

    private fun decodeParams(s: String): GenParams? =
        try {
            json.decodeFromString(GenParams.serializer(), s)
        } catch (e: Exception) {
            null
        }

    private companion object {
        val HAS_API_KEY = booleanPreferencesKey("has_api_key")
        val DEFAULT_MODEL_ID = stringPreferencesKey("default_model_id")
        val DEFAULT_SYSTEM_PROMPT_ID = stringPreferencesKey("default_system_prompt_id")
        val DEFAULT_PARAMS = stringPreferencesKey("default_params_json")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    }
}
