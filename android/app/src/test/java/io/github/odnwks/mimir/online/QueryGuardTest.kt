package io.github.odnwks.mimir.online

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P21 stage 2 (plan section 5, "Query guard"): words the model makes up for a search never carry
 * private things out of the phone. Anything doubtful falls back to the owner's own words.
 */
class QueryGuardTest {
    private fun fallback(owner: String) = Router.lookUp(owner)?.words ?: owner

    @Test fun anAttachedFilesUnusualPhraseFallsBackToTheOwnersWords() {
        val owner = "who is this company, look them up"
        val file = "Lease between Halvorsen Fabrication and the tenant of unit 4, signed in March."
        // An unusual long word found only in the file.
        assertEquals(fallback(owner), QueryGuard.safe("Halvorsen reviews", owner, listOf(owner), file, null))
        // A run of three words found only in the file, even common ones.
        assertEquals(fallback(owner), QueryGuard.safe("the tenant of unit", owner, listOf(owner), file, null))
        // The same word typed by the owner is theirs to send.
        val typed = "is Halvorsen Fabrication a good landlord"
        assertEquals("Halvorsen Fabrication reviews", QueryGuard.safe("Halvorsen Fabrication reviews", typed, listOf(typed), file, null))
    }

    @Test fun anEmailAddressFallsBack() {
        val owner = "who sent this"
        assertEquals(fallback(owner), QueryGuard.safe("sam.lee@example.org sender", owner, listOf(owner), null, null))
    }

    @Test fun aLongNumberTheOwnerDidNotTypeFallsBackOneTheyTypedStays() {
        val owner = "whose number is this"
        assertEquals(fallback(owner), QueryGuard.safe("555 123 4567 caller", owner, listOf(owner), null, null))
        assertEquals(fallback(owner), QueryGuard.safe("5551234567 caller", owner, listOf(owner), null, null))
        val typed = "whose number is 555-123-4567"
        assertEquals("5551234567 caller", QueryGuard.safe("5551234567 caller", typed, listOf("hello", typed), null, null))
        // Short numbers (a year, a model number) are fine.
        assertEquals("Pixel 9 release date 2026", QueryGuard.safe("Pixel 9 release date 2026", owner, listOf(owner), null, null))
    }

    @Test fun theSavedPlaceFallsBackUnlessTheOwnerSaidNearMe() {
        val saved = "Leeds, England, United Kingdom"
        val owner = "any good pizza places open now"
        assertEquals(fallback(owner), QueryGuard.safe("pizza open now Leeds", owner, listOf(owner), null, saved))
        val near = "any good pizza places open now near me"
        assertEquals("pizza open now Leeds", QueryGuard.safe("pizza open now Leeds", near, listOf(near), null, saved))
        for (w in listOf("is the market here open today", "pharmacy nearby", "what's on around me")) {
            assertEquals(w, "events Leeds", QueryGuard.safe("events Leeds", w, listOf(w), null, saved))
        }
    }

    @Test fun plainGoodWordsStayUnchanged() {
        val owner = "when do the lakers play next"
        assertEquals("Lakers schedule 2026", QueryGuard.safe("Lakers schedule 2026", owner, listOf(owner), null, "Leeds, England"))
        // A file that shares only everyday words with them changes nothing.
        val file = "The meeting is on Monday morning at the office."
        assertEquals("Monday morning traffic news", QueryGuard.safe("Monday morning traffic news", "traffic news", listOf("traffic news"), file, null))
        // Empty words from the model: the owner's own.
        assertEquals(fallback(owner), QueryGuard.safe("  ", owner, listOf(owner), null, null))
    }
}
