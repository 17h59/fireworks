package app.fwchat.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillingFormatTest {
    @Test fun format_usd_francais() {
        assertEquals("12,34 $", formatUsd(12.34))
        assertEquals("0,00 $", formatUsd(0.0))
        assertEquals("0,005 $", formatUsd(0.005))
        assertEquals("1 234,50 $", formatUsd(1234.5))
    }

    @Test fun saisie_du_solde() {
        assertEquals(12.5, parseBalanceInput("12,5")!!, 1e-9)
        assertEquals(12.5, parseBalanceInput(" 12.50 ")!!, 1e-9)
        assertEquals(1234.5, parseBalanceInput("1 234,5 $")!!, 1e-9)
        assertEquals(0.0, parseBalanceInput("0")!!, 1e-9)
        assertNull(parseBalanceInput(""))
        assertNull(parseBalanceInput("abc"))
        assertNull(parseBalanceInput("-3"))
    }
}
