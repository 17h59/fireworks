package app.fwchat.ui.shell

import app.fwchat.domain.FireworksException
import app.fwchat.domain.Provider
import app.fwchat.ui.FakeModelRepo
import app.fwchat.ui.FakeSettingsRepo
import app.fwchat.ui.flModel
import app.fwchat.ui.model
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val fw = Provider.FIREWORKS
    private val fl = Provider.FEATHERLESS

    @Test
    fun `pickDefaultModel prefere glm-5p3-flash puis gpt-oss-120b puis le premier`() {
        val glm = model("glm-5p3-flash")
        val oss = model("gpt-oss-120b")
        val other = model("kimi-k3")
        assertEquals(glm.id, pickDefaultModel(listOf(other, oss, glm)))
        assertEquals(oss.id, pickDefaultModel(listOf(other, oss, model("glm-5p3"))))
        assertEquals(other.id, pickDefaultModel(listOf(other, model("qwen3p8-max"))))
        assertNull(pickDefaultModel(emptyList()))
    }

    @Test
    fun `pickDefaultModel Featherless prefere un petit modele connu utilisable`() {
        val gatedSmall = flModel("meta-llama/Llama-3.1-8B-Instruct", gated = true)
        val offPlan = flModel("Qwen/Qwen2.5-7B-Instruct", availableOnPlan = false)
        val big = flModel("Qwen/Qwen3-32B", concurrencyCost = 2)
        val small = flModel("Qwen/Qwen3-8B")
        val first = flModel("aaa/premier")
        // Verrouillé et hors plan sont écartés; le petit modèle connu l'emporte sur le simple premier.
        assertEquals(small.id, pickDefaultModel(listOf(gatedSmall, offPlan, first, big, small), fl))
        // Sans modèle connu: le premier utilisable.
        assertEquals(first.id, pickDefaultModel(listOf(gatedSmall, offPlan, first, big), fl))
        // Rien d'utilisable: le premier de la liste.
        assertEquals(gatedSmall.id, pickDefaultModel(listOf(gatedSmall, offPlan), fl))
        assertNull(pickDefaultModel(emptyList(), fl))
    }

    @Test
    fun `succes enregistre la cle charge les modeles et choisit le modele par defaut`() = runTest(dispatcher) {
        val settings = FakeSettingsRepo()
        val models = FakeModelRepo(listOf(model("kimi-k3"), model("glm-5p3-flash")))
        val vm = OnboardingViewModel(settings, models)

        vm.submit(fw, "  fw_secret  ")
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()

        assertTrue(vm.state.value.done)
        assertNull(vm.state.value.error)
        assertEquals("fw_secret", settings.key)
        assertEquals(1, models.refreshCount)
        assertEquals(listOf(fw), models.refreshed)
        assertEquals("accounts/fireworks/models/glm-5p3-flash", settings.current.defaultModelId)
        assertEquals(fw, settings.current.activeProvider)
    }

    @Test
    fun `succes Featherless active le mode Featherless et choisit un modele du plan`() = runTest(dispatcher) {
        val settings = FakeSettingsRepo()
        val models = FakeModelRepo().follow(settings)
        models.lists[fl] = listOf(flModel("zzz/verrouille", gated = true), flModel("Qwen/Qwen3-8B"), flModel("aaa/autre"))
        val vm = OnboardingViewModel(settings, models)

        vm.submit(fl, "fl_secret")
        advanceUntilIdle()

        assertTrue(vm.state.value.done)
        assertEquals("fl_secret", settings.keyOf(fl))
        assertNull(settings.keyOf(fw))
        assertEquals(listOf(fl), models.refreshed)
        assertEquals(fl, settings.current.activeProvider)
        assertEquals("Qwen/Qwen3-8B", settings.current.defaultModelIds[fl])
        assertEquals("Qwen/Qwen3-8B", settings.current.defaultModelId)
        assertEquals(1, models.planRefreshCount)
    }

    @Test
    fun `succes garde un modele par defaut deja valide`() = runTest(dispatcher) {
        val keep = model("kimi-k3")
        val settings = FakeSettingsRepo(defaultModel = keep.id)
        val vm = OnboardingViewModel(settings, FakeModelRepo(listOf(keep, model("glm-5p3-flash"))))
        vm.submit(fw, "k")
        advanceUntilIdle()
        assertEquals(keep.id, settings.current.defaultModelId)
    }

    @Test
    fun `cle refusee efface la cle et affiche l erreur`() = runTest(dispatcher) {
        val settings = FakeSettingsRepo()
        val models = FakeModelRepo(failure = FireworksException.Unauthorized("401"))
        val vm = OnboardingViewModel(settings, models)

        vm.submit(fw, "mauvaise")
        advanceUntilIdle()

        assertFalse(vm.state.value.done)
        assertFalse(vm.state.value.loading)
        assertEquals(OnboardingError.UNAUTHORIZED, vm.state.value.error)
        assertTrue(vm.state.value.message!!.contains("Fireworks"))
        assertNull(settings.key)
        assertFalse(settings.current.hasApiKey)
        assertNull(settings.current.defaultModelId)
    }

    @Test
    fun `cle Featherless refusee nomme Featherless et n efface pas l autre cle`() = runTest(dispatcher) {
        val settings = FakeSettingsRepo(key = "fw_existante")
        val models = FakeModelRepo().follow(settings)
        models.failures[fl] = FireworksException.Unauthorized("401")
        val vm = OnboardingViewModel(settings, models)

        vm.submit(fl, "mauvaise")
        advanceUntilIdle()

        assertEquals(OnboardingError.UNAUTHORIZED, vm.state.value.error)
        assertTrue(vm.state.value.message!!.contains("Featherless"))
        assertNull(settings.keyOf(fl))
        assertEquals("fw_existante", settings.key)
        assertEquals(fw, settings.current.activeProvider)
    }

    @Test
    fun `erreur reseau est distinguee et efface aussi la cle`() = runTest(dispatcher) {
        val settings = FakeSettingsRepo()
        val vm = OnboardingViewModel(settings, FakeModelRepo(failure = FireworksException.Network(IOException("off"))))
        vm.submit(fw, "k")
        advanceUntilIdle()
        assertEquals(OnboardingError.NETWORK, vm.state.value.error)
        assertNull(settings.key)
    }

    @Test
    fun `erreur de modele verrouille ou de plan est lisible`() = runTest(dispatcher) {
        val settings = FakeSettingsRepo()
        val models = FakeModelRepo(failure = FireworksException.ModelNotInPlan("x"))
        val vm = OnboardingViewModel(settings, models)
        vm.submit(fl, "k")
        advanceUntilIdle()
        assertEquals(OnboardingError.OTHER, vm.state.value.error)
        assertEquals("Ce modèle est hors de ton plan Featherless.", vm.state.value.message)
        assertNull(settings.keyOf(fl))
    }

    @Test
    fun `cle vide est refusee sans appel reseau`() = runTest(dispatcher) {
        val models = FakeModelRepo()
        val vm = OnboardingViewModel(FakeSettingsRepo(), models)
        vm.submit(fl, "   ")
        advanceUntilIdle()
        assertEquals(OnboardingError.EMPTY_KEY, vm.state.value.error)
        assertEquals(0, models.refreshCount)
    }

    @Test
    fun `nouvelle saisie permet de reessayer apres une erreur`() = runTest(dispatcher) {
        val models = FakeModelRepo(list = listOf(model("gpt-oss-120b")), failure = FireworksException.Unauthorized("401"))
        val settings = FakeSettingsRepo()
        val vm = OnboardingViewModel(settings, models)
        vm.submit(fw, "a"); advanceUntilIdle()
        assertEquals(OnboardingError.UNAUTHORIZED, vm.state.value.error)
        vm.clearError()
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.message)

        models.failure = null
        vm.submit(fw, "b"); advanceUntilIdle()
        assertTrue(vm.state.value.done)
        assertEquals("b", settings.key)
        assertEquals("accounts/fireworks/models/gpt-oss-120b", settings.current.defaultModelId)
    }
}
