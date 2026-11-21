package io.github.odnwks.mimir.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeStationTest {
    private val station = FakeStation()
    private val s get() = station.state.value

    @Test fun exampleDataMatchesTheBoards() {
        assertEquals(4, s.needsYou.size)
        assertEquals(2, s.pausedOn)
        assertEquals(listOf("Mímir", "Recipe box", "Budget-App", "Garden planner", "Garden hub", "General"), s.projects.map { it.name })
        assertEquals("All 7 passing", s.project("Mímir")!!.words)
        assertEquals("1 of 5 failing", s.project("Budget-App")!!.words)
        assertTrue("no \"No checks yet\" (Stage 1)", "No checks" !in s.project("Mímir")!!.words)
        assertEquals(3, s.needsYou.count { it.project == "Mímir" })
        assertEquals(5, s.updates.size)
        assertEquals(1, s.updates.count { it.heard == Heard.New })
        assertEquals("u-allow", s.player.updateId)
        assertEquals(3, s.pc.tasksRunning)
        assertTrue(s.pc.online)
        assertEquals(5, s.chat.size)
    }

    @Test fun everyNeedsYouItemThatNamesATaskFindsIt() {
        s.needsYou.mapNotNull { it.taskId }.forEach { assertNotNull(s.task(it)) }
        s.projects.flatMap { it.tasks }.mapNotNull { it.taskId }.forEach { assertNotNull(s.task(it)) }
    }

    @Test fun allowingTheEditClearsThePermission() {
        station.answer("p-remote", Answer.Allow)
        assertEquals(3, s.needsYou.size)
        assertEquals(1, s.pausedOn)
        assertNull(s.task(ExampleData.TASK_ALLOW)!!.permission)
        assertNotNull(s.task(ExampleData.TASK_ALLOW)!!.question)
    }

    @Test fun releasingAnswersTheQuestion() {
        station.answer("q-release", Answer.Release)
        assertNull(s.task(ExampleData.TASK_ALLOW)!!.question)
        assertEquals(3, s.needsYou.size)
    }

    @Test fun openingADesignKeepsItWaiting() {
        station.answer("d-keep", Answer.Opened)
        assertEquals(4, s.needsYou.size)
    }

    @Test fun checkingAFixClearsIt() {
        station.answer("f-budgetapp", Answer.ItWorks)
        assertFalse(s.needsYou.any { it.id == "f-budgetapp" })
    }

    @Test fun aMessageStartsATaskOrFilesAnIdea() {
        station.send("Make the gauges bigger")
        val task = s.chat.last() as ChatEntry.Mine
        assertEquals(FilingKind.NewTask, task.filing!!.kind)
        assertEquals("New task in Mímir", task.filing.text)
        assertEquals(4, s.pc.tasksRunning)
        station.send("Idea: a weekly summary")
        assertEquals(FilingKind.Idea, (s.chat.last() as ChatEntry.Mine).filing!!.kind)
        station.send("   ")
        assertEquals(7, s.chat.size)
    }

    @Test fun undoRemovesTheMessage() {
        station.send("Make the gauges bigger")
        val id = s.chat.last().id
        station.undoMessage(id)
        assertFalse(s.chat.any { it.id == id })
        assertEquals(3, s.pc.tasksRunning)
    }

    @Test fun whileThePcSleepsMessagesWaitOnThePhone() {
        val asleep = FakeStation(ExampleData.asleep())
        assertFalse(asleep.state.value.pc.online)
        assertEquals(3, asleep.state.value.needsYou.size)
        asleep.send("Check the gauges")
        val m = asleep.state.value.chat.last() as ChatEntry.Mine
        assertEquals(FilingKind.Waiting, m.filing!!.kind)
        assertEquals("Cancel", m.filing.action)
    }

    @Test fun replyGoesIntoTheTask() {
        station.reply(ExampleData.TASK_ALLOW, "Go ahead")
        assertEquals("Go ahead", s.task(ExampleData.TASK_ALLOW)!!.messages.last().text)
    }

    @Test fun stoppingATaskOnlyCountsOnce() {
        station.stopTask(ExampleData.TASK_CELL)
        station.stopTask(ExampleData.TASK_CELL)
        assertTrue(s.task(ExampleData.TASK_CELL)!!.stopped)
        assertEquals(2, s.pc.tasksRunning)
    }

    @Test fun startingTasks() {
        val id = station.startTask("Tidy the logs folder", "Garden planner", incognito = false)
        assertEquals("Garden planner", s.task(id)!!.project)
        station.endIncognito()
        assertNull(s.incognito)
        assertFalse(s.chat.any { it is ChatEntry.IncognitoRunning })
        assertEquals(FakeStation.INCOGNITO, station.startTask("Read the letter", "General", incognito = true))
        assertEquals("General", s.incognito!!.project)
        assertEquals(1, s.chat.count { it is ChatEntry.IncognitoRunning })
    }

    @Test fun thePlayer() {
        station.playPause("u-allow")
        assertFalse(s.player.playing)
        station.playPause("u-incog")
        assertTrue(s.player.playing)
        assertEquals("u-incog", s.player.updateId)
        assertEquals(Heard.Heard, s.updates.first { it.id == "u-incog" }.heard)
        station.playNext()
        assertEquals("u-models", s.player.updateId)
        station.back10()
        assertEquals(0, s.player.positionSeconds)
        station.cycleSpeed()
        assertEquals(1.5f, s.player.speed)
        station.cycleSpeed(); station.cycleSpeed(); station.cycleSpeed()
        assertEquals(1.25f, s.player.speed)
    }

    @Test fun theLastUpdateStopsThePlayer() {
        station.playPause("u-budgetapp")
        station.playNext()
        assertFalse(s.player.playing)
    }

    @Test fun settings() {
        station.selectModel("qwen-coding")
        assertEquals("Qwen 3.6 Coding", s.models.selectedName)
        station.setSwitchToFree(false)
        assertFalse(s.models.switchToFree)
        station.setFingerprintLock(false)
        assertFalse(s.fingerprintLock)
        station.removePhone("s25")
        assertEquals(2, s.phones.size)
        station.removePhone("s8")
        assertEquals(listOf("s25"), s.phones.map { it.id })
    }
}
