package app.fwchat.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellLogicTest {

    @Test
    fun `cle perdue sur un chat renvoie a l onboarding`() {
        assertTrue(shouldReturnToOnboarding(hasApiKey = false, route = Routes.CHAT))
        assertTrue(shouldReturnToOnboarding(hasApiKey = false, route = Routes.CHAT_NEW))
        assertTrue(shouldReturnToOnboarding(hasApiKey = false, route = Routes.SETTINGS))
        assertTrue(shouldReturnToOnboarding(hasApiKey = false, route = Routes.PROMPTS))
    }

    @Test
    fun `cle presente ou deja sur l onboarding ne fait rien`() {
        assertFalse(shouldReturnToOnboarding(hasApiKey = true, route = Routes.CHAT))
        assertFalse(shouldReturnToOnboarding(hasApiKey = false, route = Routes.ONBOARDING))
        assertFalse(shouldReturnToOnboarding(hasApiKey = true, route = Routes.ONBOARDING))
    }

    @Test
    fun `route inconnue pas encore chargee ne fait rien`() {
        assertFalse(shouldReturnToOnboarding(hasApiKey = false, route = null))
    }
}
