package io.github.odnwks.mimir.standalone

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Where a model's download stands. */
sealed interface ModelStatus {
    data object Missing : ModelStatus
    /** Downloaded, and a newer file is offered ("Update model"). */
    data object Outdated : ModelStatus
    data class Downloading(val done: Long, val total: Long) : ModelStatus
    data class Checking(val done: Long, val total: Long) : ModelStatus
    /** Part downloaded, then stopped (cancelled, or the app was closed): resume or remove it. */
    data class Paused(val done: Long, val total: Long) : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val why: String) : ModelStatus
}

/** Everything the standalone screens show. */
data class LocalState(
    val supported: Boolean = true,
    val status: Map<String, ModelStatus> = emptyMap(),
    val model: String = Catalog.default.id,
    val chat: LocalChat = LocalChat(newId(), Catalog.default.id, emptyList()),
    val earlier: List<LocalChat> = emptyList(),
    /** The reply being written, while [writing]. */
    val partial: String = "",
    val writing: Boolean = false,
    val loading: Boolean = false,
    /** "18 words a second" style note under the last reply. */
    val speed: String? = null,
    val problem: String? = null,
    /** The owner pressed Stop on the answer in this chat (it says "You stopped this answer"). */
    val stoppedChat: String? = null,
    /** The problem is "a Whisper can't use an online model": the chat offers This phone / Your PC beside it. */
    val whisperChoice: Boolean = false,
    /** Models whose file is on the phone (a model being updated stays usable). */
    val have: Set<String> = emptySet(),
    /** Models whose vision part is on the phone too: they can see photos. */
    val sees: Set<String> = emptySet(),
    /** "Think first" (for models that can): the same as [effort] Thorough. */
    val think: Boolean = false,
    /** How hard it works on an answer (the effort chip on the message box). */
    val effort: Effort = Effort.Balanced,
    /** True while the model is still thinking, before its answer starts. */
    val thinking: Boolean = false,
    /**
     * While the model reads the message before its first word: "Reading it…" ([readingFound], with
     * what a lookup found) or "Reading your message…". Never called thinking: it isn't.
     */
    val reading: Boolean = false,
    val readingFound: Boolean = false,
    /** A one-line note about tidying (an unused model removed, and the space freed). */
    val note: String? = null,
    /** What "Test on this device" found, per model. */
    val tests: Map<String, TestResult> = emptyMap(),
    /** The test under way, if any. */
    val testing: TestProgress? = null,
    /** The open chat is a Whisper: on this phone only, never saved, forgotten when it ends. */
    val whisper: Boolean = false,
    /** What the app is looking up before Mímir answers ("Weather for Leeds…"), while it does. */
    val looking: String? = null,
    /** The last answer's lookup failed: "Try again" under it asks again. */
    val lookupFailed: Boolean = false,
    /** The last message asks for the weather and there's no place yet: "Weather for where?". */
    val askPlace: Boolean = false,
    /** The first search: the one-time warning about DuckDuckGo shows, with these search words. */
    val askSearch: String? = null,
    /** The Whisper has internet: lookups only, through Tor (with [whisper]). */
    val web: Boolean = false,
    /** A Whisper was just ended with one tap: "Whisper ended. Nothing was kept." shows for a moment. */
    val whisperEnded: Boolean = false,
    /** A lookup is still waiting on Tor after about 20 s: "Still waiting…" with Cancel. */
    val torSlow: Boolean = false,
    /** The composer's place chip: "Your PC when connected" (true, the default) or "This phone only". */
    val usePc: Boolean = true,
    /** The reply being written comes from the PC. */
    val onPc: Boolean = false,
    /** The link dropped while the PC answered: "Waiting for your PC…" with Answer on this phone. */
    val pcWaiting: Boolean = false,
    /** What the PC says while its answer hasn't started ("Waking your PC's model…"). */
    val pcLine: String? = null,
    /** The first sync brought the PC's earlier chats here: Chat history says so once. */
    val movedNote: Boolean = false,
    /** False for one answer: "Just chat" with a PC that can't be told so, so this phone answers it. */
    val usePcOnce: Boolean = true,
    /** Your own models: the folder's name (null: none chosen), what's in it, and how that stands. */
    val ownFolder: String? = null,
    val own: List<OwnEntry> = emptyList(),
    val ownLooking: Boolean = false,
    /** The folder can't be read any more (removed, or Android took the permission back). */
    val ownLost: Boolean = false,
    /** "Copying your model · 40%" while a copy is made because it couldn't be read from the folder. */
    val ownCopy: String? = null,
) {
    val ready: Boolean get() = model in have
}

private fun newId() = UUID.randomUUID().toString()

/** A chat just deleted and where it was (-1: it was the open one), for Undo. */
data class Deleted(val chat: LocalChat, val at: Int)

/** What an edit of an earlier message cut away (for Undo). */
data class Edited(val chatId: String, val removed: List<Turn>)

/** Runs a model: llama.cpp on the phone, or a stand-in in checks. */
interface Runner {
    /** False if the model can't load. */
    fun open(path: String): Boolean
    /** A JSON line with counts and times, or {"error": ...}; [pictures] are JPEGs, one per marker. */
    fun reply(prompt: String, maxTokens: Int, sink: PieceSink, pictures: List<ByteArray> = emptyList()): String = reply(prompt, maxTokens, sink)
    fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String
    /**
     * Reads [prompt] without answering, so the next [reply] that starts with it only reads what
     * is new (done while a lookup runs). Stand-ins in checks do nothing.
     */
    fun warm(prompt: String): String = ""
    /** Loads the model's vision part (its mmproj file); false if it can't. */
    fun see(mmproj: String): Boolean = false
    fun stop()
    fun close()
}

/** llama.cpp, 8K of context (4K if 8K won't fit). */
class LlmRunner(private val threads: Int) : Runner {
    private var handle = 0L

    override fun open(path: String): Boolean {
        close()
        handle = Llm.open(path, 8192, threads, 0.8f)
        if (handle == 0L) handle = Llm.open(path, 4096, threads, 0.8f)
        return handle != 0L
    }

    override fun reply(prompt: String, maxTokens: Int, sink: PieceSink): String = Llm.reply(handle, prompt, maxTokens, sink)
    override fun warm(prompt: String): String = if (handle == 0L) "" else Llm.reply(handle, prompt, 0, PieceSink { true })
    override fun reply(prompt: String, maxTokens: Int, sink: PieceSink, pictures: List<ByteArray>): String {
        if (pictures.isEmpty()) return reply(prompt, maxTokens, sink)
        val rgb = pictures.map { Pictures.rgb(it) ?: return """{"error":"picture"}""" }
        return Llm.reply(handle, prompt, maxTokens, sink, rgb)
    }
    override fun see(mmproj: String): Boolean = handle != 0L && Llm.see(handle, mmproj, threads)
    override fun stop() { if (handle != 0L) Llm.stop(handle) }
    override fun close() { if (handle != 0L) { Llm.free(handle); handle = 0L } }
}

/**
 * Standalone mode: downloads, the chosen model, and its chats. One model is kept loaded; replies
 * are written one at a time on their own thread.
 */
class Engine(
    private val dir: File,
    private val store: Store,
    private val runner: Runner,
    supported: Boolean,
    /** Called with true when work starts that must not be stopped (a reply, a download), false when it ends. */
    private val busy: (Boolean) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** The phone's temperature in °C, for "Test on this device" (null: unknown). */
    private val temperature: () -> Float? = { null },
    /** Memory the app uses in MB, for "Test on this device". */
    private val memoryMb: () -> Long = { ModelTester.residentMb() },
    /** Turns the folder the owner chose (its saved address) into something to read; null: it can't be read. */
    private val ownFolders: (String) -> OwnFolder? = { null },
    private val chatRepo: io.github.odnwks.mimir.data.store.ChatRepository? = null,
    private val modelLedger: io.github.odnwks.mimir.data.store.ModelLedger? = null,
) {
    /** Chats deleted whose Undo hasn't passed yet: still in the file, not on screen. */
    private val undoable = ArrayList<LocalChat>()

    /** The PC, once paired (set by the link): messages go there while it can answer (Stage 1, 2.1). */
    @Volatile var pc: PcTalk? = null

    /** Messages the PC was asked to answer: an answer given here instead replaces the PC's (version 2). */
    private val askedPc = java.util.Collections.synchronizedSet(HashSet<String>())
    /** "Answer on this phone", tapped while waiting for the PC. */
    private val answerHere = AtomicBoolean(false)

    // ---- One history (Stage 1, 2.5): run one at a time, apart from replies ----
    private val syncer = Executors.newSingleThreadExecutor { Thread(it, "standalone-sync").apply { isDaemon = true } }
    @Volatile private var lastSeq = 0L
    private val pendingDeletes = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    @Volatile private var synced = false
    /** This connection has asked the PC what changed while apart. */
    @Volatile private var caughtUp = false
    /** Chats whose put is on its way. */
    private val sending = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Whether the next message goes to the PC: paired and connected, "Your PC" chosen, not a Whisper. */
    // A phone that can't run a model always uses the PC ("This phone only" isn't offered there).
    fun pcAnswers(): Boolean = _state.value.let { (it.usePc || !it.supported) && it.usePcOnce && !it.whisper } && pc?.canTalk() == true && !onlineAnswers()

    /**
     * Pictures when the PC answers: true when its answerer can look at them (they go to the PC, so no
     * phone model is needed), false when it can't, null when the PC isn't answering or is too old to say.
     */
    fun pcPictures(): Boolean? {
        if (!pcAnswers()) return null
        val p = pc ?: return null
        return when {
            p.canSeePictures() -> true
            p.picturesRefused() -> false
            else -> null
        }
    }

    /** Files go to the PC to be read when it answers and takes them (null-safe: false for an older PC). */
    fun pcFiles(): Boolean = pcAnswers() && pc?.canSendFiles() == true

    /** Whether the next message goes to an online model the owner set up (never in a Whisper). */
    fun onlineAnswers(): Boolean = !_state.value.whisper && io.github.odnwks.mimir.online.OnlineModels.answering() != null

    private val _state = MutableStateFlow(LocalState(supported = supported))
    val state: StateFlow<LocalState> = _state.asStateFlow()

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "standalone-writer") }
    /** Lets the model go after [UNLOAD_IDLE_MS] without a reply, so about 3 GB of RAM isn't held for nothing. */
    private val idler = Executors.newSingleThreadScheduledExecutor { Thread(it, "standalone-idle").apply { isDaemon = true } }
    /** What each chat's message box holds (kept here so Settings or History in between loses nothing); the words are kept on the phone too. */
    val drafts = ChatDrafts(store.loadDrafts()) { scheduleDraftSave() }
    @Volatile private var draftSave: java.util.concurrent.ScheduledFuture<*>? = null
    private fun scheduleDraftSave() {
        draftSave?.cancel(false)
        draftSave = runCatching { idler.schedule({ flushDrafts() }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
    }
    /** Writes the draft words now (the app is leaving the screen, or a chat's draft went). */
    fun flushDrafts() { runCatching { store.saveDrafts(drafts.texts()) } }
    /** A chat is gone for good: so is what was left in its box. */
    fun forgetChatDraft(id: String) { drafts.remove(id) }
    @Volatile private var lastReplyAt = 0L
    private val fetcher = Executors.newSingleThreadExecutor { Thread(it, "standalone-download") }
    private val cancel = AtomicBoolean(false)
    private val stopReply = AtomicBoolean(false)
    private val stopTest = AtomicBoolean(false)
    private var loadedModel: String? = null
    /** The model whose vision part is loaded too. */
    @Volatile private var seeing: String? = null
    private var downloading: String? = null

    private val models = File(dir, "models")
    /** Recordings kept while the PC is away (sealed like the chats). */
    private val voiceDir = File(dir, "voice")
    /** The PC's list of models, kept so it still applies with no PC. */
    private val catalogFile = File(dir, "standalone-catalog.json")
    /** What "Test on this device" found (numbers only). */
    private val testsFile = File(dir, "model-tests.json")
    private val lastUsed = HashMap<String, Long>()
    private val files = HashMap<String, String>()

    // Your own models: what the owner chose (kept in a small file), and what is held open while it's loaded.
    private val ownSaved = File(dir, "own-models.json")
    private val ownCopyFile = File(File(dir, "own-copy"), "model.gguf")
    @Volatile private var ownFolder: OwnFolder? = null
    private var ownFolderUri: String? = null
    private var ownFolderName: String? = null
    /** The own model in use, as it was looked at when chosen. */
    private var chosenOwn: OwnEntry? = null
    private val scanner = Executors.newSingleThreadExecutor { Thread(it, "standalone-own").apply { isDaemon = true } }
    private val ownRead = HashMap<String, OwnEntry>()
    /** The own model loaded: its file (and its photo part's) are held open while it is. */
    private var ownOpen: OwnOpen? = null
    private var ownVisionOpen: OwnOpen? = null

    /** One snapshot at a time: the state is read inside the lock, so an older snapshot never lands after a newer one. (Before init: init saves.) */
    private val persistLock = Any()

    init {
        catalogFile.takeIf { it.isFile }?.let { runCatching { Catalog.use(it.readText()) } }
        ModelSearch.load(File(dir, "found-models.json"))
        loadOwn()
        val saved = store.load()
        lastUsed += saved.lastUsed
        files += saved.files
        // A chat saved with an answer before its question (an older sync) is put right.
        val chats = saved.chats.map { c -> if (c.turns.all { it.at > 0 }) c.copy(turns = ChatSync.inOrder(c.turns)) else c }
        val chosen = if (Catalog.has(saved.model)) saved.model else Catalog.default.id
        val current = chats.firstOrNull() ?: LocalChat(newId(), chosen, emptyList())
        val effort = Effort.of(saved.effort) ?: if (saved.think) Effort.Thorough else Effort.Balanced
        _state.value = _state.value.copy(model = chosen, chat = current, earlier = chats.drop(1), think = effort == Effort.Thorough, effort = effort, tests = loadTests(), usePc = saved.usePc)
        lastSeq = saved.lastSeq
        pendingDeletes += saved.pendingDeletes
        synced = saved.synced
        if (store.unreadable) _state.update { it.copy(problem = "Your earlier chats couldn't be read. They are kept safe in a separate file on this phone. Ask for help before clearing the app's data.") }
        tidy()
        tidyVoice()
    }

    private fun fileFor(m: LocalModel) = File(models, m.file)
    private fun fileFor(id: String) = File(models, "$id.gguf")
    private fun visionFor(m: LocalModel) = File(models, m.visionFile)
    /** The vision part is on the phone and is the one this list names. */
    private fun seesNow(m: LocalModel) =
        if (m.own) m.vision != null
        else m.vision != null && visionFor(m).isFile && files[m.visionFile].let { it == null || it == m.vision.sha256 }

    // ---------- Keeping only what's used ----------

    /**
     * Nothing sits unused: files of models no longer offered go, a model not used for
     * [UNUSED_DAYS] days goes (it downloads again when wanted), and stale part-downloads go.
     * Then each model's status is worked out again.
     */
    private fun tidy() {
        val t = now()
        var freed = 0L
        val gone = ArrayList<String>()
        models.listFiles()?.forEach { f ->
            when {
                f.name.endsWith(".gguf") -> {
                    val id = f.name.removeSuffix(".gguf")
                    val used = lastUsed[id] ?: f.lastModified()
                    val unused = t - used > UNUSED_DAYS * DAY
                    // Only a model not used for a long time goes; the PC's list changing never removes one (PHE-17).
                    if (unused) {
                        freed += f.length()
                        gone += (Catalog.models.firstOrNull { it.id == id }?.name ?: "an old model")
                        f.delete()
                        files.remove(id)
                        lastUsed.remove(id)
                    } else if (id !in files && Catalog.has(id)) {
                        // Downloaded before checksums were kept: it's the file this list names.
                        files[id] = Catalog.byId(id).sha256
                    }
                }
                f.name.endsWith(".gguf.part") || f.name.endsWith(".vision.part") -> {
                    val id = f.name.removeSuffix(".part").removeSuffix(".gguf").removeSuffix(".vision")
                    // A paused download is kept for [PART_DAYS] days, whichever model it is for.
                    if (t - f.lastModified() > PART_DAYS * DAY) { freed += f.length(); f.delete() }
                }
                else -> {}
            }
        }
        // A vision part goes with its model, and when the list no longer offers one.
        models.listFiles()?.filter { it.name.endsWith(".vision") }?.forEach { f ->
            val id = f.name.removeSuffix(".vision")
            // A vision part goes with its model: only when the model's own file is gone (a changed list never takes it).
            if (!fileFor(id).isFile) {
                freed += f.length()
                f.delete()
                files.remove(f.name)
            }
        }
        val note = if (gone.isEmpty()) null else
            "Removed ${gone.joinToString(" and ")}, unused, to free %.1f GB. It downloads again when you need it.".format(freed / 1e9)
        refreshStatus(note)
        persist()
    }

    private fun refreshStatus(note: String? = _state.value.note) {
        val own = Catalog.own?.id
        val have = Catalog.models.filter { fileFor(it).isFile }.map { it.id }.toSet() + listOfNotNull(own)
        _state.update { s ->
            s.copy(
                have = have,
                sees = (Catalog.models + listOfNotNull(Catalog.own)).filter { it.id in have && seesNow(it) }.map { it.id }.toSet(),
                note = note,
                status = Catalog.models.associate { m ->
                    val old = s.status[m.id]
                    val part = File(models, m.file + ".part")
                    m.id to when {
                        (old is ModelStatus.Downloading || old is ModelStatus.Checking) && downloading == m.id -> old
                        m.id !in have && part.isFile && part.length() > 0 -> ModelStatus.Paused(part.length(), m.totalBytes)
                        m.id !in have -> if (old is ModelStatus.Failed) old else ModelStatus.Missing
                        files[m.id] != null && files[m.id] != m.sha256 -> ModelStatus.Outdated
                        // Downloaded before it came with its vision part: Update adds it.
                        m.vision != null && !seesNow(m) -> ModelStatus.Outdated
                        else -> ModelStatus.Ready
                    }
                },
            )
        }
    }

    /** The PC's list of models arrived: keep it (for use with no PC), and tidy against it. */
    fun useCatalog(json: String) {
        if (json == runCatching { catalogFile.readText() }.getOrNull()) return
        if (!Catalog.use(json)) return
        runCatching { catalogFile.parentFile?.mkdirs(); catalogFile.writeText(json) }
        if (!Catalog.has(_state.value.model)) _state.update { it.copy(model = Catalog.default.id) }
        if (_state.value.writing || downloading != null) refreshStatus() else tidy()
    }

    /** "Think first" is effort Thorough; turning it off goes back to Balanced. */
    fun setThink(on: Boolean) = setEffort(if (on) Effort.Thorough else _state.value.effort.takeIf { it != Effort.Thorough } ?: Effort.Balanced)

    fun setEffort(effort: Effort) {
        _state.update { it.copy(effort = effort, think = effort == Effort.Thorough) }
        persist()
    }

    fun dismissNote() = _state.update { it.copy(note = null) }

    /** A one-line note for the chat (with OK), such as why the location couldn't be read. */
    fun say(note: String) = _state.update { it.copy(note = note) }

    // ---------- Models ----------

    fun choose(id: String) {
        // Another model than the own one: the own one is let go (it is only ever read from its folder, so nothing is deleted).
        if (!OwnModels.isOwn(id) && Catalog.own != null) forgetOwnChoice()
        _state.update { it.copy(model = id, problem = null) }
        modelLedger?.setChosen(id)
        refreshStatus()
        persist()
    }

    fun download(id: String) {
        val m = Catalog.byId(id)
        if (downloading != null) return
        downloading = id
        cancel.set(false)
        setStatus(id, ModelStatus.Downloading(0, m.totalBytes))
        busy(true)
        fetcher.execute {
            try {
                fetch(m)
            } catch (e: Exception) {
                Log.w(TAG, "download of ${m.id} failed", e)
                if (!cancel.get()) setStatus(id, ModelStatus.Failed(downloadProblem(e)))
            } finally {
                downloading = null
                // A stopped download shows as paused (resume or remove), not "Not downloaded".
                refreshStatus()
                busy(false)
                answerIfWaiting()
            }
        }
    }

    fun cancelDownload() = cancel.set(true)

    /** Why a download stopped, in plain words. */
    private fun downloadProblem(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "no internet. Connect to Wi-Fi and try again; it carries on from where it stopped"
        is java.net.SocketTimeoutException, is javax.net.ssl.SSLException, is java.net.SocketException ->
            "the connection dropped. Try again; it carries on from where it stopped"
        else -> e.message ?: "download failed"
    }

    /** Removes a part-download, freeing its space. */
    fun removePartial(id: String) {
        if (downloading == id) return
        File(models, Catalog.byId(id).file + ".part").delete()
        File(models, Catalog.byId(id).visionFile + ".part").delete()
        setStatus(id, ModelStatus.Missing)
        refreshStatus()
    }

    /** How much room the chats kept on this phone take (Settings › Storage). */
    fun chatBytes(): Long = store.bytes()

    fun delete(id: String) {
        modelLedger?.recordDelete(id)
        if (_state.value.testing?.id == id) cancelTest()
        if (loadedModel == id) writer.execute { closeModel(); loadedModel = null }
        fileFor(id).delete()
        File(models, "$id.gguf.part").delete()
        File(models, Catalog.byId(id).file + ".part").delete()
        File(models, "$id.vision").delete()
        File(models, "$id.vision.part").delete()
        files.remove(id)
        files.remove("$id.vision")
        lastUsed.remove(id)
        setStatus(id, ModelStatus.Missing)
        refreshStatus()
        persist()
    }

    /**
     * Downloads a model and its vision part (if it has one), each resumed where it stopped and
     * checked against its SHA-256 before use. A part already here and current isn't fetched again.
     */
    private fun fetch(m: LocalModel) {
        models.mkdirs()
        val total = m.totalBytes
        val mainDone = fileFor(m).isFile && files[m.id] == m.sha256
        if (!mainDone) fetchOne(m.id, m.url, m.bytes, m.sha256, File(models, m.file + ".part"), 0, total)
        val v = m.vision
        val visionPart = File(models, m.visionFile + ".part")
        if (v != null && !(visionFor(m).isFile && files[m.visionFile] == v.sha256)) fetchOne(m.id, v.url, v.bytes, v.sha256, visionPart, m.bytes, total)
        // An update replaces the old files in one step, so there are never two copies kept.
        if (loadedModel == m.id) writer.execute { closeModel(); loadedModel = null }
        if (!mainDone) {
            File(models, m.file + ".part").renameTo(fileFor(m))
            files[m.id] = m.sha256
        }
        if (v != null && visionPart.isFile) {
            visionPart.renameTo(visionFor(m))
            files[m.visionFile] = v.sha256
        }
        lastUsed[m.id] = now()
        modelLedger?.recordDownload(m.id)
        setStatus(m.id, ModelStatus.Ready)
        refreshStatus()
        persist()
    }

    /** Resumes one part-downloaded file into [part], then checks its SHA-256 (progress counts from [before] of [total]). */
    private fun fetchOne(id: String, url: String, bytes: Long, sha256: String, part: File, before: Long, total: Long) {
        var have = part.length()
        // What goes in and out meanwhile is the download's (Settings › Privacy check).
        if (have < bytes) io.github.odnwks.mimir.data.NetUse.download {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            // PHE-22: resume from partial offset, not restart
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code == 200) have = 0 else if (code != 206) error("the download site had a problem")
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(have)
                out.seek(have)
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var shown = 0L
                    while (true) {
                        if (cancel.get()) error("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - shown > 8_000_000) { shown = have; setStatus(id, ModelStatus.Downloading(before + have, total)) }
                    }
                }
            }
        }
        if (part.length() != bytes) error("the download stopped early")
        val sha = MessageDigest.getInstance("SHA-256")
        part.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            var done = 0L
            var shown = 0L
            while (true) {
                if (cancel.get()) error("cancelled")
                val n = input.read(buf)
                if (n < 0) break
                sha.update(buf, 0, n)
                done += n
                if (done - shown > 64_000_000) { shown = done; setStatus(id, ModelStatus.Checking(before + done, total)) }
            }
        }
        val got = sha.digest().joinToString("") { "%02x".format(it) }
        if (got != sha256) {
            part.delete()
            error("the file arrived damaged, so it was removed")
        }
    }

    private fun setStatus(id: String, s: ModelStatus) = _state.update { it.copy(status = it.status + (id to s)) }

    // ---------- Chats ----------

    fun newChat() {
        if (_state.value.web && !_state.value.writing) io.github.odnwks.mimir.online.TorLink.stop()
        if (_state.value.writing) {
            _state.update { it.copy(problem = "Stop the reply first, then start a new chat.") }
            return
        }
        // Leaving a Whisper by any way forgets what it looked up.
        _state.value.takeIf { it.whisper }?.let { forgetWhisperLookups(it.chat) }
        _state.update { s ->
            val keep = if ((s.chat.turns.isEmpty() && s.chat.queued.isEmpty()) || s.whisper) s.earlier else listOf(s.chat) + s.earlier
            s.copy(chat = LocalChat(newId(), s.model, emptyList()), earlier = keep, whisper = false, web = false, speed = null, problem = null, askPlace = false, askSearch = null)
        }
        persist()
    }

    fun open(id: String) {
        if (_state.value.writing) return
        if (_state.value.earlier.none { it.id == id }) return
        _state.value.takeIf { it.whisper }?.let { forgetWhisperLookups(it.chat) }
        _state.update { s ->
            val pick = s.earlier.firstOrNull { it.id == id } ?: return@update s
            val rest = s.earlier.filter { it.id != id } + if ((s.chat.turns.isEmpty() && s.chat.queued.isEmpty()) || s.whisper) emptyList() else listOf(s.chat)
            if (s.web) io.github.odnwks.mimir.online.TorLink.stop()
            s.copy(chat = pick, earlier = rest, whisper = false, web = false, speed = null, problem = null, askPlace = false, askSearch = null)
        }
        persist()
        // Messages that waited in that chat go now, if the PC is there.
        if (_state.value.chat.queued.isNotEmpty()) syncer.execute { drainQueue() }
    }

    /**
     * Starts a Whisper: a new chat on this phone's model that is never written anywhere and is
     * forgotten when it ends. The chat that was open goes to the earlier ones, as with New chat.
     */
    fun startWhisper(web: Boolean = false) {
        if (_state.value.writing) return
        // A Whisper never uses an online model: said plainly, and nothing starts.
        if (io.github.odnwks.mimir.online.OnlineModels.answering() != null) {
            _state.update { it.copy(problem = io.github.odnwks.mimir.online.OnlineModels.WHISPER_NO_ONLINE, whisperChoice = true) }
            return
        }
        if (_state.value.web) io.github.odnwks.mimir.online.TorLink.stop()
        _state.update { s ->
            val keep = if (s.chat.turns.isEmpty() || s.whisper) s.earlier else listOf(s.chat) + s.earlier
            s.copy(chat = LocalChat(newId(), s.model, emptyList()), earlier = keep, whisper = true, web = web, torSlow = false, speed = null, problem = null, whisperChoice = false, askPlace = false, askSearch = null)
        }
        // A Whisper with internet starts connecting to Tor at once (it's slow the first time).
        if (web && io.github.odnwks.mimir.online.Online.allowed(io.github.odnwks.mimir.online.ChatMode.WhisperWeb)) io.github.odnwks.mimir.online.TorLink.start()
        persist()
    }

    /** "Still waiting…" › Cancel: the lookup stops, and Mímir answers without the internet. */
    fun cancelTor() {
        if (!_state.value.web) return
        io.github.odnwks.mimir.online.TorLink.cancel()
        _state.update { it.copy(torSlow = false) }
    }

    /**
     * Ends the Whisper and forgets it: nothing of it is kept. A reply being written is stopped, and
     * with [told] the fresh chat says so for a moment ([LocalState.whisperEnded]).
     */
    fun endWhisper(told: Boolean = false) {
        if (!_state.value.whisper) return
        if (_state.value.writing) stop()
        if (_state.value.web) io.github.odnwks.mimir.online.TorLink.stop()
        forgetWhisperLookups(_state.value.chat)
        _state.update { it.copy(chat = LocalChat(newId(), it.model, emptyList()), whisper = false, web = false, torSlow = false, partial = "", speed = null, problem = null, askPlace = false, askSearch = null, looking = null, lookupFailed = false, whisperEnded = told) }
        persist()
    }

    /** What a Whisper looked up leaves the memory and the Privacy check list (PHE-03, PHE-18). */
    internal fun forgetWhisperLookups(chat: LocalChat) {
        io.github.odnwks.mimir.online.LookupLog.forgetWhisper()
        io.github.odnwks.mimir.online.Net.forget()
        io.github.odnwks.mimir.online.Search.forget()
        synchronized(fetchedFor) { chat.turns.forEach { fetchedFor.remove(it.id) } }
        synchronized(lastLookup) { lastLookup.remove(chat.id) }
        forcedSearch.set(null)
    }

    /** The "Whisper ended" line has been shown. */
    fun dismissWhisperEnded() = _state.update { it.copy(whisperEnded = false) }

    /**
     * The app left the screen (not a rotation): with "End Whispers when I leave the app" on
     * ([endOnLeave]), an open Whisper is ended and forgotten.
     */
    fun appLeft(endOnLeave: Boolean, rotating: Boolean) {
        if (endOnLeave && !rotating) endWhisper()
    }

    /** Names a chat, the open one by default (blank: back to the topic from its first message). */
    fun rename(name: String, id: String = _state.value.chat.id) {
        val n = name.trim().take(60).ifBlank { null }
        _state.update { s ->
            if (s.chat.id == id) s.copy(chat = s.chat.copy(name = n, pendingName = true))
            else s.copy(earlier = s.earlier.map { if (it.id == id) it.copy(name = n, pendingName = true) else it })
        }
        persist()
    }

    /** This chat's own rules (the model is told them first, in this chat only; empty: none). */
    fun setChatRules(text: String, id: String = _state.value.chat.id) {
        val t = text.trim().take(MAX_CHAT_RULES)
        _state.update { s ->
            if (s.chat.id == id) s.copy(chat = s.chat.copy(rules = t))
            else s.copy(earlier = s.earlier.map { if (it.id == id) it.copy(rules = t) else it })
        }
        persist()
    }

    fun forget(id: String) {
        _state.update { s -> s.copy(earlier = s.earlier.filter { it.id != id }) }
        pendingDeletes += id
        forgetChatDraft(id)
        persist()
    }

    /**
     * Deletes a chat (the open one or an earlier one): it goes from the screen at once, and from the
     * phone's storage when its Undo has passed ([forgetDeleted]). Returns it with its place, for
     * Undo, or null when there was nothing to delete.
     */
    fun deleteChat(id: String): Deleted? {
        val s = _state.value
        val d = if (s.chat.id == id) {
            if (s.writing || s.chat.turns.isEmpty()) return null
            _state.update { it.copy(chat = LocalChat(newId(), it.model, emptyList()), speed = null, problem = null, askPlace = false, askSearch = null) }
            Deleted(s.chat, -1)
        } else {
            val at = s.earlier.indexOfFirst { it.id == id }
            if (at < 0) return null
            _state.update { st -> st.copy(earlier = st.earlier.filter { it.id != id }) }
            Deleted(s.earlier[at], at)
        }
        synchronized(undoable) { undoable += d.chat }
        return d
    }

    /** Undo has passed (or the app went to the background): deleted chats leave the phone's storage. */
    fun forgetDeleted() {
        val gone = synchronized(undoable) { undoable.map { it.id }.also { undoable.clear() } }
        // The PC is told too, so the chat goes from every device and never comes back.
        pendingDeletes += gone
        gone.forEach { forgetChatDraft(it); chatRepo?.deleteChat(it) }
        flushDrafts()
        if (gone.isNotEmpty()) persist()
    }

    /** Undo: a deleted chat comes back where it was (the open one opens again if nothing was started since). */
    fun restore(d: Deleted) {
        synchronized(undoable) { undoable.removeAll { it.id == d.chat.id } }
        _state.update { s ->
            when {
                s.chat.id == d.chat.id || s.earlier.any { it.id == d.chat.id } -> s
                d.at < 0 && s.chat.turns.isEmpty() && !s.writing -> s.copy(chat = d.chat)
                else -> s.copy(earlier = s.earlier.toMutableList().apply { add(d.at.coerceIn(0, size), d.chat) })
            }
        }
        persist()
    }

    /**
     * The PC says this phone now belongs to another person: the chats kept here were the earlier
     * person's, so they go, without telling the PC (it has its own copy for that person), and the
     * new person's history is asked for from the start.
     */
    fun newPerson() = syncer.execute {
        synchronized(undoable) { undoable.clear() }
        pendingDeletes.clear(); sending.clear()
        drafts.clear()
        lastSeq = 0; caughtUp = false
        _state.update { it.copy(chat = LocalChat(newId(), it.model, emptyList()), earlier = emptyList(), speed = null, problem = null, askPlace = false, askSearch = null) }
        persistQuietly()
    }

    /** Every chat on the phone, gone (Settings › Storage). */
    fun deleteAll() {
        if (_state.value.writing) return
        synchronized(undoable) { undoable.clear() }
        _state.value.let { s -> pendingDeletes += (listOf(s.chat).filter { it.turns.isNotEmpty() && !s.whisper } + s.earlier).map { it.id } }
        _state.update { it.copy(chat = LocalChat(newId(), it.model, emptyList()), earlier = emptyList(), speed = null, problem = null, askPlace = false, askSearch = null) }
        persist()
    }

    /**
     * The last message got no answer (the app was closed mid-reply, or the reply failed):
     * it is asked again.
     */
    fun retry() {
        val s0 = _state.value
        val last = s0.chat.turns.lastOrNull() ?: return
        if (s0.writing || !last.user) return
        // The message is taken out of the chat only when it can really be sent again (PHE-02).
        if (!canSend()) return
        _state.update { it.copy(chat = it.chat.copy(turns = it.chat.turns.dropLast(1))) }
        send(last.text, last.files, last.pictures, last.id, last.at, last.version)
    }

    /**
     * "Try again" on the newest answer: the answer is taken out and written again for the same question (the
     * question stays once). The new answer has the same id and a higher version, so it replaces the old one
     * on the PC's copy too. Returns false when there was nothing to redo, or it can't be sent now.
     */
    fun tryAgain(): Boolean {
        val s0 = _state.value
        val turns = s0.chat.turns
        val answer = turns.lastOrNull()?.takeIf { !it.user && it.task == null } ?: return false
        val question = turns.getOrNull(turns.lastIndex - 1)?.takeIf { it.user } ?: return false
        if (s0.writing || s0.whisper || !canSend()) return false
        if (question.id.isNotEmpty()) retryFloor["${question.id}.a"] = answer.version + 1
        _state.update { it.copy(chat = it.chat.copy(turns = it.chat.turns.dropLast(2))) }
        persist()
        return send(question.text, question.files, question.pictures, question.id, question.at, question.version)
    }

    /**
     * Edits the owner's message [turnId] in the open chat: its words become [text], every later turn is
     * cut away (and told to the PC as tombstones), and it is asked again from there. Returns what was
     * cut, for [undoEdit]; null when it can't be done now (an answer is being written, a project task
     * follows the message, nothing can answer).
     */
    fun editMessage(turnId: String, text: String): Edited? {
        val t = text.trim()
        val s0 = _state.value
        val c = s0.chat
        val pos = c.turns.indexOfFirst { it.id == turnId && it.user }
        if (t.isEmpty() || pos < 0 || s0.writing || !canEdit(c, pos)) return null
        if (!canSend()) {
            if (s0.testing == null) _state.update { it.copy(problem = "Nothing can answer right now, so this can't be edited yet. Try again when your PC is back.") }
            return null
        }
        val removed = c.turns.drop(pos)
        val orig = removed[0]
        val answerId = "${orig.id}.a"
        val cuts = removed.drop(1).map { Cut(it.id, it.user, it.at, it.version + 1) } +
            (if (removed.none { it.id == answerId }) listOf(Cut(answerId, false, orig.at, 2)) else emptyList())
        val before = _state.value
        // The new answer takes the version above the tombstone's.
        retryFloor[answerId] = (cuts.firstOrNull { it.id == answerId }?.version ?: 2) + 1
        _state.update { it.copy(chat = c.copy(turns = c.turns.take(pos), cuts = c.cuts.filter { k -> cuts.none { n -> n.id == k.id } } + cuts), stoppedChat = null) }
        if (!send(t, orig.files, orig.pictures, orig.id, orig.at, orig.version + 1)) {
            _state.update { it.copy(chat = before.chat) }
            retryFloor.remove(answerId)
            return null
        }
        return Edited(c.id, removed)
    }

    /** Whether the message at [pos] can be edited: not once a project task followed it (its file changes can't be undone). */
    fun canEdit(c: LocalChat, pos: Int): Boolean = pos in c.turns.indices && c.turns[pos].user && c.turns.drop(pos).none { it.task != null }

    /** "Undo" after [editMessage]: the cut turns and the message's old words come back (an answer being written is stopped). */
    fun undoEdit(e: Edited) {
        if (_state.value.writing) stop()
        _state.update { s ->
            if (s.chat.id != e.chatId) return@update s
            val c = s.chat
            val back = e.removed.map { r ->
                val now = c.turns.firstOrNull { it.id == r.id }?.version ?: 0
                r.copy(version = maxOf(r.version + 3, now + 1), seq = 0)
            }
            val ids = back.map { it.id }.toSet()
            val kept = c.turns.filter { it.id !in ids }
            s.copy(chat = c.copy(turns = ChatSync.inOrder(kept + back), cuts = c.cuts.filter { it.id !in ids }), stoppedChat = null)
        }
        persist()
    }

    /** An answer's version never goes below what a "Try again" asked for, so the new one replaces the old everywhere. */
    private val retryFloor = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun floored(t: Turn): Turn {
        val f = retryFloor[t.id] ?: return t
        return if (t.version >= f) t else t.copy(version = f, seq = 0)
    }

    /**
     * A fresh app start opens on the home screen: the chat that was open is one tap away in the menu.
     * (Not a Whisper, and not a chat with nothing in it.)
     */
    fun startOnHome() {
        val s = _state.value
        if (s.writing || s.whisper || (s.chat.turns.isEmpty() && s.chat.queued.isEmpty())) return
        _state.update { st -> st.copy(chat = LocalChat(newId(), st.model, emptyList()), earlier = listOf(st.chat) + st.earlier) }
        persist()
    }

    /** Whether [send] would take a message now; a model test running is told to the owner. */
    /** True when something could answer a message right now (used to hide Edit when it would go nowhere). */
    fun canReplyNow(): Boolean = _state.value.let { !it.writing && (it.ready || pcAnswers() || onlineAnswers()) }

    private fun canSend(): Boolean {
        val s = _state.value
        if (s.writing || !(s.ready || pcAnswers() || onlineAnswers())) return false
        if (s.testing != null) {
            _state.update { it.copy(problem = "A model test is running: cancel it in Settings › Who answers, or wait a moment.") }
            return false
        }
        return true
    }

    fun stop() {
        // The owner stopped it: the chat says so (and keeps what was written).
        _state.update { if (it.writing && !it.whisper) it.copy(stoppedChat = it.chat.id) else it }
        stopReply.set(true)
        runner.stop()
    }

    /**
     * A message from the chat whether or not the model is here yet: with no model it waits in the
     * chat (the one-time download is the owner's choice: it's big), and it's answered as soon as
     * the model is ready.
     */
    /** True when the message was taken (sent, or kept to wait): only then does the box clear (PHE-02). */
    fun ask(text: String, files: List<TurnFile> = emptyList(), pictures: List<TurnPicture> = emptyList()): Boolean {
        val t = text.trim()
        val s0 = _state.value
        if ((t.isEmpty() && files.isEmpty() && pictures.isEmpty()) || s0.writing) return false
        // Messages already waiting go first: a new one lines up behind them, so the order never changes.
        if (s0.chat.queued.isNotEmpty() && !s0.whisper) { enqueue(Turn(true, t, files, pictures, id = newId(), at = now())); drainQueue(); return true }
        if (s0.ready || pcAnswers() || onlineAnswers()) return send(t, files, pictures)
        // Paired and the PC is the answerer: with nothing to answer it now (the PC away, this phone with no model),
        // the message stays in the chat, marked "Sends when your PC is back", and goes by itself, in order, once.
        if (pc != null && !s0.whisper && (s0.usePc || !s0.supported)) { enqueue(Turn(true, t, files, pictures, id = newId(), at = now())); return true }
        if (waiting || (!s0.supported && pc == null)) return false
        _state.update { it.copy(chat = it.chat.copy(turns = it.chat.turns + Turn(true, t, files, pictures, id = newId(), at = now()), model = it.model, at = now()), problem = null) }
        persist()
        return true
    }

    /** A message that waited for the model is answered now it's here. */
    fun answerIfWaiting() {
        refreshStatus()
        val s = _state.value
        if ((s.ready || pcAnswers() || onlineAnswers()) && !s.writing && s.chat.turns.lastOrNull()?.user == true) retry()
    }

    // ---------- Messages waiting for the PC ----------

    private val drainLock = Any()
    /** Recordings being turned into words on the PC right now (by message id), so none goes twice. */
    private val hearing = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun enqueue(t: Turn) {
        _state.update { it.copy(chat = it.chat.copy(queued = it.chat.queued + t, at = now()), problem = null) }
        persist()
    }

    /** The owner took a waiting message back (✕): it is never sent, and a kept recording is deleted. */
    fun cancelQueued(id: String) {
        val gone = _state.value.chat.queued.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(chat = it.chat.copy(queued = it.chat.queued.filter { q -> q.id != id })) }
        gone.voice?.let { File(voiceDir, it).delete() }
        persist()
    }

    /**
     * Sends the next waiting message of the open chat, if something can answer now and nothing is being written.
     * One at a time: the next goes when this one's answer is in (the end of [send]). A kept recording is first
     * turned into words on the PC, then goes the same way.
     */
    fun drainQueue() {
        val link = pc
        synchronized(drainLock) {
            val s = _state.value
            if (s.whisper || s.writing) return
            if (s.chat.queued.isEmpty()) {
                // Waiting messages of another chat (after a restart the app opens on the home screen): that chat is opened for them.
                val other = s.earlier.firstOrNull { it.queued.isNotEmpty() }
                if (other != null && s.chat.turns.isEmpty() && (pcAnswers() || s.ready || onlineAnswers())) open(other.id) else return
                if (_state.value.chat.queued.isEmpty()) return
            }
            val first = _state.value.chat.queued.first()
            if (first.voice != null && first.text.isBlank()) { hear(first, link); return }
            if (!canSend() || _state.value.chat.turns.lastOrNull()?.user == true) return
            _state.update { it.copy(chat = it.chat.copy(queued = it.chat.queued.drop(1))) }
            // It is dated when it goes (same id), so its answer never lands before the answers of the ones sent earlier.
            if (!send(first.text, first.files, first.pictures, first.id, maxOf(first.at, now()), first.version)) {
                _state.update { it.copy(chat = it.chat.copy(queued = listOf(first) + it.chat.queued)) }
            }
        }
    }

    private fun hear(t: Turn, link: PcTalk?) {
        if (link?.canTalk() != true || !hearing.add(t.id)) return
        val file = File(voiceDir, t.voice ?: return)
        val wav = store.openAudio(file)
        if (wav == null) { hearing -= t.id; cancelQueued(t.id); return }
        val sent = link.hear(wav) { text, err ->
            syncer.execute {
                hearing -= t.id
                when {
                    // The PC may be busy or gone again: the recording stays and is tried at the next connect.
                    err != null -> return@execute
                    text.isNullOrBlank() -> {
                        cancelQueued(t.id)
                        _state.update { it.copy(note = "Nothing was heard in your recording, so it wasn't sent. Say it again.") }
                    }
                    else -> {
                        file.delete()
                        _state.update { st -> st.copy(chat = st.chat.copy(queued = st.chat.queued.map { q -> if (q.id == t.id) q.copy(text = text.trim(), voice = null) else q })) }
                        persist()
                        drainQueue()
                    }
                }
            }
        }
        if (!sent) hearing -= t.id
    }

    /**
     * A recording made while the PC is away, kept on this phone (sealed like the chats) and shown in the chat as a
     * waiting message; the PC turns it into words when it is back, and it goes then. The audio is deleted once done.
     */
    fun keepRecording(wav: ByteArray, seconds: Int): Boolean {
        if (_state.value.whisper || pc == null) return false
        val id = newId()
        val name = "$id.wav"
        val ok = runCatching { voiceDir.mkdirs(); store.sealAudio(File(voiceDir, name), wav) }.isSuccess
        if (!ok) { _state.update { it.copy(note = "The recording couldn't be kept on this phone. Type it instead.") }; return false }
        enqueue(Turn(true, "", id = id, at = now(), voice = name))
        drainQueue()
        return true
    }

    /** Recordings no waiting message points to are deleted (nothing sits unused). */
    private fun tidyVoice() {
        val used = (listOf(_state.value.chat) + _state.value.earlier).flatMap { c -> c.queued.mapNotNull { it.voice } }.toSet()
        voiceDir.listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
    }

    /** The last message waits for the model (it came before the model was here). */
    val waiting: Boolean get() = _state.value.let { !it.ready && !it.writing && it.chat.turns.lastOrNull()?.user == true }

    /** True when the message was taken and a reply is being written. */
    fun send(text: String, files: List<TurnFile> = emptyList(), pictures: List<TurnPicture> = emptyList(), id: String = "", at: Long = 0L, version: Int = 1): Boolean {
        val t = text.trim()
        val s0 = _state.value
        val toPc = pcAnswers()
        val viaOnline = onlineAnswers()
        if ((t.isEmpty() && files.isEmpty() && pictures.isEmpty()) || s0.writing || !(s0.ready || toPc || viaOnline)) return false
        if (s0.testing != null) {
            _state.update { it.copy(problem = "A model test is running: cancel it in Settings › Who answers, or wait a moment.") }
            return false
        }
        val model = Catalog.byId(s0.model)
        // The same words sent again after a failed answer are that message again (same id): never a second copy.
        val failed = s0.chat.turns.lastOrNull()?.takeIf { id.isEmpty() && it.user && it.text.trim() == t && it.files == files && it.pictures == pictures }
        // A message sent again (it waited for the PC) keeps the time it was typed, so its answer never sorts before it.
        val turn = if (failed != null) failed.copy(at = failed.at) else Turn(true, t, files, pictures, id = id.ifEmpty { newId() }, at = if (at > 0) at else now(), version = version)
        _state.update { it.copy(chat = it.chat.copy(turns = (if (failed != null) it.chat.turns.dropLast(1) else it.chat.turns) + turn, model = model.id, at = now()), writing = true, partial = "", problem = null, speed = null, askPlace = false, askSearch = null, stoppedChat = null) }
        persist()
        pcWhy = null
        stopReply.set(false)
        answerHere.set(false)
        busy(true)
        writer.execute {
            try {
                // Paired and connected, the PC answers; if it can't (gone, busy, refused), this phone does.
                val answered = if (viaOnline) { writeOnline(); true } else toPc && pcAnswers() && writePc()
                if (!answered && !stopReply.get()) {
                    if (_state.value.ready) write(model)
                    else _state.update {
                        // The PC answers pings but could not answer this message: say that, once, and let the owner send again (never "not reachable").
                        if (pc?.canTalk() == true) it.copy(problem = pcWhy ?: PC_COULD_NOT_ANSWER, note = null)
                        else {
                            // The PC went away on the way: the message goes back to waiting, and goes when the PC is back.
                            val last = it.chat.turns.lastOrNull()?.takeIf { t -> t.user && t.id == turn.id }
                            if (last == null) it else it.copy(chat = it.chat.copy(turns = it.chat.turns.dropLast(1), queued = listOf(last) + it.chat.queued), problem = null)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "reply failed", e)
                _state.update { it.copy(problem = "The reply stopped. Try again.") }
            } finally {
                // PHE-04: one-shot choices (forced search, typed place, tapped page) are always
                // cleared after a send, even when the PC answered and lookUp() was never called.
                forcedSearch.set(null)
                typedPlace.set(null)
                tappedPage.set(null)
                skipLookup.set(false)
                _state.update { it.copy(writing = false, loading = false, thinking = false, looking = null, onPc = false, pcWaiting = false, pcLine = null, usePcOnce = true) }
                persist()
                busy(false)
                lastReplyAt = now()
                idler.schedule({ writer.execute { unloadIfIdle() } }, UNLOAD_IDLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                // The next waiting message goes now this one is done.
                if (_state.value.chat.queued.isNotEmpty()) drainQueue()
            }
        }
        return true
    }

    /**
     * The model is let go once no reply has been written for [UNLOAD_IDLE_MS] (Stage 1, 1.6), or
     * at once with [force] (the PC is answering instead). The next reply loads it again
     * ("Loading…", a few seconds). Runs where replies run, so it never meets one.
     */
    internal fun unloadIfIdle(force: Boolean = false) {
        if (loadedModel == null || _state.value.writing || _state.value.testing != null) return
        if (!force && now() - lastReplyAt < UNLOAD_IDLE_MS) return
        closeModel()
        Log.i(TAG, "let ${loadedModel} go after ${if (force) "the PC took over" else "3 idle minutes"}")
        loadedModel = null
        seeing = null
    }

    /** The PC answers now: this phone's model is let go at once (after any reply still being written). */
    fun unloadNow() = writer.execute { unloadIfIdle(force = true) }

    /** Whether this phone's model is in memory (for checks). */
    val modelLoaded: Boolean get() = loadedModel != null

    // ---------- Looking things up (Mímir online) ----------

    /** A place typed in answer to "Weather for where?": found, saved, then the message is asked again. */
    private val typedPlace = java.util.concurrent.atomic.AtomicReference<String?>(null)
    /** "Use my rough location once": for the next answer only, never saved. */
    private val roughPlace = java.util.concurrent.atomic.AtomicReference<io.github.odnwks.mimir.online.Place?>(null)
    /** "Not now" to the place question: the next answer goes without the weather. */
    private val skipLookup = AtomicBoolean(false)
    /** What the PC said about why it could not answer, when it said it in its own plain words. */
    @Volatile private var pcWhy: String? = null

    /** "Weather for where?" answered with a place's name: it's saved as the weather place. */
    fun answerPlace(name: String) {
        val n = name.trim().take(80)
        if (n.isEmpty() || !_state.value.askPlace) return
        typedPlace.set(n)
        _state.update { it.copy(askPlace = false) }
        retry()
    }

    /** "Use my rough location once": rounded to about 10 km here, before anything leaves the phone. */
    fun answerRoughPlace(lat: Double, lon: Double) {
        if (!_state.value.askPlace) return
        roughPlace.set(io.github.odnwks.mimir.online.Place(ROUGH_PLACE, Math.round(lat * 10) / 10.0, Math.round(lon * 10) / 10.0))
        _state.update { it.copy(askPlace = false) }
        retry()
    }

    /** "Not now": Mímir answers without the weather. */
    fun skipPlace() {
        if (!_state.value.askPlace) return
        skipLookup.set(true)
        _state.update { it.copy(askPlace = false) }
        retry()
    }

    /** "Look it up" on an answer: the next answer searches with the owner's message first. */
    private val forcedSearch = java.util.concurrent.atomic.AtomicReference<io.github.odnwks.mimir.online.Router.Need.Search?>(null)

    /** The one-time warning about searching DuckDuckGo, accepted: the question is searched. */
    fun acceptSearch() {
        if (_state.value.askSearch == null) return
        io.github.odnwks.mimir.online.Online.let { o -> o.set(o.settings.value.copy(searchAccepted = true)) }
        askedFor.set(null)
        _state.update { it.copy(askSearch = null) }
        retry()
    }

    /** "Not now" to the warning: Mímir answers without searching, or from Wikipedia alone (asked again next time). */
    fun declineSearch() {
        if (_state.value.askSearch == null) return
        if (askedFor.getAndSet(null) is io.github.odnwks.mimir.online.Router.Need.Encyclopedia) wikipediaOnly.set(true) else skipLookup.set(true)
        _state.update { it.copy(askSearch = null) }
        retry()
    }

    /**
     * "Look it up" (an answer's long-press menu, normal mode): that answer is replaced by one
     * written after searching the web for the owner's message.
     */
    fun lookItUp() {
        val s = _state.value
        if (s.writing || (s.whisper && !s.web) || s.askPlace || s.askSearch != null) return
        val turns = s.chat.turns
        if (turns.size < 2 || turns.last().user || !turns[turns.size - 2].user) return
        val words = io.github.odnwks.mimir.online.Router.lookUp(turns[turns.size - 2].text) ?: return
        if (!canSend()) return
        forcedSearch.set(words)
        _state.update { it.copy(chat = it.chat.copy(turns = it.chat.turns.dropLast(1))) }
        retry()
    }

    private val noLookup: Pair<String?, List<io.github.odnwks.mimir.online.Source>> = null to emptyList()

    /** A page the owner tapped to have read: the next answer reads it first. */
    private val tappedPage = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /**
     * Reads the page at [url] (only ever after the owner's tap: "Read example.com?" under a message,
     * or "Have Mímir read this page" in the app's browser) and answers the last message with it; with
     * nothing asked yet, it says what the page is about. Never in a Whisper.
     */
    fun readPage(url: String) {
        val s = _state.value
        if (s.writing || (s.whisper && !s.web) || s.askPlace || s.askSearch != null || !s.ready) return
        if (!url.startsWith("https://")) return
        tappedPage.set(url)
        val turns = s.chat.turns
        when {
            turns.lastOrNull()?.user == true -> retry()
            turns.size >= 2 && turns[turns.size - 2].user -> {
                _state.update { it.copy(chat = it.chat.copy(turns = it.chat.turns.dropLast(1))) }
                retry()
            }
            else -> send("What does this page say? $url")
        }
    }

    private fun page(url: String): Pair<String?, List<io.github.odnwks.mimir.online.Source>> {
        val site = io.github.odnwks.mimir.online.Net.hostOf(url)
        if (io.github.odnwks.mimir.online.Online.dataSaver()) {
            return "\n\n[The owner asked to read $site, but Android's Data Saver is on, so the app didn't read it. Say so in one sentence.]" to emptyList()
        }
        _state.update { it.copy(looking = "Reading $site…") }
        return try {
            val got = io.github.odnwks.mimir.online.Net.get(url, mode(), words = url, site = site, accept = setOf("text/html", "text/plain"))
            val text = if (got.type == "text/plain") io.github.odnwks.mimir.online.PageText.cut(got.text, PAGE_CHARS)
            else io.github.odnwks.mimir.online.PageText.of(got.text, PAGE_CHARS).let { p -> (if (p.title.isNotEmpty()) "Page title: ${p.title}\n" else "") + p.text }
            Prompt.fetched("The page $url, as plain words:\n$text", site, clock()) to listOf(io.github.odnwks.mimir.online.Source(site, url, ""))
        } catch (e: io.github.odnwks.mimir.online.Net.Refused) {
            "\n\n[The app couldn't read $site: ${e.message}. Say so plainly.]" to emptyList()
        } catch (e: Exception) {
            failed("read the page on $site", e)
        } finally {
            _state.update { it.copy(looking = null) }
        }
    }

    /** What the one-time warning is showing for (a search, or an encyclopedia question). */
    private val askedFor = java.util.concurrent.atomic.AtomicReference<io.github.odnwks.mimir.online.Router.Need?>(null)
    /** "Not now" to the warning on an encyclopedia question: that answer reads Wikipedia alone. */
    private val wikipediaOnly = AtomicBoolean(false)

    private fun mode() = when {
        _state.value.whisper && _state.value.web -> io.github.odnwks.mimir.online.ChatMode.WhisperWeb
        _state.value.whisper -> io.github.odnwks.mimir.online.ChatMode.Whisper
        else -> io.github.odnwks.mimir.online.ChatMode.Normal
    }

    private fun clock() = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(now()))

    /**
     * Looks up what the last message needs before Mímir answers (only in normal mode with lookups
     * on; never in a Whisper). Returns the marked information and its sources, or null when the
     * reply must wait (a question is showing: the place, or the one-time search warning). With
     * [asked] (what the model asked for, its words checked), that is looked up instead of what the
     * Router finds in the owner's words.
     */
    private fun lookUp(turns: List<Turn>, asked: io.github.odnwks.mimir.online.Router.Need? = null): Pair<String?, List<io.github.odnwks.mimir.online.Source>>? {
        val forced = forcedSearch.getAndSet(null)
        val tapped = tappedPage.getAndSet(null)
        if (skipLookup.getAndSet(false)) return noLookup
        if (!io.github.odnwks.mimir.online.Online.allowed(mode())) return noLookup
        val last = turns.lastOrNull()?.takeIf { it.user } ?: return noLookup
        if (mode() == io.github.odnwks.mimir.online.ChatMode.WhisperWeb && (tapped != null || forced != null || io.github.odnwks.mimir.online.Router.need(last.text) != null)) {
            torWait()?.let { return it }
        }
        // A normal chat's lookup, all its steps together, takes about 12 s at most (Tor is slower).
        val budget = if (mode() == io.github.odnwks.mimir.online.ChatMode.WhisperWeb) Long.MAX_VALUE / 4 else io.github.odnwks.mimir.online.Net.BUDGET_MS
        val previous = turns.dropLast(1).lastOrNull { it.user }?.text
        return io.github.odnwks.mimir.online.Net.within(budget) {
            if (tapped != null) page(tapped)
            else when (val need = forced ?: asked ?: io.github.odnwks.mimir.online.Router.need(last.text) ?: (if (mode() == io.github.odnwks.mimir.online.ChatMode.Normal) io.github.odnwks.mimir.online.Router.followUp(last.text, previous) else null)) {
                is io.github.odnwks.mimir.online.Router.Need.Weather -> weather(need)
                is io.github.odnwks.mimir.online.Router.Need.Search -> search(need, forced != null)
                is io.github.odnwks.mimir.online.Router.Need.Encyclopedia -> encyclopedia(need)
                null -> noLookup
            }
        }
    }

    /**
     * A Whisper with internet: waits for Tor, saying so in the reply's place ("Connecting through
     * Tor…", then "Still waiting…" with Cancel after about 20 s). Null once Tor is ready;
     * otherwise what Mímir is told (it answers without the internet, never directly).
     */
    private fun torWait(): Pair<String?, List<io.github.odnwks.mimir.online.Source>>? {
        val tor = io.github.odnwks.mimir.online.TorLink
        if (tor.isReady) return null
        _state.update { it.copy(looking = "Connecting by a hidden route…") }
        val t0 = System.currentTimeMillis()
        tor.start()
        while (!tor.await(500)) {
            if (stopReply.get() || !_state.value.web || !tor.connectingNow) break
            val waited = System.currentTimeMillis() - t0
            if (waited > TOR_SLOW_MS && !_state.value.torSlow) _state.update { it.copy(torSlow = true, looking = "Still waiting…") }
            else if (waited <= TOR_SLOW_MS) _state.update { it.copy(looking = "Connecting by a hidden route… ${tor.progress()}%") }
            if (waited > io.github.odnwks.mimir.online.TorLink.CONNECT_MS) break
            // Cancel stops Tor; nothing restarts it for this answer.
            if (!_state.value.torSlow && waited > TOR_SLOW_MS) break
        }
        _state.update { it.copy(torSlow = false, looking = null) }
        if (tor.isReady) return null
        return "\n\n[The app couldn't connect by its hidden route, so it looked nothing up (it never goes around it). Say \"Couldn't connect\" and answer from what you know, saying it may be out of date.]" to emptyList()
    }

    private fun failed(what: String, e: Throwable): Pair<String?, List<io.github.odnwks.mimir.online.Source>> {
        // Given up on already (Stop, or out of time): nothing more to say or show.
        if (Thread.currentThread().isInterrupted || e is InterruptedException) return noLookup
        // A Whisper's words and searches are never logged.
        if (_state.value.whisper) Log.w(TAG, "lookup failed in a Whisper") else Log.w(TAG, "lookup failed (${what.substringBefore(' ')}): ${e.javaClass.simpleName}")
        _state.update { it.copy(lookupFailed = true) }
        val why = if (e is io.github.odnwks.mimir.online.Net.Failed || e is io.github.odnwks.mimir.online.Net.PcAway) e.message
            else io.github.odnwks.mimir.online.Net.plain(e, !io.github.odnwks.mimir.online.Net.phoneOnline())
        return lookupFailedNote(what, why ?: "it gave no answer") to emptyList()
    }

    /** "Try again" under an answer whose lookup failed: that answer is replaced by a new one. */
    fun lookupAgain() {
        val s = _state.value
        if (s.writing || !s.lookupFailed) return
        val turns = s.chat.turns
        if (turns.size < 2 || turns.last().user || !turns[turns.size - 2].user) return
        _state.update { it.copy(lookupFailed = false, chat = it.chat.copy(turns = it.chat.turns.dropLast(1))) }
        retry()
    }

    /** "Answer without looking it up" under an answer whose lookup failed: asked again, with no lookup this time. */
    fun answerWithoutLookup() {
        val s = _state.value
        if (s.writing || !s.lookupFailed) return
        val turns = s.chat.turns
        if (turns.size < 2 || turns.last().user || !turns[turns.size - 2].user) return
        skipLookup.set(true)
        _state.update { it.copy(lookupFailed = false, chat = it.chat.copy(turns = it.chat.turns.dropLast(1))) }
        retry()
    }

    /** The answer whose failed lookup was already asked again by itself (chat id and turn count). */
    private var retriedLookup: String? = null

    /**
     * The phone's network came back: an answer whose lookup failed is asked again by itself,
     * once (QA I1). "Try again" under it still works after that.
     */
    fun networkBack() {
        val s = _state.value
        if (!s.lookupFailed || s.writing) return
        val key = "${s.chat.id}:${s.chat.turns.size}"
        if (retriedLookup == key) return
        retriedLookup = key
        lookupAgain()
    }

    private fun weather(need: io.github.odnwks.mimir.online.Router.Need.Weather): Pair<String?, List<io.github.odnwks.mimir.online.Source>>? {
        val mode = mode()
        val typed = typedPlace.getAndSet(null)
        val named = need.place ?: typed
        val place = roughPlace.getAndSet(null) ?: if (named != null) {
            _state.update { it.copy(looking = "Looking up the weather for $named…") }
            val found = try { io.github.odnwks.mimir.online.Weather.find(named, mode) } catch (e: Exception) { return failed("look up the weather for \"$named\"", e) }
                ?: return "\n\n[The app couldn't find a place called \"$named\". Say so and ask which place they mean.]" to emptyList()
            // A place given in answer to "Weather for where?" is kept as the weather place.
            if (typed != null && need.place == null && mode == io.github.odnwks.mimir.online.ChatMode.Normal) io.github.odnwks.mimir.online.Online.let { o -> o.set(o.settings.value.copy(place = found)) }
            found
        } else if (mode == io.github.odnwks.mimir.online.ChatMode.Normal) io.github.odnwks.mimir.online.Online.settings.value.place else null
        if (place == null) {
            _state.update { it.copy(askPlace = true, looking = null) }
            return null
        }
        _state.update { it.copy(looking = "Looking up the weather for ${place.name}…") }
        return try {
            val f = io.github.odnwks.mimir.online.Weather.forecast(place, mode, ask = _state.value.chat.turns.lastOrNull { it.user }?.text.orEmpty())
            Prompt.fetched(f.block, f.sources.firstOrNull()?.site ?: io.github.odnwks.mimir.online.Weather.SITE, clock()) to f.sources
        } catch (e: Exception) {
            failed("get the weather for ${place.name}", e)
        } finally {
            _state.update { it.copy(looking = null) }
        }
    }

    /** How many of a search's results are read as pages. */
    private val pagesToRead = 4

    private fun search(need: io.github.odnwks.mimir.online.Router.Need.Search, forced: Boolean): Pair<String?, List<io.github.odnwks.mimir.online.Source>>? {
        val settings = io.github.odnwks.mimir.online.Online.settings.value
        if (!settings.search) return noLookup
        val mode = mode()
        // A normal chat reads the top pages found (owner, 2026-11-25: no extra warning, as little sent as possible).
        val readPages = mode == io.github.odnwks.mimir.online.ChatMode.Normal
        // PC-first (PHW-04, PHE-14): ask the PC's SearXNG, wait up to 3 s, then fall back.
        _state.update { it.copy(looking = "Searching: ${need.words}…") }
        io.github.odnwks.mimir.online.Search.searchViaPc(need.words, mode)?.let { results ->
            _state.update { it.copy(looking = null) }
            // PHW-07: source label shows "SearXNG" (what Settings shows), not the underlying engine names.
            val via = "your PC's search"
            if (results.isEmpty()) return "\n\n[The app searched the web for \"${need.words}\" through the owner's PC and found nothing. Say so.]" to emptyList()
            if (readPages) evidence(need.words, results, "a web search", via)?.let { return it }
            return Prompt.fetched(io.github.odnwks.mimir.online.Search.describe(need.words, results, "the owner's PC search"), "a web search", clock()) to
                results.distinctBy { it.site }.take(3).map { io.github.odnwks.mimir.online.Source(it.site, it.url, need.words, via = via) }
        }
        _state.update { it.copy(looking = null) }
        if (!settings.searchAccepted) {
            if (forced) forcedSearch.set(need)
            askedFor.set(need)
            _state.update { it.copy(askSearch = need.words, looking = null) }
            return null
        }
        val words = need.words
        _state.update { it.copy(looking = "Searching: $words…") }
        return try {
            val results = io.github.odnwks.mimir.online.Search.search(words, mode)
            when {
                results.isEmpty() -> "\n\n[The app searched the web for \"$words\" and found nothing. Say so.]" to emptyList()
                else -> (if (readPages) evidence(words, results, "DuckDuckGo", "") else null)
                    ?: (Prompt.fetched(io.github.odnwks.mimir.online.Search.describe(words, results), "DuckDuckGo", clock()) to
                        results.distinctBy { it.site }.take(3).map { io.github.odnwks.mimir.online.Source(it.site, it.url, words) })
            }
        } catch (e: io.github.odnwks.mimir.online.Search.Blocked) {
            if (!_state.value.whisper) Log.w(TAG, "search blocked: ${e.message}")
            // PHE-12: show the real reason, not always "DuckDuckGo blocked".
            val reason = when {
                e.message?.contains("robot check", ignoreCase = true) == true -> "the search site asked for a robot check"
                e.message?.contains("no signal", ignoreCase = true) == true || e.message?.contains("unavailable", ignoreCase = true) == true -> "the search site is not answering right now"
                e.message != null -> e.message
                else -> "the search site is unavailable"
            }
            "\n\n[The app searched the web for \"$words\", but it was blocked: $reason. Say so in one sentence, " +
                "and that the results can be opened with the button under your answer. Don't guess the answer.]" to
                listOf(io.github.odnwks.mimir.online.Source("DuckDuckGo", e.results, words, results = true))
        } catch (e: io.github.odnwks.mimir.online.Net.Failed) {
            // PHE-12: show the real failure reason (no signal, timeout, etc.).
            if (!_state.value.whisper) Log.w(TAG, "search failed: ${e.message}")
            failed("search DuckDuckGo for \"$words\"", e)
        } catch (e: io.github.odnwks.mimir.online.Net.Refused) {
            noLookup
        } finally {
            _state.update { it.copy(looking = null) }
        }
    }

    /**
     * The top pages of [results] read (all at once, within the lookup's time), the passages that
     * answer the owner's message chosen, and the numbered evidence block made from them, with one
     * source per number. Null when nothing could be chosen: the caller uses the results as before.
     */
    private fun evidence(
        words: String, results: List<io.github.odnwks.mimir.online.Search.Result>, from: String, via: String,
    ): Pair<String?, List<io.github.odnwks.mimir.online.Source>>? {
        val picked = io.github.odnwks.mimir.online.Fuse.pick(results, pagesToRead)
        if (picked.isNotEmpty() && !io.github.odnwks.mimir.online.Online.dataSaver()) {
            _state.update { it.copy(looking = if (picked.size == 1) "Reading 1 page…" else "Reading ${picked.size} pages…") }
        }
        try {
            val reads = io.github.odnwks.mimir.online.Reader.read(picked, mode())
            val question = _state.value.chat.turns.lastOrNull { it.user }?.text ?: words
            val ranked = io.github.odnwks.mimir.online.Rank.choose(question, words, reads)
                .filter { io.github.odnwks.mimir.online.Evidence.clean(it.quote).isNotEmpty() }
            val renumber = ranked.map { it.n }.distinct().withIndex().associate { (i, n) -> n to i + 1 }
            val chosen = ranked.map { it.copy(n = renumber.getValue(it.n)) }
            if (chosen.isEmpty()) return null
            val block = io.github.odnwks.mimir.online.Evidence.block(words, chosen, reads.count { it.text != null })
            val sources = chosen.distinctBy { it.n }.sortedBy { it.n }.map { io.github.odnwks.mimir.online.Source(it.site, it.url, words, via = via) }
            return Prompt.fetched(block, from, clock()) to sources
        } finally {
            _state.update { it.copy(looking = null) }
        }
    }

    /** An answer written from numbered evidence: a source number that doesn't exist ([7] of 3) is taken out. */
    private fun cited(reply: String, block: String?, sources: Int): String =
        if (block != null && io.github.odnwks.mimir.online.Evidence.INSTRUCTION in block) io.github.odnwks.mimir.online.Verify.citations(reply, sources) else reply

    /**
     * "What is / who was": Wikipedia and Grokipedia (as chosen in Settings), the same article on
     * both, compared by Mímir. Grokipedia only after the one-time warning; without it, Wikipedia.
     */
    private fun encyclopedia(need: io.github.odnwks.mimir.online.Router.Need.Encyclopedia): Pair<String?, List<io.github.odnwks.mimir.online.Source>>? {
        val settings = io.github.odnwks.mimir.online.Online.settings.value
        val which = settings.encyclopedias
        // Until the one-time warning is accepted, Wikipedia alone answers: the first ask is never held up by a question.
        val declined = wikipediaOnly.getAndSet(false)
        val wikiOnly = declined || !settings.searchAccepted
        val words = need.words
        _state.update { it.copy(looking = "Looking up $words…") }
        return try {
            val f = io.github.odnwks.mimir.online.Encyclopedia.look(words, which, grok = settings.searchAccepted && !wikiOnly, mode())
            when {
                // 0.09 item 25: an encyclopedia with no article falls through to the web search (unless "Not now" was just said).
                (f == null || (f.wikipedia == null && f.grokipedia == null)) && !declined && settings.search -> {
                    _state.update { it.copy(looking = null) }
                    search(io.github.odnwks.mimir.online.Router.Need.Search(words), false)
                }
                f == null || (f.wikipedia == null && f.grokipedia == null) -> "\n\n[The app looked up \"$words\" in the encyclopedias and found no article. Say so; don't guess.]" to emptyList()
                else -> Prompt.fetched(io.github.odnwks.mimir.online.Encyclopedia.describe(f), listOfNotNull("Wikipedia".takeIf { f.wikipedia != null }, "Grokipedia".takeIf { f.grokipedia != null }).joinToString(" and "), clock()) to
                    io.github.odnwks.mimir.online.Encyclopedia.sources(f, words)
            }
        } catch (e: io.github.odnwks.mimir.online.Net.Refused) {
            noLookup
        } catch (e: Exception) {
            failed("look up \"$words\" in the encyclopedias", e)
        } finally {
            _state.update { it.copy(looking = null) }
        }
    }

    /** The last lookup of each chat, short (kept in memory only; the newest 20 chats). */
    private val lastLookup = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 20
    }

    /**
     * What was looked up for each message (by its id), kept with it: later prompts in the chat
     * hold it where it was, so they start exactly as the last one did and the model only reads
     * what's new (memory only; the newest 100).
     */
    private val fetchedFor = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 100
    }

    /** Lookups run here, next to the writer, so the model loads and reads while they fetch. */
    private val lookups = Executors.newCachedThreadPool { Thread(it, "standalone-lookup").apply { isDaemon = true } }

    /** Loads [model] if it isn't loaded; false (with the problem shown) if it can't. */
    private fun load(model: LocalModel, timing: StringBuilder): Boolean {
        if (loadedModel == model.id) return true
        _state.update { it.copy(loading = true) }
        val t0 = System.nanoTime()
        seeing = null
        closeModel()
        if (!(if (model.own) openOwn(model) else runner.open(fileFor(model).path))) {
            loadedModel = null
            _state.update { it.copy(loading = false) }
            if (!ownGone(model)) _state.update { it.copy(problem = "The model couldn't load. Try closing other apps, or a smaller model.") }
            return false
        }
        loadedModel = model.id
        timing.append(" load=").append((System.nanoTime() - t0) / 1_000_000).append("ms")
        _state.update { it.copy(loading = false) }
        dropOthers(model.id)
        return true
    }

    /** What [prepare] last read ahead (chat, its length, model, effort), so typing doesn't repeat it. */
    @Volatile private var prepared: String? = null

    /**
     * The owner started typing: the model loads and reads the chat so far now, while they type,
     * so the answer starts sooner (on an S25: about 4 s to load, and 3 to 4 s to read the
     * instructions). Nothing is answered; an unused model is let go after the usual idle time.
     */
    fun prepare() {
        val s = _state.value
        if (!s.ready || s.writing || s.testing != null || pcAnswers()) return
        val key = "${s.chat.id}:${s.chat.turns.size}:${s.model}:${s.effort}"
        if (prepared == key) return
        prepared = key
        writer.execute {
            val st = _state.value
            if (st.writing || st.testing != null || st.chat.turns.any { it.pictures.isNotEmpty() }) return@execute
            val model = Catalog.byId(st.model)
            val t0 = System.nanoTime()
            val timing = StringBuilder()
            if (!load(model, timing)) return@execute
            val earlier = synchronized(fetchedFor) { HashMap(fetchedFor) }
            val online = io.github.odnwks.mimir.online.Online.allowed(mode())
            val r = runCatching {
                runner.warm(Prompt.build(model.format, st.chat.turns + Turn(true, ""), brief = st.effort == Effort.Quick, online = online, earlier = earlier, head = true, chatRules = st.chat.rules, ask = askLine(model, mode())))
            }.getOrDefault("")
            if (!st.whisper) Log.i(TAG, "ready ahead:$timing read=${runCatching { JSONObject(r).optInt("new_prompt_tokens") }.getOrDefault(-1)}tok total=${(System.nanoTime() - t0) / 1_000_000}ms")
            lastReplyAt = now()
            idler.schedule({ writer.execute { unloadIfIdle() } }, UNLOAD_IDLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Today's date and the model's way to ask for a lookup itself ([Prompt.askSearch]), for a
     * normal chat with lookups and web search on and a model that asks; null otherwise (a
     * Whisper never has it).
     */
    private fun askLine(model: LocalModel, mode: io.github.odnwks.mimir.online.ChatMode): String? =
        if (searchable(mode) && model.asksToSearch) Prompt.askSearch(now()) else null

    /** A normal chat with lookups and web search on: the model may ask for a lookup, and the safety net may look one up. */
    private fun searchable(mode: io.github.odnwks.mimir.online.ChatMode) = mode == io.github.odnwks.mimir.online.ChatMode.Normal &&
        io.github.odnwks.mimir.online.Online.allowed(mode) && io.github.odnwks.mimir.online.Online.settings.value.search

    private fun write(model: LocalModel) {
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        // Durations and sizes only, never the words; a Whisper logs nothing.
        val timing = StringBuilder()
        val whisper = _state.value.whisper
        _state.update { it.copy(lookupFailed = false) }
        val chatId = _state.value.chat.id
        val mode = mode()
        val online = io.github.odnwks.mimir.online.Online.allowed(mode)
        // "Answer without looking it up" or "Not now": nothing is looked up for this answer, not even when the model asks.
        val skipped = skipLookup.get()
        val searchable = searchable(mode) && !skipped
        val ask = if (skipped) null else askLine(model, mode)
        val effort = _state.value.effort
        val owner = _state.value.chat.turns.lastOrNull()?.takeIf { it.user }
        // The reference rule: the owner's words point back ("search for the cheaper one"), so the
        // model, which has read the chat, gives the words first.
        val routed = if (ask != null && owner != null && _state.value.chat.turns.size > 1 && forcedSearch.get() == null && tappedPage.get() == null) {
            io.github.odnwks.mimir.online.Router.need(owner.text)
        } else null
        val refer = routed != null && pointsBack(routed)
        // The lookup starts at once on its own thread; meanwhile this one loads the model and
        // reads the chat so far, so neither waits for the other.
        val lookupMs = java.util.concurrent.atomic.AtomicLong(-1)
        fun startLookup(need: io.github.odnwks.mimir.online.Router.Need?) =
            java.util.concurrent.FutureTask { lookUp(_state.value.chat.turns, need).also { lookupMs.set(ms()) } }.also { lookups.execute(it) }
        var lookup = if (refer) null else startLookup(null)
        if (!load(model, timing)) { lookup?.cancel(true); return }
        lastUsed[model.id] = now()
        var turns = _state.value.chat.turns
        // Pictures in the chat: the vision part loads the first time one is sent (it needs memory).
        var sees = false
        if (turns.any { it.pictures.isNotEmpty() } && seesNow(model)) {
            if (seeing == model.id) sees = true
            else {
                _state.update { it.copy(loading = true) }
                sees = if (model.own) seeOwn(model) else runner.see(visionFor(model).path)
                _state.update { it.copy(loading = false) }
                if (sees) seeing = model.id
                else _state.update { it.copy(problem = "The model couldn't load the part that sees pictures, so it got only the words. Try closing other apps.") }
            }
        }
        val earlier = synchronized(fetchedFor) { HashMap(fetchedFor) }
        fun prompt(think: Boolean, fetched: String?) = Prompt.build(
            model.format, turns, think, brief = effort == Effort.Quick, sees = sees, online = online, fetched = fetched, earlier = earlier,
            chatRules = _state.value.chat.rules, ask = ask,
        )
        fun pictures() = if (sees) Prompt.pictures(turns).map { it.jpeg } else emptyList()

        /**
         * The short decision, with thinking off and at most [DECIDE_TOKENS]: the words of the
         * model's SEARCH: line, or null for an answer (which is dropped). It stops as soon as it can tell.
         */
        fun decide(): String? {
            val d0 = ms()
            val out = StringBuilder()
            var pieces = 0
            var words: String? = null
            runCatching {
                runner.reply(prompt(false, null), DECIDE_TOKENS, PieceSink { bytes ->
                    out.append(String(bytes, Charsets.UTF_8))
                    pieces++
                    when (val a = asked(Thought.split(out.toString(), model.format, false).second, pieces)) {
                        is Ask.Unsure -> !stopReply.get()
                        is Ask.Plain -> false
                        is Ask.Line -> { if (a.done) words = a.words; !a.done && !stopReply.get() }
                    }
                }, pictures())
            }
            if (words == null) (asked(Thought.split(out.toString(), model.format, false).second, 0) as? Ask.Line)?.let { words = it.words }
            timing.append(" decide=").append(ms() - d0).append("ms")
            return words?.takeIf { it.isNotBlank() }
        }

        /** The model's words, checked ([io.github.odnwks.mimir.online.QueryGuard]), as what to look up; null when nothing is left. */
        fun modelNeed(words: String): io.github.odnwks.mimir.online.Router.Need? {
            val chat = _state.value.chat.turns
            val ownerText = owner?.text.orEmpty()
            val attached = chat.flatMap { it.files }.map { it.text }.filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
            val safe = io.github.odnwks.mimir.online.QueryGuard.safe(
                words, io.github.odnwks.mimir.online.Router.lookUp(ownerText)?.words ?: ownerText,
                chat.filter { it.user }.map { it.text }, attached, io.github.odnwks.mimir.online.Online.settings.value.place?.name,
            )
            val searchWords = io.github.odnwks.mimir.online.Router.words(safe) ?: return null
            return io.github.odnwks.mimir.online.Router.need(searchWords) ?: io.github.odnwks.mimir.online.Router.Need.Search(searchWords)
        }

        /** Waits for [task] as long as a lookup may take; null when the reply must stop here (Stop, or a question showing). */
        fun await(task: java.util.concurrent.FutureTask<Pair<String?, List<io.github.odnwks.mimir.online.Source>>?>, since: Long):
            Pair<String?, List<io.github.odnwks.mimir.online.Source>>? {
            // The lookup: never longer than its budget (Tor waits as long as it says on screen).
            val l0 = ms()
            val cap = if (mode == io.github.odnwks.mimir.online.ChatMode.WhisperWeb) Long.MAX_VALUE / 4 else LOOKUP_WAIT_MS
            var got: Pair<String?, List<io.github.odnwks.mimir.online.Source>>? = null
            while (true) {
                if (stopReply.get()) {
                    task.cancel(true)
                    _state.update { it.copy(looking = null, reading = false) }
                    return null
                }
                try {
                    got = task.get(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: run {
                        // A question shows first (the place, or the one-time search warning).
                        _state.update { it.copy(reading = false) }
                        return null
                    }
                    break
                } catch (e: java.util.concurrent.TimeoutException) {
                    if (ms() - since < cap) continue
                    task.cancel(true)
                    timing.append(" lookup=timeout")
                    // PHE-09: cancel lookup and clear stale state
                    _state.update { it.copy(looking = null, lookupFailed = true) }
                    got = LATE to emptyList()
                    break
                } catch (e: Exception) {
                    if (!whisper) Log.w(TAG, "lookup crashed: ${(e.cause ?: e).javaClass.simpleName}")
                    _state.update { it.copy(looking = null, lookupFailed = true) }
                    got = LATE to emptyList()
                    break
                }
            }
            val looked = got
            val block = looked.first
            if (block != null) timing.append(" lookup=").append(lookupMs.get()).append("ms waited=").append(ms() - l0).append("ms block=").append(block.length).append("ch")
            val asking = _state.value.chat.turns.lastOrNull()?.takeIf { it.user }
            if (block != null && asking != null && asking.id.isNotEmpty() && block.contains("[Information the app fetched")) {
                synchronized(fetchedFor) { fetchedFor[asking.id] = block }
                // The PC's answers to a follow-up get it too.
                Prompt.earlier(block)?.let { lastLookup[chatId] = it }
            }
            return looked
        }

        var lookupFrom = 0L
        if (refer) {
            // The model's words first (the Router's when it gives none), then the lookup.
            val need = decide()?.let { modelNeed(it) }
            lookupFrom = ms()
            lookup = startLookup(need)
        } else if (!sees && !stopReply.get() && lookup?.isDone == false) {
            // The chat up to the new message is read while the lookup fetches (the lookup goes after it).
            val w0 = ms()
            _state.update { if (it.chat.id != chatId || it.looking != null) it else it.copy(reading = true) }
            val warm = runCatching {
                runner.warm(Prompt.build(model.format, turns, brief = effort == Effort.Quick, online = online, earlier = earlier, head = true, chatRules = _state.value.chat.rules, ask = ask))
            }.getOrDefault("")
            timing.append(" warm=").append(ms() - w0).append("ms")
            runCatching { JSONObject(warm).optInt("new_prompt_tokens", -1) }.getOrNull()?.takeIf { it >= 0 }?.let { timing.append("/").append(it).append("tok") }
        }
        var (block, sources) = await(lookup!!, lookupFrom) ?: return
        if (stopReply.get()) { _state.update { it.copy(reading = false) }; return }
        // A lookup's answer is a plain fact: no long thought before it (Think first stays for the rest).
        val think = effort.thinks(model) && block == null
        fun limitFor(fetched: String?) =
            if (fetched != null) minOf(LOOKUP_REPLY_TOKENS, if (effort == Effort.Quick) Effort.QUICK_TOKENS else MAX_REPLY_TOKENS) else effort.maxTokens(model)
        // The model may ask for a lookup only before any fetched text is in this message's prompt, so a web page can never start one.
        val honour = ask != null && block == null && !refer
        _state.update { if (it.chat.id != chatId) it else it.copy(looking = null, reading = true, readingFound = block != null) }
        var firstPiece = -1L
        var firstWord = -1L
        var thoughtEnd = -1L
        var tooLong = false
        var stats: JSONObject? = null
        // PHE-25: context overflow warning
        val totalChars = turns.sumOf { it.text.length + it.files.sumOf { f -> f.text.length } }
        if (totalChars > 16000) {
            android.util.Log.w("Engine", "PHE-25: context ~${totalChars/4} tokens, may overflow")
            // truncate oldest file text if attached files are large
        }

        /**
         * One reply, streamed; with [honour], its start is held back until it is clear it is not a
         * SEARCH: line, and such a line is never shown: the model is stopped at its end and its
         * words are returned. Null when it was stopped before it began.
         */
        fun pass(fetched: String?, thinks: Boolean, limit: Int, honour: Boolean): Pair<String, String?>? {
            val out = StringBuilder()
            val g0 = ms()
            var forced = false
            var answerPieces = 0
            var words: String? = null
            val sink = PieceSink { bytes ->
                out.append(String(bytes, Charsets.UTF_8))
                val t = ms()
                if (firstPiece < 0) firstPiece = t
                val (thought, answer) = Thought.split(out.toString(), model.format, thinks)
                if (answer.isNotEmpty()) answerPieces++
                val a = if (honour) asked(answer, answerPieces) else Ask.Plain
                if (a is Ask.Line && a.done) words = a.words
                val shown = if (a is Ask.Plain) answer else ""
                if (thoughtEnd < 0 && answer.isNotEmpty() && thought.isNotEmpty()) thoughtEnd = t
                if (firstWord < 0 && shown.isNotBlank()) firstWord = t
                // A Whisper wiped meanwhile must not leave words in the chat that took its place.
                _state.update { if (it.chat.id != chatId) it else it.copy(partial = shown, reading = false, thinking = (thought.isNotEmpty() || thinks) && answer.isEmpty()) }
                when {
                    stopReply.get() -> false
                    // The model asked for a lookup: the rest of its reply isn't needed.
                    a is Ask.Line && a.done -> false
                    // Nothing may run forever: the whole reply has a time limit.
                    t > REPLY_MAX_MS -> { tooLong = true; false }
                    // Thinking that runs too long is closed, and the answer is written from it.
                    thinks && !forced && answer.isEmpty() && t - g0 > THINK_MAX_MS -> { forced = true; false }
                    else -> true
                }
            }
            // PHE-23: reset ready-ahead flag when new task starts
            while (true) {
                if (stopReply.get()) { /* PHE-08: abandon cleanly before read */ return null }
                val prompt = prompt(thinks, fetched)
                val pictures = pictures()
                out.setLength(0)
                answerPieces = 0
                forced = false
                var result = JSONObject(runner.reply(prompt, limit, sink, pictures))
                val error = result.optString("error")
                // Too long for the model's memory: the oldest exchange drops out and it tries again.
                if (error == "context" && turns.size > 2) { turns = turns.drop(2); continue }
                if (forced && !stopReply.get() && !tooLong) {
                    timing.append(" thought-cut=").append(ms()).append("ms")
                    out.append(if (model.format == Format.Gemma4) Thought.GEMMA_CLOSE else "\n</think>\n\n")
                    val more = JSONObject(runner.reply(prompt + out.toString(), minOf(LOOKUP_REPLY_TOKENS * 2, limit), sink, pictures))
                    if (more.optString("error").isEmpty()) result = more
                }
                val err = result.optString("error")
                if (err.isNotEmpty() && err != "stopped") {
                    _state.update {
                        it.copy(problem = when (err) {
                            "context" -> "That message is too long for this model."
                            "picture", "pictures" -> "A picture couldn't be read. Try another one."
                            else -> "Something went wrong while answering. Try again."
                        })
                    }
                }
                if (err.isEmpty()) {
                    stats = result
                    val tokens = result.optInt("gen_tokens")
                    val gms = result.optLong("gen_ms").coerceAtLeast(1)
                    _state.update { it.copy(speed = "%.0f tokens a second".format(tokens * 1000.0 / gms)) }
                }
                break
            }
            val answer = Thought.split(out.toString(), model.format, thinks).second
            if (honour && words == null) (asked(answer, 0) as? Ask.Line)?.let { words = it.words }
            return answer to words
        }

        /** Looks [need] up as the app's own lookups do (same time limit, same lines, same sources); false when the reply must stop here. */
        fun lookFor(need: io.github.odnwks.mimir.online.Router.Need?): Boolean {
            if (need == null) return true
            _state.update { if (it.chat.id != chatId) it else it.copy(partial = "") }
            val got = await(startLookup(need), ms()) ?: return false
            block = got.first
            sources = got.second
            if (stopReply.get()) { _state.update { it.copy(reading = false) }; return false }
            _state.update { if (it.chat.id != chatId) it else it.copy(looking = null, reading = true, readingFound = block != null) }
            return true
        }

        // Think first: a short decision with thinking off comes first, so a question that needs a lookup isn't thought about first.
        val decided = if (honour && think) decide() else null
        var answer: String
        var thinks = think
        if (decided != null) {
            if (!lookFor(modelNeed(decided))) return
            thinks = false
            answer = (pass(block, false, limitFor(block), false) ?: return).first
        } else {
            val (first, words) = pass(block, think, limitFor(block), honour) ?: return
            answer = first
            if (words != null && !stopReply.get()) {
                // The model asked: its line is never shown or kept, and it answers again from what was found (never asking twice).
                if (!lookFor(modelNeed(words))) return
                thinks = false
                answer = (pass(block, false, limitFor(block), false) ?: return).first
            } else if (searchable && block == null && !tooLong && !stopReply.get() && couldNotLook(answer)) {
                // The safety net: an answer that says it couldn't look it up is looked up once, and replaced.
                val need = owner?.let { io.github.odnwks.mimir.online.Router.lookUp(it.text) }
                if (need != null) {
                    timing.append(" safety-net")
                    if (!lookFor(need)) return
                    thinks = false
                    answer = (pass(block, false, limitFor(block), false) ?: return).first
                }
            }
        }
        if (!whisper) {
            stats?.let { r ->
                timing.append(" read=").append(r.optInt("new_prompt_tokens")).append("/").append(r.optInt("prompt_tokens")).append("tok in ").append(r.optLong("prompt_ms")).append("ms")
                    .append(" wrote=").append(r.optInt("gen_tokens")).append("tok in ").append(r.optLong("gen_ms")).append("ms threads=").append(r.optInt("threads"))
            }
            Log.i(TAG, "reply timing:$timing first-piece=${firstPiece}ms thought-end=${thoughtEnd}ms first-word=${firstWord}ms total=${ms()}ms think=$thinks${if (tooLong) " too-long" else ""}")
        }
        var reply = cited(answer.trim(), block, sources.size)
        if (tooLong && reply.isNotEmpty()) reply += "\n\n(Stopped here: it was taking too long on this phone.)"
        if (tooLong && reply.isEmpty()) _state.update { it.copy(problem = TOO_LONG) }
        // The chat was ended (a Whisper wiped) while it wrote: the words go nowhere.
        if (_state.value.chat.id != chatId) { _state.update { it.copy(partial = "", reading = false) }; return }
        val asked = _state.value.chat.turns.lastOrNull()?.takeIf { it.user }
        // Paired but away: a message naming a project gets this answer, and one plain line (2.3).
        if (asked != null && !_state.value.whisper && pc?.projectNamed(asked.text) != null) _state.update { it.copy(note = PROJECT_NEEDS_PC) }
        if (reply.isNotEmpty()) _state.update {
            it.copy(chat = it.chat.copy(turns = it.chat.turns + Turn(
                false, reply, sources = sources, id = asked?.id?.let { a -> "$a.a" }.orEmpty(), at = now(), ran = "phone",
                modelWords = "${model.name.substringBefore(',').trim()} · This phone",
                // Answered here after the PC was asked: this one replaces the PC's (2.2).
                version = if (asked != null && asked.id in askedPc) 2 else 1,
            ).let(::floored)), partial = "", reading = false)
        }
        else _state.update { it.copy(partial = "", reading = false) }
    }

    // ---------- One history (Stage 1, 2.5) ----------

    /** The link to the PC is up again: catch up on what changed while apart, then send what's waiting here. */
    fun linkUp() {
        // The PC answers again: "not reachable" is no longer true, and a message that waited goes now.
        _state.update { if (it.problem == PC_COULD_NOT_ANSWER) it.copy(problem = null) else it }
        caughtUp = false
        sending.clear()
        syncSoon()
        recoverThenAnswer()
    }

    /**
     * The link is back. A message whose answer was cut off (the link dropped, or the app was closed) is first
     * asked about on the PC: if the PC finished it, that answer is shown in place; if it is still writing, it
     * is awaited; only a PC that never had the message (or lost it) is sent it again. Then the waiting messages go.
     */
    private fun recoverThenAnswer(tries: Int = 0) {
        val s = _state.value
        val last = s.chat.turns.lastOrNull()
        val link = pc
        if (last != null && last.user && last.id.isNotEmpty() && !s.writing && !s.whisper && link?.canTalk() == true) {
            val chatId = s.chat.id
            val asked = link.talkTurn(chatId, last.id) { st -> syncer.execute {
                val now = _state.value
                // Nothing changed meanwhile: the chat still ends with that message.
                val same = now.chat.id == chatId && !now.writing && now.chat.turns.lastOrNull()?.id == last.id
                when {
                    !same -> {}
                    st is io.github.odnwks.mimir.link.LTalkState.Done -> {
                        _state.update { x -> x.copy(chat = x.chat.copy(turns = ChatSync.withAnswer(x.chat.turns, ChatSync.fromWire(st.turn).let { a -> a.copy(id = a.id.ifEmpty { "${last.id}.a" }, ran = a.ran ?: "pc") })), problem = null) }
                        persistQuietly()
                    }
                    // The PC tried and could not finish it: one card says so, with Try again (never a silent second try).
                    // The PC says its own plain cause and next step ("Your PC's model isn't running, ...") when it has one.
                    st is io.github.odnwks.mimir.link.LTalkState.Failed -> _state.update { x -> x.copy(problem = st.why.trim().ifEmpty { PC_COULD_NOT_ANSWER }) }
                    st is io.github.odnwks.mimir.link.LTalkState.Answering && tries < RECOVER_TRIES -> {
                        idler.schedule({ syncer.execute { recoverThenAnswer(tries + 1) } }, PC_ASK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                        return@execute
                    }
                    else -> answerIfWaiting()
                }
                drainQueue()
            } }
            if (asked) return
        }
        answerIfWaiting()
        drainQueue()
    }

    /** A chat changed on the PC (answered there, continued in the window, renamed, deleted). */
    fun chatChanged(r: io.github.odnwks.mimir.link.LChatRecord) = syncer.execute {
        take(r)
        lastSeq = maxOf(lastSeq, r.seq)
        persistQuietly()
    }

    /** Chat history's one-time line has been seen. */
    fun movedNoteSeen() = _state.update { it.copy(movedNote = false) }

    private fun syncSoon() {
        if (pc?.canSync() == true) syncer.execute { sync() }
    }

    private fun sync() {
        val link = pc ?: return
        if (!link.canSync()) return
        if (!caughtUp) {
            caughtUp = true
            catchUp(link, lastSeq)
            return
        }
        send()
    }

    /** Asks what changed after [from], page by page (each about 1.5 MB at most), then sends what waits here. */
    private fun catchUp(link: PcTalk, from: Long) {
        val asked = link.chatsSince(from) { page ->
            syncer.execute {
                when {
                    page == null -> caughtUp = false
                    else -> {
                        takePage(page)
                        if (page.more && page.seq > from) return@execute catchUp(link, page.seq)
                    }
                }
                send()
            }
        }
        if (!asked) caughtUp = false
    }

    private fun takePage(page: io.github.odnwks.mimir.link.LChatsPage) {
        page.chats.forEach(::take)
        lastSeq = maxOf(lastSeq, page.seq)
        if (!synced) {
            synced = true
            pc?.oldChatsDone()
            _state.update { it.copy(movedNote = true) }
        }
        persistQuietly()
    }

    /** One chat from the PC into this phone's copy; a deleted one goes from here too. */
    private fun take(r: io.github.odnwks.mimir.link.LChatRecord) {
        if (r.deleted != null || r.id in pendingDeletes || synchronized(undoable) { undoable.any { it.id == r.id } }) {
            if (r.deleted != null) pendingDeletes -= r.id
            _state.update { s ->
                val open = s.chat.id == r.id && !s.whisper
                if (open && s.writing) s
                else s.copy(chat = if (open) LocalChat(newId(), s.model, emptyList()) else s.chat, earlier = s.earlier.filter { it.id != r.id })
            }
            return
        }
        _state.update { s ->
            when {
                s.chat.id == r.id && !s.whisper -> s.copy(chat = ChatSync.merge(s.chat, r, s.model))
                s.earlier.any { it.id == r.id } -> s.copy(earlier = s.earlier.map { if (it.id == r.id) ChatSync.merge(it, r, s.model) else it })
                r.turns.isEmpty() -> s
                // PHD-16: new chat — merge it in and re-sort by time (only the sort is O(n); content is not rebuilt)
                else -> s.copy(earlier = (s.earlier + ChatSync.merge(null, r, s.model)).sortedByDescending { it.at })
            }
        }
    }

    /** What the PC hasn't got: deletions first, then each chat's new turns and names. */
    private fun send() {
        val link = pc ?: return
        if (!link.canSync()) return
        synchronized(pendingDeletes) { pendingDeletes.toList() }.filter { sending.add(it) }.forEach { id ->
            if (!link.chatPut(id, null, emptyList(), true) { done -> syncer.execute { sending -= id; if (done != null) { pendingDeletes -= id; persistQuietly() } } }) sending -= id
        }
        val s = _state.value
        // The open chat waits while an answer is being written (the PC gets the message with it).
        val chats = (if (s.whisper || s.writing || s.chat.turns.isEmpty()) emptyList() else listOf(s.chat)) + s.earlier
        for (c in chats) {
            val turns = ChatSync.unsent(c)
            val cuts = if (link.canCut()) ChatSync.unsentCuts(c) else emptyList()
            if ((turns.isEmpty() && cuts.isEmpty() && !c.pendingName) || !sending.add(c.id)) continue
            val ids = turns.associate { it.id to it.version }
            val sent = link.chatPut(c.id, c.name, turns.map { it.wire() } + cuts.map { it.wire() }, false) { done -> syncer.execute {
                sending -= c.id
                if (done == null) return@execute
                if (done.deleted) take(io.github.odnwks.mimir.link.LChatRecord(c.id, null, 0, done.seq, emptyList(), deleted = 1))
                else _state.update { st ->
                    fun mark(x: LocalChat) = if (x.id != c.id) x else x.copy(
                        pendingName = false,
                        turns = x.turns.map { t -> if (ids[t.id] == t.version && t.seq == 0L) t.copy(seq = done.seq) else t },
                        cuts = x.cuts.map { k -> if (!k.sent && cuts.any { it.id == k.id && it.version == k.version }) k.copy(sent = true) else k },
                    )
                    st.copy(chat = mark(st.chat), earlier = st.earlier.map(::mark))
                }
                persistQuietly()
            } }
            if (!sent) sending -= c.id
        }
    }

    /** Saves without starting another sync (the sync's own changes). */
    private fun persistQuietly() = persist(sync = false)

    // ---------- The PC answers (Stage 1, 2.1 to 2.3) ----------

    /** What the link hands the writer while the PC answers. */
    private sealed interface FromPc {
        data class Filed(val f: io.github.odnwks.mimir.link.Filed?, val err: String?) : FromPc
        data class Piece(val text: String, val thinking: Boolean) : FromPc
        data class Done(val turn: io.github.odnwks.mimir.link.LChatTurn?, val err: String?) : FromPc
        data class State(val st: io.github.odnwks.mimir.link.LTalkState?) : FromPc
        data object Lost : FromPc
        data object Back : FromPc
        data object Nudge : FromPc
    }

    /**
     * An online model answers (the owner pasted a key): the chat goes straight to that service,
     * the words stream in as they come, and a problem is shown in plain words. Lookups (weather)
     * are done here first, as for the other answerers.
     */
    private fun writeOnline() {
        val setup = io.github.odnwks.mimir.online.OnlineModels.answering() ?: return
        _state.update { it.copy(lookupFailed = false) }
        val (looked, sources) = lookUp(_state.value.chat.turns) ?: return
        if (stopReply.get()) return
        val s = _state.value
        val chatId = s.chat.id
        val asked = s.chat.turns.lastOrNull()?.takeIf { it.user } ?: return
        val fetched = if (looked != null) {
            if (looked.contains("[Information the app fetched") && asked.id.isNotEmpty()) synchronized(fetchedFor) { fetchedFor[asked.id] = looked }
            Prompt.earlier(looked)?.let { lastLookup[chatId] = it }
            looked
        } else null
        val earlier = synchronized(fetchedFor) { HashMap(fetchedFor) }
        val last = s.chat.turns.lastIndex
        val system = io.github.odnwks.mimir.online.OnlineModels.system() + Prompt.chatTail(s.chat.rules) + if (s.effort == Effort.Quick) Prompt.BRIEF else ""
        val messages = listOf("system" to system) + s.chat.turns.mapIndexed { i, t ->
            (if (t.user) "user" else "assistant") to (t.forModel() + if (t.user && i == last) fetched.orEmpty() else if (t.user) earlier[t.id].orEmpty() else "")
        }
        _state.update { if (it.chat.id != chatId) it else it.copy(reading = true, readingFound = fetched != null) }
        try {
            val a = io.github.odnwks.mimir.online.OnlineModels.chat(setup, messages, { stopReply.get() }) { text ->
                _state.update { if (it.chat.id != chatId) it else it.copy(partial = Thought.split(text).second, reading = false) }
            }
            val reply = cited(Thought.split(a.text).second.trim(), fetched, sources.size)
            if (_state.value.chat.id != chatId) { _state.update { it.copy(partial = "", reading = false) }; return }
            val shown = a.model.substringAfterLast('/').removeSuffix(":free")
            if (reply.isNotEmpty()) _state.update {
                it.copy(chat = it.chat.copy(turns = it.chat.turns + Turn(
                    false, reply, sources = sources, id = "${asked.id}.a", at = now(), ran = "phone",
                    modelWords = "${io.github.odnwks.mimir.online.OnlineModels.serviceOf(setup).name} · $shown",
                ).let(::floored)), partial = "", reading = false)
            } else _state.update { it.copy(partial = "", reading = false) }
        } catch (e: io.github.odnwks.mimir.online.Sorry) {
            _state.update { it.copy(problem = e.words, partial = "", reading = false) }
        }
    }

    /**
     * The PC said how much it read of each file sent with message [turnId]: it is shown under the file,
     * and the file's bytes are let go (the PC has what it read; nothing sits unused here).
     */
    private fun tellFilesRead(turnId: String, notes: List<io.github.odnwks.mimir.link.LFileNote>) {
        _state.update { st ->
            st.copy(chat = st.chat.copy(turns = st.chat.turns.map { t ->
                if (t.id != turnId) t
                else t.copy(files = t.files.map { f -> notes.firstOrNull { it.name == f.name }?.let { f.copy(bytes = null, note = it.note) } ?: f })
            }))
        }
        persist()
    }

    /** "Answer on this phone" while waiting for the PC: the phone answers, and its answer replaces the PC's everywhere. */
    fun answerOnPhone() {
        if (_state.value.onPc) answerHere.set(true)
    }

    /** From the Whisper-while-online message: the online model stops answering; this phone or the PC does. */
    fun stopOnlineFor(pc: Boolean) {
        io.github.odnwks.mimir.online.OnlineModels.answerWith(null)
        _state.update { it.copy(usePc = pc, problem = null, whisperChoice = false) }
        persist()
    }

    /** The composer's place chip: "Your PC when connected" or "This phone only". */
    fun setUsePc(on: Boolean) {
        _state.update { it.copy(usePc = on) }
        persist()
    }

    /**
     * Sends the last message to the PC and fills the answer from its words, as from the phone's
     * model. Returns false when this phone must answer instead (the PC couldn't take it, said to,
     * failed, or the owner tapped Answer on this phone).
     */
    private fun writePc(): Boolean {
        val link = pc ?: return false
        _state.update { it.copy(lookupFailed = false) }
        val (looked, sources) = lookUp(_state.value.chat.turns) ?: return true
        if (stopReply.get()) return true
        val s = _state.value
        val chatId = s.chat.id
        val asked = s.chat.turns.lastOrNull()?.takeIf { it.user } ?: return true
        val fetched = if (looked != null) {
            Prompt.earlier(looked)?.let { lastLookup[chatId] = it }
            looked
        } else if (io.github.odnwks.mimir.online.Online.allowed(mode())) lastLookup[chatId] else null
        val answerId = "${asked.id}.a"
        val q = java.util.concurrent.LinkedBlockingQueue<FromPc>()
        val listener = object : TalkListener {
            override fun filed(f: io.github.odnwks.mimir.link.Filed?, err: String?) { q.put(FromPc.Filed(f, err)) }
            override fun piece(text: String, thinking: Boolean) { q.put(FromPc.Piece(text, thinking)) }
            override fun done(turn: io.github.odnwks.mimir.link.LChatTurn?, err: String?) { q.put(FromPc.Done(turn, err)) }
            override fun lost() { q.put(FromPc.Lost) }
            override fun back() { q.put(FromPc.Back) }
            override fun nudge() { q.put(FromPc.Nudge) }
        }
        // A PC whose model sees pictures gets them with this message (and only this one: it keeps the rest).
        val pcSees = link.canSeePictures()
        // Files go as they are for the PC to read, with this message only.
        val filesGo = link.canSendFiles()
        val request = TalkRequest(
            chatId, asked.id, s.chat.turns.map { it.wire(pcSees, withPictures = pcSees && it.id == asked.id, withFiles = filesGo && it.id == asked.id) }, fetched,
            sources.map { io.github.odnwks.mimir.link.LChatSource(it.site, it.url, it.words, it.results, it.via) },
            s.effort.wire, Prompt.rulesForPc(io.github.odnwks.mimir.data.GroundRules.current, s.chat.rules), if (chatOnly.get()) null else inTask(),
            chatOnly = chatOnly.getAndSet(false),
        )
        _state.update { it.copy(onPc = true, partial = "", thinking = false, pcWaiting = false, pcLine = null) }
        if (!link.talk(request, listener)) return false
        // What this chat has sent to the PC: the words of the conversation, counted each time it is asked.
        val sentNow = s.chat.turns.sumOf { it.text.toByteArray().size.toLong() } + (fetched?.toByteArray()?.size ?: 0) + request.rules.orEmpty().toByteArray().size
        _state.update { st -> if (st.chat.id != chatId) st else st.copy(chat = st.chat.copy(sentToPc = st.chat.sentToPc + sentNow)) }
        askedPc += asked.id
        // The PC answers: this phone's model isn't needed in memory meanwhile (1.6).
        unloadIfIdle(force = true)
        val out = StringBuilder()
        var stopped = false
        var lastHeard = wall()
        var askedAt = 0L
        fun finish(turn: Turn) {
            // Another model answered because the usual one can't see pictures: what the PC sees may have changed, so ask again.
            if (turn.switchNote != null) link.refreshSight()
            // The PC has the message too (it came with the Talk): it isn't sent again by the sync.
            _state.update { st ->
                val turns = st.chat.turns.map { if (it.id == asked.id && it.seq == 0L && turn.seq > 0) it.copy(seq = turn.seq) else it }
                st.copy(chat = st.chat.copy(turns = ChatSync.withAnswer(turns, floored(turn))), partial = "")
            }
        }
        fun fromPc(t: io.github.odnwks.mimir.link.LChatTurn) = Turn(
            false, Thought.split(t.text).second.trim(),
            sources = t.sources.map { io.github.odnwks.mimir.online.Source(it.site, it.url, it.words, it.results, it.via) },
            id = t.id.ifEmpty { answerId }, at = t.at.takeIf { it > 0 } ?: now(), ran = t.ran ?: "pc", modelWords = t.modelWords, version = t.version, seq = t.seq, switchNote = t.switchNote,
        )
        try {
            while (true) {
                if (answerHere.getAndSet(false)) return false
                if (stopReply.get() && !stopped) {
                    stopped = true
                    link.stopTalk(chatId, asked.id)
                    val kept = out.toString().trim()
                    if (kept.isNotEmpty()) finish(Turn(false, kept, sources = sources, id = answerId, at = now(), ran = "pc"))
                    return true
                }
                // Once the link is back, the PC is asked how its answer stands every 2 s until it's done.
                if (askedAt > 0 && wall() - askedAt > PC_ASK_MS) {
                    askedAt = wall()
                    link.talkTurn(chatId, asked.id) { st -> q.put(FromPc.State(st)) }
                }
                val m = q.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (m == null) {
                    // Nothing at all for a long while on a link that looks up: treated as gone.
                    if (!_state.value.pcWaiting && wall() - lastHeard > PC_SILENT_MS) {
                        _state.update { it.copy(pcWaiting = true) }
                        // Its words may have been lost on the way: ask how it stands.
                        askedAt = 1
                    }
                    continue
                }
                lastHeard = wall()
                when (m) {
                    is FromPc.Filed -> when (val f = m.f) {
                        null -> return false
                        is io.github.odnwks.mimir.link.Filed.Talk -> {
                            _state.update { it.copy(pcLine = f.waiting) }
                            if (f.filesRead.isNotEmpty()) tellFilesRead(asked.id, f.filesRead)
                        }
                        is io.github.odnwks.mimir.link.Filed.OnPhone -> {
                            _state.update { it.copy(note = plainPcWords(f.why)) }
                            return false
                        }
                        is io.github.odnwks.mimir.link.Filed.Status -> {
                            finish(Turn(false, f.reply.joinToString("\n"), id = answerId, at = now(), ran = "pc", modelWords = link.pcName()))
                            return true
                        }
                        is io.github.odnwks.mimir.link.Filed.NewTask -> {
                            finish(Turn(false, "Started in ${projectName(f.project)}", id = answerId, at = now(), ran = "pc", task = f.task, project = f.project))
                            return true
                        }
                        is io.github.odnwks.mimir.link.Filed.SameTask -> {
                            finish(Turn(false, "Added to the task in ${projectName(f.project)}", id = answerId, at = now(), ran = "pc", task = f.task, project = f.project))
                            return true
                        }
                        is io.github.odnwks.mimir.link.Filed.Idea -> {
                            finish(Turn(false, "Saved as an idea for ${projectName(f.project)}.", id = answerId, at = now(), ran = "pc"))
                            return true
                        }
                        is io.github.odnwks.mimir.link.Filed.Inbox -> return false
                    }
                    is FromPc.Piece -> {
                        // Words flow again: no need to ask.
                        if (!_state.value.pcWaiting) askedAt = 0
                        if (m.thinking) _state.update { it.copy(thinking = out.isEmpty(), pcLine = null) }
                        else {
                            out.append(m.text)
                            val shown = Thought.split(out.toString()).second
                            _state.update { it.copy(partial = shown, thinking = shown.isEmpty(), pcLine = null, pcWaiting = false) }
                        }
                    }
                    is FromPc.Done -> {
                        val t = m.turn ?: run {
                            // Busy for a minute, or its model stopped: this phone answers, and says why.
                            _state.update { it.copy(note = plainPcWords(m.err)) }
                            // The PC's own plain cause and next step ("Your PC's ..."), kept for the card if nothing else can answer.
                            pcWhy = m.err?.trim()?.takeIf { it.startsWith("Your PC") }
                            return false
                        }
                        finish(fromPc(t))
                        return true
                    }
                    FromPc.Lost -> { _state.update { it.copy(pcWaiting = true) }; askedAt = 0 }
                    FromPc.Back -> { askedAt = 1; _state.update { it.copy(pcWaiting = true) } }
                    // A network change: the PC is asked at once how its answer stands, without a "waiting" line.
                    FromPc.Nudge -> askedAt = 1
                    is FromPc.State -> when (val st = m.st) {
                        is io.github.odnwks.mimir.link.LTalkState.Done -> { finish(fromPc(st.turn)); return true }
                        is io.github.odnwks.mimir.link.LTalkState.Answering -> {
                            out.setLength(0); out.append(st.text)
                            _state.update { it.copy(partial = Thought.split(st.text).second) }
                        }
                        is io.github.odnwks.mimir.link.LTalkState.Failed, io.github.odnwks.mimir.link.LTalkState.Unknown -> return false
                        null -> {}
                    }
                }
            }
        } finally {
            link.letGo(answerId)
        }
    }

    /**
     * The project task the open chat is in (Stage 1, 2.3): its last project card's, until that task
     * is done or the owner taps ✕. A follow-up goes there unless it names another project.
     */
    fun inTask(): String? {
        val s = _state.value
        if (s.whisper) return null
        val card = s.chat.projectCard ?: return null
        return card.task?.takeIf { pc?.taskDone(it) != true }
    }

    /** The next message to the PC is answered as a chat ("Just chat"). */
    private val chatOnly = AtomicBoolean(false)

    /**
     * "Filed under X · change" › Just chat: the task started by mistake is archived, its card goes,
     * and the message is answered as a chat (by the PC when it can be told so, else by this phone).
     */
    fun justChat() {
        val s = _state.value
        if (s.writing) return
        val card = s.chat.turns.lastOrNull()?.takeIf { !it.user && it.task != null } ?: return
        card.task?.let { pc?.dropTask(it) }
        _state.update { it.copy(chat = it.chat.copy(turns = it.chat.turns.dropLast(1), leftTask = card.task)) }
        if (pc?.canChatOnly() == true) chatOnly.set(true) else _state.update { it.copy(usePcOnce = false) }
        retry()
    }

    /** ✕ on "In Garden planner-Mod-Manager": the next message is a general chat again. */
    fun leaveTask() {
        val task = _state.value.chat.projectCard?.task ?: return
        _state.update { it.copy(chat = it.chat.copy(leftTask = task)) }
        persist()
    }

    /** Real time, for waiting on the PC (the clock of record, [now], can be a stand-in in checks). */
    private fun wall() = System.currentTimeMillis()

    /** A project's folder name, as the chat says it ("Garden planner-Mod-Manager"). */
    private fun projectName(path: String) = path.trimEnd('/').substringAfterLast('/')

    // ---------- Test on this device ----------

    /**
     * Tests a downloaded model on this phone (load time, speed, memory, temperature, a few
     * everyday questions, and a refusal check for models with no safety filter). Runs where
     * replies run, so it never meets one; the model is closed afterwards and nothing but the
     * numbers is kept.
     */
    fun test(id: String) {
        val s0 = _state.value
        if (s0.testing != null || s0.writing || id !in s0.have || !Catalog.has(id) || OwnModels.isOwn(id)) return
        val model = Catalog.byId(id)
        stopTest.set(false)
        _state.update { it.copy(testing = TestProgress(id, 0, 1, "Starting")) }
        busy(true)
        writer.execute {
            try {
                if (loadedModel != null) { closeModel(); loadedModel = null }
                val result = ModelTester(runner, stopTest, memoryMb, temperature, now).run(model, fileFor(model).path) { p ->
                    _state.update { it.copy(testing = p) }
                }
                if (result != null) {
                    _state.update { it.copy(tests = it.tests + (id to result)) }
                    saveTests()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "test of $id failed", e)
                _state.update { it.copy(problem = "The test stopped. Try again.") }
            } finally {
                closeModel()
                loadedModel = null
                _state.update { it.copy(testing = null) }
                busy(false)
            }
        }
    }

    fun cancelTest() {
        stopTest.set(true)
        runner.stop()
    }

    private fun loadTests(): Map<String, TestResult> = runCatching {
        val o = JSONObject(testsFile.readText())
        o.keys().asSequence().mapNotNull { k -> TestResult.fromJson(o.getJSONObject(k))?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    /** Keeps only results for models still offered. */
    private fun saveTests() {
        val o = JSONObject()
        _state.value.tests.filterKeys { Catalog.has(it) }.forEach { (k, v) -> o.put(k, v.toJson()) }
        runCatching { testsFile.parentFile?.mkdirs(); testsFile.writeText(o.toString()) }
    }

    private fun persist(sync: Boolean = true) {
        synchronized(persistLock) {
            val s = _state.value
            // A chat deleted moments ago stays in the file until its Undo has passed.
            val all = (if ((s.chat.turns.isEmpty() && s.chat.queued.isEmpty()) || s.whisper) emptyList() else listOf(s.chat)) + s.earlier + synchronized(undoable) { undoable.toList() }.filter { d -> d.id != s.chat.id && s.earlier.none { it.id == d.id } }
            runCatching { store.save(Saved(s.model, all, s.think, HashMap(lastUsed), HashMap(files), s.effort.name, s.usePc, lastSeq, synchronized(pendingDeletes) { pendingDeletes.toList() }, synced)) }
                .onFailure {
                    Log.w(TAG, "couldn't save chats", it)
                    // The next save writes everything again, so this is retried by itself.
                    _state.update { st -> st.copy(problem = "Your chats couldn't be saved just now. Mímir will try again.") }
                }
            if (s.chat.turns.isNotEmpty() && !s.whisper) chatRepo?.saveChat(s.chat)
        }
        if (sync) syncSoon()
    }

    // ---------- Your own models (a folder the owner chose) ----------


    /** The runner lets the model go, and the folder's files with it. */
    private fun closeModel() {
        runner.close()
        runCatching { ownOpen?.close() }
        runCatching { ownVisionOpen?.close() }
        ownOpen = null
        ownVisionOpen = null
    }

    /** What was chosen and where, from the last run (the model's own facts are kept, so it works before the folder is read). */
    private fun loadOwn() {
        Catalog.own = null
        val o = runCatching { JSONObject(ownSaved.readText()) }.getOrNull() ?: return
        ownFolderUri = o.optString("folder").ifEmpty { null }
        val f = ownFolderUri?.let { runCatching { ownFolders(it) }.getOrNull() }
        ownFolder = f
        val m = o.optJSONObject("model")
        if (m != null) {
            val vision = m.optString("vision").takeIf { it.isNotEmpty() }
            val e = OwnEntry(
                m.getString("doc"), m.getString("file"), m.getLong("bytes"),
                if (m.optString("format") == "gemma4") Format.Gemma4 else Format.ChatMl,
                m.optBoolean("thinking"), m.optBoolean("uncensored"),
                vision?.let { OwnFile(it, m.optString("visionFile"), m.optLong("visionBytes")) },
            )
            chosenOwn = e
            Catalog.own = e.model()
        }
        ownFolderName = o.optString("folderName").ifEmpty { null }
        _state.update { it.copy(ownFolder = ownFolderName, ownLost = ownFolderUri != null && f == null) }
    }

    private fun saveOwn() {
        val o = JSONObject()
        ownFolderUri?.let { o.put("folder", it) }
        ownFolderName?.let { o.put("folderName", it) }
        chosenOwn?.takeIf { Catalog.own?.id == it.id }?.let { e ->
            o.put("model", JSONObject().put("doc", e.docId).put("file", e.file).put("bytes", e.bytes)
                .put("format", if (e.format == Format.Gemma4) "gemma4" else "chatml").put("thinking", e.thinking).put("uncensored", e.uncensored)
                .apply { e.vision?.let { v -> put("vision", v.docId).put("visionFile", v.name).put("visionBytes", v.bytes) } })
        }
        runCatching { ownSaved.parentFile?.mkdirs(); ownSaved.writeText(o.toString()) }
    }

    /** The owner chose a folder (the address Android's picker gave): it is looked at now. */
    fun setOwnFolder(uri: String) {
        val f = runCatching { ownFolders(uri) }.getOrNull()
        if (f == null) { _state.update { it.copy(ownLost = true) }; return }
        ownFolder = f
        ownFolderUri = uri
        ownFolderName = f.name
        ownRead.clear()
        _state.update { it.copy(ownFolder = f.name, ownLost = false, own = emptyList()) }
        saveOwn()
        lookAgain()
    }

    /** Looks in the folder again: new files appear, removed ones go, and an own model in use that is gone is noticed. */
    fun lookAgain() {
        val f = ownFolder ?: return
        _state.update { it.copy(ownLooking = true) }
        scanner.execute {
            try {
                val found = OwnModels.scan(f, ownRead)
                if (found == null) {
                    _state.update { it.copy(ownLost = true, own = emptyList()) }
                    Catalog.own?.let { ownGone(it, force = true) }
                    return@execute
                }
                ownFolderName = f.name
                _state.update { it.copy(own = found, ownLost = false, ownFolder = f.name) }
                val inUse = Catalog.own
                if (inUse != null && found.none { it.id == inUse.id }) ownGone(inUse, force = true)
                else if (inUse != null) refreshStatus()
            } finally {
                _state.update { it.copy(ownLooking = false) }
            }
        }
    }

    /** "Use this one" on a file in the folder: read straight from there, nothing copied. */
    fun useOwn(docId: String) {
        val s = _state.value
        val e = s.own.firstOrNull { it.docId == docId } ?: return
        if (e.format == null || s.writing || s.testing != null) return
        val model = e.model()
        chosenOwn = e
        Catalog.own = model
        if (loadedModel != null && loadedModel != model.id) writer.execute { closeModel(); loadedModel = null; seeing = null }
        _state.update { it.copy(model = model.id, problem = null) }
        refreshStatus()
        saveOwn()
        persist()
    }

    /** Back to the built-in models: the own choice is forgotten (the file stays in its folder, untouched). */
    private fun forgetOwnChoice() {
        Catalog.own = null
        chosenOwn = null
        writer.execute { ownCopyFile.delete(); ownCopyFile.parentFile?.delete() }
        saveOwn()
    }

    /**
     * The own model [m] couldn't be opened from its folder: if the file is no longer there (or the
     * folder is gone), Mímir goes back to "No model yet" and says why. False if the file is still there.
     */
    private fun ownGone(m: LocalModel, force: Boolean = false): Boolean {
        if (!m.own) return false
        val files = runCatching { ownFolder?.files() }.getOrNull()
        val here = !force && files?.any { it.docId == OwnModels.docIdOf(m.id) } == true
        if (here) return false
        if (loadedModel == m.id) { closeModel(); loadedModel = null; seeing = null }
        Catalog.own = null
        chosenOwn = null
        ownCopyFile.delete()
        val note = if (files == null) "Your own model ${m.name} can't be read any more: its folder is gone or Android took back the permission. Choose the folder again in Settings › Who answers."
        else "Your own model ${m.name} is no longer in its folder, so it can't be used. Pick another model in Settings › Who answers."
        _state.update { it.copy(model = Catalog.default.id, problem = null, ownLost = files == null, note = note) }
        saveOwn()
        refreshStatus(note)
        persist()
        return true
    }

    /** Opens the own model for the runner straight from its folder; if that can't be loaded, a copy is made and loaded instead. */
    private fun openOwn(m: LocalModel): Boolean {
        val folder = ownFolder ?: return false
        val doc = OwnModels.docIdOf(m.id)
        val h = runCatching { folder.open(doc) }.getOrNull() ?: return false
        ownOpen = h
        if (runner.open(h.path)) return true
        // Loading straight from the folder didn't work on this phone: copy it into the app once (space is used twice until the original is deleted).
        h.close(); ownOpen = null
        if (_state.value.ownCopy != null) return false
        val total = m.bytes.coerceAtLeast(1)
        _state.update { it.copy(ownCopy = "Copying your model into the app · 0%", loading = true) }
        try {
            ownCopyFile.parentFile?.mkdirs()
            val ok = folder.copy(doc, ownCopyFile) { done ->
                _state.update { it.copy(ownCopy = "Copying your model into the app · ${(done * 100 / total).coerceAtMost(100)}%. This takes the space twice until you delete the original from the folder.") }
            }
            return ok && runner.open(ownCopyFile.path)
        } finally {
            _state.update { it.copy(ownCopy = null) }
        }
    }

    /** Loads the own model's photo part, straight from its folder. */
    private fun seeOwn(m: LocalModel): Boolean {
        val folder = ownFolder ?: return false
        val v = m.vision ?: return false
        val h = runCatching { folder.open(OwnModels.docIdOf(v.url)) }.getOrNull() ?: return false
        ownVisionOpen = h
        return runner.see(h.path)
    }

    /** Only the model in use stays: once [keep] has loaded, the others' files go. */
    private fun dropOthers(keep: String) {
        var freed = 0L
        val gone = ArrayList<String>()
        // Paused downloads (".part") are not touched here: they are kept 7 days (PHE-17).
        val kinds = listOf(".gguf", ".vision")
        models.listFiles()?.filter { f -> kinds.any { f.name.endsWith(it) } && !f.name.startsWith("$keep.") }?.forEach { f ->
            val id = f.name.removeSuffix(".part").removeSuffix(".gguf").removeSuffix(".vision")
            if (id == downloading) return@forEach
            freed += f.length()
            if (f.name.endsWith(".gguf")) gone += Catalog.models.firstOrNull { it.id == id }?.name ?: "an old model"
            f.delete()
            files.remove(id)
            files.remove("$id.vision")
            lastUsed.remove(id)
        }
        if (freed > 0) {
            val note = if (gone.isEmpty()) null else
                "Removed ${gone.joinToString(" and ")} to free %.1f GB, since you switched. It downloads again if you pick it.".format(freed / 1e9)
            refreshStatus(note)
        }
    }

    companion object {
        const val TAG = "MimirStandalone"

        /** How a rough location is named: it's never turned into a place's name. */
        const val ROUGH_PLACE = "near you"
        const val MAX_REPLY_TOKENS = 1024
        /** After this long waiting on Tor, "Still waiting…" with Cancel (checks shorten it). */
        @Volatile var TOR_SLOW_MS = 20_000L
        /** A page read for an answer: about a quarter of an 8K context (about 1,500 words). */
        const val PAGE_CHARS = io.github.odnwks.mimir.online.PageText.PAGE_CHARS
        /** Thinking needs room before the answer starts. */
        const val MAX_THINKING_TOKENS = 3072
        /**
         * Thinking longer than this is closed and the answer written from it: on an S25 the
         * default model thought about a weather question for over 2 minutes (1,500 tokens)
         * without starting its answer (checks shorten it).
         */
        @Volatile var THINK_MAX_MS = 45_000L
        /** No reply runs longer than this, in front or behind (checks shorten it). */
        @Volatile var REPLY_MAX_MS = 150_000L
        /** The longest a lookup is waited for before Mímir answers without it (its own budget is 6 s). */
        @Volatile var LOOKUP_WAIT_MS = io.github.odnwks.mimir.online.Net.BUDGET_MS + 1_500L
        /** A lookup's answer is short: a few lines about what was found. */
        const val LOOKUP_REPLY_TOKENS = 512
        /** The most a model's own ask for a lookup ("SEARCH: <words>", one line) may run to, and the short decision call before Think first. */
        const val DECIDE_TOKENS = 24
        /** A model's ask for a lookup is one line of at most this many characters and words. */
        const val ASK_CHARS = 120
        const val ASK_WORDS = 12
        private const val SEARCH_MARK = "SEARCH:"

        /** What the start of an answer says about a SEARCH: line (P21 stage 2). */
        internal sealed interface Ask {
            /** Too short to tell yet: held back. */
            object Unsure : Ask
            /** An answer: shown as it comes. */
            object Plain : Ask
            /** A SEARCH: line: its [words] so far, and [done] once the line ended or reached its cap. */
            data class Line(val words: String, val done: Boolean) : Ask
        }

        /** [answer] so far (after [pieces] pieces of it): a SEARCH: line (any case, after spaces), an answer, or not clear yet. */
        internal fun asked(answer: String, pieces: Int): Ask {
            val t = answer.trimStart()
            if (t.length < SEARCH_MARK.length) return if (SEARCH_MARK.startsWith(t, ignoreCase = true)) Ask.Unsure else Ask.Plain
            if (!t.startsWith(SEARCH_MARK, ignoreCase = true)) return Ask.Plain
            val rest = t.substring(SEARCH_MARK.length)
            val line = rest.substringBefore('\n').trim()
            val count = line.split(Regex("\\s+")).count { it.isNotEmpty() }
            val done = '\n' in rest || pieces >= DECIDE_TOKENS || line.length > ASK_CHARS || count > ASK_WORDS
            val words = line.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(ASK_WORDS).joinToString(" ").take(ASK_CHARS).trim()
            return Ask.Line(words, done)
        }

        private val POINTS_BACK = Regex("\\b(?:that|it|this|those|same|before|earlier|again)\\b|\\bthe \\p{L}+er one\\b", RegexOption.IGNORE_CASE)
        private val FILLER = setOf(
            "a", "an", "the", "of", "in", "on", "at", "for", "to", "and", "or", "is", "are", "was", "were", "be", "what", "who",
            "how", "which", "about", "with", "by", "from", "me", "one", "ones", "some", "any",
        )

        /**
         * The reference rule: a search's words that point back to the chat ("that", "the cheaper
         * one") or hold fewer than 3 words of substance. The weather has its own follow-ups.
         */
        internal fun pointsBack(need: io.github.odnwks.mimir.online.Router.Need): Boolean {
            val words = when (need) {
                is io.github.odnwks.mimir.online.Router.Need.Search -> need.words
                is io.github.odnwks.mimir.online.Router.Need.Encyclopedia -> need.words
                is io.github.odnwks.mimir.online.Router.Need.Weather -> return false
            }
            return POINTS_BACK.containsMatchIn(words) ||
                words.split(Regex("\\s+")).map { it.lowercase().trim { c -> !c.isLetterOrDigit() } }.count { it.isNotEmpty() && it !in FILLER } < 3
        }

        private val COULD_NOT_LOOK = Regex(
            "\\b(?:couldn't|could not|can't|cannot|can not) look\\b(?! at)|\\bno web search results\\b|\\b(?:haven't|have not) looked up\\b|" + // lint:log-only
                "\\b(?:don't|do not) have (?:any )?current\\b|\\bnot able to browse\\b|\\bno looked-up\\b",
            RegexOption.IGNORE_CASE,
        )

        /** The safety net: an answer that says it couldn't look something up or has no current information. */
        internal fun couldNotLook(answer: String): Boolean = COULD_NOT_LOOK.containsMatchIn(answer.replace('\u2019', '\''))
        /** What Mímir is told when a lookup failed: the reason is the connection, never the place or the words. A button under the answer does the retrying. */
        fun lookupFailedNote(what: String, why: String): String =
            "\n\n[The app tried to $what but couldn't: $why. Say so plainly in one sentence, with that reason. " +
                "The connection is the problem, not the place or the words, so don't blame them. " +
                "Don't tell them what to do next (a button under your answer does that), and don't guess.]"
        /** What Mímir is told when a lookup didn't come back in time. */
        const val LATE = "\n\n[The app tried to look this up, but it didn't come back in time. Say so in one short sentence, " +
            "then answer from what you know, saying it may be out of date. Don't tell them what to do next (a button under your answer does that).]"
        /** A reply stopped at its time limit before it wrote anything. */
        const val TOO_LONG = "The answer was taking too long on this phone, so it was stopped. Try again, or choose Quick for a shorter answer."
        /** A message naming a project, answered on the phone while the PC can't be reached. */
        const val PROJECT_NEEDS_PC = "Project work needs your PC. Send it again when it's connected."
        /** Paired, the PC can't answer and this phone has no model to answer with. */
        /** The longest rules text a chat keeps. */
        const val MAX_CHAT_RULES = 2000

        const val PC_COULD_NOT_ANSWER = "Your PC didn't finish that answer."

        /** A reason the PC gave, in the app's words ("Claude Code" is the PC's tool: the owner knows it as Claude). */
        fun plainPcWords(why: String?): String? = why?.replace(Regex("Claude\\s+Code"), "Claude")?.trim()?.ifBlank { null }
        /** While waiting for the PC after the link came back, how often it's asked how its answer stands. */
        const val PC_ASK_MS = 2_000L
        /** How many times a still-writing PC is asked after the link came back (about 3 minutes). */
        const val RECOVER_TRIES = 90
        /** No word from the PC for this long, on a link that looks up: "Waiting for your PC…". */
        @Volatile var PC_SILENT_MS = 30_000L
        /** This phone's model is let go after this long without a reply. */
        const val UNLOAD_IDLE_MS = 3 * 60_000L
        /** A model not used for this long is removed. */
        const val UNUSED_DAYS = 14L
        /** A part-download left this long is removed. */
        const val PART_DAYS = 7L
        private const val DAY = 24L * 60 * 60 * 1000

        @Volatile private var instance: Engine? = null

        /** The app's engine if it has been made (nothing is loaded just to ask). */
        fun made(): Engine? = instance

        /** The app's one engine. */
        fun get(context: Context): Engine = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val cores = Runtime.getRuntime().availableProcessors()
                io.github.odnwks.mimir.online.OnlineModels.ensure(app.filesDir)
                Engine(
                    dir = app.filesDir,
                    store = Store(File(app.filesDir, "standalone.bin")),
                    runner = LlmRunner(threads = threadsFor(cores)),
                    supported = Llm.usable(),
                    busy = { on -> StandaloneService.busy(app, on) },
                    temperature = { batteryTemperature(app) },
                    ownFolders = { SafFolder.of(app, it) },
                ).also { e ->
                    e.pc = io.github.odnwks.mimir.data.Stations.current() as? PcTalk
                    instance = e
                    // A fresh start opens on the home screen; the chat that was open is one tap away.
                    e.startOnHome()
                }
            }
        }

        /** The battery's temperature in °C, the phone's own measure of how warm it is (null: unknown). */
        fun batteryTemperature(context: Context): Float? = runCatching {
            val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.div(10f)
        }.getOrNull()

        /** Threads for llama.cpp: the fast cores, leaving the rest for the screen. */
        fun threadsFor(cores: Int): Int = (cores - 2).coerceIn(1, 6)
    }
}
