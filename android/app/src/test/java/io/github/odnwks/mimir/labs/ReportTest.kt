package io.github.odnwks.mimir.labs

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    private val facts = ReportFacts(
        version = "0.01", flavor = "dev", built = "2026-11-21", android = "8.0.0 (API 26)",
        phoneFamily = "Samsung Galaxy S-series", screenClass = Report.screenClass(360), paired = true, whoAnswers = "Your PC",
    )

    @Test fun phoneFamilyNeverShowsTheModelNumber() {
        assertEquals("Samsung Galaxy S-series", Report.phoneFamily("samsung", "SM-G991B"))
        assertEquals("Samsung Galaxy S-series", Report.phoneFamily("samsung", "SM-S911B"))
        assertEquals("Samsung Galaxy A-series", Report.phoneFamily("samsung", "SM-A546B"))
        assertEquals("Google Pixel", Report.phoneFamily("Google", "Pixel 8"))
        assertEquals("Lenovo tablet", Report.phoneFamily("LENOVO", "TB-X606F"))
        assertEquals("Fairphone phone", Report.phoneFamily("Fairphone", "FP5"))
        assertEquals("Android phone", Report.phoneFamily("", ""))
    }

    @Test fun screenClasses() {
        assertTrue(Report.screenClass(339).startsWith("compact"))
        assertTrue(Report.screenClass(700).startsWith("medium"))
        assertTrue(Report.screenClass(900).startsWith("expanded"))
    }

    @Test fun reportHasTheFactsInOrder() {
        val text = Report.build(
            facts, listOf(CheckLine("Your PC", CheckLine.Result.Good, "Reached in 80 ms"), CheckLine("Lookups", CheckLine.Result.Problem, "Didn't work", "Check internet")),
            listOf("E Net: timed out"), what = "It stops.\nHelp",
        )
        val i = listOf("problem report", "What happened: It stops.", "App: Mímir 0.01 (dev, built 2026-11-21)", "Android: 8.0.0", "Phone: Samsung Galaxy S-series", "Screen: compact (360 dp wide)",
            "Paired with a PC: yes", "Who answers: Your PC", "✓ Your PC: Reached in 80 ms", "✗ Lookups: Didn't work (Check internet)", "Recent errors", "E Net: timed out")
            .map { text.indexOf(it) }
        assertTrue("missing or out of order: $i\n$text", i.all { it >= 0 } && i == i.sorted())
    }

    @Test fun reportLeavesOutWhatIsSwitchedOff() {
        val text = Report.build(facts, null, null)
        assertFalse(text.contains("Checks:"))
        assertFalse(text.contains("Recent errors"))
        assertTrue(Report.build(facts, null, emptyList()).contains("(none)"))
    }

    private fun param(url: String, key: String) = url.substringAfter("?").split("&").map { it.split("=", limit = 2) }
        .first { it[0] == key }[1].let { URLDecoder.decode(it, "UTF-8") }

    @Test fun linkHasTheFormAndTheBoxes() {
        val url = GithubLink.build("It stops", "It stops when I open a chat & close it", "Mímir 0.01")
        assertTrue(url.startsWith("https://github.com/ODN-WKS/mimir/issues/new?template=report-a-problem.yml&title="))
        assertEquals("It stops", param(url, "title"))
        assertEquals("It stops when I open a chat & close it", param(url, "what"))
        assertTrue(param(url, "report").startsWith("Mímir 0.01"))
        assertTrue(param(url, "report").contains(GithubLink.PASTE_NOTE))
        assertFalse(url.contains(" "))
        assertFalse(url.contains("labels="))
    }

    @Test fun longTextIsCutToKeepTheLinkShort() {
        val url = GithubLink.build("t", "word ".repeat(2000), "summary")
        assertTrue("${url.length}", url.length <= GithubLink.MAX_URL)
        assertTrue(param(url, "what").startsWith("word word"))
    }

    @Test fun hugeSummaryStillFits() {
        val url = GithubLink.build("t".repeat(500), "é".repeat(5000), "s ".repeat(5000))
        assertTrue("${url.length}", url.length <= GithubLink.MAX_URL)
    }

    @Test fun titleComesFromTheFirstLine() {
        assertEquals("It stops", GithubLink.titleFrom("\n It stops \nmore"))
        assertEquals("A problem in Mímir", GithubLink.titleFrom("   "))
        assertEquals(70, GithubLink.titleFrom("x".repeat(300)).length)
    }
}
