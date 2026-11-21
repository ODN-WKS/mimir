package io.github.odnwks.mimir.devlog

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevLogTest {
    private fun dir(): File = Files.createTempDirectory("devlog").toFile()
    /** The normal files' lines (the important file has its own tests). */
    private fun lines(d: File) = d.listFiles().orEmpty().filter { it.name.startsWith("devlog-") }.sortedBy { it.name }.flatMap { it.readLines() }
    private fun line(tag: String, msg: String) = "11-19 12:00:00.000  1234  1240 I $tag: $msg"

    @Test fun rotatesAtTheSizeAndKeepsTheLastFiles() {
        val d = dir(); var t = 1_000L
        val log = DevLog(d, bufferBytes = 0, maxBytes = 200, maxFiles = 5, now = { t++ })
        repeat(100) { log.write(line("Tag", "message number $it with some padding to make it longer")) }
        // The normal files (a flood this fast also makes a "log busy" note in the important file).
        val files = d.listFiles().orEmpty().filter { it.name.startsWith("devlog-") }
        assertEquals(5, files.size)
        assertTrue(files.all { it.length() < 400 })
        assertTrue(lines(d).last().contains("number 99"))
    }

    @Test fun filesOlderThanSevenDaysGo() {
        val d = dir(); var t = 20L * 24 * 3600 * 1000
        val old = File(d, "devlog-0000000000001-0.txt").also { it.writeText("old\n"); it.setLastModified(t - 8L * 24 * 3600 * 1000) }
        val fresh = File(d, "devlog-0000000000002-0.txt").also { it.writeText("new\n"); it.setLastModified(t - 6L * 24 * 3600 * 1000) }
        DevLog(d, bufferBytes = 0, now = { t }).tidy()
        assertFalse(old.exists()); assertTrue(fresh.exists())
    }

    @Test fun noiseIsLeftOut() {
        assertTrue(DevLog.isNoise(line("llama", ".")))
        assertTrue(DevLog.isNoise(line("llama", "....")))
        assertTrue(DevLog.isNoise(line("ViewRootImpl@1[Main]", "setFrameRate: 60")))
        assertTrue(DevLog.isNoise(line("ViewRootImpl@5d01606[MainActivity]", "ViewPostIme pointer 1")))
        assertTrue(DevLog.isNoise(line("art", "Background concurrent copying GC freed 1MB, paused 1ms")))
        assertFalse(DevLog.isNoise(line("Net", "api.example.com direct: 300 ms, answered 200")))
        assertFalse(DevLog.isNoise(line("Choreographer", "Skipped 40 frames!")))
        val d = dir(); val log = DevLog(d, bufferBytes = 0)
        log.write(line("llama", ".")); log.write(line("Net", "kept"))
        assertEquals(1, lines(d).size)
    }

    @Test fun nothingIsWrittenDuringAWhisperExceptOneNote() {
        val d = dir(); var w = false
        val log = DevLog(d, bufferBytes = 0, whisperOpen = { w })
        log.write(line("A", "before"))
        w = true
        log.write(line("A", "secret one")); log.write(line("A", "secret two")); log.writeNow("CRASH x")
        w = false
        log.write(line("A", "after"))
        val all = lines(d)
        assertEquals(listOf("Whisper: not logged"), all.filter { it.startsWith("Whisper") })
        assertFalse(all.any { it.contains("secret") || it.startsWith("CRASH") })
        assertTrue(all.first().contains("before") && all.last().contains("after"))
    }

    @Test fun switchingOffDeletesTheFiles() {
        val d = dir(); val log = DevLog(d, bufferBytes = 0)
        log.write(line("A", "x"))
        assertTrue(d.listFiles().orEmpty().isNotEmpty())
        log.clear()
        assertTrue(d.listFiles().orEmpty().isEmpty())
    }

    @Test fun theCrashIsWrittenAtOnce() {
        val d = dir(); val log = DevLog(d, bufferBytes = 0)
        log.writeNow("CRASH in thread main:\njava.lang.IllegalStateException: boom")
        assertTrue(lines(d).any { it.contains("IllegalStateException") })
    }

    @Test fun headerHasEverything() {
        val h = DevLogHeader.text("0.21 (dev)", "2026-11-19 20:00", "Samsung EX-100", "9 (API 28)", 3700, "gemma", "think off, effort Balanced", false, true, "Ingrid")
        listOf("0.21", "2026-11-19", "EX-100", "API 28", "3700", "gemma", "effort Balanced", "paired no", "lookups on", "Ingrid").forEach { assertTrue(it, h.contains(it)) }
    }

    @Test fun chatLinesHaveIdRoleTimeAndTextCutAt4000() {
        val d = dir(); val log = DevLog(d, bufferBytes = 0, chatsOn = { true })
        log.chat("c1", "you", "hello\nthere")
        log.chat("c1", "answer", "x".repeat(5000))
        val all = lines(d)
        assertTrue(all[0].startsWith("CHAT c1 you ") && all[0].endsWith("hello\\nthere"))
        assertTrue(all[0].contains(Regex("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d")))
        assertEquals("x".repeat(4000) + "[cut]", all[1].substringAfter("answer ").substringAfter(" ").substringAfter(" "))
    }

    @Test fun chatLinesNeedTheSwitch() {
        val d = dir(); val log = DevLog(d, bufferBytes = 0, chatsOn = { false })
        log.chat("c1", "you", "private")
        assertTrue(lines(d).isEmpty())
    }

    @Test fun whispersAreNeverLoggedEvenWithBothSwitchesOn() {
        val d = dir(); var w = true
        val log = DevLog(d, bufferBytes = 0, whisperOpen = { w }, chatsOn = { true })
        val watch = ChatWatch(log)
        watch.see("c0", false, false, emptyList())
        watch.see("w1", true, false, listOf(SeenTurn(true, "whisper secret")))
        log.chat("w1", "you", "whisper secret")
        assertFalse(lines(d).any { it.contains("secret") })
        w = false
        watch.see("c1", false, false, listOf(SeenTurn(true, "ordinary")))
        assertTrue(lines(d).any { it.contains("ordinary") })
    }

    @Test fun switchingChatsOffRemovesOnlyTheChatLines() {
        val d = dir(); val log = DevLog(d, bufferBytes = 0, chatsOn = { true })
        log.write(line("Net", "keep me")); log.chat("c1", "you", "gone"); log.write(line("Net", "keep too"))
        log.removeChatLines()
        val all = lines(d)
        assertEquals(2, all.size)
        assertFalse(all.any { it.contains("gone") })
    }

    @Test fun theWatchLogsEachMessageOnceAndAnswersWhenFinished() {
        val d = dir(); val log = DevLog(d, bufferBytes = 0, chatsOn = { true }); val watch = ChatWatch(log)
        val old = listOf(SeenTurn(true, "earlier"), SeenTurn(false, "earlier answer"))
        watch.see("c1", false, false, old)                       // already there: not replayed
        val asked = old + SeenTurn(true, "new question")
        watch.see("c1", false, false, asked); watch.see("c1", false, false, asked)
        watch.see("c1", false, true, asked)                      // still writing: nothing more
        val answered = asked + SeenTurn(false, "new answer")
        watch.see("c1", false, true, answered)                   // last answer not final yet
        watch.see("c1", false, false, answered)
        val all = lines(d)
        assertEquals(listOf("you", "answer"), all.map { it.split(" ")[2] })
        assertTrue(all[0].endsWith("new question") && all[1].endsWith("new answer"))
        // A brand-new chat's first message counts.
        watch.see("c2", false, false, listOf(SeenTurn(true, "first words")))
        assertTrue(lines(d).last().endsWith("first words"))
    }
}
