package io.github.odnwks.mimir.data

import android.os.Build
import io.github.odnwks.mimir.link.Ask
import io.github.odnwks.mimir.link.Ev
import io.github.odnwks.mimir.link.Filed
import io.github.odnwks.mimir.link.ItemAnswer
import io.github.odnwks.mimir.link.LEntry
import io.github.odnwks.mimir.link.LItem
import io.github.odnwks.mimir.link.LStatus
import io.github.odnwks.mimir.link.LinkTask
import io.github.odnwks.mimir.link.Native
import io.github.odnwks.mimir.link.Vault
import io.github.odnwks.mimir.link.Wire
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The real Mímir on the PC, over the encrypted link (phone-core).
 *
 * *   The general chat sends each message to the PC, which files it (a status question, an idea,
 *     a request, or the Inbox when the project isn't clear). Each message has an id, so one sent
 *     again after the link dropped is done once.
 * *   Needs you is the PC's one list for every phone and window; the first answer wins.
 * *   Status and the project pages come from the PC's status page (its run logs, tasks and git).
 *
 * Messages sent while the PC can't be reached wait in the [Vault] and go out once it can.
 */
class LinkStation(
    private val vault: Vault,
    private val appVersion: String,
    relays: Boolean,
    hints: List<String>,
    /** Whether the phone has a screen lock (pairing without one is warned about, never refused). */
    private val secure: () -> Boolean,
    /** Where the staying-in-touch mode is kept. */
    private val stayStore: StayStore = StayStore.InMemory(),
    /** The model new tasks from this phone start on (kept on the phone). */
    private val modelStore: ModelStore = ModelStore.InMemory(),
    /** Where update audio is kept (fetched from the PC, so it plays while the PC sleeps). */
    private val audioDir: java.io.File? = null,
    private val playback: io.github.odnwks.mimir.audio.Playback = io.github.odnwks.mimir.audio.Playback.None(),
    private val heard: HeardStore = HeardStore.InMemory(),
    /** Whether this phone has a network at all (so it's not the PC that's away). */
    private val network: () -> Boolean = { true },
    /** The PC's project list, kept so the picker works while the PC is away. */
    private val projectStore: ProjectStore = ProjectStore.InMemory(),
    /** The name this device shows on the PC (the check build can set it; otherwise maker and model). */
    private val deviceName: String? = null,
    /** Where recordings spoken while the PC is away wait (one sound file each). */
    private val recordDir: java.io.File? = null,
    /** Whether the app asks for a fingerprint or PIN to open (Settings › App lock). */
    private val lockStore: LockStore = LockStore.InMemory(),
    /** Whether the app is on the screen (closed, the link is tried only while something wants it). */
    private val onScreen: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    /** The link itself (the real one; a check stands in one of its own). */
    private val api: io.github.odnwks.mimir.link.LinkApi = io.github.odnwks.mimir.link.NativeLink,
    private val identityStore: io.github.odnwks.mimir.data.store.IdentityStore? = null,
    private val chatRepo: io.github.odnwks.mimir.data.store.ChatRepository? = null,
) : Station, io.github.odnwks.mimir.standalone.PcTalk {
    private val lock = Any()
    /** PHD-15: wakes the loop when network state or pairing state changes so it doesn't poll at 200 ms. */
    private val loopWake = java.util.concurrent.locks.ReentrantLock()
    private val loopReady = loopWake.newCondition()
    /** Wake the connection loop early (e.g. on network change or state change). */
    private fun wakeLoop() = loopWake.withLock { loopReady.signal() }
    /** When to try the PC again: at once on a network change, else backing off to 30 s (4.4). */
    private val reconnect = Reconnect(clock)
    /** Who waits for each chat answer the PC writes, by the answer's id (`<message id>.a`). */
    private val talking = HashMap<String, io.github.odnwks.mimir.standalone.TalkListener>()
    private var data = vault.load()
    /**
     * The link's network side (iroh). It opens only once the phone pairs, or is paired: open, it
     * keeps a connection to a relay server and publishes where it can be reached, so before pairing
     * the app sends nothing at all. It closes again when the phone is unpaired.
     */
    @Volatile private var handle = 0L
    private val linkGate = java.util.concurrent.locks.ReentrantReadWriteLock()
    private val useRelays = relays
    private val linkHints = hints

    /** Runs [f] with the open link, or gives [none] while it's closed. */
    private inline fun <T> withLink(none: T, f: (Long) -> T): T {
        linkGate.readLock().lock()
        try { val h = handle; return if (h == 0L) none else f(h) } finally { linkGate.readLock().unlock() }
    }

    /** Opens the link's network side if it isn't open (pairing, or paired); false if it can't. */
    private fun openLink(): Boolean {
        linkGate.writeLock().lock()
        try {
            if (handle == 0L) {
                handle = api.start(data.key, useRelays)
                if (handle != 0L) { LinkLog.i("link opened"); linkHints.forEach { api.addHint(handle, it) } }
            }
            return handle != 0L
        } finally { linkGate.writeLock().unlock() }
    }

    /** Closes the link's network side when there's no PC and no pairing under way. */
    private fun closeLinkIfUnused() {
        // Read first, without the gate: a thread holding the lock may be waiting to use the link.
        val unused = synchronized(lock) { data.pcId == null && pairing !is PairProgress.Working && pairing !is PairProgress.ConfirmOnPc }
        if (!unused || handle == 0L) return
        linkGate.writeLock().lock()
        try {
            if (handle != 0L) { api.stop(handle); handle = 0L; LinkLog.i("link closed: no PC paired") }
        } finally { linkGate.writeLock().unlock() }
    }
    private val flow = MutableStateFlow(empty(appVersion).copy(stayMode = stayStore.mode()))
    override val state: StateFlow<StationState> = flow

    private var connected = false
    /** When the newest snapshot finished arriving (0: not since connecting). */
    @Volatile private var readyAt = 0L
    private var pcName = data.pcName ?: "your PC"
    private var pcVersion = ""
    /**
     * What the PC can do beyond the protocol (from its Welcome). A call it doesn't know ends the
     * session on an older PC, so searches and lookups go to the PC only when it lists them.
     */
    @Volatile private var pcFeatures: Set<String> = emptySet()
    private var relays = true
    private var pairing: PairProgress = if (data.pcId != null) PairProgress.Paired else PairProgress.Idle
    private var pairWords: List<String> = emptyList()
    private val tasks = LinkedHashMap<String, LinkTask>()
    private val entries = HashMap<String, MutableList<LEntry>>()
    private val live = HashMap<String, String>()
    /** PHW-06: when each task last got a Live (streaming) event, to detect "answering" state. */
    private val liveAt = HashMap<String, Long>()
    private var items: List<LItem> = emptyList()
    private var page: LStatus? = null
    /** The PC's one truth about its work (Stage 1, 3.1), newest by (run, revision). */
    private var truth: io.github.odnwks.mimir.link.LTruth? = null
    /** The PC's level as last seen, so the don't-touch notice follows its changes only. */
    private var level = "Free"
    private var pageAt = 0L
    private val ideas = HashMap<String, List<String>>()
    private var projects: List<Pair<String, String>> = projectStore.load()
    private var lastProject: String? = null
    /** Tasks started here: the name the screen opened on → the PC's task id ("" if it failed). */
    private val started = HashMap<String, String>()
    private val chat = ArrayList<ChatEntry>()
    /** The chat as last kept in the vault (its hash), so it's saved only when it changed. */
    private var savedChat = 0
    /** What each sent chat message became on the PC, by message id. */
    private val filed = HashMap<String, Filed>()
    /** Earlier conversations of the chat, newest first (kept in the vault). */
    private val past = ArrayList<KeptChat>(KeptChat.read(data.past))
    /** Their summaries for the chats list, made again only when the list changes. */
    private var pastShown: List<PastChat> = past.map { it.summary() }
    private val replies = CallBook()
    private val delivery = Delivery()
    private var seq = 0L
    private var problem: String? = null
    private var yoloFromPhone: Boolean? = null
    /** PC problems the owner dismissed here (the PC sends its list again at each connect). */
    private val dismissed = HashSet<String>()
    @Volatile private var running = true
    private var chosenModel: String = modelStore.model()
    private var pcModels: io.github.odnwks.mimir.link.LModels? = null
    /** The PC's update cards, newest first. */
    private var cards: List<io.github.odnwks.mimir.link.LCard> = emptyList()
    private var playState = io.github.odnwks.mimir.audio.PlayState()
    /** Real lengths of played updates, by id. */
    private val lengths = HashMap<String, Int>()
    private val fetching = HashSet<String>()

    /** Called with the needs-you items each time the PC's list changes (for notifications). */
    @Volatile var onItems: ((List<LItem>) -> Unit)? = null
    /** Called with how many tasks Claude works on, each time it changes (the "working" note). */
    @Volatile var onWorking: ((Int) -> Unit)? = null
    private var lastWorking = -1

    /** The PC's list of standalone models arrived (kept for use with no PC). */
    @Volatile var onStandaloneModels: ((String) -> Unit)? = null
    /** Online was taken away from this phone's person while chosen: the one line to say once. */
    @Volatile var onOnlineWithdrawn: ((String) -> Unit)? = null

    /** This phone belongs to the PC's owner (an older PC does not say, and has only the owner). */
    private fun isOwner(): Boolean = synchronized(lock) { data.person.isNullOrEmpty() || data.person == OWNER_PERSON }
    init {
        playback.onState = { ps ->
            synchronized(lock) {
                playState = ps
                ps.trackId?.let { id ->
                    if (ps.lengthMs > 0) lengths[id] = (ps.lengthMs / 1000).toInt()
                    if (ps.playing && id !in heard.heard()) heard.mark(id)
                }
            }
            publish()
        }
        // The chat as it was when the app last closed; anything still waiting shows as waiting.
        ChatLog.read(data.chat).let { (kept, where) -> chat += kept; filed += where }
        if (vault.unreadable) chat += ChatEntry.Note("n-pairing-lost", "Your pairing couldn't be read, so this phone starts again. Pair your PC again (Settings › Connect your PC).", true)
        for ((id, text) in data.outbox) {
            val i = chat.indexOfFirst { it.id == id }
            val mine = ChatEntry.Mine(id, text, Filing(FilingKind.Waiting, ""))
            if (i >= 0) chat[i] = mine else chat += mine
        }
        savedChat = chat.hashCode()
        expireOld()
        publish()
        Thread({ loop() }, "mimir-link").apply { isDaemon = true }.start()
    }

    val paired: Boolean get() = synchronized(lock) { data.pcId != null }

    /**
     * Waits until the link is up and the PC's snapshot has arrived (while connected, the
     * needs-you list stays current by itself), or the time runs out.
     */
    fun awaitSynced(timeoutMs: Long): Boolean {
        synchronized(lock) { reconnect.want(timeoutMs) }
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (readyAt > 0) return true
            Thread.sleep(100)
        }
        return false
    }

    /** The newest needs-you items (for the check job's notifications). */
    fun currentItems(): List<LItem> = synchronized(lock) { items }

    // ---- the link's own thread ----

    /**
     * The phone's network changed, or the app came back to the front: iroh is told (it can't see
     * this on Android) and, if the link is down, it's tried again at once instead of after the
     * usual wait.
     */
    fun networkChanged(why: String) {
        if (!withLink(false) { h -> LinkLog.i("network changed ($why)"); api.networkChanged(h); true }) return
        synchronized(lock) { reconnect.soon() }
        // A chat answer on its way may have lost words: it's asked for at once (once connected).
        talkers().forEach { it.nudge() }
        publish()
        // A session that still looks open may be on a path that just went away (Wi-Fi to mobile
        // data): it's checked, and dropped and made again at once if the PC doesn't answer,
        // instead of waiting for the link to time out by itself.
        if (synchronized(lock) { connected }) Thread({ probe() }, "mimir-probe").apply { isDaemon = true }.start()
    }

    /** The session didn't answer after a network change: the link loop drops it and connects again. */
    @Volatile private var dropNow = false

    private fun probe() {
        // iroh gets a moment to move to the new network first.
        Thread.sleep(PROBE_SETTLE_MS)
        val done = java.util.concurrent.CountDownLatch(1)
        if (!call(Wire.projects()) { _, _ -> done.countDown() }) return
        if (!done.await(PROBE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            LinkLog.i("no answer after the network changed: connecting again")
            synchronized(lock) { reconnect.soon() }
            dropNow = true
        }
    }

    private fun loop() {
        var lastTry = 0L
        while (running) {
            val pc = synchronized(lock) { data.pcId }
            if (pc == null) {
                closeLinkIfUnused()
                Thread.sleep(300)
                continue
            }
            if (!openLink()) {
                Thread.sleep(3000)
                continue
            }
            if (dropNow || !withLink(false) { api.connected(it) }) {
                dropNow = false
                if (connected) {
                    LinkLog.i("session ended: ${withLink("") { api.endedBecause(it) }.ifEmpty { "no reason given" }}")
                    lastSeenAt = System.currentTimeMillis()
                    failedOnce = true
                    synchronized(lock) { connected = false; readyAt = 0; data = data.copy(seenAt = lastSeenAt); saveData() }
                    // PHD-03: every call still waiting for a reply is finished now (none waits for ever). A talk
                    // answer is not: it is kept and told lost(), so it resumes after the reconnect (back()), or the
                    // owner taps Answer on this phone.
                    replies.failAll()
                    talkers().forEach { it.lost() }
                    // PHD-06: clear the "Claude is working on N tasks" notification within 5 s
                    // of detecting the PC is offline (not on the main thread, so this is safe).
                    onWorking?.invoke(0)
                    publish()
                }
                if (withLink("") { api.endedBecause(it) } == "not paired") {
                    forget("This phone was removed on the PC. Pair it again to use it.")
                    continue
                }
                // A task working, or a chat answer the PC is still writing for this phone.
                val busy = synchronized(lock) { lastWorking > 0 || talking.isNotEmpty() }
                if (!synchronized(lock) { reconnect.due(onScreen(), busy) }) {
                    // PHD-15: wait on condition instead of polling; woken by wakeLoop() on state changes
                    loopWake.withLock { loopReady.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                    continue
                }
                synchronized(lock) { reconnect.tried() }
                lastTry = System.currentTimeMillis()
                LinkLog.i("connecting to the PC")
                val why = withLink("Couldn't connect. Try again.") { api.connect(it, pc) }
                if (why.isNotEmpty()) {
                    LinkLog.i("connect failed after ${System.currentTimeMillis() - lastTry} ms: $why")
                    synchronized(lock) { reconnect.failed() }
                    if (!failedOnce) { failedOnce = true; publish() }
                    continue
                }
                LinkLog.i("connected after ${System.currentTimeMillis() - lastTry} ms")
                // A call made between the old session ending and this one starting was registered against the old session.
                replies.failAll()
                synchronized(lock) { connected = true; reconnect.connected() }
                // PHD-03: re-send unsent turns on reconnect
                chatRepo?.unsent()?.forEach { (chatId, turn) -> /* PHD-03: re-send stub — wired through Engine */ }
                // PHD-04: show the PC's refused reason, or our own fallback if the PC gave none.
                call(Wire.hello(appVersion, phoneName(), null)) { _, err ->
                    if (err != null && err != WENT_AWAY) {
                        val msg = if (err == "The PC refused.") "Mímir on your PC did not accept this phone. Pair it again." else err
                        note(msg)
                    }
                }
                publish()
                continue
            }
            // PHD-05: status page polled every 5 min as a fallback; a change event from the PC
            // refreshes it immediately (the PC's change channel, once available, replaces the timer).
            if (readyAt > 0 && System.currentTimeMillis() - pageAt > 300_000 && synchronized(lock) { truth } == null) refreshStatus()
            val json = withLink(null) { api.nextEvent(it, 500) } ?: continue
            val (n, ev) = runCatching { Wire.event(json) }.getOrNull() ?: continue
            take(n, ev)
        }
    }

    private fun take(n: Long, ev: Ev) {
        lastSeenAt = System.currentTimeMillis()
        var after: (() -> Unit)? = null
        synchronized(lock) {
            seq = maxOf(seq, n)
            when (ev) {
                is Ev.Welcome -> {
                    pcName = ev.pcName; pcVersion = ev.pcVersion; pcFeatures = ev.features
                    val talks = Wire.TALK in ev.features
                    if (talks != data.pcTalks) { data = data.copy(pcTalks = talks); saveData() }
                    // Paired again as another person: what this phone kept of the earlier one goes
                    // (never sent to the PC) and the new person's history is asked for from the start.
                    if (ev.person.isNotEmpty() && ev.person != data.person) {
                        val earlier = data.person
                        data = data.copy(person = ev.person); saveData()
                        if (personChanged(earlier, ev.person)) io.github.odnwks.mimir.standalone.Engine.made()?.newPerson()
                    }
                }
                // The PC sends everything again, its problems that still stand among it: one
                // that's gone is no longer shown.
                is Ev.Snapshot -> { tasks.clear(); entries.clear(); live.clear(); liveAt.clear(); problem = null }
                Ev.Ready -> after = {
                    talkers().forEach { it.back() }
                    refreshProjects {
                        expireOld()
                        flush()
                        flushRecordings()
                        flushReplies()
                        flushAnswers()
                        refreshStatus()
                        refreshModels()
                        refreshPhoneSettings()
                        publish()
                    }
                    readyAt = System.currentTimeMillis()
                    // The one chat catches up with the PC's history, sends what waited, and a message that
                    // waited for an answer (no PC, no model here) is answered by the PC now.
                    if (Wire.TALK in pcFeatures || Wire.CHAT_SYNC in pcFeatures) io.github.odnwks.mimir.standalone.Engine.made()?.linkUp()
                    sendGroundRules()
                }
                is Ev.TaskHeader -> tasks[ev.task.id] = ev.task
                is Ev.Removed -> { tasks.remove(ev.task); entries.remove(ev.task) }
                is Ev.Entries -> entries.getOrPut(ev.task) { ArrayList() }.let { l ->
                    while (l.size > ev.from) l.removeAt(l.size - 1)
                    l.addAll(ev.entries)
                    live.remove(ev.task)
                }
                is Ev.Live -> { live[ev.task] = ev.text; liveAt[ev.task] = System.currentTimeMillis() }
                is Ev.Problem -> if (ev.text !in dismissed) problem = ev.text
                is Ev.NeedsYou -> {
                    items = ev.items
                    // An answer for something no longer asked has nothing left to do.
                    val kept = Held.stillAsked(data.answers, ev.items.map { it.askId }.toSet())
                    if (readyAt > 0 && kept.size != data.answers.size) {
                        data = data.copy(answers = kept)
                        saveData()
                    }
                    val list = Held.asking(ev.items, data.answers) { it.askId }
                    after = { onItems?.invoke(list) }
                }
                is Ev.Updates -> {
                    // One card per id: the PC can send the same update twice (same text in the same second), and a repeated key closes the Updates list.
                    cards = ev.cards.distinctBy { it.id }
                    after = { fetchAudio() }
                }
                is Ev.Reply -> replies.answered(ev.callId)?.let { cb -> after = { cb(ev.ok, ev.err) } }
                is Ev.TestNotice, is Ev.TestNoticeOff -> {}
                is Ev.ModelsChanged -> after = { refreshModels() }
                is Ev.TalkPiece -> talking[ev.turnId]?.let { l -> after = { l.piece(ev.text, ev.thinking) } }
                is Ev.TalkDone -> talking[ev.turnId]?.let { l -> after = { l.done(ev.turn, ev.err) } }
                is Ev.ChatChanged -> after = { io.github.odnwks.mimir.standalone.Engine.made()?.chatChanged(ev.chat) }
                is Ev.Truth -> if (ev.truth.newerThan(truth)) {
                    truth = ev.truth
                    level = ev.truth.pc.level
                }
                is Ev.Other -> {}
            }
        }
        // A task or the needs-you list changed: the status page is read again within 5 s
        // (not at every event), so its counts don't lag a minute behind.
        if (ev is Ev.TaskHeader || ev is Ev.NeedsYou || ev is Ev.Removed) {
            pageAt = minOf(pageAt, System.currentTimeMillis() - 55_000)
        }
        after?.invoke()
        publish()
    }

    // ---- The one chat: the PC answers (Stage 1, 2.1) ----


    private fun talkers() = synchronized(lock) { talking.values.toList() }

    override fun canTalk(): Boolean = synchronized(lock) { connected && readyAt > 0 && data.pcId != null } && Wire.TALK in pcFeatures

    override fun canSeePictures(): Boolean = canTalk() && Wire.CHAT_PICTURES in pcFeatures && synchronized(lock) { pcModels?.seesPictures == true }

    override fun picturesRefused(): Boolean = canTalk() && Wire.CHAT_PICTURES in pcFeatures && synchronized(lock) { pcModels != null && pcModels?.seesPictures != true }

    override fun pcName(): String = Mapping.pcName(synchronized(lock) { pcName })

    override fun talk(request: io.github.odnwks.mimir.standalone.TalkRequest, listener: io.github.odnwks.mimir.standalone.TalkListener): Boolean {
        val answer = "${request.turnId}.a"
        synchronized(lock) { talking[answer] = listener }
        // PHD-03: 1-second deadline for the initial wire call (not for the reply itself)
        val deadline = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        deadline.schedule({
            synchronized(lock) { if (talking.containsKey(answer)) { talking.remove(answer)?.done(null, "timeout") } }
            deadline.shutdown()
        }, 1, java.util.concurrent.TimeUnit.SECONDS)
        val sent = call(Wire.talk(request.chatId, request.turnId, request.turns, request.lookup, request.sources, request.effort, request.rules, request.task, request.chatOnly && canChatOnly())) { ok, err ->
            deadline.shutdownNow()
            // A link that went away is not the PC's answer: the finished answer is picked up after the reconnect (lost()/back()).
            if (err == WENT_AWAY) return@call
            listener.filed(if (err == null) Wire.filed(ok) else null, err)
        }
        if (!sent) { deadline.shutdownNow(); synchronized(lock) { talking.remove(answer) } }
        return sent
    }

    fun onTurnAck(chatId: io.github.odnwks.mimir.data.store.ChatId, turnIndex: Int) {
        chatRepo?.markSent(chatId, turnIndex)
    }

    override fun stopTalk(chatId: String, turnId: String) { call(Wire.talkStop(chatId, turnId)) }

    override fun talkTurn(chatId: String, turnId: String, done: (io.github.odnwks.mimir.link.LTalkState?) -> Unit): Boolean =
        call(Wire.talkTurn(chatId, turnId)) { ok, err -> done(if (err == null) Wire.talkState(ok) else null) }

    override fun letGo(answerId: String) { synchronized(lock) { talking.remove(answerId) } }

    override fun canCut(): Boolean = Wire.CHAT_CUT in pcFeatures

    override fun canSync(): Boolean = synchronized(lock) { connected && readyAt > 0 && data.pcId != null } && Wire.CHAT_SYNC in pcFeatures

    override fun chatPut(chatId: String, name: String?, turns: List<io.github.odnwks.mimir.link.LChatTurn>, deleted: Boolean, done: (io.github.odnwks.mimir.link.LChatPutDone?) -> Unit): Boolean =
        call(Wire.chatPut(chatId, name, turns, deleted)) { ok, err -> done(if (err == null) Wire.chatPutDone(ok) else null) }

    override fun chatsSince(seq: Long, done: (io.github.odnwks.mimir.link.LChatsPage?) -> Unit): Boolean =
        call(Wire.chatsSince(seq)) { ok, err -> done(if (err == null) Wire.chatsPage(ok) else null) }

    override fun taskDone(id: String): Boolean = synchronized(lock) { tasks[id]?.let { !Mapping.working(it) && it.status == "Done" } ?: false }

    override fun projectNamed(text: String): String? = ProjectNamed.of(text, synchronized(lock) { projects.map { it.first } })

    override fun canChatOnly(): Boolean = Wire.CHAT_ONLY in pcFeatures

    override fun dropTask(id: String) = archiveTask(id)

    /**
     * The ground rules, one text on both sides (Stage 1, 1.5): this phone's copy goes to the PC at
     * each connect and each change here, and the PC answers with the rules that count (the newer).
     */
    fun sendGroundRules() {
        if (Wire.CHAT_SYNC !in pcFeatures) return
        val g = io.github.odnwks.mimir.data.GroundRules
        call(Wire.setGroundRules(g.current.orEmpty(), g.at)) { ok, err -> if (err == null) Wire.groundRules(ok)?.let { g.fromPc(it.text, it.at) } }
    }

    /** The PC's history is in the one chat now: the old paired chat and its earlier conversations go. */
    override fun oldChatsDone() {
        synchronized(lock) {
            if (chat.isEmpty() && past.isEmpty()) return
            chat.clear(); past.clear(); filed.clear()
            pastShown = emptyList()
            savedChat = chat.hashCode()
            data = data.copy(chat = "[]", past = "[]")
            saveData()
        }
        publish()
    }

    private fun call(json: String, onReply: ((Any?, String?) -> Unit)? = null): Boolean {
        // Registered before the link's events are read again, so a quick answer is never missed.
        synchronized(lock) {
            val id = withLink(-1L) { api.call(it, json) }
            if (id < 0) return false
            if (onReply != null) replies.register(id, onReply)
        }
        return true
    }

    /**
     * A web search through the owner's own SearXNG on the PC: the results as JSON, or null when the
     * PC isn't connected, refuses or doesn't answer within [timeoutMs]. Waits; never on the main thread.
     */
    fun webSearch(query: String, max: Int = 5, timeoutMs: Long = SEARCH_WAIT_MS): JSONArray? {
        if (!synchronized(lock) { connected } || "search" !in pcFeatures) return null
        val done = java.util.concurrent.CountDownLatch(1)
        var got: JSONArray? = null
        if (!call(Wire.webSearch(query, max)) { ok, err -> if (err == null) got = ok as? JSONArray; done.countDown() }) return null
        done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return got
    }

    /**
     * One of Mímir's lookups fetched by the PC (only from the PC's own fixed list of sites: the
     * weather and the encyclopedias). Throws [io.github.odnwks.mimir.online.Net.PcAway]
     * at once when the PC isn't connected, or when it doesn't answer within [timeoutMs]: a lookup
     * never waits for the link.
     */
    fun lookup(url: String, timeoutMs: Long): io.github.odnwks.mimir.online.Net.Reply {
        val away = io.github.odnwks.mimir.online.Net.PcAway()
        if (!synchronized(lock) { connected }) throw away
        if ("lookup" !in pcFeatures) throw io.github.odnwks.mimir.online.Net.PcAway("your PC needs an update for this")
        val done = java.util.concurrent.CountDownLatch(1)
        var got: JSONObject? = null
        var problem: String? = null
        if (!call(Wire.lookup(url)) { ok, err -> got = ok as? JSONObject; problem = err; done.countDown() }) throw away
        if (!done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) throw io.github.odnwks.mimir.online.Net.PcAway("your PC didn't answer in time")
        problem?.let { throw io.github.odnwks.mimir.online.Net.Failed(it) }
        val o = got ?: throw io.github.odnwks.mimir.online.Net.Failed("your PC gave an odd answer")
        return io.github.odnwks.mimir.online.Net.Reply(
            o.optInt("status"), o.optString("location").ifEmpty { null }, o.optString("content_type"), o.optString("body").toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * The PC's project list for To: and More projects: read on connect and with the status page
     * (within 5 s of a task appearing, else every minute), so a project the PC lists later (General,
     * made with the first task filed there) shows up without reconnecting.
     */
    private fun refreshProjects(then: (() -> Unit)? = null) {
        call(Wire.projects()) { ok, _ ->
            val o = ok as? JSONObject ?: return@call
            val list = o.optJSONArray("projects")
            val got = (0 until (list?.length() ?: 0)).map { i ->
                list!!.getJSONObject(i).let { it.getString("path") to it.getString("name") }
            }
            synchronized(lock) {
                if (got != projects) {
                    projects = got
                    projectStore.save(got)
                }
                lastProject = o.optString("last").takeIf { it.isNotEmpty() && !o.isNull("last") }
            }
            if (then != null) then() else publish()
        }
    }

    private fun refreshStatus() {
        pageAt = System.currentTimeMillis()
        refreshProjects()
        call(Wire.status()) { ok, _ ->
            val p = Wire.statusPage(ok) ?: return@call
            synchronized(lock) { page = p }
            for (proj in p.projects) refreshIdeas(proj.path)
            publish()
        }
    }

    private fun refreshIdeas(path: String) {
        call(Wire.ideas(path)) { ok, _ ->
            val a = ok as? JSONArray ?: return@call
            synchronized(lock) { ideas[path] = (0 until a.length()).map { a.getJSONObject(it).optString("text") } }
            publish()
        }
    }

    /** Recordings that waited more than a day for the PC go, with their lines (PHD-10); old read-aloud audio goes too (PHD-09). */
    private fun expireOld() {
        Expiry.speakFiles(audioDir, clock())
        val gone = synchronized(lock) {
            val ids = Expiry.recordings(recordDir, data.recordings.map { it.first }, clock())
            if (ids.isEmpty()) return@synchronized 0
            data = data.copy(recordings = data.recordings.filterNot { it.first in ids })
            chat.removeAll { it.id in ids }
            saveData()
            ids.size
        }
        if (gone > 0) {
            note(if (gone == 1) "A recording waited more than a day for your PC, so it was removed." else "$gone recordings waited more than a day for your PC, so they were removed.", false)
            publish()
        }
    }

    /** Whether the last save of the vault didn't reach the disk (tried again at the next send, and the owner is told once). */
    @Volatile private var vaultDirty = false
    private var vaultNoteAt = 0L

    /** Saves the vault; a save that fails is remembered and tried again, and the owner is told (a queued message is only safe once it is on the disk). */
    private fun saveData() {
        vault.save(data)
        if (vault.lastSaveOk) { vaultDirty = false; return }
        vaultDirty = true
        val t = clock()
        if (t - vaultNoteAt > 60_000) {
            vaultNoteAt = t
            note(if (vault.readOnly) "Your pairing couldn't be read, so nothing is saved on this phone yet. Pair your PC again (Settings › Connect your PC)." else "What waits to be sent couldn't be saved on this phone just now. It will try again.")
        }
    }

    /** Sends the messages waiting on this phone, oldest first, to be filed by the PC. */
    private fun flush() {
        if (vaultDirty) synchronized(lock) { saveData() }
        val waiting = synchronized(lock) { data.outbox.takeIf { connected } } ?: return
        for ((id, text) in waiting) {
            // It leaves the outbox only when the PC has answered; a link that went away leaves it there.
            val sent = delivery.start(id, { cb -> call(Wire.chat(id, text, GroundRules.current), cb) }) { ok, err ->
                synchronized(lock) {
                    data = data.copy(outbox = data.outbox.filterNot { it.first == id }); saveData()
                    // The PC said no: the line says so (with Try again and Delete) instead of waiting for ever.
                    if (err != null) chat.indexOfFirst { it.id == id }.let { i -> if (i >= 0) chat[i] = ChatEntry.Mine(id, text, Filing(FilingKind.Failed, "")) }
                }
                if (err != null) { note(err); publish() }
                else Wire.filed(ok)?.let { filedAs(id, text, it) }
            }
            if (sent == false) return
        }
    }

    /** A chat message was filed: its line says where; a status question gets its answer below it. */
    private fun filedAs(id: String, text: String, f: Filed) {
        synchronized(lock) {
            filed[id] = f
            val i = chat.indexOfFirst { it.id == id }
            if (i >= 0) chat[i] = ChatEntry.Mine(id, text, Mapping.filing(f, ::name))
            if (f is Filed.Status) {
                val reply = ChatEntry.Reply("r-$id", f.reply, "Play the status")
                if (i >= 0) chat.add(i + 1, reply) else chat += reply
            }
            if (f is Filed.Idea) ideas[f.project] = ideas[f.project].orEmpty() + text
        }
        publish()
    }

    /** A line in the chat: a problem (red-outlined, with why), unless it's plain news ([problem] false). */
    override fun dismissNote(id: String) {
        synchronized(lock) {
            if (id == "problem") problem?.let { dismissed += it; problem = null }
            chat.removeAll { it.id == id }
        }
        publish()
    }

    private fun note(text: String, problem: Boolean = true) {
        synchronized(lock) { chat += ChatEntry.Note("n-${UUID.randomUUID()}", text, problem) }
        publish()
    }

    private fun forget(why: String) {
        synchronized(lock) {
            data = data.copy(pcId = null, pcName = null, outbox = emptyList(), replies = emptyList(), answers = emptyList(), recordings = emptyList(), past = "[]", chatAt = 0)
            past.clear(); pastShown = emptyList()
            recordDir?.listFiles()?.forEach { it.delete() }
            saveData()
            pairing = PairProgress.Failed(why)
            tasks.clear(); entries.clear(); live.clear(); items = emptyList()
            chat.clear(); filed.clear()
            connected = false
        }
        replies.failAll()
        publish()
    }

    // ---- what the screens and the app call ----

    override fun pair(code: String) {
        // No screen lock is warned about on the pairing screen, never refused (owner, 2026-11-19).
        synchronized(lock) { pairing = PairProgress.Working }
        publish()
        Thread({
            if (!openLink()) {
                synchronized(lock) { pairing = PairProgress.Failed("This phone couldn't connect. Check your internet and try again.") }
                publish()
                return@Thread
            }
            val start = JSONObject(withLink("{\"error\":\"The connection closed. Try again.\"}") { api.pairStart(it, code.trim(), phoneName()) })
            if (start.has("error")) {
                synchronized(lock) { pairing = PairProgress.Failed(start.getString("error")) }
                publish()
                return@Thread
            }
            val words = start.getJSONArray("words").let { a -> (0 until a.length()).map { a.getString(it) } }
            synchronized(lock) { pairWords = words; pairing = PairProgress.ConfirmOnPc(words) }
            publish()
            val done = JSONObject(withLink("{\"error\":\"The connection closed. Try again.\"}") { api.pairFinish(it, code.trim(), 150_000) })
            synchronized(lock) {
                if (done.has("error")) {
                    LinkLog.i("pairing not confirmed: ${done.getString("error")}")
                    pairing = PairProgress.Failed(Mapping.pairWhy(done.getString("error")))
                } else {
                    data = data.copy(pcId = done.getString("pc_id"), pcName = done.getString("pc_name"))
                    pcName = done.getString("pc_name")
                    saveData()
                    LinkLog.i("paired")
                    pairing = PairProgress.Paired
                }
            }
            publish()
        }, "mimir-pair").start()
    }

    override fun send(text: String) {
        val id = "m-${UUID.randomUUID()}"
        val now = synchronized(lock) {
            data = data.copy(outbox = data.outbox + (id to text))
            saveData()
            chat += ChatEntry.Mine(id, text, Filing(FilingKind.Waiting, ""))
            connected
        }
        publish()
        if (now) Thread({ flush(); publish() }, "mimir-send").start()
    }

    override fun answerHere(text: String, working: String, answer: () -> List<String>) {
        val id = "m-${UUID.randomUUID()}"
        val rid = "r-$id"
        synchronized(lock) {
            chat += ChatEntry.Mine(id, text, null)
            chat += ChatEntry.Reply(rid, listOf(working), "")
        }
        publish()
        answerInto(rid, working, answer)
    }

    /** The answers that couldn't be looked up, for "Try again" (while the app runs). */
    private val retries = HashMap<String, Pair<String, () -> List<String>>>()

    private fun answerInto(rid: String, working: String, answer: () -> List<String>) {
        Thread({
            val r = runCatching(answer)
            synchronized(lock) {
                if (r.isFailure) retries[rid] = working to answer else retries.remove(rid)
                val i = chat.indexOfFirst { it.id == rid }
                if (i >= 0) chat[i] = ChatEntry.Reply(rid, r.getOrElse { listOf(io.github.odnwks.mimir.online.Net.sorry(it)) }, "Play the answer", retry = r.isFailure)
            }
            publish()
        }, "mimir-answer").start()
    }

    override fun retryAnswer(replyId: String) {
        val (working, answer) = synchronized(lock) {
            val again = retries.remove(replyId) ?: return
            val i = chat.indexOfFirst { it.id == replyId }
            if (i >= 0) chat[i] = ChatEntry.Reply(replyId, listOf(again.first), "")
            again
        }
        publish()
        answerInto(replyId, working, answer)
    }

    /** The open conversation, as kept in the chats list (null when there's nothing in it). */
    private fun keepOpen(): KeptChat? {
        val waiting = data.outbox.map { it.first }.toSet()
        val said = chat.filter { it !is ChatEntry.IncognitoRunning && it.id !in waiting }
        if (said.none { it is ChatEntry.Mine || it is ChatEntry.Reply }) return null
        return KeptChat("c-${UUID.randomUUID()}", null, data.chatAt.takeIf { it > 0 } ?: System.currentTimeMillis(), ChatLog.write(said.takeLast(200), filed.filterKeys { k -> said.any { it.id == k } }))
    }

    private fun savePast() {
        while (past.size > KeptChat.MAX) past.removeAt(past.size - 1)
        pastShown = past.map { it.summary() }
        data = data.copy(past = KeptChat.write(past))
        saveData()
    }

    override fun newChat() {
        synchronized(lock) {
            val kept = keepOpen() ?: return
            past.add(0, kept)
            // Messages still waiting to be sent stay, so they aren't lost.
            val waiting = data.outbox.map { it.first }.toSet()
            chat.retainAll { it.id in waiting }
            filed.keys.retainAll(waiting)
            savePast()
        }
        publish()
    }

    override fun openPastChat(id: String) {
        synchronized(lock) {
            val i = past.indexOfFirst { it.id == id }
            if (i < 0) return
            val pick = past.removeAt(i)
            keepOpen()?.let { past.add(0, it) }
            val waiting = data.outbox.map { it.first }.toSet()
            val left = chat.filter { it.id in waiting }
            chat.clear(); filed.keys.retainAll(waiting)
            ChatLog.read(pick.log).let { (kept, where) -> chat += kept; filed += where }
            chat += left
            savePast()
        }
        publish()
    }

    override fun renamePastChat(id: String, name: String) {
        synchronized(lock) {
            val i = past.indexOfFirst { it.id == id }
            if (i < 0) return
            past[i] = past[i].copy(name = name.trim().take(60).ifBlank { null })
            savePast()
        }
        publish()
    }

    override fun deletePastChat(id: String): KeptChat? {
        val gone = synchronized(lock) {
            val i = past.indexOfFirst { it.id == id }
            if (i < 0) return null
            past.removeAt(i).also { savePast() }
        }
        publish()
        return gone
    }

    override fun restorePastChat(chat: KeptChat) {
        synchronized(lock) {
            if (past.any { it.id == chat.id }) return
            val at = past.indexOfFirst { it.at < chat.at }.let { if (it < 0) past.size else it }
            past.add(at, chat)
            savePast()
        }
        publish()
    }

    override fun retryMessage(messageId: String) {
        val now = synchronized(lock) {
            val i = chat.indexOfFirst { it.id == messageId }
            val m = chat.getOrNull(i) as? ChatEntry.Mine ?: return
            if (m.filing?.kind != FilingKind.Failed) return
            data = data.copy(outbox = data.outbox + (messageId to m.text))
            saveData()
            chat[i] = ChatEntry.Mine(messageId, m.text, Filing(FilingKind.Waiting, ""))
            connected
        }
        publish()
        if (now) Thread({ flush(); publish() }, "mimir-send").start()
    }

    override fun undoMessage(messageId: String) {
        val f = synchronized(lock) {
            if (data.outbox.any { it.first == messageId }) {
                data = data.copy(outbox = data.outbox.filterNot { it.first == messageId })
                saveData()
                chat.removeAll { it.id == messageId }
                null
            } else if (chat.any { it.id == messageId && it is ChatEntry.Mine && it.filing?.kind == FilingKind.Failed }) {
                chat.removeAll { it.id == messageId }
                null
            } else filed[messageId]
        }
        if (f is Filed.Idea) call(Wire.undoIdea(f.project, f.idea)) { _, err ->
            if (err != null) return@call note(err)
            synchronized(lock) {
                val i = chat.indexOfFirst { it.id == messageId }
                if (i >= 0) chat[i] = (chat[i] as ChatEntry.Mine).copy(filing = null)
                filed.remove(messageId)
            }
            refreshIdeas(f.project)
            note("The idea was taken out of ${name(f.project)}.", problem = false)
        }
        publish()
    }

    private fun pathOf(project: String): String? = synchronized(lock) {
        projects.firstOrNull { it.second == project || it.first == project }?.first
    }

    override fun moveMessage(messageId: String, project: String) {
        val task = when (val f = synchronized(lock) { filed[messageId] }) {
            is Filed.NewTask -> f.task
            is Filed.SameTask -> f.task
            else -> return
        }
        val path = pathOf(project) ?: return
        call(Wire.moveTask(task, path)) { ok, err ->
            if (err != null) return@call note(err)
            val newTask = ok as? String ?: return@call
            val text = synchronized(lock) { (chat.firstOrNull { it.id == messageId } as? ChatEntry.Mine)?.text }.orEmpty()
            filedAs(messageId, text, Filed.NewTask(path, newTask))
        }
    }

    override fun fileInbox(messageId: String, project: String) {
        val f = synchronized(lock) { filed[messageId] } as? Filed.Inbox ?: return
        val path = pathOf(project) ?: return
        call(Wire.fileInbox(f.inboxId, path)) { ok, err ->
            if (err != null) return@call note(err)
            val g = Wire.filed(ok) ?: return@call
            val text = synchronized(lock) { (chat.firstOrNull { it.id == messageId } as? ChatEntry.Mine)?.text }.orEmpty()
            filedAs(messageId, text, g)
        }
    }

    /**
     * A reply inside a task. It is kept on this phone first, so a reply typed while the PC is away
     * waits and goes out (once: the id makes a resend harmless) as soon as the link is back.
     */
    override fun reply(taskId: String, text: String) {
        if (text.isBlank()) return
        val id = "s-${UUID.randomUUID()}"
        // A Whisper's words are never kept on the phone to wait: sent now, or not at all (PHD-11).
        if (!Expiry.mayWait(synchronized(lock) { tasks[taskId] })) {
            val sent = synchronized(lock) { connected } && call(Wire.send(taskId, text, id)) { _, err -> if (err != null) note(err) }
            if (!sent) note("Your PC is away: this Whisper can't wait. Send it again when your PC is back.")
            return
        }
        val now = synchronized(lock) {
            data = data.copy(replies = data.replies + Triple(id, taskId, text))
            saveData()
            connected
        }
        publish()
        if (now) Thread({ flushReplies(); publish() }, "mimir-reply").start()
    }

    /** Sends the task replies waiting on this phone, oldest first. */
    private fun flushReplies() {
        val waiting = synchronized(lock) { data.replies.takeIf { connected } } ?: return
        for ((id, task, text) in waiting) {
            // Kept until the PC answers; a link that went away leaves it for the next connect.
            val sent = delivery.start(id, { cb -> call(Wire.send(task, text, id), cb) }) { _, err ->
                synchronized(lock) { data = data.copy(replies = data.replies.filterNot { it.first == id }); saveData() }
                if (err != null) note(err)
                publish()
            }
            if (sent == false) return
        }
    }

    /**
     * A recording spoken while the PC is away is kept on this phone, like a typed message, and
     * turned into words and sent once the PC is back: to its task, or to the chat.
     */
    override fun keepRecording(taskId: String?, wav: ByteArray, seconds: Int) {
        // A Whisper's recording is never kept on the phone (PHD-11).
        if (taskId != null && !Expiry.mayWait(synchronized(lock) { tasks[taskId] })) {
            return note("Your PC is away: this Whisper can't wait. Say it again when your PC is back.")
        }
        val dir = recordDir ?: return note("The recording couldn't be kept on this phone.")
        val id = (if (taskId == null) "m-" else "s-") + UUID.randomUUID()
        val ok = runCatching { dir.mkdirs(); java.io.File(dir, "$id.wav").writeBytes(wav) }.isSuccess
        if (!ok) return note("The recording couldn't be kept on this phone.")
        val now = synchronized(lock) {
            data = data.copy(recordings = data.recordings + Triple(id, taskId.orEmpty(), seconds))
            saveData()
            if (taskId == null) chat += ChatEntry.Mine(id, recordingWords(seconds), Filing(FilingKind.Waiting, ""))
            connected
        }
        publish()
        if (now) Thread({ flushRecordings(); publish() }, "mimir-recording").start()
    }

    /** Turns the recordings waiting on this phone into words on the PC and sends them, oldest first. */
    private fun flushRecordings() {
        val waiting = synchronized(lock) { data.recordings.takeIf { connected } } ?: return
        for ((id, task, _) in waiting) {
            val file = recordDir?.let { java.io.File(it, "$id.wav") }
            val wav = file?.let { runCatching { it.readBytes() }.getOrNull() }
            val done = { text: String? ->
                file?.delete()
                synchronized(lock) {
                    data = data.copy(recordings = data.recordings.filterNot { it.first == id })
                    if (task.isEmpty()) {
                        val i = chat.indexOfFirst { it.id == id }
                        if (text.isNullOrBlank()) { if (i >= 0) chat.removeAt(i) }
                        else {
                            // It goes out as the message it was spoken as, in the same place.
                            data = data.copy(outbox = data.outbox + (id to text))
                            if (i >= 0) chat[i] = ChatEntry.Mine(id, text, Filing(FilingKind.Waiting, ""))
                        }
                    } else if (!text.isNullOrBlank()) data = data.copy(replies = data.replies + Triple(id, task, text))
                    saveData()
                }
            }
            if (wav == null) { done(null); continue }
            val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
            // A link that went away leaves the recording where it is (Delivery doesn't call this then).
            val sent = delivery.start(id, { cb -> call(Wire.listen(b64), cb) }) { ok, err ->
                val text = (ok as? String).orEmpty().trim()
                when {
                    // A PC error may be temporary (its speech model busy): the recording stays and is tried again at the next connect (it expires after a day).
                    err != null -> note("A recording couldn't be turned into words yet. It will be tried again when your PC connects.")
                    text.isEmpty() -> { note("Nothing was heard in a recording, so it wasn't sent."); done(null) }
                    else -> done(text)
                }
                if (err == null) Thread({ if (task.isEmpty()) flush() else flushReplies(); publish() }, "mimir-recording").start()
            }
            if (sent == false) return
        }
    }

    override fun answer(itemId: String, answer: Answer) {
        val a = Mapping.answer(answer) ?: return
        answerWith(itemId, a)
    }

    override fun answerChoice(itemId: String, index: Int) = answerWith(itemId, ItemAnswer.Option(index))

    /**
     * An answer is kept on this phone first, like a message: given while the PC is away, it
     * waits and is sent as soon as the link is back (the PC takes the first answer it gets).
     */
    private fun answerWith(itemId: String, a: ItemAnswer) {
        val now = synchronized(lock) {
            data = data.copy(answers = Held.put(data.answers, itemId, Wire.answerItem(itemId, a)))
            saveData()
            connected
        }
        publish()
        if (now) Thread({ flushAnswers(); publish() }, "mimir-answer").start()
    }

    /** Sends the answers waiting on this phone, oldest first. */
    private fun flushAnswers() {
        val waiting = synchronized(lock) { data.answers.takeIf { connected } } ?: return
        for ((ask, json) in waiting) {
            // Kept until the PC answers, like a message; a link that went away leaves it for the next connect.
            val sent = delivery.start("a-$ask", { cb -> call(json, cb) }) { _, err ->
                // Answered on the PC or another phone meanwhile: nothing to say.
                if (err != null && !err.contains("answered", ignoreCase = true)) note(answerProblem(err))
                synchronized(lock) {
                    data = data.copy(answers = data.answers.filterNot { it.first == ask })
                    saveData()
                    // It goes from this phone's list now; the PC's list follows.
                    items = items.filterNot { it.askId == ask }
                }
                publish()
            }
            if (sent == false) return
        }
    }

    override fun stopTask(taskId: String) {
        call(Wire.stop(taskId)) { _, err -> if (err != null) note(err) }
    }

    override fun startTask(text: String, project: String, incognito: Boolean, plan: Boolean, model: String?): String {
        // Nothing to do: no empty task is made on the PC.
        if (text.isBlank()) return ""
        if (incognito) {
            val path = pathOf(project) ?: synchronized(lock) { projects.firstOrNull()?.first }
            val free = synchronized(lock) {
                chosenModel.takeIf { it.startsWith("ollama:") } ?: pcModels?.freeModel ?: pcModels?.free?.firstOrNull()?.id
            }
            if (path == null || free == null) {
                note(if (free == null) "Whisper mode needs a free model on your PC, and none is set up." else "Pick a project first.")
                return ""
            }
            if (!call(Wire.newIncognito(path, text, free, GroundRules.current)) { _, err -> if (err != null) note(err) }) {
                note("Your PC is away: start the Whisper when it's back.")
                return ""
            }
            return FakeStation.INCOGNITO
        }
        val path = pathOf(project) ?: synchronized(lock) { projects.firstOrNull()?.first }
        val model = respectPower(model ?: synchronized(lock) { chosenModel }).takeIf { it != "auto" }
        if (path == null) { note("Pick a project first."); return "" }
        // The screen opens on this name at once and becomes the real task when the PC answers.
        val pending = "t-${UUID.randomUUID()}"
        val sent = call(Wire.newTask(path, text, pending, model ?: "auto", plan, GroundRules.current)) { ok, err ->
            // The PC may have started it before the link went: its task turns up in the next snapshot.
            if (err == WENT_AWAY) return@call
            synchronized(lock) { started[pending] = if (err == null) (ok as? String).orEmpty() else "" }
            if (err != null) note(err)
            publish()
        }
        if (!sent) { note("Your PC is away: start the task again when it's back."); return "" }
        return pending
    }

    override fun newProject(name: String, done: (String?, String) -> Unit) {
        val paired = synchronized(lock) { data.pcId != null }
        if (!paired) { done(null, "Pair with your PC to make projects there."); return }
        val sent = call(Wire.newProject(name.trim())) { ok, err ->
            val o = ok as? JSONObject
            if (err != null || o == null) { done(null, err ?: "Your PC didn't make the project."); return@call }
            val path = o.getString("path")
            val n = o.getString("name")
            synchronized(lock) {
                if (projects.none { it.first == path }) projects = projects + (path to n)
                projectStore.save(projects)
            }
            refreshStatus()
            publish()
            val shown = path.replaceFirst(Regex("^/home/[^/]+"), "~")
            done(n, if (o.optBoolean("made")) "Made $shown on your PC" else "$n is already on your PC: picked it.")
        }
        if (!sent) done(null, "Your PC is away: new projects can be made when it's back.")
    }

    private fun taskCall(json: String) { if (!call(json) { _, err -> if (err != null) note(err) }) note("Your PC is away: try again when it's back.") }
    override fun pauseTask(taskId: String) = taskCall(Wire.pause(taskId))
    override fun continueTask(taskId: String) = taskCall(Wire.continueTask(taskId))
    override fun renameTask(taskId: String, title: String) { if (title.isNotBlank()) taskCall(Wire.rename(taskId, title.trim())) }
    override fun archiveTask(taskId: String) = taskCall(Wire.archive(taskId, true))
    override fun unarchiveTask(taskId: String) = taskCall(Wire.archive(taskId, false))
    override fun moveTask(taskId: String, project: String) {
        val path = pathOf(project) ?: return note("That project isn't on your PC.")
        taskCall(Wire.moveTo(taskId, path))
    }
    override fun setTaskPlan(taskId: String, plan: Boolean) = taskCall(Wire.setMode(taskId, plan))
    override fun setTaskYolo(taskId: String) = taskCall(Wire.setYolo(taskId))
    override fun turnOnYolo(taskId: String, done: (String?) -> Unit) {
        val sent = call(Wire.setYolo(taskId)) { _, err ->
            synchronized(lock) {
                if (err == null) yoloFromPhone = true
                else if (err.contains("YOLO from the phone is off", ignoreCase = true)) yoloFromPhone = false
            }
            publish()
            done(err)
        }
        if (!sent) done("Your PC is away: try again when it's back.")
    }
    /**
     * The PC's "What powers your tasks" counts: with free models only, a task never goes to
     * Claude (the free model the PC uses takes it); with Claude only, never to a free model.
     */
    private fun respectPower(model: String): String = synchronized(lock) {
        val free = model.startsWith("ollama:")
        when (Power.of(pcModels?.power)) {
            Power.FreeOnly -> if (free) model else pcModels?.freeModel ?: pcModels?.free?.firstOrNull()?.id ?: model
            Power.ClaudeOnly -> if (free) "auto" else model
            else -> model
        }
    }

    override fun setTaskModel(taskId: String, modelId: String) = taskCall(Wire.setModel(taskId, modelId.takeIf { it != "auto" }))

    override fun setStayMode(mode: StayMode) {
        stayStore.save(mode)
        flow.value = flow.value.copy(stayMode = mode)
    }

    // Not part of this version: kept on the phone only.
    override fun endIncognito() {
        val id = synchronized(lock) { tasks.values.firstOrNull { it.incognito }?.id } ?: return
        call(Wire.endIncognito(id)) { _, err ->
            if (err != null) note(err)
            // Nothing of it stays on this phone either.
            synchronized(lock) { tasks.remove(id); entries.remove(id); live.remove(id) }
            publish()
        }
    }
    override fun selectModel(modelId: String) {
        synchronized(lock) { chosenModel = modelId }
        modelStore.save(modelId)
        publish()
    }

    override fun setSwitchToFree(on: Boolean) {
        call(Wire.setFreeChoice(on, null)) { _, err -> if (err != null) note(err); refreshModels() }
    }

    override fun continueOnClaude(taskId: String) {
        call(Wire.continueOnClaude(taskId)) { _, err -> if (err != null) note(err) }
    }

    override fun setWeeklyCheck(on: Boolean) {
        if (!call(Wire.setWeeklyCheck(on)) { _, err -> if (err != null) note(err); refreshModels() }) note("Your PC is away: try again when it's back.")
    }

    override fun setChatModel(id: String) {
        if (Wire.CHAT_MODEL !in pcFeatures) return note("Update Mímir on your PC to change this.")
        synchronized(lock) { pcModels = pcModels?.copy(chatsModel = id) }
        publish()
        if (!call(Wire.setChatModel(id)) { _, err -> if (err != null) note(err); refreshModels() }) {
            note("Your PC is away: try again when it's back.")
            refreshModels()
        }
    }

    override fun setEngines(chats: String?, project: String?) {
        if (Wire.ENGINES !in pcFeatures) return note("Update Mímir on your PC to change this.")
        // Shown at once; the PC's own answer then replaces it.
        synchronized(lock) { pcModels = pcModels?.copy(chatsEngine = chats ?: pcModels?.chatsEngine, projectEngine = project ?: pcModels?.projectEngine) }
        publish()
        if (!call(Wire.setEngines(chats, project)) { _, err -> if (err != null) note(err); refreshModels() }) {
            note("Your PC is away: try again when it's back.")
            refreshModels()
        }
    }

    override fun setPcName(name: String) {
        val ok = call(Wire.setPcName(name.trim())) { v, err ->
            if (err != null) { note(err); return@call }
            synchronized(lock) {
                (v as? String)?.takeIf { it.isNotBlank() }?.let { n -> pcName = n; data = data.copy(pcName = n); saveData() }
            }
            publish()
        }
        if (!ok) note("Your PC is away: try again when it's back.")
    }

    override fun setRelays(on: Boolean) {
        if (!call(Wire.setRelays(on)) { _, err -> if (err != null) note(err); refreshPhoneSettings() }) note("Your PC is away: try again when it's back.")
    }

    override fun alwaysAllowed(taskId: String, done: (List<String>?) -> Unit) {
        val ok = call(Wire.alwaysAllowed(taskId)) { v, err ->
            val a = v as? org.json.JSONArray
            done(if (err != null || a == null) null else (0 until a.length()).map { a.getString(it) })
        }
        if (!ok) done(null)
    }

    override fun stopAllowing(taskId: String, file: String) = taskCall(Wire.stopAllowing(taskId, file))

    private fun refreshPhoneSettings() {
        call(Wire.phoneSettings()) { v, _ ->
            val o = v as? JSONObject ?: return@call
            synchronized(lock) {
                relays = o.optBoolean("relays", true)
                if (o.has("yolo_from_phone")) yoloFromPhone = o.optBoolean("yolo_from_phone")
            }
            publish()
        }
    }

    private fun refreshModels() {
        call(Wire.models()) { ok, _ ->
            val m = Wire.modelList(ok) ?: return@call
            val before = synchronized(lock) { pcModels.also { pcModels = m } }
            // The owner took Online away from this person while it was chosen: say so once.
            if (onlineWithdrawn(before?.chatsEngine, m.chatsEngine, isOwner())) onOnlineWithdrawn?.invoke(ONLINE_WITHDRAWN)
            m.standalone?.let { list -> onStandaloneModels?.invoke(list) }
            publish()
        }
    }
    override fun setFingerprintLock(on: Boolean) { lockStore.save(on); publish() }
    /** "this": this phone forgets its PC (the PC can remove it too, in Settings › Phone). */
    override fun removePhone(phoneId: String) {
        if (phoneId == "this") {
            synchronized(lock) { projects = emptyList(); projectStore.save(emptyList()) }
            forget("Unpaired on this phone.")
            synchronized(lock) { pairing = PairProgress.Idle }
            publish()
        }
    }
    // ---- updates: listening on the phone ----

    private fun audioFile(name: String): java.io.File? = audioDir?.let { java.io.File(it, name) }

    /** Fetches each card's audio once, so updates play even while the PC sleeps. */
    private fun fetchAudio() {
        val want = synchronized(lock) {
            cards.mapNotNull { c -> c.audio?.takeIf { n -> audioFile(n)?.isFile == false && n !in fetching } }
                .also { fetching += it }
        }
        for (name in want) {
            call(Wire.getFile(name)) { ok, _ ->
                val o = ok as? JSONObject
                val f = audioFile(name)
                if (o != null && f != null) runCatching {
                    f.parentFile?.mkdirs()
                    val tmp = java.io.File(f.parentFile, ".$name.part")
                    tmp.writeBytes(android.util.Base64.decode(o.getString("base64"), android.util.Base64.DEFAULT))
                    tmp.renameTo(f)
                }
                synchronized(lock) { fetching -= name }
                publish()
            }
        }
        Expiry.speakFiles(audioDir, clock())
        // Files no card uses any more go.
        val keep = synchronized(lock) { cards.mapNotNull { it.audio }.toSet() }
        audioDir?.listFiles()?.filter { it.name.endsWith(".ogg") && !it.name.startsWith("speak-") && it.name !in keep }?.forEach { it.delete() }
    }

    /** Plays [updateId], then the older ones in turn ("Play latest" starts at the newest). */
    override fun playPause(updateId: String) {
        val (same, tracks) = synchronized(lock) {
            (playState.trackId == updateId) to Mapping.playlist(cards, updateId, ::name) { audioFile(it) }
        }
        if (same) playback.toggle()
        else if (tracks.isNotEmpty()) playback.play(tracks, tracks.first().id)
        else note("This update has no audio yet.")
    }

    override fun playNext() = playback.next()
    override fun back10() = playback.back10()
    override fun setSpeed(speed: Float) = playback.setSpeed(io.github.odnwks.mimir.audio.ReadSpeed.snap(speed))

    override fun cycleSpeed() {
        playback.setSpeed(Mapping.nextSpeed(synchronized(lock) { playState.speed }))
    }

    override fun playMessage(taskId: String, messageId: String) {
        val entry = messageId.substringAfterLast('-').toIntOrNull() ?: return
        call(Wire.speak(taskId, entry)) { ok, err ->
            val name = ok as? String
            if (err != null || name == null) { note(err ?: "That message can't be read aloud."); return@call }
            val f = audioFile(name)
            val go = {
                if (f != null && f.isFile) {
                    playback.play(listOf(io.github.odnwks.mimir.audio.Track("msg:$messageId", "Reply", io.github.odnwks.mimir.ui.AI_NAME, f)), "msg:$messageId")
                }
            }
            if (f?.isFile == true) go() else call(Wire.getFile(name)) { o, _ ->
                val b = (o as? JSONObject)?.optString("base64")
                if (b != null && f != null) runCatching {
                    f.parentFile?.mkdirs()
                    f.writeBytes(android.util.Base64.decode(b, android.util.Base64.DEFAULT))
                }
                go()
            }
        }
    }

    override fun listen(wav: ByteArray, done: (String?, String?) -> Unit) {
        val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        val sent = call(Wire.listen(b64)) { ok, err ->
            if (err != null) done(null, err) else done((ok as? String).orEmpty().trim(), null)
        }
        if (!sent) done(null, "Your PC isn't connected right now.")
    }

    fun close() {
        running = false
        linkGate.writeLock().lock()
        try { if (handle != 0L) api.stop(handle); handle = 0L } finally { linkGate.writeLock().unlock() }
    }

    // ---- the screens' state ----

    private fun name(path: String): String =
        projects.firstOrNull { it.first == path }?.second ?: path.trimEnd('/').substringAfterLast('/')

    private fun publish() {
        val s = synchronized(lock) {
            // The chat is kept (its newest 200 entries) whenever it changed.
            val h = chat.hashCode()
            if (h != savedChat) {
                savedChat = h
                data = data.copy(chat = ChatLog.write(chat.takeLast(200), filed), chatAt = if (chat.isEmpty()) 0 else System.currentTimeMillis())
                saveData()
            }
            build()
        }
        flow.value = s
        // Known only while connected: away, the last count stands until the PC says otherwise.
        val working = s.tasks.count { it.busy }
        if (s.pc.online && working != lastWorking) { lastWorking = working; onWorking?.invoke(working) }
    }

    private fun build(): StationState {
        val now = System.currentTimeMillis() / 1000
        // Something answered here waits to be sent; it no longer asks.
        val needs = Mapping.needs(Held.asking(items, data.answers) { it.askId }, ::name, now)
        // The PC's rule: most recent activity first (a live conversation on top).
        val all = tasks.values.filter { !it.incognito }.sortedByDescending { maxOf(it.activeUnix, it.createdUnix) }
        val ts = all.filter { !it.archived }.map { t -> task(t, needs) }
        val inc = tasks.values.firstOrNull { it.incognito }
        val base = flow.value
        // The truth, when the PC sends it, is the one source for states, counts, projects and usage.
        val t = truth
        val p = t?.let { Mapping.pageFromTruth(it, page) } ?: page
        return base.copy(
            screenLock = secure(),
            paidOn = t?.engine?.paidOn,
            pcTalks = data.pcTalks,
            owner = isOwner(),
            pc = Pc(Mapping.pcName(pcName), online = connected, lastSeen = when { connected -> "now"; !failedOnce -> "not connected"; else -> seenWords(lastSeenAt, System.currentTimeMillis()) }, route = "private connection", tasksRunning = if (!connected) 0 else t?.working ?: tasks.values.count { Mapping.working(it) }),
            needsYou = needs,
            tasks = ts,
            archived = all.filter { it.archived }.map { t -> task(t, needs) },
            pcVersion = pcVersion,
            relays = relays,
            yoloFromPhone = yoloFromPhone,
            fingerprintLock = lockStore.on(),
            // The PC's status page, plus any project it doesn't list yet (one just made here):
            // every project the picker offers has a page, with its tasks and New task.
            projects = run {
                val listed = if (p != null) Mapping.projects(p, tasks.values, ideas, now) else emptyList()
                val seen = (p?.projects?.map { it.path }.orEmpty()).toSet()
                listed + projects.filterNot { it.first in seen }.map { (path, n) ->
                    Project(n, path, CheckState.None, 0, 0, "", "", emptyList(), "", tasks.values.filter { it.project == path }.map {
                        ProjectTask(it.id, it.title, if (Mapping.working(it) && connected) "Working" else it.status, Chip.of(Mapping.working(it) && connected, it.status))
                    }, tasks.values.count { it.project == path && Mapping.working(it) && connected })
                }
            },
            usage = if (p != null) Mapping.usage(p, now) else base.usage,
            pairWords = pairWords,
            pairing = pairing,
            models = Mapping.models(pcModels, chosenModel),
            incognito = inc?.let { t ->
                val es = entries[t.id].orEmpty()
                IncognitoTask(
                    title = t.title,
                    project = name(t.project),
                    model = (t.model ?: "").removePrefix("ollama:"),
                    prompt = es.filterIsInstance<LEntry.You>().firstOrNull()?.text ?: "",
                    reply = live[t.id]?.takeIf { it.isNotBlank() } ?: es.filterIsInstance<LEntry.Claude>().lastOrNull()?.text ?: "",
                    playLabel = "Play the reply",
                    id = t.id,
                    asking = t.asks.firstOrNull()?.title,
                )
            },
            chat = (chat.toList() + listOfNotNull(problem?.let { ChatEntry.Note("problem", it, problem = true) })).filterNot { it is ChatEntry.IncognitoRunning } +
                listOfNotNull(inc?.let { ChatEntry.IncognitoRunning("incognito", name(it.project)) }),
            updates = run {
                val h = heard.heard()
                cards.map { c -> Mapping.update(c, ::name, c.id in h, now, lengths[c.id]) }
            },
            player = Player(
                playState.trackId, playState.playing, (playState.positionMs / 1000).toInt(), playState.speed, real = true,
            ),
            // The phones list: at least this one, once paired.
            phones = if (data.pcId != null) listOf(Phone("this", phoneName(), "This phone", thisPhone = true)) else emptyList(),
            phoneOnline = network(),
            paired = data.pcId != null,
            projectChoices = projects.map { it.second },
            lastProject = lastProject?.let { name(it) }.orEmpty(),
            startedTasks = HashMap(started),
            answersWaiting = if (connected) 0 else data.answers.size,
            pcLine = if (connected) p?.pcLine else null,
            pastChats = pastShown,
            chatAt = data.chatAt,
        )
    }

    private fun task(t: LinkTask, needs: List<NeedsYouItem>): Task {
        val es = entries[t.id].orEmpty()
        // PHD-29(c): disable Play while the task is live-streaming; enable once the message is complete.
        val streaming = live[t.id]?.isNotBlank() == true
        val messages = es.mapIndexedNotNull { i, e ->
            when (e) {
                is LEntry.You -> TaskMessage("${t.id}-$i", false, e.text)
                is LEntry.Claude -> TaskMessage("${t.id}-$i", true, e.text, if (streaming && i == es.lastIndex) "" else "Play the reply")
                else -> null
            }
        } + listOfNotNull(live[t.id]?.takeIf { it.isNotBlank() }?.let { TaskMessage("${t.id}-live", true, it) }) +
            data.replies.filter { it.second == t.id }.map { (id, _, text) -> TaskMessage(id, false, text, waiting = true) } +
            data.recordings.filter { it.second == t.id }.map { (id, _, secs) -> TaskMessage(id, false, recordingWords(secs), waiting = true) }
        val ask = t.asks.firstOrNull()
        val change = ask?.let { change(it) }
        val perm = needs.firstOrNull { it.taskId == t.id && it.kind == NeedsKind.Permission }
        val question = needs.firstOrNull { it.taskId == t.id && it.kind == NeedsKind.Question }
        // The truth's state, when the PC sends one, is the only state (no rule of the phone's own).
        val said = truth?.tasks?.firstOrNull { it.id == t.id }?.takeIf { connected }
        return Task(
            id = t.id,
            title = t.title,
            project = name(t.project),
            model = Mapping.modelName(t.model),
            age = "",
            // ✓ done, ✕ refused or failed, … still going or waiting for an answer.
            steps = es.filterIsInstance<LEntry.Step>().takeLast(8).map { (when (it.ok) { true -> "✓ "; false -> "✕ "; null -> "… " }) + it.what },
            messages = messages,
            question = question?.let { Question(it.id, it.text, "", "", it.choices) },
            permission = if (ask != null && perm != null) permission(perm.id, ask, change) else null,
            change = change,
            stopped = t.status == "Stopped",
            freeModel = t.model?.startsWith("ollama:") == true,
            outOfRoom = es.takeLast(3).any { it is LEntry.Note && it.text.startsWith("This free model ran out of room") },
            refusalsRemoved = t.model?.let { m -> listOf("abliterated", "heretic", "uncensored").any { m.lowercase().contains(it) } } == true,
            // PHW-06: "Answering…" while a Live heartbeat came in within 4 s (PC sends every 2 s).
            status = said?.let { Mapping.truthWords(it.state) }
                ?: if (liveAt[t.id]?.let { System.currentTimeMillis() - it < 4_000L } == true) "Answering…"
                else Mapping.taskWords(t, asks = perm != null || question != null, connected = connected),
            busy = said?.let { it.state == "Working" } ?: (Mapping.working(t) && connected),
            createdUnix = t.createdUnix,
            activeUnix = t.activeUnix,
            liveElsewhere = t.liveElsewhere,
            plan = t.plan,
            mode = t.mode,
            whisper = t.incognito,
        )
    }

    /** The card for a request: an edit shows its change, a command shows the command. */
    private fun permission(itemId: String, a: Ask, change: Change?): Permission {
        val command = if (a.tool == "Bash") a.input.optString("command") else ""
        return Permission(
            itemId, a.title, a.subject,
            change?.lines?.count { l -> l.kind != DiffKind.Context } ?: 0,
            kind = when {
                change != null -> PermissionKind.Edit
                command.isNotEmpty() -> PermissionKind.Command
                else -> PermissionKind.Other
            },
            command = command,
        )
    }

    /** "Show the change" for an edit or a new file. */
    private fun change(a: Ask): Change? {
        val file = a.input.optString("file_path").ifEmpty { return null }
        val lines = when (a.tool) {
            "Edit" -> a.input.optString("old_string").lines().map { DiffLine(DiffKind.Removed, it) } +
                a.input.optString("new_string").lines().map { DiffLine(DiffKind.Added, it) }
            "Write" -> a.input.optString("content").lines().take(200).map { DiffLine(DiffKind.Added, it) }
            else -> return null
        }
        return Change(file, a.title, lines, "", "")
    }

    /** When the PC was last reached (kept, so "as of" is true after the app closes too). */
    @Volatile private var lastSeenAt = data.seenAt
    /** A try to reach the PC failed since the app started (until then it's still being reached). */
    @Volatile private var failedOnce = false

    private fun phoneName(): String = deviceName?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()

    companion object {
        /**
         * How long a search waits for the PC's SearXNG before searching from this phone instead
         * (a lookup's whole budget is about 12 s).
         */
        const val SEARCH_WAIT_MS = 6_000L

        /** After a network change: a moment for the link to move, then how long the PC has to answer a check. */
        const val PROBE_SETTLE_MS = 1_000L
        const val PROBE_WAIT_MS = 4_000L

        /** What the screens show before anything comes from the PC. */
        fun empty(appVersion: String) = StationState(
            pc = Pc("your PC", online = false, lastSeen = "not connected", route = "private connection", tasksRunning = 0),
            chat = emptyList(),
            needsYou = emptyList(),
            tasks = emptyList(),
            projects = emptyList(),
            usage = Usage("—", 0, "", 85),
            updates = emptyList(),
            player = Player(null, playing = false, positionSeconds = 0, speed = 1f),
            models = Models(emptyList(), emptyList(), "auto", switchToFree = false, freeFallback = ""),
            phones = emptyList(),
            fingerprintLock = true,
            incognito = null,
            pairWords = emptyList(),
            appVersion = appVersion,
        )
    }
}

/** Where the staying-in-touch mode is kept between runs. */
interface StayStore {
    fun mode(): StayMode
    fun save(mode: StayMode)

    class InMemory(private var m: StayMode = StayMode.Every5) : StayStore {
        override fun mode() = m
        override fun save(mode: StayMode) { m = mode }
    }
}

/** Whether the app opens only with a fingerprint or the phone's PIN (on unless turned off). */
interface LockStore {
    fun on(): Boolean
    fun save(on: Boolean)

    class InMemory(private var v: Boolean = true) : LockStore {
        override fun on() = v
        override fun save(on: Boolean) { v = on }
    }
}

/** The model new tasks from this phone start on. */
interface ModelStore {
    fun model(): String
    fun save(model: String)

    class InMemory(private var m: String = "auto") : ModelStore {
        override fun model() = m
        override fun save(model: String) { m = model }
    }
}

/** The PC's project list (folder, name), kept on the phone. */
interface ProjectStore {
    fun load(): List<Pair<String, String>>
    fun save(list: List<Pair<String, String>>)

    class InMemory(private var l: List<Pair<String, String>> = emptyList()) : ProjectStore {
        override fun load() = l
        override fun save(list: List<Pair<String, String>>) { l = list }
    }
}

/**
 * Why an answer from this phone wasn't used, in plain words: the PC refuses one for an item that
 * has moved on (answered on the PC, or Claude went on) or that doesn't take that kind of answer.
 */
fun answerProblem(err: String): String = when {
    err.contains("doesn't fit", ignoreCase = true) || err.contains("isn't there", ignoreCase = true) ||
        err.contains("no longer", ignoreCase = true) || err.contains("not found", ignoreCase = true) ->
        "Your answer wasn't used: that question has moved on (it may have been answered on the PC)."
    else -> "Your answer wasn't used: $err"
}

/**
 * Whether the PC now names another person than this phone's chats belong to. A phone that never
 * recorded one (paired before people were told apart) was the owner's, so it changes when it is
 * now anyone else's; nothing it kept may carry over.
 */
internal fun personChanged(earlier: String?, now: String): Boolean =
    if (earlier != null) earlier != now else now != "owner"

/** What the PC calls the owner's own phones. */
internal const val OWNER_PERSON = "owner"

/** Said once when the PC's owner takes Online away from this phone's person while it was chosen. */
internal const val ONLINE_WITHDRAWN = "Your PC's owner turned off Online for you, so your PC's free model answers now."

/** Online was chosen before and is not offered to this person now (the owner's own phone always keeps it). */
internal fun onlineWithdrawn(before: String?, now: String?, owner: Boolean): Boolean =
    !owner && before == "paid" && now != "paid"
