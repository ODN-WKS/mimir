package io.github.odnwks.mimir.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.odnwks.mimir.ui.AlertWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.09 item 7: a fresh install tells the person only what needs them; saved choices are kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class P09Item7QuietDefaultsTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun freshInstallHasOnlyNeedsYouOn() {
        c.getSharedPreferences("alert_choices", Context.MODE_PRIVATE).edit().clear().commit()
        val d = AlertStore.get(c)
        assertTrue(d.needsYou)
        assertFalse(d.taskFinished)
        assertFalse(d.updates)
        assertFalse(d.showWorking)
        assertEquals(OutsideMode.NeedMe, d.outside)
    }

    @Test fun savedChoicesAreKept() {
        AlertStore.set(c, AlertChoices(needsYou = true, taskFinished = true, updates = true, showWorking = true))
        val d = AlertStore.get(c)
        assertTrue(d.taskFinished && d.updates && d.showWorking)
    }

    @Test fun settingsWordsNameTheDefault() {
        assertTrue(AlertWords.DEFAULT_NOTE.contains("Only when they need me"))
    }
}
