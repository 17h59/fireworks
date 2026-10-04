package app.fwchat.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.fwchat.data.net.InMemorySecretStore
import app.fwchat.domain.AppDefaults
import app.fwchat.domain.GenParams
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
    private lateinit var repo: SettingsRepositoryImpl

    @Before
    fun setUp() {
        secrets = InMemorySecretStore()
        val store = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("settings.preferences_pb").also { it.delete() } }
        repo = SettingsRepositoryImpl(store, secrets)
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
        assertNull(repo.apiKey())
    }

    @Test
    fun apiKeyIsStoredThroughSecretStoreOnly() = runBlocking {
        repo.setApiKey("  fw_secret  ")
        assertEquals("fw_secret", repo.apiKey())
        assertEquals("fw_secret", secrets.value)
        assertTrue(repo.settings.first().hasApiKey)

        repo.setApiKey(null)
        assertNull(repo.apiKey())
        assertNull(secrets.value)
        assertFalse(repo.settings.first().hasApiKey)

        repo.setApiKey("   ")
        assertFalse(repo.settings.first().hasApiKey)
    }

    @Test
    fun defaultsRoundTrip() = runBlocking {
        val params = GenParams(temperature = 0.3, maxTokens = 2048, stop = listOf("x"), reasoningEffort = ReasoningEffort.MAX)
        repo.setDefaultModel("accounts/fireworks/models/glm-5p3")
        repo.setDefaultSystemPrompt("prompt-1")
        repo.setDefaultParams(params)
        val s = repo.settings.first()
        assertEquals("accounts/fireworks/models/glm-5p3", s.defaultModelId)
        assertEquals("prompt-1", s.defaultSystemPromptId)
        assertEquals(params, s.defaultParams)

        repo.setDefaultModel(null)
        repo.setDefaultSystemPrompt(null)
        val s2 = repo.settings.first()
        assertNull(s2.defaultModelId)
        assertNull(s2.defaultSystemPromptId)
    }

    @Test
    fun unreadableKeyResetsFlagAndClearsBlobOnApiKey() = runBlocking {
        repo.setApiKey("fw_secret")
        assertTrue(repo.settings.first().hasApiKey)

        secrets.unreadable = true
        assertNull(repo.apiKey())

        assertFalse(repo.settings.first().hasApiKey)
        assertNull(secrets.value)
        assertFalse(secrets.unreadable)
        assertNull(repo.apiKey())
    }

    @Test
    fun reconcileAtStartupResetsFlagWhenKeyIsUnreadable() = runBlocking {
        repo.setApiKey("fw_secret")
        secrets.unreadable = true

        repo.reconcileApiKey()

        assertFalse(repo.settings.first().hasApiKey)
        assertNull(secrets.value)
        assertFalse(secrets.unreadable)
    }

    @Test
    fun reconcileAtStartupResetsFlagWhenBlobIsMissing() = runBlocking {
        repo.setApiKey("fw_secret")
        secrets.value = null

        repo.reconcileApiKey()

        assertFalse(repo.settings.first().hasApiKey)
    }

    @Test
    fun reconcileKeepsFlagWhenKeyIsReadable() = runBlocking {
        repo.setApiKey("fw_secret")
        repo.reconcileApiKey()
        assertTrue(repo.settings.first().hasApiKey)
        assertEquals("fw_secret", repo.apiKey())
    }

    @Test
    fun reconcileLeavesStateAloneOnTransientError() = runBlocking {
        repo.setApiKey("fw_secret")
        val flaky = object : SecretStore {
            override suspend fun read(): String? = throw java.io.IOException("disque occupé")
            override suspend fun write(secret: String?) = Unit
        }
        val store = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s2.preferences_pb").also { it.delete() } }
        val repo2 = SettingsRepositoryImpl(store, flaky)
        repo2.setApiKey("x")
        repo2.reconcileApiKey()
        assertTrue(repo2.settings.first().hasApiKey)
        assertNull(repo2.apiKey())
        assertTrue(repo2.settings.first().hasApiKey)
    }
}
