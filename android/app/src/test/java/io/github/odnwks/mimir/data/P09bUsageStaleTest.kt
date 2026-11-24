package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.LStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** 0.09 review finding 4: a use reading whose reset time has passed counts as not read. */
class P09bUsageStaleTest {
    private val page = LStatus(projects = emptyList(), weeklyUsed = 0.62, weeklyResets = 1000, reply = emptyList(), dataTime = 900)

    @Test fun aReadingPastItsResetIsNotRead() {
        val u = Mapping.usage(page, 2000)
        assertEquals("—", u.percentText)
    }

    @Test fun aReadingBeforeItsResetStays() = assertEquals("62%", Mapping.usage(page, 500).percentText)

    @Test fun aReadingWithNoResetTimeStays() = assertEquals("62%", Mapping.usage(page.copy(weeklyResets = null), 2000).percentText)
}
