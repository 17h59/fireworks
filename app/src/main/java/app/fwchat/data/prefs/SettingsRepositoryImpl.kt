package app.fwchat.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.fwchat.data.decodeParamsOrNull
import app.fwchat.data.encodeParams
import app.fwchat.domain.AppDefaults
import app.fwchat.domain.AppSettings
import app.fwchat.domain.GenParams
import app.fwchat.domain.Provider
import app.fwchat.domain.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Réglages dans un DataStore Preferences; chaque clé API est chiffrée par le [SecretStore] de son fournisseur
 * (le DataStore ne contient que les drapeaux « a une clé »). La clé Fireworks garde son drapeau, son blob et son
 * modèle par défaut historiques: un utilisateur existant garde sa clé sans migration.
 */
class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val secretStores: Map<Provider, SecretStore>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val withKey = Provider.entries.filter { p[hasKeyKey(it)] ?: false }.toSet()
            val active = effectiveActive(p[ACTIVE_PROVIDER]?.let(::parseProvider), withKey)
            val models = Provider.entries.associateWith { p[defaultModelKey(it)] }
            AppSettings(
                hasApiKey = withKey.isNotEmpty(),
                defaultModelId = models[active],
                defaultSystemPromptId = p[DEFAULT_SYSTEM_PROMPT_ID],
                defaultParams = p[DEFAULT_PARAMS]?.let(::decodeParamsOrNull) ?: AppDefaults.GEN_PARAMS,
                activeProvider = active,
                providersWithKey = withKey,
                defaultModelIds = models,
            )
        }

    override suspend fun apiKey(provider: Provider): String? = try {
        store(provider).read()?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: UnreadableSecretException) {
        // Clé Keystore perdue: on efface le blob et le drapeau, le fournisseur n'a plus de clé.
        dropUnreadableKey(provider)
        null
    } catch (e: Exception) {
        null
    }

    /**
     * À appeler au démarrage: si le drapeau « a une clé » d'un fournisseur est vrai alors qu'aucune clé n'est lisible
     * (blob illisible ou absent), le remet à faux; le fournisseur actif bascule sur l'autre s'il en a une, sinon l'app
     * retombe sur l'onboarding au lieu de boucler sur des erreurs 401. Une erreur transitoire (E/S, Keystore
     * indisponible) ne change rien.
     */
    suspend fun reconcileApiKey() {
        for (provider in Provider.entries) reconcile(provider)
    }

    private suspend fun reconcile(provider: Provider) {
        val flagged = try {
            dataStore.data.first()[hasKeyKey(provider)] ?: false
        } catch (e: IOException) {
            return
        }
        if (!flagged) return
        val readable = try {
            !store(provider).read().isNullOrBlank()
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnreadableSecretException) {
            dropUnreadableKey(provider)
            return
        } catch (e: Exception) {
            return
        }
        if (!readable) {
            dataStore.edit {
                it[hasKeyKey(provider)] = false
                fixActive(it)
            }
        }
    }

    private suspend fun dropUnreadableKey(provider: Provider) {
        try {
            store(provider).write(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Suppression impossible: le drapeau est quand même remis à faux.
        }
        dataStore.edit {
            it[hasKeyKey(provider)] = false
            fixActive(it)
        }
    }

    override suspend fun setApiKey(provider: Provider, key: String?) {
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        store(provider).write(clean)
        dataStore.edit {
            it[hasKeyKey(provider)] = clean != null
            fixActive(it, preferred = provider)
        }
    }

    override suspend fun setActiveProvider(provider: Provider) {
        dataStore.edit {
            val withKey = Provider.entries.filter { p -> it[hasKeyKey(p)] ?: false }.toSet()
            // Sans clé pour ce fournisseur, la bascule est refusée tant qu'un autre en a une (l'UI demande d'abord la clé).
            if (withKey.isEmpty() || provider in withKey) it[ACTIVE_PROVIDER] = provider.name
        }
    }

    /** Si le fournisseur actif n'a plus de clé, bascule sur [preferred] (s'il en a une) puis sur un autre qui en a une. */
    private fun fixActive(p: MutablePreferences, preferred: Provider? = null) {
        val withKey = Provider.entries.filter { p[hasKeyKey(it)] ?: false }.toSet()
        val stored = p[ACTIVE_PROVIDER]?.let(::parseProvider) ?: Provider.FIREWORKS
        if (withKey.isEmpty() || stored in withKey) return
        val next = preferred?.takeIf { it in withKey } ?: withKey.first()
        p[ACTIVE_PROVIDER] = next.name
    }

    override suspend fun setDefaultModel(provider: Provider, modelId: String?) {
        dataStore.edit {
            val k = defaultModelKey(provider)
            if (modelId == null) it.remove(k) else it[k] = modelId
        }
    }

    override suspend fun setDefaultSystemPrompt(promptId: String?) {
        dataStore.edit {
            if (promptId == null) it.remove(DEFAULT_SYSTEM_PROMPT_ID) else it[DEFAULT_SYSTEM_PROMPT_ID] = promptId
        }
    }

    override suspend fun setDefaultParams(params: GenParams) {
        dataStore.edit { it[DEFAULT_PARAMS] = encodeParams(params) }
    }

    private fun store(provider: Provider): SecretStore =
        secretStores[provider] ?: throw IllegalStateException("Pas de stockage de clé pour $provider")

    private companion object {
        val DEFAULT_SYSTEM_PROMPT_ID = stringPreferencesKey("default_system_prompt_id")
        val DEFAULT_PARAMS = stringPreferencesKey("default_params_json")
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")

        // Clés historiques conservées pour Fireworks (migration transparente).
        fun hasKeyKey(p: Provider) = when (p) {
            Provider.FIREWORKS -> booleanPreferencesKey("has_api_key")
            Provider.FEATHERLESS -> booleanPreferencesKey("has_api_key_featherless")
        }

        fun defaultModelKey(p: Provider) = when (p) {
            Provider.FIREWORKS -> stringPreferencesKey("default_model_id")
            Provider.FEATHERLESS -> stringPreferencesKey("default_model_id_featherless")
        }

        fun parseProvider(name: String): Provider? = Provider.entries.firstOrNull { it.name == name }

        /** Le fournisseur stocké, sauf s'il n'a pas de clé alors qu'un autre en a une. */
        fun effectiveActive(stored: Provider?, withKey: Set<Provider>): Provider {
            val s = stored ?: Provider.FIREWORKS
            return if (withKey.isEmpty() || s in withKey) s else withKey.first()
        }
    }
}
