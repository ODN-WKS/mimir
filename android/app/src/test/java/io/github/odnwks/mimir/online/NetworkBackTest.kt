package io.github.odnwks.mimir.online

import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.ui.testEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** QA I1: a lookup that failed for want of a network is asked again by itself, once, when the network is back. */
@RunWith(RobolectricTestRunner::class)
class NetworkBackTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
        LookupLog.clear()
    }

    @After fun tearDown() { Online.set(Online.Settings()) }

    private fun settle(e: Engine) {
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(20)
        while (e.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(e.state.value.writing)
    }

    @Test fun aFailedLookupIsAskedAgainOnceWhenTheNetworkIsBack() {
        val e = testEngine(tmp.newFolder(), ready = true)
        web.fail = true
        e.send("What's the weather in Leeds?"); settle(e)
        assertTrue(e.state.value.lookupFailed)
        web.fail = false
        e.networkBack(); settle(e)
        assertFalse(e.state.value.lookupFailed)
        assertEquals(2, e.state.value.chat.turns.size)
    }

    @Test fun itIsAskedAgainOnlyOnceNotAtEveryNetworkChange() {
        val e = testEngine(tmp.newFolder(), ready = true)
        web.fail = true
        e.send("What's the weather in Leeds?"); settle(e)
        e.networkBack(); settle(e)
        assertTrue("still no network: it failed again", e.state.value.lookupFailed)
        val asked = web.asked.size
        e.networkBack(); settle(e)
        assertEquals("not asked a third time by itself", asked, web.asked.size)
        // "Try again" under the answer still works.
        web.fail = false
        e.lookupAgain(); settle(e)
        assertFalse(e.state.value.lookupFailed)
    }
}
