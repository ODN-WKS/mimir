package io.github.odnwks.mimir.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The phone reads the PC's session list and asks for it exactly as the PC's side says. */
@RunWith(RobolectricTestRunner::class)
class PcSessionsWireTest {
    @Test fun theListEventIsReadWithAppAndTaskAndState() {
        val line = java.io.File("../../crates/proto/tests/fixtures/events.jsonl").readLines().first { it.contains("\"PcSessions\"") }
        val ev = Wire.event(line).second as Ev.PcSessions
        assertEquals(2, ev.sessions.size)
        assertEquals(LPcState.Working, ev.sessions[0].state)
        assertEquals("Recipe box", ev.sessions[0].project)
        assertFalse(ev.sessions[0].here)
        assertEquals(null, ev.sessions[0].task)
        assertTrue(ev.sessions[1].here)
        assertEquals("1794-1-1", ev.sessions[1].task)
        assertEquals(LPcState.Ended, ev.sessions[1].state)
    }

    @Test fun theAppFlagIsReadAndItsCallsMatchTheProtocol() {
        val o = JSONObject("""{"id":"x","title":"T","project":"P","here":false,"app":true,"state":"idle","last":5}""")
        val s = Wire.pcSession(o)
        assertTrue(s.app)
        assertEquals(LPcState.Idle, s.state)
        assertEquals("\"PcSessions\"", Wire.pcSessions())
        assertEquals("""{"OpenPcSession":{"session":"x"}}""", Wire.openPcSession("x"))
        assertTrue(Wire.PC_SESSIONS in Wire.FEATURES)
    }
}
