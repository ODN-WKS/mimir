package io.github.odnwks.mimir.labs

import android.content.Context
import io.github.odnwks.mimir.BuildConfig
import io.github.odnwks.mimir.audio.VoiceCatalog
import io.github.odnwks.mimir.audio.Voices
import io.github.odnwks.mimir.data.Stations
import io.github.odnwks.mimir.devlog.DevLogHeader
import io.github.odnwks.mimir.devlog.DevLogRunner
import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.online.ChatMode
import io.github.odnwks.mimir.online.Encyclopedia
import io.github.odnwks.mimir.online.Online
import io.github.odnwks.mimir.standalone.Engine
import io.github.odnwks.mimir.standalone.TalkListener
import io.github.odnwks.mimir.standalone.TalkRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The phone's real answers for "Run checks". Slow: call from a background thread. */
class LabsProbes(context: Context) : Probes {
    private val app = context.applicationContext
    private val station get() = Stations.current()
    private val pc get() = Engine.made()?.pc

    override fun version() = "${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})"
    override fun freeBytes() = app.filesDir.usableSpace
    override fun paired() = station?.state?.value?.paired == true
    override fun pcOnline() = station?.state?.value?.pc?.online == true

    override fun askPc(): PcAnswer? {
        val talk = pc?.takeIf { it.canTalk() } ?: return null
        val start = System.nanoTime()
        var reached = -1L
        val done = CountDownLatch(1)
        var ok = false
        val turnId = "labs-" + UUID.randomUUID()
        val said = LChatTurn(turnId + "-q", true, "Reply with one word: yes", at = System.currentTimeMillis())
        val sent = talk.talk(
            TalkRequest("labs-check", turnId, listOf(said), null, emptyList(), "low", null, null, chatOnly = true),
            object : TalkListener {
                override fun filed(f: io.github.odnwks.mimir.link.Filed?, err: String?) { if (reached < 0) reached = ms(start) }
                override fun piece(text: String, thinking: Boolean) { if (reached < 0) reached = ms(start) }
                override fun done(turn: LChatTurn?, err: String?) { ok = err == null && !turn?.text.isNullOrBlank(); if (reached < 0) reached = ms(start); done.countDown() }
                override fun lost() { done.countDown() }
                override fun back() {}
            },
        )
        if (!sent) return PcAnswer(ms(start), false)
        done.await(TIMEOUT_S, TimeUnit.SECONDS)
        runCatching { talk.letGo(turnId) }
        return PcAnswer(if (reached >= 0) reached else ms(start), ok)
    }

    override fun voiceLoads(): Boolean {
        val voices = runCatching { Voices.get(app) }.getOrNull() ?: return false
        voices.onScreen()
        val id = VoiceCatalog.default.id
        val end = System.nanoTime() + 12_000_000_000L
        while (!voices.reader.isReady(id) && System.nanoTime() < end) Thread.sleep(250)
        val ready = voices.reader.isReady(id)
        voices.offScreen()
        return ready
    }

    override fun lookupsOn() = Online.settings.value.lookups
    override fun lookup() = runCatching { Encyclopedia.title("Moon", ChatMode.Normal) != null }.getOrDefault(false)

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000

    companion object { const val TIMEOUT_S = 12L }
}

/** The facts and the log lines a report is made from, read from the running app. */
object LabsReport {
    fun facts(context: Context): ReportFacts {
        val app = context.applicationContext
        val dm = app.resources.displayMetrics
        val swDp = (minOf(dm.widthPixels, dm.heightPixels) / dm.density).toInt()
        val tablet = swDp >= 600
        val s = Stations.current()?.state?.value
        val engine = Engine.made()
        val who = when {
            engine?.onlineAnswers() == true -> "Online"
            engine?.pcAnswers() == true -> if (s?.models?.chatsEngine == "paid") "Online" else "Your PC"
            else -> "This phone"
        }
        return ReportFacts(
            version = BuildConfig.VERSION_NAME, flavor = BuildConfig.FLAVOR, built = BuildConfig.BUILD_TIME,
            android = DevLogHeader.androidName(),
            phoneFamily = Report.phoneFamily(android.os.Build.MANUFACTURER, android.os.Build.MODEL, tablet),
            deviceLabel = if (tablet) "Tablet" else "Phone",
            screenClass = Report.screenClass(swDp), paired = s?.paired == true, whoAnswers = who,
        )
    }

    /** Words to hide wherever they appear: the PC's name and the titles of chats, projects and tasks. */
    fun names(): List<String> {
        val st = Stations.current()
        val s = st?.state?.value
        return buildList {
            runCatching { st?.pcName() }.getOrNull()?.let { add(it) }
            s?.pc?.name?.let { add(it) }
            s?.pastChats?.forEach { add(it.title) }
            s?.projects?.forEach { add(it.name) }
            s?.tasks?.forEach { add(it.title) }
            Engine.made()?.state?.value?.chat?.let { c -> c.name?.let { add(it) } }
        }.filter { it.isNotBlank() && it !in COMMON }.distinct()
    }

    /** The last 50 important log lines, scrubbed. Null when the log is off. */
    fun errors(context: Context): List<String>? {
        val app = context.applicationContext
        if (!DevLogRunner.enabled(app)) return null
        return Scrub.lines(importantLines(File(app.filesDir, "devlog")), names())
    }

    fun importantLines(dir: File): List<String> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("important-") && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }?.takeLast(2)?.flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) }.orEmpty()

    private val COMMON = setOf("Your PC", "This phone", "New chat", "Chat", "Inbox")
}
