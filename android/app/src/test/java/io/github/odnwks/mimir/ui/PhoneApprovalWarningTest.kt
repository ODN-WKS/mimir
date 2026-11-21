package io.github.odnwks.mimir.ui

import io.github.odnwks.mimir.data.Mapping
import io.github.odnwks.mimir.data.NeedsKind
import io.github.odnwks.mimir.data.NeedsYouItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PCS-10: an action flagged leaves_pc = true shows the "Leaves this PC and is permanent, so check it first"
 * warning in the phone's approval UI.
 */
class PhoneApprovalWarningTest {

    private fun item(leavesPC: Boolean) = NeedsYouItem(
        id = "test-ask",
        kind = NeedsKind.Permission,
        project = "My Project",
        age = "1 min",
        text = "Run git push origin main",
        taskId = "task-1",
        choices = listOf("Allow", "Don't Allow"),
        serious = true,
        leavesPC = leavesPC,
    )

    @Test
    fun action_with_leaves_pc_true_shows_warning() {
        val warning = Mapping.approvalWarning(item(leavesPC = true))
        assertEquals(
            "Warning string must match the PC's wording",
            Mapping.LEAVES_PC_WARNING,
            warning,
        )
    }

    @Test
    fun action_without_leaves_pc_shows_no_warning() {
        val warning = Mapping.approvalWarning(item(leavesPC = false))
        assertNull("No warning for a local action", warning)
    }

    @Test
    fun mapping_needs_passes_leaves_pc_through() {
        val lItem = io.github.odnwks.mimir.link.LItem(
            askId = "a1",
            kind = "Permission",
            project = "/home/x/app",
            task = "t1",
            title = "Push",
            text = "git push origin main",
            preview = emptyList(),
            choices = listOf("Allow", "Don't Allow"),
            serious = true,
            created = 1000L,
            link = null,
            leavesPC = true,
        )
        val name = { p: String -> p.substringAfterLast('/') }
        val mapped = Mapping.needs(listOf(lItem), name, 1060L)
        assertEquals(1, mapped.size)
        assertEquals(true, mapped[0].leavesPC)
        assertEquals(Mapping.LEAVES_PC_WARNING, Mapping.approvalWarning(mapped[0]))
    }
}
