package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Engine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A chat message the PC may file as work says whether the person typed it. */
@RunWith(RobolectricTestRunner::class)
class TypedChatTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val pc = FakePc()
    private lateinit var engine: Engine

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
        engine = testEngine(tmp.newFolder(), ready = true).also { it.pc = pc }
    }

    @After fun tearDown() { Engine.PC_SILENT_MS = 30_000L }

    private fun settle() {
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(20)
        while (engine.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse("the answer never finished", engine.state.value.writing)
    }

    @Test fun typedFromTheBoxAndEditedButNotAStarterOrTryAgain() {
        engine.ask("typed in the box", typed = true); settle()
        engine.tryAgain(); settle()
        engine.ask("a starter"); settle()
        engine.editMessage(engine.state.value.chat.turns.last { it.user }.id, "edited words"); settle()
        assertEquals(listOf(true, false, false, true), pc.sent.map { it.typed })
    }

    @Test fun aMessageWaitingForThePcKeepsItsMark() {
        engine = testEngine(tmp.newFolder(), ready = false).also { it.pc = pc }
        engine.setUsePc(true)
        pc.reachable = false
        engine.ask("typed while away", typed = true)
        engine.ask("not typed")
        pc.reachable = true
        engine.drainQueue(); settle()
        val until = System.currentTimeMillis() + 5_000
        while (pc.sent.size < 2 && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(listOf(true, false), pc.sent.map { it.typed })
    }
}
