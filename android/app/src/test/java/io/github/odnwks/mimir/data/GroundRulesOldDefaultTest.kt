package io.github.odnwks.mimir.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GroundRulesOldDefaultTest {
    @Test
    fun a_stored_old_default_is_removed_and_the_current_default_applies() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val old = GroundRules.OLD_DEFAULTS.first()
        assertTrue(GroundRules.isOldDefault(" $old\n"))
        assertFalse(GroundRules.isOldDefault(GroundRules.DEFAULT))
        context.getSharedPreferences("ground_rules", 0).edit().putString("text", old).commit()
        GroundRules.load(context)
        assertEquals(GroundRules.DEFAULT, GroundRules.rules.value.text)
        assertFalse(context.getSharedPreferences("ground_rules", 0).contains("text"))
        context.getSharedPreferences("ground_rules", 0).edit().putString("text", "Be brief.").commit()
        GroundRules.load(context)
        assertEquals("Be brief.", GroundRules.rules.value.text)
        // GroundRules is a process-wide singleton: leave it as a fresh install has it.
        context.getSharedPreferences("ground_rules", 0).edit().clear().commit()
        GroundRules.load(context)
    }
}
