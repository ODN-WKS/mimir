package io.github.odnwks.mimir.update

import io.github.odnwks.mimir.ui.installButtonWords
import io.github.odnwks.mimir.ui.showsInstallButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T1: the install button comes back after a failed or not-yet-allowed install. T2: only the permissions the app uses. */
class T1T2UpdateButtonTest {
    @Test fun failedNeedsAllowStillShowsInstallNow() {
        val s = UpdateStatus.Failed(AppUpdater.NEEDS_ALLOW)
        assertTrue(showsInstallButton(s, releaseSaved = true))
        assertEquals("Install now", installButtonWords(s))
    }

    @Test fun otherFailuresShowTryAgain() {
        val s = UpdateStatus.Failed("The update couldn't be installed. Try again later.")
        assertTrue(showsInstallButton(s, true))
        assertEquals("Try again", installButtonWords(s))
    }

    @Test fun readyShowsItAndNothingSavedHidesIt() {
        assertTrue(showsInstallButton(UpdateStatus.Ready("0.05", ""), true))
        assertFalse(showsInstallButton(UpdateStatus.Failed(AppUpdater.NEEDS_ALLOW), false))
        assertFalse(showsInstallButton(UpdateStatus.Newest("0.04"), true))
        assertFalse(showsInstallButton(UpdateStatus.Downloading, true))
    }

    @Test fun manifestAsksOnlyForTheAllowedPermissions() {
        val xml = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
        val found = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1].removePrefix("android.permission.") }.toSet()
        val allowed = setOf("INTERNET", "ACCESS_NETWORK_STATE", "CAMERA", "ACCESS_COARSE_LOCATION", "USE_BIOMETRIC", "POST_NOTIFICATIONS", "RECEIVE_BOOT_COMPLETED",
            "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC", "FOREGROUND_SERVICE_MEDIA_PLAYBACK", "RECORD_AUDIO", "FOREGROUND_SERVICE_SPECIAL_USE", "REQUEST_INSTALL_PACKAGES")
        assertFalse("SYSTEM_ALERT_WINDOW" in found)
        assertTrue("REQUEST_INSTALL_PACKAGES" in found)
        assertEquals(emptySet<String>(), found - allowed)
    }
}
