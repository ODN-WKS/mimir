package io.github.odnwks.mimir.devlog

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The developer log's caps and ages, the important file, the flood note, the space guard and the buffering. */
class DevLogCapsTest {
    private fun dir(): File = Files.createTempDirectory("devlog").toFile()
    private fun line(tag: String, msg: String, level: String = "I") = "11-19 12:00:00.000  1234  1240 $level $tag: $msg"
    private fun normal(d: File) = d.listFiles().orEmpty().filter { it.name.startsWith("devlog-") }.sortedBy { it.name }
    private fun important(d: File) = d.listFiles().orEmpty().filter { it.name.startsWith("important-") }.sortedBy { it.name }
    private fun text(fs: List<File>) = fs.joinToString("\n") { it.readText() }
    private val day = 24L * 3600 * 1000
    private val mb = 1_000_000L

    @Test fun theLimitsAreFiveMegabytesTwentyFilesAndAWeek_andForTheImportantOnesTwentyMegabytesAndAMonth() {
        assertEquals(5 * mb, DevLog.MAX_BYTES)
        assertEquals(20, DevLog.MAX_FILES)
        assertEquals(7 * day, DevLog.MAX_AGE_MS)
        assertEquals(20 * mb, DevLog.IMPORTANT_FILE_BYTES * DevLog.IMPORTANT_FILES)
        assertEquals(30 * day, DevLog.IMPORTANT_AGE_MS)
        assertEquals(64 * 1024, DevLog.BUFFER_BYTES)
        assertEquals(5_000L, DevLog.FLUSH_MS)
        assertEquals(1_000_000_000L, DevLog.MIN_FREE_BYTES)
    }

    @Test fun theNormalFilesStopAtTheCountAndTheSizeCap() {
        val d = dir(); var t = 1_000L
        val log = DevLog(d, maxBytes = 2_000, maxFiles = 20, now = { t++ }, bufferBytes = 0)
        repeat(5_000) { log.write(line("Net", "ordinary message number $it with padding padding padding")) }
        val files = normal(d)
        assertEquals(20, files.size)
        // Each file stops at its size (the line that fills it may run a little over).
        assertTrue(files.all { it.length() < 2_000 + 200 })
        assertTrue(text(files).contains("number 4999"))
    }

    @Test fun theNormalFilesGoAfterSevenDaysAndTheImportantOnesAfterThirty() {
        val d = dir(); val t = 100L * day
        fun file(name: String, age: Long) = File(d, name).also { it.writeText("x\n"); it.setLastModified(t - age) }
        val n8 = file("devlog-0000000000001-0.txt", 8 * day); val n6 = file("devlog-0000000000002-0.txt", 6 * day)
        val i31 = file("important-0000000000001-0.txt", 31 * day); val i29 = file("important-0000000000002-0.txt", 29 * day)
        DevLog(d, now = { t }).tidy()
        assertFalse(n8.exists()); assertTrue(n6.exists())
        assertFalse(i31.exists()); assertTrue(i29.exists())
    }

    @Test fun theImportantFileKeepsItsFilesToItsOwnCap() {
        val d = dir(); var t = 1_000L
        val log = DevLog(d, now = { t++ }, importantBytes = 1_000, importantFiles = 4, bufferBytes = 0)
        repeat(2_000) { log.write(line("Net", "error number $it with padding padding", "E")) }
        val files = important(d)
        assertEquals(4, files.size)
        assertTrue(files.sumOf { it.length() } < 4 * (1_000 + 120))
        assertTrue(text(files).contains("number 1999"))
    }

    @Test fun anImportantLineSurvivesAFloodThatPushesTheNormalFilesOut() {
        val d = dir(); var t = 1_000L
        val log = DevLog(d, maxBytes = 2_000, maxFiles = 5, now = { t++ }, bufferBytes = 0)
        log.write(line("Net", "lookup failed: api.example.com timed out"))
        log.writeNow("=== Mimir dev log === app 0.21 (dev)")
        repeat(3_000) { log.write(line("Chatty", "noise noise noise noise noise noise $it")) }
        assertFalse("the flood pushed the first lines out of the normal files", text(normal(d)).contains("timed out"))
        assertFalse(text(normal(d)).contains("=== Mimir dev log"))
        val kept = text(important(d))
        assertTrue(kept.contains("api.example.com timed out"))
        assertTrue(kept.contains("=== Mimir dev log"))
    }

    @Test fun theImportantFileTakesErrorsCrashesStallsFailedLookupsAndModelsOnly() {
        val d = dir(); val log = DevLog(d, bufferBytes = 0)
        log.write(line("Engine", "an error happened", "E"))
        log.write(line("Engine", "fatal", "F"))
        log.write(line("Choreographer", "Skipped 8 frames!  The application may be doing too much work on its main thread."))
        log.write(line("Choreographer", "Skipped 3 frames!  The application may be doing too much work on its main thread."))
        log.write(line("HostConnection", "Davey! duration=900ms; Flags=0"))
        log.write(line("Net", "api.example.com direct: 300 ms, answered 200"))
        log.write(line("Net", "api.example.com: the site is down (it answered 503)"))
        log.write(line("MimirStandalone", "check series weather 1: 14 ms FAILED (uo2: this phone has no signal)"))
        log.write(line("NaturalReader", "alba wouldn't load; reading with the phone's own voice"))
        log.write(line("MimirStore", "couldn't read standalone.bin (try 1, 5 bytes): AEADBadTagException: null", "W"))
        log.write(line("Other", "a perfectly ordinary line"))
        log.writeNow("CRASH in thread main:\njava.lang.IllegalStateException: boom")
        val kept = text(important(d))
        assertTrue(kept.contains("an error happened")); assertTrue(kept.contains("fatal"))
        assertTrue(kept.contains("Skipped 8 frames")); assertFalse(kept.contains("Skipped 3 frames"))
        assertTrue(kept.contains("Davey!"))
        assertTrue(kept.contains("answered 503")); assertFalse(kept.contains("answered 200"))
        assertTrue(kept.contains("no signal")); assertTrue(kept.contains("wouldn't load"))
        assertTrue(kept.contains("AEADBadTag")); assertTrue(kept.contains("IllegalStateException"))
        assertFalse(kept.contains("ordinary line"))
        // Everything is still in the normal files too.
        assertTrue(text(normal(d)).contains("ordinary line"))
    }

    @Test fun chatTextNeverGoesToTheImportantFile() {
        val d = dir(); val log = DevLog(d, chatsOn = { true }, bufferBytes = 0)
        log.chat("c1", "you", "my private error question")
        assertTrue(important(d).isEmpty())
    }

    @Test fun aWhisperWritesNothingToTheImportantFileEither() {
        val d = dir(); val log = DevLog(d, whisperOpen = { true }, bufferBytes = 0)
        log.write(line("Net", "failed", "E")); log.writeNow("CRASH x")
        assertTrue(important(d).isEmpty())
    }

    /** Writes lines of [tag] until the log has [n] normal files (so n-1 of them have filled up). */
    private fun untilFiles(d: File, log: DevLog, tag: String, n: Int, tick: () -> Unit = {}) {
        var i = 0
        while (normal(d).size < n && i < 10_000) { tick(); log.write(line(tag, "flooding flooding flooding flooding ${i++}")) }
    }

    @Test fun twoFilesFillingWithinAnHourMakeOneLogBusyLineNamingTheFloodingTag() {
        val d = dir(); var t = 1_000L
        val log = DevLog(d, maxBytes = 1_000, maxFiles = 50, now = { t }, bufferBytes = 0)
        untilFiles(d, log, "Spammy", 2) { t += 10 }
        assertFalse("one file filling is not a flood", text(important(d)).contains("log busy"))
        untilFiles(d, log, "Spammy", 3) { t += 10 }
        val busy = text(important(d)).lines().filter { it.contains("log busy") }
        assertEquals(1, busy.size)
        assertTrue(busy[0], busy[0].contains("Spammy"))
        // Said once an hour, not for every file.
        untilFiles(d, log, "Spammy", 6) { t += 10 }
        assertEquals(1, text(important(d)).lines().count { it.contains("log busy") })
    }

    @Test fun filesThatFillMoreThanAnHourApartAreNotABusyLog() {
        val d = dir(); var t = 1_000L
        val log = DevLog(d, maxBytes = 1_000, maxFiles = 50, now = { t }, bufferBytes = 0)
        untilFiles(d, log, "Net", 2) { t += 10 }
        t += 2 * 3_600_000L
        untilFiles(d, log, "Net", 3) { t += 10 }
        t += 2 * 3_600_000L
        untilFiles(d, log, "Net", 4) { t += 10 }
        assertFalse(text(important(d)).contains("log busy"))
    }

    @Test fun withUnderOneGigabyteFreeOnlyTheImportantFileIsKept() {
        val d = dir(); var t = 1_000L; var free = 5_000_000_000L
        val log = DevLog(d, now = { t }, freeBytes = { free }, bufferBytes = 0)
        log.write(line("Net", "first ordinary line")); assertTrue(normal(d).isNotEmpty())
        free = 500_000_000L; t += 60_000
        log.write(line("Net", "ordinary line while the space is low"))
        log.write(line("Net", "an error while the space is low", "E"))
        assertTrue("the normal files are removed to free the space", normal(d).isEmpty())
        val kept = text(important(d))
        assertTrue(kept.contains("only this important log is kept"))
        assertTrue(kept.contains("an error while the space is low"))
        assertFalse(kept.contains("ordinary line while"))
        // Room again: the full log resumes, and says so.
        free = 3_000_000_000L; t += 60_000
        log.write(line("Net", "ordinary line after the space is back"))
        assertTrue(text(normal(d)).contains("after the space is back"))
        assertTrue(text(important(d)).contains("full log is kept again"))
    }

    @Test fun linesGatherUntilSixtyFourKilobytesOrFiveSecondsAndThenAreWrittenTogether() {
        val d = dir(); var t = 1_000L
        val log = DevLog(d, now = { t })
        repeat(10) { log.write(line("Net", "gathered line $it")) }
        assertTrue("nothing is written line by line", normal(d).isEmpty())
        t += 6_000
        log.write(line("Net", "the line after five seconds"))
        assertEquals(11, text(normal(d)).lines().count { it.isNotBlank() })
        // Size: 64 KB gathers and goes at once.
        val big = "x".repeat(1_000)
        repeat(70) { log.write(line("Net", "$big $it")) }
        assertTrue(text(normal(d)).lines().count { it.isNotBlank() } > 11 + 50)
    }

    @Test fun flushWritesWhatIsGatheredAndTheImportantFileIsNeverLeftWaiting() {
        val d = dir(); val t = 1_000L
        val log = DevLog(d, now = { t })
        log.write(line("Net", "a quiet line"))
        log.write(line("Net", "an error line", "E"))
        assertTrue("the important line is on the disk at once", text(important(d)).contains("an error line"))
        assertTrue(normal(d).isEmpty())
        log.flush()
        assertTrue(text(normal(d)).contains("a quiet line"))
    }

    @Test fun aCrashIsOnTheDiskAtOnceWithTheLinesBeforeIt() {
        val d = dir(); val t = 1_000L
        val log = DevLog(d, now = { t })
        log.write(line("Net", "before the crash"))
        log.writeNow("CRASH in thread main:\njava.lang.IllegalStateException: boom")
        val n = text(normal(d))
        assertTrue(n.contains("before the crash") && n.contains("IllegalStateException"))
    }

    @Test fun aFailedNetworkLookupReachesTheImportantFileButNoLookupWordsDo() {
        val d = dir(); val log = DevLog(d, bufferBytes = 0)
        // Net's real tag is MimirLookup.
        log.write(line("MimirLookup", "geocoding-api.open-meteo.com from this phone: dns -, total 0 ms, failed (UnknownHostException: couldn't find geocoding-api.open-meteo.com)", "W"))
        // What Engine writes for a failed lookup: the kind of lookup and the error, never the words asked.
        log.write(line("MimirStandalone", "lookup failed (look): UnknownHostException", "W"))
        val kept = text(important(d))
        assertTrue(kept.contains("UnknownHostException: couldn't find geocoding"))
        assertTrue(kept.contains("lookup failed (look)"))
    }
}
