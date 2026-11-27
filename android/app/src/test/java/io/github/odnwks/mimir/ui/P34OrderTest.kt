package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.Mapping
import io.github.odnwks.mimir.data.Task
import io.github.odnwks.mimir.link.Ev
import io.github.odnwks.mimir.link.LProject
import io.github.odnwks.mimir.link.LStatus
import io.github.odnwks.mimir.link.LinkTask
import io.github.odnwks.mimir.link.Wire
import io.github.odnwks.mimir.standalone.LocalChat
import io.github.odnwks.mimir.standalone.LocalState
import io.github.odnwks.mimir.standalone.Turn
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** P34: projects, chats and tasks are newest first by the person's last message; replies move nothing. */
@RunWith(RobolectricTestRunner::class)
class P34OrderTest {
    private fun link(id: String, project: String, created: Long, you: Long, active: Long) =
        LinkTask(id, id, project, "Done", false, null, created, emptyList(), activeUnix = active, youMs = you)

    private fun task(id: String, created: Long, you: Long, active: Long, status: String = "Done") = Task(
        id, id, "Garden planner", "Sonnet", "", emptyList(), emptyList(), null, null, null,
        status = status, createdUnix = created, activeUnix = active, youMs = you,
    )

    private fun page(vararg names: String) = LStatus(names.map { LProject("/p/$it", it, null, null, 0, 0, "", null) }, null, null, emptyList(), 0)

    @Test fun projectsAndTheirTasksFollowThePersonsMessages() {
        val tasks = listOf(
            link("beds", "/p/Garden planner", 100, 200_000, 9_000),
            link("soup", "/p/Recipe box", 100, 500_000, 600),
            link("bread", "/p/Recipe box", 400, 300_000, 8_000),
        )
        val got = Mapping.projects(page("Garden planner", "Empty one", "Recipe box"), tasks, emptyMap(), 10_000)
        assertEquals(listOf("Recipe box", "Garden planner", "Empty one"), got.map { it.name })
        assertEquals(listOf("soup", "bread"), got[0].tasks.map { it.taskId })
    }

    @Test fun chatsFollowThePersonsMessagesNotTheReplies() {
        val old = LocalChat("old", "m", listOf(Turn(true, "soil", id = "a", at = 1_000), Turn(false, "loam", id = "a.a", at = 9_000)), at = 9_000)
        val new = LocalChat("new", "m", listOf(Turn(true, "soup", id = "b", at = 2_000), Turn(false, "leek", id = "b.a", at = 2_500)), at = 2_500)
        val local = LocalState(chat = LocalChat("empty", "m", emptyList()), earlier = listOf(old, new))
        val got = menuChats(ExampleData.awake().copy(pcTalks = true), local, 10_000)
        assertEquals(listOf("new", "old"), got.map { it.id })
        assertEquals(listOf(2_000L, 1_000L), got.map { it.at })
    }

    @Test fun recentActivityPutsATaskByThePersonsMessage() {
        val state = ExampleData.awake().copy(
            pastChats = emptyList(), pcTalks = true,
            tasks = listOf(task("replied", 100, 200_000, 9_000), task("asked", 100, 500_000, 600), task("silent", 300, 0, 9_500)),
        )
        assertEquals(listOf("task:asked", "task:silent", "task:replied"), recentActivity(state, null, 10_000_000).map { it.key })
    }

    @Test fun allTasksDoesNotMoveATaskForItsState() {
        val list = listOf(task("working", 100, 200_000, 9_000, "Working"), task("done", 100, 500_000, 600))
        assertEquals(listOf("done", "working"), allTasksOrder(list).map { it.id })
    }

    @Test fun thePcsFieldIsReadAndAnOlderPcSendsNone() {
        val line = { extra: String ->
            """{"seq":1,"event":{"Task":{"header":{"id":"a","title":"a","project":"/p","status":"Done","created_unix":100,"mode":"Ask"$extra}}}}"""
        }
        assertEquals(500_000L, (Wire.event(line(",\"you_ms\":500000")).second as Ev.TaskHeader).task.youMs)
        assertEquals(0L, (Wire.event(line("")).second as Ev.TaskHeader).task.youMs)
    }
}
