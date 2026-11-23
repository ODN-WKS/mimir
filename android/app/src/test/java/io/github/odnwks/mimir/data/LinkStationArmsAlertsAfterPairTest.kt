package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.LinkApi
import io.github.odnwks.mimir.link.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** N5: the 5-minute check is armed right after pairing succeeds, not at the next app start. */
@RunWith(RobolectricTestRunner::class)
class LinkStationArmsAlertsAfterPairTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Plain : Vault.Codec {
        override fun seal(text: String) = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    /** A link that pairs at once (or says the PC refused). */
    private class PairingLink(private val ok: Boolean) : LinkApi {
        override fun start(key: ByteArray, relays: Boolean) = 1L
        override fun stop(handle: Long) {}
        override fun addHint(handle: Long, addr: String) {}
        override fun pairStart(handle: Long, code: String, phoneName: String) = """{"words":["one","two","three"]}"""
        override fun pairFinish(handle: Long, code: String, timeoutMs: Long) =
            if (ok) """{"pc_id":"pc-1","pc_name":"Test PC"}""" else """{"error":"The PC said no."}"""
        override fun connect(handle: Long, pcId: String) = "Couldn't connect."
        override fun call(handle: Long, json: String) = -1L
        override fun nextEvent(handle: Long, timeoutMs: Long): String? { Thread.sleep(minOf(timeoutMs, 20)); return null }
        override fun connected(handle: Long) = false
        override fun networkChanged(handle: Long) {}
        override fun endedBecause(handle: Long) = ""
    }

    private class CountingStay : StayStore {
        val armed = AtomicInteger(0)
        override fun mode() = StayMode.Every5
        override fun save(mode: StayMode) {}
        override fun pairedNow() { armed.incrementAndGet() }
    }

    private fun station(link: LinkApi, stay: StayStore): LinkStation {
        val v = Vault(File(tmp.root, "link.vault"), Plain(), { ByteArray(32) }, tries = 1, pause = {})
        return LinkStation(v, "0.21", relays = false, hints = emptyList(), secure = { true }, stayStore = stay, api = link)
    }

    private fun until(ms: Long = 8_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test fun theCheckIsArmedRightAfterPairingSucceeds() {
        val stay = CountingStay()
        val st = station(PairingLink(ok = true), stay)
        st.pair("mimir1:code")
        assertTrue("paired", until { st.state.value.pairing == PairProgress.Paired })
        assertEquals("armed once, right after pairing", 1, stay.armed.get())
        st.close()
    }

    @Test fun aFailedPairingArmsNothing() {
        val stay = CountingStay()
        val st = station(PairingLink(ok = false), stay)
        st.pair("mimir1:code")
        assertTrue("failed", until { st.state.value.pairing is PairProgress.Failed })
        assertEquals(0, stay.armed.get())
        st.close()
    }
}
