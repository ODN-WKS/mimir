package io.github.odnwks.mimir.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.09 review finding 2: the phone's takeover rests on live state, not on an answer older than the person's choice. */
class P09bFixTest {
    @Test fun anOldPhoneAnswerDoesNotSayThePhoneTookOverAfterPickingYourPc() {
        val w = whoNow(true, true, true, pcOnline = true, lastRan = "phone", lastNote = "x", whisper = false, online = false, answeredAfterChoice = false)
        assertFalse(w.phoneTookOver)
    }

    @Test fun aPhoneAnswerAfterTheChoiceStillDoes() {
        assertTrue(whoNow(true, true, true, pcOnline = true, lastRan = "phone", lastNote = null, whisper = false, online = false, answeredAfterChoice = true).phoneTookOver)
    }

    @Test fun aPcThatIsAwayStillDoesWhateverTheOldAnswers() {
        assertTrue(whoNow(true, true, true, pcOnline = false, lastRan = "pc", lastNote = null, whisper = false, online = false, answeredAfterChoice = false).phoneTookOver)
    }
}
