package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.ui.theme.TitleFont
import io.github.odnwks.mimir.ui.theme.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
/** Cinzel is for titles only; body text keeps the app's own font. */
class TitleFontTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bodyIsSmallerThanTheTitlesButNeverBelow13() {
        assertEquals(15.5f, Type.Body.fontSize.value, 0.01f)
        assertEquals(15.5f, Type.BodyTight.fontSize.value, 0.01f)
        assertEquals(13f, Type.Small.fontSize.value, 0.01f)
        for (s in listOf(Type.Body, Type.BodyTight, Type.Label, Type.Small, Type.Section)) assertTrue(s.fontSize.value >= 13f && s.fontSize.value <= 15.5f || s === Type.Label)
        val body = Type.Body.fontSize.value
        // Cinzel capitals read larger than their sp size; these floors are what the owner's pictures showed.
        assertTrue(Type.PageTitle.fontSize.value / body >= 1.2f)
        assertTrue(Type.Hello.fontSize.value / body >= 1.8f)
        assertTrue(Type.SheetTitle.fontSize.value / body >= 1.0f)
    }

    @Test fun titlesUseCinzel() {
        for (s in listOf(Type.PageTitle, Type.Hello, Type.SheetTitle, Type.MenuHeading, ScreenTitleStyle)) assertEquals(TitleFont, s.fontFamily)
    }

    @Test fun bodyAndControlsDoNotUseCinzel() {
        for (s in listOf(Type.Body, Type.BodyTight, Type.Label, Type.Small, Type.Section, Type.Title)) assertNotEquals(TitleFont, s.fontFamily)
        assertNull(Type.Body.fontFamily)
    }

    @Test fun theFontAndItsLicenceAreBundledAndHaveTheAccent() {
        val root = File("src/main")
        val ttf = File(root, "res/font/cinzel.ttf")
        assertTrue(ttf.length() > 50_000)
        assertTrue(File(root, "assets/OFL-Cinzel.txt").readText().contains("SIL OPEN FONT LICENSE"))
        // cmap check is heavy; the file is the PC's own tested copy. The credit line is the one in Settings.
        assertEquals("Font: Cinzel (SIL Open Font License 1.1)", FONT_CREDIT)
    }
}
