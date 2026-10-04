package app.fwchat.ui.prompts

import app.fwchat.domain.PromptFamily
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesTest {

    private val forbidden = listOf("<|", "|>", "[gMASK]", "<sop>", "<think>", "</think>", "<｜", "Reasoning Effort", "reasoning_effort")

    @Test
    fun everyFamilyHasTemplateAndTips() {
        for (f in PromptFamily.values()) {
            assertTrue("template pour $f", PromptTemplates.forFamily(f).isNotEmpty())
            assertTrue("tips pour $f", FamilyTips.tips(f).size in 2..4)
            if (f != PromptFamily.OTHER) {
                assertTrue("template dédié pour $f", PromptTemplates.all.any { it.family == f })
                assertEquals(f, PromptTemplates.forFamily(f).first().family)
            }
        }
        assertEquals(listOf(PromptTemplates.generic), PromptTemplates.forFamily(null))
        assertNull(PromptTemplates.generic.family)
    }

    @Test
    fun templatesAreWellFormed() {
        assertEquals(PromptTemplates.all.size, PromptTemplates.all.map { it.id }.toSet().size)
        for (t in PromptTemplates.all) {
            assertTrue("texte vide: ${t.id}", t.text.isNotBlank())
            assertTrue("nom vide: ${t.id}", t.name.isNotBlank())
            assertTrue("tips ${t.id}", t.tips.size >= 2 && t.tips.all { it.isNotBlank() })
            for (bad in forbidden) assertFalse("${t.id} contient $bad", t.text.contains(bad))
            // gpt-oss reçoit déjà la date du jour du serveur (message system Harmony).
            if (t.family != PromptFamily.GPT_OSS) assertTrue("${t.id} devrait contenir {{date}}", t.text.contains("{{date}}"))
        }
        for (f in PromptFamily.values()) for (tip in FamilyTips.tips(f)) {
            assertTrue(tip.isNotBlank())
            for (bad in listOf("<|", "[gMASK]", "<think>")) assertFalse(tip.contains(bad))
        }
    }

    @Test
    fun placeholdersAreResolved() {
        val now = ZonedDateTime.of(2026, 10, 4, 14, 5, 0, 0, ZoneId.of("Europe/Paris"))
        val out = resolvePlaceholders("Nous sommes le {{date}} ({{date_iso}}), il est {{heure}}.", now)
        assertEquals("Nous sommes le dimanche 4 octobre 2026 (2026-10-04), il est 14:05.", out)
        for (t in PromptTemplates.all) {
            val r = resolvePlaceholders(t.text, now)
            assertFalse(t.id, r.contains("{{"))
            assertNotNull(r)
            if (t.text.contains("{{date}}")) assertTrue(r.contains("dimanche 4 octobre 2026"))
        }
        assertEquals("sans variable", resolvePlaceholders("sans variable", now))
    }
}
