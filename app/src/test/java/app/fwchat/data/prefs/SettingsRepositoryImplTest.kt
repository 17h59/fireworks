package app.fwchat.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import app.fwchat.data.net.InMemorySecretStore
import app.fwchat.domain.AppDefaults
import app.fwchat.domain.GenParams
import app.fwchat.domain.Provider
import app.fwchat.domain.ReasoningEffort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryImplTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var secrets: InMemorySecretStore
    private lateinit var flSecrets: InMemorySecretStore
    private lateinit var repo: SettingsRepositoryImpl

    @Before
    fun setUp() {
        secrets = InMemorySecretStore()
        flSecrets = InMemorySecretStore()
        val store = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("settings.preferences_pb").also { it.delete() } }
        repo = SettingsRepositoryImpl(store, mapOf(Provider.FIREWORKS to secrets, Provider.FEATHERLESS to flSecrets))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaults() = runBlocking {
        val s = repo.settings.first()
        assertFalse(s.hasApiKey)
        assertNull(s.defaultModelId)
        assertNull(s.defaultSystemPromptId)
        assertEquals(AppDefaults.GEN_PARAMS, s.defaultParams)
        assertNull(repo.apiKey(Provider.FIREWORKS))
    }

    @Test
    fun apiKeyIsStoredThroughSecretStoreOnly() = runBlocking {
        repo.setApiKey(Provider.FIREWORKS, "  fw_secret  ")
        assertEquals("fw_secret", repo.apiKey(Provider.FIREWORKS))
        assertEquals("fw_secret", secrets.value)
        assertTrue(repo.settings.first().hasApiKey)

        repo.setApiKey(Provider.FIREWORKS, null)
        assertNull(repo.apiKey(Provider.FIREWORKS))
        assertNull(secrets.value)
        assertFalse(repo.settings.first().hasApiKey)

        repo.setApiKey(Provider.FIREWORKS, "   ")
        assertFalse(repo.settings.first().hasApiKey)
    }

    @Test
    fun defaultsRoundTrip() = runBlocking {
        val params = GenParams(temperature = 0.3, maxTokens = 2048, stop = listOf("x"), reasoningEffort = ReasoningEffort.MAX)
        repo.setDefaultModel(Provider.FIREWORKS, "accounts/fireworks/models/glm-5p3")
        repo.setDefaultSystemPrompt("prompt-1")
        repo.setDefaultParams(params)
        val s = repo.settings.first()
        assertEquals("accounts/fireworks/models/glm-5p3", s.defaultModelId)
        assertEquals("prompt-1", s.defaultSystemPromptId)
        assertEquals(params, s.defaultParams)

        repo.setDefaultModel(Provider.FIREWORKS, null)
        repo.setDefaultSystemPrompt(null)
        val s2 = repo.settings.first()
        assertNull(s2.defaultModelId)
        assertNull(s2.defaultSystemPromptId)
    }

    @Test
    fun globalParamsSurviveANewRepositoryOnTheSameFile() = runBlocking {
        val file = tmp.newFile("persist.preferences_pb").also { it.delete() }
        val params = GenParams(
            temperature = 0.7, topP = 0.9, maxTokens = 4096, stop = listOf("A", "B"),
            frequencyPenalty = 0.1, presencePenalty = 0.2, reasoningEffort = ReasoningEffort.NONE,
        )
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = SettingsRepositoryImpl(PreferenceDataStoreFactory.create(scope = scope1) { file }, mapOf(Provider.FIREWORKS to InMemorySecretStore(), Provider.FEATHERLESS to InMemorySecretStore()))
        first.setDefaultParams(params)
        scope1.cancel()
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val second = SettingsRepositoryImpl(PreferenceDataStoreFactory.create(scope = scope2) { file }, mapOf(Provider.FIREWORKS to InMemorySecretStore(), Provider.FEATHERLESS to InMemorySecretStore()))
        assertEquals(params, second.settings.first().defaultParams)
        scope2.cancel()
    }

    @Test
    fun unreadableKeyResetsFlagAndClearsBlobOnApiKey() = runBlocking {
        repo.setApiKey(Provider.FIREWORKS, "fw_secret")
        assertTrue(repo.settings.first().hasApiKey)

        secrets.unreadable = true
        assertNull(repo.apiKey(Provider.FIREWORKS))

        assertFalse(repo.settings.first().hasApiKey)
        assertNull(secrets.value)
        assertFalse(secrets.unreadable)
        assertNull(repo.apiKey(Provider.FIREWORKS))
    }

    @Test
    fun reconcileAtStartupResetsFlagWhenKeyIsUnreadable() = runBlocking {
        repo.setApiKey(Provider.FIREWORKS, "fw_secret")
        secrets.unreadable = true

        repo.reconcileApiKey()

        assertFalse(repo.settings.first().hasApiKey)
        assertNull(secrets.value)
        assertFalse(secrets.unreadable)
    }

    @Test
    fun reconcileAtStartupResetsFlagWhenBlobIsMissing() = runBlocking {
        repo.setApiKey(Provider.FIREWORKS, "fw_secret")
        secrets.value = null

        repo.reconcileApiKey()

        assertFalse(repo.settings.first().hasApiKey)
    }

    @Test
    fun reconcileKeepsFlagWhenKeyIsReadable() = runBlocking {
        repo.setApiKey(Provider.FIREWORKS, "fw_secret")
        repo.reconcileApiKey()
        assertTrue(repo.settings.first().hasApiKey)
        assertEquals("fw_secret", repo.apiKey(Provider.FIREWORKS))
    }

    @Test
    fun reconcileLeavesStateAloneOnTransientError() = runBlocking {
        repo.setApiKey(Provider.FIREWORKS, "fw_secret")
        val flaky = object : SecretStore {
            override suspend fun read(): String? = throw java.io.IOException("disque occupé")
            override suspend fun write(secret: String?) = Unit
        }
        val store = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s2.preferences_pb").also { it.delete() } }
        val repo2 = SettingsRepositoryImpl(store, mapOf(Provider.FIREWORKS to flaky, Provider.FEATHERLESS to InMemorySecretStore()))
        repo2.setApiKey(Provider.FIREWORKS, "x")
        repo2.reconcileApiKey()
        assertTrue(repo2.settings.first().hasApiKey)
        assertNull(repo2.apiKey(Provider.FIREWORKS))
        assertTrue(repo2.settings.first().hasApiKey)
    }

    @Test
    fun keysAreStoredPerProviderInSeparateBlobs() = runBlocking {
        repo.setApiKey(Provider.FIREWORKS, "fw_one")
        repo.setApiKey(Provider.FEATHERLESS, "  rc_two  ")
        assertEquals("fw_one", repo.apiKey(Provider.FIREWORKS))
        assertEquals("rc_two", repo.apiKey(Provider.FEATHERLESS))
        assertEquals("fw_one", secrets.value)
        assertEquals("rc_two", flSecrets.value)
        val s = repo.settings.first()
        assertEquals(setOf(Provider.FIREWORKS, Provider.FEATHERLESS), s.providersWithKey)
        assertTrue(s.hasApiKey)
    }

    @Test
    fun firstKeyBecomesActiveProviderWhenActiveHasNone() = runBlocking {
        assertEquals(Provider.FIREWORKS, repo.settings.first().activeProvider)
        repo.setApiKey(Provider.FEATHERLESS, "rc")
        val s = repo.settings.first()
        assertEquals(Provider.FEATHERLESS, s.activeProvider)
        assertEquals(setOf(Provider.FEATHERLESS), s.providersWithKey)
        // Une 2e clé ne change pas le fournisseur actif.
        repo.setApiKey(Provider.FIREWORKS, "fw")
        assertEquals(Provider.FEATHERLESS, repo.settings.first().activeProvider)
    }

    @Test
    fun existingFireworksKeyIsKeptAfterUpgrade() = runBlocking {
        // Etat d un utilisateur existant: drapeau et modele historiques, aucune cle Featherless, pas de fournisseur stocke.
        val file = tmp.newFile("legacy.preferences_pb").also { it.delete() }
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val legacySecret = InMemorySecretStore("fw_old")
        val ds = PreferenceDataStoreFactory.create(scope = scope1) { file }
        ds.edit {
            it[androidx.datastore.preferences.core.booleanPreferencesKey("has_api_key")] = true
            it[androidx.datastore.preferences.core.stringPreferencesKey("default_model_id")] = "accounts/fireworks/models/glm-5p3"
        }
        val upgraded = SettingsRepositoryImpl(ds, mapOf(Provider.FIREWORKS to legacySecret, Provider.FEATHERLESS to InMemorySecretStore()))
        val s = upgraded.settings.first()
        assertTrue(s.hasApiKey)
        assertEquals(Provider.FIREWORKS, s.activeProvider)
        assertEquals(setOf(Provider.FIREWORKS), s.providersWithKey)
        assertEquals("accounts/fireworks/models/glm-5p3", s.defaultModelId)
        assertEquals("fw_old", upgraded.apiKey(Provider.FIREWORKS))
        upgraded.reconcileApiKey()
        assertTrue(upgraded.settings.first().hasApiKey)
        scope1.cancel()
    }

    @Test
    fun removingActiveProviderKeyFallsBackToTheOtherOne() = runBlocking {
        repo.setApiKey(Provider.FIREWORKS, "fw")
        repo.setApiKey(Provider.FEATHERLESS, "rc")
        repo.setActiveProvider(Provider.FEATHERLESS)
        assertEquals(Provider.FEATHERLESS, repo.settings.first().activeProvider)

        repo.setApiKey(Provider.FEATHERLESS, null)
        val s = repo.settings.first()
        assertEquals(Provider.FIREWORKS, s.activeProvider)
        assertTrue(s.hasApiKey)
        assertEquals(setOf(Provider.FIREWORKS), s.providersWithKey)

        repo.setApiKey(Provider.FIREWORKS, null)
        assertFalse(repo.settings.first().hasApiKey)
    }

    @Test
    fun unreadableActiveKeySwitchesToTheOtherProviderOnReconcile() = runBlocking {
        repo.setApiKey(Provider.FIREWORKS, "fw")
        repo.setApiKey(Provider.FEATHERLESS, "rc")
        repo.setActiveProvider(Provider.FEATHERLESS)
        flSecrets.unreadable = true

        repo.reconcileApiKey()

        val s = repo.settings.first()
        assertEquals(Provider.FIREWORKS, s.activeProvider)
        assertEquals(setOf(Provider.FIREWORKS), s.providersWithKey)
        assertNull(flSecrets.value)
        assertEquals("fw", repo.apiKey(Provider.FIREWORKS))
    }

    @Test
    fun setActiveProviderIsRefusedWithoutKeyWhileTheOtherHasOne() = runBlocking {
        repo.setApiKey(Provider.FIREWORKS, "fw")
        repo.setActiveProvider(Provider.FEATHERLESS)
        assertEquals(Provider.FIREWORKS, repo.settings.first().activeProvider)
    }

    @Test
    fun defaultModelIsPerProviderAndFollowsTheActiveOne() = runBlocking {
        repo.setApiKey(Provider.FIREWORKS, "fw")
        repo.setApiKey(Provider.FEATHERLESS, "rc")
        repo.setDefaultModel(Provider.FIREWORKS, "accounts/fireworks/models/glm-5p3")
        repo.setDefaultModel(Provider.FEATHERLESS, "Qwen/Qwen3-4B")
        assertEquals("accounts/fireworks/models/glm-5p3", repo.settings.first().defaultModelId)
        repo.setActiveProvider(Provider.FEATHERLESS)
        val s = repo.settings.first()
        assertEquals("Qwen/Qwen3-4B", s.defaultModelId)
        assertEquals("accounts/fireworks/models/glm-5p3", s.defaultModelIds[Provider.FIREWORKS])
        repo.setDefaultModel(Provider.FEATHERLESS, null)
        assertNull(repo.settings.first().defaultModelId)
    }
}
