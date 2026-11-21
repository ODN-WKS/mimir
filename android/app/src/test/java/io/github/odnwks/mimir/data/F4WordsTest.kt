package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.ui.adaptive.Device
import io.github.odnwks.mimir.ui.chatsKeptWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** QA2 fixes that are plain words: device names, where chats are kept, why pairing ended, and "tablet" on a tablet. */
class F4WordsTest {
    @Test fun aTabletWithAGenericBuildIsNamedPlainly() {
        assertEquals("Android tablet", DeviceName.friendly(null, "unknown", "TrebleDroid with GApps", tablet = true))
        assertEquals("Android phone", DeviceName.friendly(null, "", "", tablet = false))
        assertEquals("Lenovo tablet", DeviceName.friendly(null, "LENOVO", "generic_x86 GSI", tablet = true))
    }

    @Test fun theNameTheOwnerGaveWinsAndMakerAndModelAreTidied() {
        assertEquals("Kitchen tablet", DeviceName.friendly("Kitchen tablet", "unknown", "TrebleDroid", tablet = true))
        assertEquals("Samsung SM-A123B", DeviceName.friendly(null, "samsung", "SM-A123B", tablet = false))
        assertEquals("Samsung SM-A123B", DeviceName.friendly("Unknown", "samsung", "SM-A123B", tablet = false))
    }

    @Test fun chatsAreSaidToBeOnThePcToo() {
        assertEquals("Your chats are kept on this phone and your PC.", chatsKeptWords(paired = true, onlineAnswers = false))
        assertFalse(chatsKeptWords(paired = false, onlineAnswers = false).contains("never sent anywhere"))
    }

    @Test fun aPcThatStoppedWaitingIsNotAMismatch() {
        assertEquals("Your PC stopped waiting. Start pairing again on your PC.", Mapping.pairWhy("Nobody confirmed on the PC in time."))
        assertEquals("The PC said the words don't match. Make a new code on the PC.", Mapping.pairWhy("The PC said the words don't match. Make a new code on the PC."))
    }

    @Test fun aTabletSaysTablet() {
        assertEquals("Unpair this tablet?", Device.Tablet.reword("Unpair this phone?"))
        assertEquals("Point this tablet's camera at it.", Device.Tablet.reword("Point this phone's camera at it."))
        assertEquals("Disconnect this tablet", Device.Tablet.reword("Disconnect this phone"))
    }
}
