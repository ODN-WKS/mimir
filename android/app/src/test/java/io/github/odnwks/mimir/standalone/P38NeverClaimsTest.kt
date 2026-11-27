package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.data.LinkStation
import io.github.odnwks.mimir.link.LinkApi
import io.github.odnwks.mimir.link.Vault
import io.github.odnwks.mimir.online.OnlineModels
import io.github.odnwks.mimir.online.OnlineModelsTest
import io.github.odnwks.mimir.online.Setup
import io.github.odnwks.mimir.ui.testEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * P38 (owner-found): a chat cannot run anything, so what every answerer reached from the phone is
 * told says so and forbids a made-up result. Project work is offered only where it exists: a
 * paired phone whose person is the PC's owner.
 */
@RunWith(RobolectricTestRunner::class)
class P38NeverClaimsTest {
    @get:Rule val tmp = TemporaryFolder()
    private var station: LinkStation? = null

    @After fun down() {
        station?.close()
        OnlineModels.answerWith(null)
        OnlineModels.guard = io.github.odnwks.mimir.online.Net::answerGuard
    }

    private fun told(what: String, projects: Boolean) {
        for (words in listOf(
            "You cannot run, start, stop",
            "never make up its result",
            "never answer \"Stopped\"",
            "wants your replies to stop or change",
            "just do it. When they want work stopped",
            "you can't from this chat",
            "Add no warnings or lecture",
        )) assertTrue("$words in: $what", what.contains(words))
        for (words in listOf(
            "offer it as project work",
            "a task in one of their projects on their PC",
            "Stop button",
            "project names",
        )) assertEquals("$words in: $what", projects, what.contains(words))
        if (!projects) assertFalse("no project is mentioned: $what", what.contains("project", ignoreCase = true))
    }

    @Test fun thePhonesModelIsToldInEveryKindOfChat() {
        val turns = listOf(Turn(true, "run a 5 minute benchmark stressing my entire PC"))
        for (projects in listOf(false, true)) {
            for (rules in listOf<String?>(null, "Be brief.")) for (online in listOf(false, true)) {
                told(Prompt.system(rules, online, projects = projects), projects)
                for (format in Format.entries) told(Prompt.build(format, turns, rules = rules, online = online, projects = projects), projects)
            }
            told(Prompt.system("Be brief.", online = true, ask = "Ask.", projects = projects), projects)
        }
    }

    @Test fun anOnlineModelAskedFromThePhoneIsTold() {
        for (projects in listOf(false, true)) {
            told(OnlineModels.system(null, projects), projects)
            told(OnlineModels.system("Be brief.", projects), projects)
        }
    }

    // ---- What the phone really sends: its own link says whether project work exists ----

    private class Plain : Vault.Codec {
        override fun seal(text: String): ByteArray = text.toByteArray()
        override fun open(bytes: ByteArray) = String(bytes)
    }

    /** A PC that is never reached, so the phone answers. */
    private class DownLink : LinkApi {
        override fun start(key: ByteArray, relays: Boolean) = 1L
        override fun stop(handle: Long) {}
        override fun addHint(handle: Long, addr: String) {}
        override fun pairStart(handle: Long, code: String, phoneName: String) = "{}"
        override fun pairFinish(handle: Long, code: String, timeoutMs: Long) = "{}"
        override fun connect(handle: Long, pcId: String) = "The PC didn't answer."
        override fun call(handle: Long, json: String) = -1L
        override fun nextEvent(handle: Long, timeoutMs: Long): String? { Thread.sleep(minOf(timeoutMs, 50)); return null }
        override fun connected(handle: Long) = false
        override fun networkChanged(handle: Long) {}
        override fun endedBecause(handle: Long) = ""
    }

    private fun link(pcId: String?, person: String?): LinkStation {
        station?.close()
        val file = File(tmp.newFolder(), "link.vault")
        fun vault() = Vault(file, Plain(), { ByteArray(32) }, tries = 1, pause = {})
        vault().let { it.save(it.load().copy(pcId = pcId, pcName = pcId?.let { "Test PC" }, person = person)) }
        return LinkStation(vault(), "0.21", relays = false, hints = emptyList(), secure = { true }, api = DownLink()).also { station = it }
    }

    private fun Engine.askAndWait() {
        assertTrue(ask("run a 5 minute benchmark stressing my entire PC"))
        val end = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < end && (state.value.writing || state.value.chat.turns.size < 2)) Thread.sleep(10)
    }

    /** What the phone's own model was sent. */
    private fun onPhone(pc: LinkStation): String {
        val prompts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val runner = object : Runner {
            override fun open(path: String) = true
            override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String {
                prompts += prompt
                sink.onPiece("No.".toByteArray())
                return """{"gen_tokens":1,"gen_ms":100}"""
            }
            override fun stop() {}
            override fun close() {}
        }
        val e = testEngine(tmp.newFolder(), ready = true, runner = runner).also { it.pc = pc }
        e.askAndWait()
        return prompts.last()
    }

    /** What an online model asked from the phone was sent. */
    private fun online(pc: LinkStation): String {
        OnlineModels.init(tmp.newFolder(), OnlineModelsTest.PlainSealer())
        OnlineModels.guard = { }
        OnlineModels.save(Setup("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "sk-or-v1-ABCDEFGHIJKLMNOP1234567890wxyz", "a/b:free"))
        OnlineModels.answerWith("openrouter")
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        val before = OnlineModels.transport
        OnlineModels.transport = { req, onLine ->
            req.body?.let { sent += it }
            "data: {\"choices\":[{\"delta\":{\"content\":\"No.\"}}]}\n\ndata: [DONE]".lines().forEach { onLine?.invoke(it) }
            OnlineModels.Resp(200, "")
        }
        try {
            testEngine(tmp.newFolder(), ready = false, supported = false).also { it.pc = pc }.askAndWait()
            return org.json.JSONObject(sent.last()).getJSONArray("messages").getJSONObject(0).getString("content")
        } finally {
            OnlineModels.transport = before
            OnlineModels.answerWith(null)
        }
    }

    @Test fun aPhoneWithNoPcOffersNoProjectWork() {
        val pc = link(pcId = null, person = null)
        told(onPhone(pc), false)
        told(online(pc), false)
    }

    @Test fun aPersonWhoIsNotTheOwnerIsOfferedNoProjectWork() {
        val pc = link(pcId = "pc-1", person = "guest")
        told(onPhone(pc), false)
        told(online(pc), false)
    }

    @Test fun theOwnersPairedPhoneOffersProjectWork() {
        val pc = link(pcId = "pc-1", person = "owner")
        told(onPhone(pc), true)
        told(online(pc), true)
    }
}
