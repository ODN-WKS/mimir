package io.github.odnwks.mimir.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
/** Privacy check's counts: added up across phone restarts, reset on request, downloads apart. */
class NetUseTest {
    private var now: NetUse.Bytes? = NetUse.Bytes(0, 0)

    private val phoneCount = NetUse.read

    @After fun done() { NetUse.clear(); NetUse.read = phoneCount }

    private fun start() { NetUse.clear(); NetUse.read = { now } }

    @Test fun countsAddUpAcrossARestartOfThePhone() {
        start()
        now = NetUse.Bytes(1_000, 200); NetUse.sample()
        now = NetUse.Bytes(5_000, 300); NetUse.sample()
        // The phone restarted: Android counts from 0 again.
        now = NetUse.Bytes(700, 50)
        val u = NetUse.sample()
        assertEquals(NetUse.Bytes(5_700, 350), u.sinceInstall)
    }

    @Test fun resetStartsTheSecondCountAgainAndDownloadsAreCountedApart() {
        start()
        now = NetUse.Bytes(10_000, 100); NetUse.sample()
        NetUse.reset(at = 42)
        // A download the owner started: what came in, and the phone's confirmations going out.
        NetUse.download { now = NetUse.Bytes(13_000, 160) }
        now = NetUse.Bytes(14_000, 170)
        val u = NetUse.sample()
        assertEquals(NetUse.Bytes(14_000, 170), u.sinceInstall)
        assertEquals(NetUse.Bytes(4_000, 70), u.sinceReset)
        assertEquals(3_000, u.downloads)
        assertEquals(3_000, u.downloadsSinceReset)
        assertEquals(60, u.downloadsSent)
        assertEquals(42, u.resetAt)
        // The open chat's line leaves the download's confirmations out.
        assertEquals(110L, NetUse.sentNow())
    }

    @Test fun countingStartsWhenTheAppFirstCountsNotWhenThePhoneStarted() {
        start()
        // Android already counted 6 GB for this app's number (earlier copies, since the phone started).
        now = NetUse.Bytes(6_000_000_000, 22_000_000)
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("net_use", 0).edit().clear().commit()
        NetUse.load(ctx)
        assertEquals(NetUse.Bytes(0, 0), NetUse.use.value.sinceInstall)
        now = NetUse.Bytes(6_000_001_000, 22_000_100)
        assertEquals(NetUse.Bytes(1_000, 100), NetUse.sample().sinceInstall)
        // Opened again later: it carries on from what it kept.
        NetUse.load(ctx)
        assertEquals(NetUse.Bytes(1_000, 100), NetUse.use.value.sinceInstall)
    }

    @Test fun aPhoneThatDoesntCountSaysSo() {
        start()
        now = null
        assertFalse(NetUse.sample().counted)
    }
}
