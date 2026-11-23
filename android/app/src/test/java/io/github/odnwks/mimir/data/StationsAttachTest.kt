package io.github.odnwks.mimir.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** QA14 (0.08): the real app's link was made without a folder for attachments, so no picture or file could be sent. */
@RunWith(RobolectricTestRunner::class)
class StationsAttachTest {
    @Test
    fun theAppsOwnLinkCanKeepPicturesAndFiles() {
        val station = Stations.get(ApplicationProvider.getApplicationContext())
        assertTrue("the app's link has a folder for pictures and files", station.attachmentsReady)
    }
}
