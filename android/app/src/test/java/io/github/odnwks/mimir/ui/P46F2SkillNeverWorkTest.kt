package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.online.FakeWeb
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.PcTalk
import io.github.odnwks.mimir.standalone.Skill
import io.github.odnwks.mimir.standalone.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P46 fix round 2, "found while hunting" A: a skill is for one message. It never goes with a message the PC
 * may file as project work (where the PC keeps it as the task's standing rules, or drops it), and then the
 * message's line no longer says a skill was used. A message the PC answers as a chat takes it, sent as a chat.
 */
@RunWith(RobolectricTestRunner::class)
class P46F2SkillNeverWorkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val web = FakeWeb()
    private val fake = FakePc()

    /** The PC as the link shows it: whether it can be told "chat", and the project a message names. */
    private class Pc(val inner: FakePc) : PcTalk by inner {
        @Volatile var chatOnly = true
        override fun canChatOnly() = chatOnly
        override fun projectNamed(text: String): String? = "Garden planner".takeIf { text.startsWith("Garden planner:") }
    }
    private val pc = Pc(fake)

    @Before fun setUp() {
        web.install()
        Online.set(Online.Settings())
    }

    private fun engine(turns: List<Turn> = emptyList()): Engine = testEngine(tmp.newFolder(), ready = true, turns = turns).also { it.pc = pc }

    private fun settle(e: Engine) {
        val until = System.currentTimeMillis() + 5_000
        Thread.sleep(20)
        while (e.state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse("the answer never finished", e.state.value.writing)
    }

    @Test fun aChatMessageTakesItsSkillAndGoesAsAChat() {
        val e = engine()
        assertTrue(e.send("How do tides work?", skill = Skill.Teach)); settle(e)
        val r = fake.sent.single()
        assertTrue("the skill's instruction goes with the rules: ${r.rules}", r.rules.orEmpty().contains(Skill.Teach.instruction))
        assertTrue("a message with a skill is answered as a chat, never filed as work", r.chatOnly)
        assertEquals(Skill.Teach.id, e.state.value.chat.turns.first { it.user }.skill)
    }

    @Test fun aMessageNamingAProjectGoesWithoutItsSkill() {
        val e = engine()
        assertTrue(e.send("Garden planner: tidy the readme", skill = Skill.Writing)); settle(e)
        val r = fake.sent.single()
        assertFalse("no skill goes with a message that may become work: ${r.rules}", r.rules.orEmpty().contains(Skill.Writing.instruction))
        assertFalse("it can still become work", r.chatOnly)
        assertNull("its line no longer says a skill was used", e.state.value.chat.turns.first { it.user }.skill)
    }

    @Test fun aMessageInAnOpenTaskGoesWithoutItsSkill() {
        val e = engine(listOf(Turn(true, "Garden planner: add a watering list", id = "u1", at = 1), Turn(false, "Filed under Garden planner", id = "u1.a", at = 2, task = "t1")))
        assertTrue(e.send("and sort it by day", skill = Skill.Teach)); settle(e)
        val st = e.state.value
        assertEquals("sent to the PC (problem=${st.problem}, turns=${st.chat.turns.map { it.id + ":" + it.ran }})", 1, fake.sent.size)
        val r = fake.sent.single()
        assertEquals("t1", r.task)
        assertFalse("no skill goes into the task: ${r.rules}", r.rules.orEmpty().contains(Skill.Teach.instruction))
        assertNull(e.state.value.chat.turns.last { it.user }.skill)
    }

    @Test fun aPcThatCannotBeToldChatGetsNoSkill() {
        pc.chatOnly = false
        val e = engine()
        assertTrue(e.send("How do tides work?", skill = Skill.Teach)); settle(e)
        val r = fake.sent.single()
        assertFalse(r.rules.orEmpty().contains(Skill.Teach.instruction))
        assertNull(e.state.value.chat.turns.first { it.user }.skill)
    }
}
