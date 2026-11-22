package io.github.odnwks.mimir.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the same journeys on a build whose class names are not renamed, and writes down which code
 * ran. Its output goes into app/src/main/baseline-prof.txt (tools/baseline-profile.sh does that).
 */
@RunWith(AndroidJUnit4::class)
class ProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = Steps.APP, includeInStartupProfile = true) {
        Steps.start(this)
        Steps.menu(this)
        Steps.settings(this)
        Steps.chatScroll(this)
    }
}
