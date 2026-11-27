package io.github.odnwks.mimir.ui

import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import io.github.odnwks.mimir.R
import io.github.odnwks.mimir.keepLeavingPicture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Coming back to the app is calm: no old picture with the keyboard in it, and no grey card in its place. */
@RunWith(RobolectricTestRunner::class)
class CalmReturnTest {
    @Test fun `no picture is kept when the app is left with the keyboard up`() {
        assertFalse(keepLeavingPicture(lockOn = false, keyboardUp = true))
        assertTrue(keepLeavingPicture(lockOn = false, keyboardUp = false))
        // Lock Mímir keeps no picture either way.
        assertFalse(keepLeavingPicture(lockOn = true, keyboardUp = false))
        assertFalse(keepLeavingPicture(lockOn = true, keyboardUp = true))
    }

    @Test fun `the card drawn without a picture is the app's own dark ground`() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val themed = ContextThemeWrapper(app, R.style.Theme_Mimir)
        val value = TypedValue()
        assertTrue(themed.theme.resolveAttribute(android.R.attr.colorBackground, value, true))
        val seen = if (value.resourceId != 0) themed.getColor(value.resourceId) else value.data
        assertEquals(themed.getColor(R.color.ground), seen)
    }
}
