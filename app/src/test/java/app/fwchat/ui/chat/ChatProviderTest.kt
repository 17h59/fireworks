package app.fwchat.ui.chat

import app.cash.turbine.test
import app.fwchat.data.net.toUserMessage
import app.fwchat.domain.Chat
import app.fwchat.domain.FireworksException
import app.fwchat.domain.GenParams
import app.fwchat.domain.PlanInfo
import app.fwchat.domain.Provider
import app.fwchat.ui.FakeModelRepo
import app.fwchat.ui.FakePromptRepo
import app.fwchat.ui.FakeSettingsRepo
import app.fwchat.ui.flModel
import app.fwchat.ui.model
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Chat et brouillon face aux deux fournisseurs. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatProviderTest {

    private val dispatcher = StandardTestDispatcher()
    private val log = mutableListOf<String>()
    private val repo = MemChatRepo(log)
    private val engine = RecordingEngine(log)
    private val fw = Provider.FIREWORKS
    private val fl = Provider.FEATHERLESS

    private val glm = model("glm-5p3")
    private val kimi = model("kimi-k3")
    private val qwen = flModel("Qwen/Qwen3-8B")
    private val llama = flModel("meta-llama/Llama-3.1-8B-Instruct")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** Deux clés, Fireworks actif par défaut; listes préchargées pour les deux fournisseurs. */
    private suspend fun env(activeFl: Boolean = false): Pair<FakeSettingsRepo, FakeModelRepo> {
        val settings = FakeSettingsRepo(key = "fw_key", defaultModel = glm.id)
        settings.setApiKey(fl, "fl_key")
        settings.setDefaultModel(fl, qwen.id)
        if (activeFl) settings.setActiveProvider(fl)
        val models = FakeModelRepo().follow(settings)
        models.preload(fw, listOf(glm, kimi))
        models.preload(fl, listOf(qwen, llama))
        return settings to models
    }

    private fun TestScope.vm(chatId: String?, settings: FakeSettingsRepo, models: FakeModelRepo) = ChatViewModel(
        chats = repo,
        prompts = FakePromptRepo(),
        settings = settings,
        models = models,
        engine = engine,
        chatId = chatId,
        appScope = CoroutineScope(dispatcher + SupervisorJob()),
    )

    private fun chatWith(modelId: String): String {
        val id = "chat-$modelId"
        repo.chats[id] = Chat(id, "Titre", modelId, null, null, GenParams(maxTokens = 100), null, 1, 1)
        return id
    }

    // ------------------------------------------------------------------ brouillon

    @Test
    fun brouillon_prendLeModeleParDefautDuFournisseurActif() = runTest(dispatcher) {
        val (settings, models) = env(activeFl = true)
        val vm = vm(null, settings, models)
        advanceUntilIdle()
        assertEquals(qwen.id, vm.state.value.modelId)
        assertEquals(fl, vm.state.value.activeProvider)
        assertEquals(listOf(qwen, llama), vm.state.value.models)
    }

    @Test
    fun brouillon_sansDefautPrendLePremierDeLaListeDuFournisseurActif() = runTest(dispatcher) {
        val (settings, models) = env(activeFl = true)
        settings.setDefaultModel(fl, null)
        val vm = vm(null, settings, models)
        advanceUntilIdle()
        assertEquals(qwen.id, vm.state.value.modelId)
    }

    @Test
    fun brouillon_suitLeFournisseurActif() = runTest(dispatcher) {
        val (settings, models) = env()
        val vm = vm(null, settings, models)
        advanceUntilIdle()
        assertEquals(glm.id, vm.state.value.modelId)

        // Changement de mode depuis le sélecteur: le brouillon passe au défaut du nouveau fournisseur.
        vm.setActiveProvider(fl)
        advanceUntilIdle()
        assertEquals(qwen.id, vm.state.value.modelId)
        assertEquals(fl, vm.state.value.activeProvider)
        assertEquals(listOf(qwen, llama), vm.state.value.models)

        vm.setActiveProvider(fw)
        advanceUntilIdle()
        assertEquals(glm.id, vm.state.value.modelId)
    }

    @Test
    fun brouillon_changementDeModeSansDefautPrendLePremier() = runTest(dispatcher) {
        val (settings, models) = env()
        settings.setDefaultModel(fl, null)
        val vm = vm(null, settings, models)
        advanceUntilIdle()
        vm.setActiveProvider(fl)
        advanceUntilIdle()
        assertEquals(qwen.id, vm.state.value.modelId)
    }

    @Test
    fun brouillon_choixDuModeleEstConserveSiLeFournisseurNeChangePas() = runTest(dispatcher) {
        val (settings, models) = env()
        val vm = vm(null, settings, models)
        advanceUntilIdle()
        vm.setModel(kimi.id)
        advanceUntilIdle()
        assertEquals(kimi.id, vm.state.value.modelId)
    }

    @Test
    fun setActiveProviderRefuseSansCle() = runTest(dispatcher) {
        val settings = FakeSettingsRepo(key = "fw_key", defaultModel = glm.id)
        val models = FakeModelRepo(listOf(glm)).follow(settings).also { it.preload(fw, listOf(glm)) }
        val vm = vm(null, settings, models)
        advanceUntilIdle()
        vm.setActiveProvider(fl)
        advanceUntilIdle()
        assertEquals(fw, vm.state.value.activeProvider)
        assertEquals(glm.id, vm.state.value.modelId)
    }

    // ------------------------------------------------------------------ chat existant

    @Test
    fun chatFeatherlessOuvertEnModeFireworksAfficheSonModeleNormalement() = runTest(dispatcher) {
        val (settings, models) = env()
        val id = chatWith(qwen.id)
        val vm = vm(id, settings, models)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(qwen.id, s.modelId)
        assertTrue("modèle disponible via la liste de son fournisseur", s.modelAvailable)
        assertEquals(fw, s.activeProvider)
        // La liste du sélecteur reste celle du fournisseur actif.
        assertEquals(listOf(glm, kimi), s.models)
    }

    @Test
    fun chatFireworksOuvertEnModeFeatherlessAfficheSonModele() = runTest(dispatcher) {
        val (settings, models) = env(activeFl = true)
        val id = chatWith(glm.id)
        val vm = vm(id, settings, models)
        advanceUntilIdle()
        assertTrue(vm.state.value.modelAvailable)
        assertEquals(listOf(qwen, llama), vm.state.value.models)
    }

    @Test
    fun modeleDisparuDeLaListeDeSonFournisseurEstIndisponible() = runTest(dispatcher) {
        val (settings, models) = env()
        val id = chatWith("Org/Disparu")
        val vm = vm(id, settings, models)
        advanceUntilIdle()
        assertFalse(vm.state.value.modelAvailable)
    }

    @Test
    fun listeDuFournisseurDuModeleVideNeSignalePasIndisponible() = runTest(dispatcher) {
        val settings = FakeSettingsRepo(key = "fw_key", defaultModel = glm.id)
        val models = FakeModelRepo().follow(settings).also { it.preload(fw, listOf(glm)) }
        val id = chatWith("Org/Pas-encore-charge")
        val vm = vm(id, settings, models)
        advanceUntilIdle()
        assertTrue(vm.state.value.modelAvailable)
    }

    @Test
    fun planFeatherlessEstExposePourLeContexteEffectif() = runTest(dispatcher) {
        val (settings, models) = env(activeFl = true)
        models.planState.value = PlanInfo("Premium", 32768, 4, null)
        val vm = vm(null, settings, models)
        advanceUntilIdle()
        assertEquals(32768, vm.state.value.plan?.maxContextLength)
    }

    @Test
    fun choisirUnModeleVerrouilleOuHorsPlanPrevientMaisEstAutorise() = runTest(dispatcher) {
        val (settings, models) = env(activeFl = true)
        val gated = flModel("meta/secret", gated = true)
        val offPlan = flModel("big/model", availableOnPlan = false)
        models.preload(fl, listOf(qwen, gated, offPlan))
        val id = chatWith(qwen.id)
        val vm = vm(id, settings, models)
        advanceUntilIdle()
        vm.events.test {
            vm.setModel(gated.id)
            advanceUntilIdle()
            val n1 = awaitItem() as ChatUiEvent.Notice
            assertTrue(n1.message.contains("verrouillé"))
            vm.setModel(offPlan.id)
            advanceUntilIdle()
            val n2 = awaitItem() as ChatUiEvent.Notice
            assertTrue(n2.message.contains("hors de ton plan"))
            vm.setModel(qwen.id)
            advanceUntilIdle()
            expectNoEvents()
        }
        assertEquals(qwen.id, repo.settingsCalls.last().modelId)
    }

    @Test
    fun rechargementDesModelesCibleLeFournisseurActifEtLePlan() = runTest(dispatcher) {
        val (settings, models) = env(activeFl = true)
        val vm = vm(null, settings, models)
        advanceUntilIdle()
        assertTrue(vm.refreshModels().isSuccess)
        assertEquals(listOf(fl), models.refreshed)
        assertEquals(1, models.planRefreshCount)
        assertNull(models.planFailure)
    }

    // ------------------------------------------------------------------ erreurs

    @Test
    fun erreursFeatherlessSontClassees() {
        fun kind(e: FireworksException) = ErrorKinds.classify(e.toUserMessage())
        assertEquals(ErrorKind.MODEL_GATED, kind(FireworksException.ModelGated("https://huggingface.co/x", "g")))
        assertEquals(ErrorKind.MODEL_NOT_IN_PLAN, kind(FireworksException.ModelNotInPlan("p")))
        assertEquals(ErrorKind.MODEL_COLD, kind(FireworksException.ModelCold("c")))
        assertEquals(ErrorKind.NO_CAPACITY, kind(FireworksException.NoCapacity("n")))
        assertEquals(ErrorKind.CONCURRENCY, kind(FireworksException.RateLimited("r", null, concurrency = true)))
        // Un RateLimited classique (Fireworks) reste « autre » (bouton Réessayer).
        assertEquals(ErrorKind.OTHER, kind(FireworksException.RateLimited("r", null)))
        // Les anciens types ne changent pas.
        assertEquals(ErrorKind.MODEL_NOT_FOUND, kind(FireworksException.ModelNotFound("x")))
        assertEquals(ErrorKind.INVALID_KEY, kind(FireworksException.Unauthorized("x")))
    }

    @Test
    fun urlDuMessageDeModeleVerrouille() {
        val gated = FireworksException.ModelGated("https://featherless.ai/account/integrations", "g").toUserMessage()
        assertEquals("https://featherless.ai/account/integrations", ErrorKinds.extractUrl(gated))
        assertEquals("https://x.org/a", ErrorKinds.extractUrl("Voir https://x.org/a."))
        assertNull(ErrorKinds.extractUrl(FireworksException.ModelGated(null, "g").toUserMessage()))
        assertNull(ErrorKinds.extractUrl(null))
    }
}
