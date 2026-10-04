package app.fwchat.ui.prompts.screens

import app.cash.turbine.test
import app.fwchat.domain.PromptFamily
import app.fwchat.ui.FakePromptRepo
import app.fwchat.ui.FakeSettingsRepo
import app.fwchat.ui.prompts.PromptTemplates
import app.fwchat.ui.shell.Routes
import app.fwchat.ui.systemPrompt
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

@OptIn(ExperimentalCoroutinesApi::class)
class PromptViewModelsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `estimation de jetons`() {
        assertEquals(0, estimateTokens(0))
        assertEquals(1, estimateTokens(1))
        assertEquals(2, estimateTokens(7))
        assertEquals(3, estimateTokens(8))
        assertEquals(286, estimateTokens(1000))
    }

    // ------------------------------------------------------------------ liste / défaut

    private fun listVm(prompts: FakePromptRepo, settings: FakeSettingsRepo) = PromptListViewModel(prompts, settings)

    @Test
    fun `l etoile definit puis retire le defaut`() = runTest(dispatcher) {
        val prompts = FakePromptRepo(listOf(systemPrompt("a"), systemPrompt("b")))
        val settings = FakeSettingsRepo()
        val vm = listVm(prompts, settings)
        vm.state.test {
            awaitItem() // initial (pas chargé)
            val first = awaitItem()
            assertTrue(first.loaded)
            assertNull(first.defaultId)

            vm.toggleDefault("a")
            assertEquals("a", awaitItem().defaultId)
            vm.toggleDefault("b") // change de défaut
            assertEquals("b", awaitItem().defaultId)
            vm.toggleDefault("b") // re-toucher retire
            assertNull(awaitItem().defaultId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `la ligne Aucun remet le defaut a null`() = runTest(dispatcher) {
        val settings = FakeSettingsRepo(defaultPrompt = "a")
        val vm = listVm(FakePromptRepo(listOf(systemPrompt("a"))), settings)
        vm.setNoneAsDefault()
        advanceUntilIdle()
        assertNull(settings.current.defaultSystemPromptId)
    }

    @Test
    fun `supprimer le prompt par defaut remet le defaut a aucun`() = runTest(dispatcher) {
        val prompts = FakePromptRepo(listOf(systemPrompt("a"), systemPrompt("b")))
        val settings = FakeSettingsRepo(defaultPrompt = "a")
        val vm = listVm(prompts, settings)
        vm.delete("a")
        advanceUntilIdle()
        assertEquals(listOf("b"), prompts.items.value.map { it.id })
        assertNull(settings.current.defaultSystemPromptId)
    }

    @Test
    fun `supprimer un autre prompt garde le defaut`() = runTest(dispatcher) {
        val prompts = FakePromptRepo(listOf(systemPrompt("a"), systemPrompt("b")))
        val settings = FakeSettingsRepo(defaultPrompt = "a")
        listVm(prompts, settings).delete("b")
        advanceUntilIdle()
        assertEquals("a", settings.current.defaultSystemPromptId)
    }

    @Test
    fun `un defaut orphelin est presente comme aucun`() = runTest(dispatcher) {
        val vm = listVm(FakePromptRepo(listOf(systemPrompt("a"))), FakeSettingsRepo(defaultPrompt = "fantome"))
        vm.state.test {
            awaitItem()
            assertNull(awaitItem().defaultId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ------------------------------------------------------------------ éditeur

    private fun editorVm(
        prompts: FakePromptRepo = FakePromptRepo(),
        settings: FakeSettingsRepo = FakeSettingsRepo(),
        id: String = Routes.PROMPT_NEW_ID,
        template: String? = null,
    ) = PromptEditorViewModel(prompts, settings, id, template)

    @Test
    fun `enregistrer exige nom et texte`() = runTest(dispatcher) {
        val prompts = FakePromptRepo()
        val vm = editorVm(prompts)
        assertFalse(vm.canSave)
        vm.onNameChange("Nom")
        assertFalse(vm.canSave)
        vm.onTextChange("   ")
        assertFalse(vm.canSave)
        vm.onTextChange("Tu es utile.")
        assertTrue(vm.canSave)

        vm.onFamilyChange(PromptFamily.QWEN)
        var saved = false
        vm.save { saved = true }
        advanceUntilIdle()
        assertTrue(saved)
        val p = prompts.items.value.single()
        assertEquals("Nom", p.name)
        assertEquals(PromptFamily.QWEN, p.family)
        assertFalse(vm.dirty)
    }

    @Test
    fun `modele initial depuis la route remplit nom texte et famille`() = runTest(dispatcher) {
        val t = PromptTemplates.all.first { it.family == PromptFamily.GLM }
        val vm = editorVm(template = t.id)
        assertEquals(t.name, vm.name)
        assertEquals(t.text, vm.text)
        assertEquals(PromptFamily.GLM, vm.family)
        assertTrue(vm.dirty)
    }

    @Test
    fun `charge un prompt existant et sauvegarde en place`() = runTest(dispatcher) {
        val prompts = FakePromptRepo(listOf(systemPrompt("p1", name = "Ancien", text = "avant")))
        val vm = editorVm(prompts, id = "p1")
        assertFalse(vm.loaded)
        advanceUntilIdle()
        assertTrue(vm.loaded)
        assertEquals("Ancien", vm.name)
        assertFalse(vm.dirty)

        vm.onTextChange("après")
        assertTrue(vm.dirty)
        vm.save {}
        advanceUntilIdle()
        val p = prompts.items.value.single()
        assertEquals("p1", p.id)
        assertEquals("après", p.text)
    }

    @Test
    fun `prompt introuvable est signale`() = runTest(dispatcher) {
        val vm = editorVm(id = "absent")
        advanceUntilIdle()
        assertTrue(vm.notFound)
    }

    @Test
    fun `appliquer un modele garde le nom saisi`() = runTest(dispatcher) {
        val vm = editorVm()
        vm.onNameChange("Mon nom")
        vm.applyTemplate(PromptTemplates.generic)
        assertEquals("Mon nom", vm.name)
        assertEquals(PromptTemplates.generic.text, vm.text)
    }

    @Test
    fun `supprimer depuis l editeur remet le defaut a aucun si besoin`() = runTest(dispatcher) {
        val prompts = FakePromptRepo(listOf(systemPrompt("p1")))
        val settings = FakeSettingsRepo(defaultPrompt = "p1")
        val vm = editorVm(prompts, settings, id = "p1")
        advanceUntilIdle()
        var done = false
        vm.delete { done = true }
        advanceUntilIdle()
        assertTrue(done)
        assertTrue(prompts.items.value.isEmpty())
        assertNull(settings.current.defaultSystemPromptId)
    }
}
