package io.github.odnwks.mimir.alerts

import io.github.odnwks.mimir.data.StayMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PHD-13: the 5-minute alarm must not be armed on a fresh (unpaired) phone. */
class AlarmOnlyAfterPairTest {

    @Test
    fun alarm_not_armed_before_first_pair() {
        assertFalse(
            "unpaired phone must not arm the alarm",
            Stay.shouldArm(StayMode.Every5, paired = false),
        )
    }

    @Test
    fun alarm_armed_after_pair() {
        assertTrue(
            "paired phone must arm the alarm",
            Stay.shouldArm(StayMode.Every5, paired = true),
        )
    }

    @Test
    fun battery_saver_suppresses_alarm_even_when_paired() {
        assertFalse(
            "Battery saver must keep the alarm off regardless of pair state",
            Stay.shouldArm(StayMode.BatterySaver, paired = true),
        )
    }
}
