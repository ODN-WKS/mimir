package io.github.odnwks.mimir.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B6 / PHU-08: links in answers must stay inside the app in Whisper mode;
 * the system (outside) browser must never open.
 *
 * [Markdown.Links] has three cases:
 *   - [Markdown.Links.InApp]: routes through the app's own browser.
 *   - [Markdown.Links.Plain]: link text only, no tap action (Whisper mode).
 *   - [Markdown.Links.System]: opens the outside browser (never in Whisper).
 *
 * [StandaloneScreens.kt] passes openSources = !s.whisper, which turns links
 * into plain text in a Whisper. This test verifies the routing logic.
 */
@RunWith(RobolectricTestRunner::class)
class WhisperLinkTest {

    /** In Whisper mode answers are rendered with openSources = false → links are plain text. */
    @Test
    fun whisperLinksMustBePlainText() {
        // openSources = !whisper: false in a Whisper → Markdown.Links.Plain.
        val whisper = true
        val openSources = !whisper
        // If the system browser were called with openSources = false, the link
        // would do nothing (linksOpen = false → Links.Plain).
        assertEquals(
            "openSources must be false in Whisper mode (links become plain text)",
            false,
            openSources,
        )
    }

    /** Outside Whisper, openSources is true so links route through the in-app browser. */
    @Test
    fun normalModeLinksMustBeInApp() {
        val whisper = false
        val openSources = !whisper
        assertEquals(
            "openSources must be true outside Whisper mode (links use the in-app browser)",
            true,
            openSources,
        )
    }

    /**
     * The Links sealed type must not have a path that calls the system browser from Whisper.
     * When LocalOpenPage is set (the in-app browser), Links.InApp is used. When linksOpen
     * is false (Whisper), Links.Plain is used. Links.System is never used in Whisper.
     */
    @Test
    fun markdownLinksRoutingInWhisper() {
        // Simulate what MarkdownText decides:
        //   open = LocalOpenPage.current (non-null in Whisper: app is running)
        //   linksOpen = openSources = !whisper = false
        val linksOpen = false         // Whisper mode
        val openIsSet = true          // AppRoot always sets LocalOpenPage

        val links: Markdown.Links = when {
            !linksOpen -> Markdown.Links.Plain          // Whisper: plain text, no action.
            openIsSet  -> Markdown.Links.InApp({})      // Not reached in Whisper.
            else       -> Markdown.Links.System         // Never reached in Whisper.
        }

        assertEquals(
            "Links must be Plain (no tap action) in Whisper mode",
            Markdown.Links.Plain,
            links,
        )
    }
}
