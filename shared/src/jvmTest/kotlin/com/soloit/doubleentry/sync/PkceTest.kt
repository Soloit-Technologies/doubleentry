package com.soloit.doubleentry.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PkceTest {
    @Test
    fun challengeMatchesTheRfcExample() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun verifiersAreLongEnoughAndRandom() {
        val verifier = Pkce.verifier()
        assertEquals(43, verifier.length)
        assertNotEquals(verifier, Pkce.verifier())
    }
}
