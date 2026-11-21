package io.github.odnwks.mimir.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHU-24: big model downloads must be blocked when the phone is on mobile data. */
class DownloadMobileDataBlockTest {

    @Test
    fun download_blocked_on_mobile_data() {
        val result = DownloadGate.check(isWifi = false)
        assertTrue(
            "must be blocked on mobile data",
            result is DownloadGate.Result.BlockedMobileData,
        )
    }

    @Test
    fun blocked_message_mentions_wifi() {
        val result = DownloadGate.check(isWifi = false) as DownloadGate.Result.BlockedMobileData
        assertTrue(
            "message must mention Wi-Fi, was: ${result.message}",
            result.message.contains("Wi-Fi", ignoreCase = true),
        )
    }

    @Test
    fun download_allowed_on_wifi() {
        assertEquals(DownloadGate.Result.Allow, DownloadGate.check(isWifi = true))
    }

    @Test
    fun download_anyway_override_allows_on_mobile_data() {
        assertEquals(
            DownloadGate.Result.Allow,
            DownloadGate.check(isWifi = false, overrideByUser = true),
        )
    }
}
