package app.fwchat.ui.shell

import app.fwchat.domain.FireworksException
import app.fwchat.ui.FakeModelRepo
import app.fwchat.ui.FakeSettingsRepo
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
    fun `succes enregistre la cle charge les modeles et choisit le modele par defaut`() = runTest(dispatcher) {
        val settings = FakeSettingsRepo()
        val models = FakeModelRepo(listOf(model("kimi-k3"), model("glm-5p3-flash")))
        val vm = OnboardingViewModel(settings, models)

        vm.submit("  fw_secret  ")
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()

        assertTrue(vm.state.value.done)
        assertNull(vm.state.value.error)
        assertEquals("fw_secret", settings.key)
        assertEquals(1, models.refreshCount)
        assertEquals("accounts/fireworks/models/glm-5p3-flash", settings.current.defaultModelId)
    }

    @Test
    fun `succes garde un modele par defaut deja valide`() = runTest(dispatcher) {
        val keep = model("kimi-k3")
        val settings = FakeSettingsRepo(defaultModel = keep.id)
        val vm = OnboardingViewModel(settings, FakeModelRepo(listOf(keep, model("glm-5p3-flash"))))
        vm.submit("k")
        advanceUntilIdle()
        assertEquals(keep.id, settings.current.defaultModelId)
    }

    @Test
    fun `cle refusee efface la cle et affiche l erreur`() = runTest(dispatcher) {
        val settings = FakeSettingsRepo()
        val models = FakeModelRepo(failure = FireworksException.Unauthorized("401"))
        val vm = OnboardingViewModel(settings, models)

        vm.submit("mauvaise")
        advanceUntilIdle()

        assertFalse(vm.state.value.done)
        assertFalse(vm.state.value.loading)
        assertEquals(OnboardingError.UNAUTHORIZED, vm.state.value.error)
        assertNull(settings.key)
        assertFalse(settings.current.hasApiKey)
        assertNull(settings.current.defaultModelId)
    }

    @Test
    fun `erreur reseau est distinguee et efface aussi la cle`() = runTest(dispatcher) {
        val settings = FakeSettingsRepo()
        val vm = OnboardingViewModel(settings, FakeModelRepo(failure = FireworksException.Network(IOException("off"))))
        vm.submit("k")
        advanceUntilIdle()
        assertEquals(OnboardingError.NETWORK, vm.state.value.error)
        assertNull(settings.key)
    }

    @Test
    fun `cle vide est refusee sans appel reseau`() = runTest(dispatcher) {
        val models = FakeModelRepo()
        val vm = OnboardingViewModel(FakeSettingsRepo(), models)
        vm.submit("   ")
        advanceUntilIdle()
        assertEquals(OnboardingError.EMPTY_KEY, vm.state.value.error)
        assertEquals(0, models.refreshCount)
    }

    @Test
    fun `nouvelle saisie permet de reessayer apres une erreur`() = runTest(dispatcher) {
        val models = FakeModelRepo(list = listOf(model("gpt-oss-120b")), failure = FireworksException.Unauthorized("401"))
        val settings = FakeSettingsRepo()
        val vm = OnboardingViewModel(settings, models)
        vm.submit("a"); advanceUntilIdle()
        assertEquals(OnboardingError.UNAUTHORIZED, vm.state.value.error)
        vm.clearError()
        assertNull(vm.state.value.error)

        models.failure = null
        vm.submit("b"); advanceUntilIdle()
        assertTrue(vm.state.value.done)
        assertEquals("b", settings.key)
        assertEquals("accounts/fireworks/models/gpt-oss-120b", settings.current.defaultModelId)
    }
}
