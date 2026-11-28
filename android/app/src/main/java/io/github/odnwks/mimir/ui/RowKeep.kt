package io.github.odnwks.mimir.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.odnwks.mimir.data.Project

/**
 * What the phone's lists keep on this phone only (nothing here is sent to the PC, and nothing is lost):
 * chats and projects put away under "Archived", projects put on top, and which projects have their
 * finished tasks folded. Kept in [prefs] (the side menu's own file).
 */
class RowKeep(private val prefs: SharedPreferences?) {
    /** An archived chat's id and when it was put away: a message in it since brings it back. */
    var archivedChats: Map<String, Long> by mutableStateOf(
        prefs?.getStringSet(CHATS, null).orEmpty().mapNotNull { line ->
            line.substringBefore('\n').toLongOrNull()?.let { at -> line.substringAfter('\n') to at }
        }.toMap(),
    )
        private set
    var archivedProjects: Set<String> by mutableStateOf(prefs?.getStringSet(PROJECTS, null).orEmpty().toSet())
        private set
    /** Newest first: the last one put on top leads. */
    var topProjects: List<String> by mutableStateOf(prefs?.getString(TOP, null).orEmpty().split('\n').filter { it.isNotEmpty() })
        private set
    var foldedDone: Set<String> by mutableStateOf(prefs?.getStringSet(DONE, null).orEmpty().toSet())
        private set

    fun archiveChat(id: String, now: Long = System.currentTimeMillis()) = saveChats(archivedChats + (id to now))
    fun bringBackChat(id: String) = saveChats(archivedChats - id)
    fun chatArchived(id: String, at: Long): Boolean = archivedChats[id]?.let { at <= it } == true

    fun archiveProject(name: String) = saveProjects(archivedProjects + name)
    fun bringBackProject(name: String) = saveProjects(archivedProjects - name)

    fun putOnTop(name: String) = saveTop(listOf(name) + (topProjects - name))
    fun takeOffTop(name: String) = saveTop(topProjects - name)

    fun toggleDone(project: String) {
        foldedDone = if (project in foldedDone) foldedDone - project else foldedDone + project
        prefs?.edit()?.putStringSet(DONE, foldedDone)?.apply()
    }

    /** The projects the menu lists: those put on top first, the archived ones left out. */
    fun listed(projects: List<Project>): List<Project> = projects.filter { it.name !in archivedProjects }
        .sortedBy { p -> topProjects.indexOf(p.name).let { if (it < 0) Int.MAX_VALUE else it } }

    fun archived(projects: List<Project>): List<Project> = projects.filter { it.name in archivedProjects }

    /** A project deleted for good leaves no trace here either. */
    fun forgetProject(name: String) {
        bringBackProject(name); takeOffTop(name)
        if (name in foldedDone) toggleDone(name)
    }

    /** Unpaired: the PC's project names go from this phone. */
    fun forgetAllProjects() {
        saveProjects(emptySet()); saveTop(emptyList())
        foldedDone = emptySet(); prefs?.edit()?.remove(DONE)?.apply()
    }

    private fun saveChats(v: Map<String, Long>) {
        archivedChats = v
        prefs?.edit()?.putStringSet(CHATS, v.map { (id, at) -> "$at\n$id" }.toSet())?.apply()
    }
    private fun saveProjects(v: Set<String>) { archivedProjects = v; prefs?.edit()?.putStringSet(PROJECTS, v)?.apply() }
    private fun saveTop(v: List<String>) { topProjects = v; prefs?.edit()?.putString(TOP, v.joinToString("\n"))?.apply() }

    companion object {
        private const val CHATS = "archived_chats"
        private const val PROJECTS = "archived_projects"
        private const val TOP = "top_projects"
        private const val DONE = "folded_done"
    }
}

/** The one [RowKeep] of the running app (a check without one keeps nothing). */
val LocalRowKeep = staticCompositionLocalOf { RowKeep(null) }

/** The side menu's folds, for the hold menu's "Collapse". */
val LocalMenuFold = staticCompositionLocalOf<MenuFold?> { null }
