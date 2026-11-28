package io.github.odnwks.mimir.link

import org.json.JSONArray
import org.json.JSONObject

/**
 * The PC's calls and events as JSON, in crates/proto's serde form (externally tagged enums:
 * `{"Send": {...}}`, a unit variant as a plain string). A unit test decodes every example the
 * Rust side writes, so the two sides can't drift unnoticed.
 */
object Wire {
    /** The place of a task message in the PC's list: ids are "<task>-<place>" (null for a live or waiting one). */
    fun entryIndex(task: String, messageId: String): Int? = messageId.removePrefix("$task-").takeIf { it != messageId }?.toIntOrNull()

    /** crates/proto's `PROTO`. */
    const val PROTO = 2

    /**
     * What this phone knows beyond [PROTO], told to the PC in [hello] (crates/proto's `FEATURES`).
     * The PC sends the new events (talk pieces, chat changes, the truth) only to a phone that lists
     * them; the phone sends the new calls only to a PC whose Welcome lists them.
     */
    val FEATURES = listOf("search", "lookup", TALK, CHAT_SYNC, TRUTH, MODELS_CHANGED, FINISHED_EVENTS, PC_SESSIONS, TASK_FILES, TASK_REPLY_OPTIONS, FRESH_START)
    const val TALK = "talk"
    const val CHAT_SYNC = "chat_sync"
    /** The PC understands a cut-away turn ([LChatTurn.gone]); an older one would keep it as an empty message. */
    const val CHAT_CUT = "chat_cut"
    const val TRUTH = "truth"
    /** The PC tells this phone when what its person may choose changed (online allowed or taken away): it asks Models again. */
    const val MODELS_CHANGED = "models_changed"
    const val FINISHED_EVENTS = "finished_events"
    /** The PC lists every Claude Code session on it ([pcSessions], [openPcSession], [Ev.PcSessions]); the owner's phone only. */
    const val PC_SESSIONS = "pc_sessions"
    /** The PC takes "answer this as a chat, not a project" (Talk.chat_only; Stage 1, 2.3). */
    const val CHAT_ONLY = "chat_only"
    /** The PC never does a chat message twice: one sent again (Talk.again) gets its first reply back (P40). */
    const val TALK_ONCE = "talk_once"
    /** The PC has Settings › Who answers' two lines: what answers chats, and what does project work (SetEngines). */
    const val ENGINES = "engines"
    /** The PC lets chats pick the Claude model ([setChatModel]) and says which model answered. */
    const val CHAT_MODEL = "chat_model"
    /** A chat message can carry pictures for the PC's model ([LChatTurn.pictures]); the Models reply says whether it can look at them. */
    const val CHAT_PICTURES = "chat_pictures"
    /** A chat message can carry files for the PC to read ([LChatTurn.files]); the reply says how much of each was read ([Filed.Talk.filesRead]). */
    const val CHAT_FILES = "chat_files"
    /** A project task's message can carry pictures and files ([send], [newTask]). */
    const val TASK_FILES = "task_files"
    /** A phone may set a task's model and effort ([setModel], [setEffort]) just before its next message. */
    const val TASK_REPLY_OPTIONS = "task_reply_options"
    /** The same chat carries on in a new Claude session from the hand-off ([freshStart]). */
    const val FRESH_START = "fresh_start"
    const val DELETE_PROJECT = "delete_project"

    fun hello(appVersion: String, phoneName: String, @Suppress("UNUSED_PARAMETER") pushToken: String? = null, features: List<String> = FEATURES): String = obj("Hello") {
        put("proto", PROTO); put("app_version", appVersion); put("client_name", phoneName)
        put("env", JSONArray())
        put("features", JSONArray(features))
    }

    // ---- General chats ("talk", "chat_sync") ----

    /**
     * A general-chat message for the PC to answer: [turns] is the conversation for the model with
     * the new message ([turnId]) last; [lookup] what the phone looked up for it, [sources] where
     * from; [task] the project task the chat is in, until it's done or the owner taps ✕. The reply
     * is a [Filed] (an answer on its way, a task, a status answer, or "answer it on the phone").
     */
    fun talk(
        chatId: String, turnId: String, turns: List<LChatTurn>, lookup: String? = null, sources: List<LChatSource> = emptyList(),
        effort: String? = null, rules: String? = null, task: String? = null, chatOnly: Boolean = false, typed: Boolean = false,
        again: Boolean = false,
    ): String = obj("Talk") {
        typed(typed)
        put("chat_id", chatId); put("turn_id", turnId); put("turns", JSONArray(turns.map { it.json() }))
        put("lookup", lookup ?: JSONObject.NULL); put("sources", JSONArray(sources.map { it.json() }))
        put("effort", effort ?: JSONObject.NULL); put("ground_rules", rules ?: JSONObject.NULL); put("task", task ?: JSONObject.NULL)
        // "Just chat": answered as a chat even though it names a project.
        if (chatOnly) put("chat_only", true)
        // Sent again because the first reply never came: a PC that took it gives that reply back (P40).
        if (again) put("again", true)
    }

    fun talkStop(chatId: String, turnId: String): String = obj("TalkStop") { put("chat_id", chatId); put("turn_id", turnId) }

    /** How the answer to [turnId] stands; the reply is an [LTalkState]. */
    fun talkTurn(chatId: String, turnId: String): String = obj("TalkTurn") { put("chat_id", chatId); put("turn_id", turnId) }

    /** New or changed turns of a chat (only those), or its deletion; the reply is an [LChatPutDone]. */
    fun chatPut(chatId: String, name: String?, turns: List<LChatTurn>, deleted: Boolean = false): String = obj("ChatPut") {
        put("chat_id", chatId); put("name", name ?: JSONObject.NULL); put("turns", JSONArray(turns.map { it.json() })); put("deleted", deleted)
    }

    /** Every chat changed after [seq] (the PC's own numbering); the reply is an [LChatsPage]. */
    fun chatsSince(seq: Long): String = obj("ChatsSince") { put("seq", seq) }

    /** The ground rules changed here at [at]; the reply is the rules that count ([LGroundRules]). */
    fun setGroundRules(text: String, at: Long): String = obj("SetGroundRules") { put("text", text); put("at", at) }

    fun chatTurn(o: JSONObject) = LChatTurn(
        id = o.getString("id"),
        user = o.getBoolean("user"),
        text = o.optString("text"),
        sources = o.optJSONArray("sources").let { a -> (0 until (a?.length() ?: 0)).map { chatSource(a!!.getJSONObject(it)) } },
        ran = opt(o, "ran"),
        modelWords = opt(o, "model_words"),
        at = o.optLong("at"),
        version = o.optInt("version", 1),
        seq = o.optLong("seq"),
        gone = o.optBoolean("gone", false),
        switchNote = opt(o, "switch_note"),
    )

    private fun chatSource(o: JSONObject) = LChatSource(o.optString("site"), o.optString("url"), o.optString("words"), o.optBoolean("results"), o.optString("via"))

    fun chatRecord(o: JSONObject) = LChatRecord(
        id = o.getString("id"),
        name = opt(o, "name"),
        at = o.optLong("at"),
        seq = o.optLong("seq"),
        turns = o.optJSONArray("turns").let { a -> (0 until (a?.length() ?: 0)).map { chatTurn(a!!.getJSONObject(it)) } },
        deleted = if (o.isNull("deleted") || !o.has("deleted")) null else o.optLong("deleted"),
    )

    /** The reply to [chatsSince]. */
    fun chatsPage(v: Any?): LChatsPage? {
        val o = v as? JSONObject ?: return null
        val a = o.optJSONArray("chats") ?: JSONArray()
        return LChatsPage(o.optLong("seq"), (0 until a.length()).map { chatRecord(a.getJSONObject(it)) }, o.optBoolean("more"))
    }

    /** The reply to [chatPut]. */
    fun chatPutDone(v: Any?): LChatPutDone? = (v as? JSONObject)?.let { LChatPutDone(it.optLong("seq"), it.optBoolean("deleted")) }

    /** The reply to [setGroundRules]. */
    fun groundRules(v: Any?): LGroundRules? = (v as? JSONObject)?.let { LGroundRules(it.optString("text"), it.optLong("at")) }

    /** The reply to [talkTurn]. */
    fun talkState(v: Any?): LTalkState? {
        if (v == "Unknown") return LTalkState.Unknown
        val o = v as? JSONObject ?: return null
        val name = o.keys().asSequence().firstOrNull() ?: return null
        val b = o.getJSONObject(name)
        return when (name) {
            "Answering" -> LTalkState.Answering(b.optString("text"))
            "Done" -> LTalkState.Done(chatTurn(b.getJSONObject("turn")))
            "Failed" -> LTalkState.Failed(b.optString("why"))
            else -> null
        }
    }

    /** The PC's one truth about its work (`Event::Truth`). */
    fun finishedItem(o: JSONObject): LFinished = LFinished(o.getString("id"), o.getString("task"), o.optString("how", "Ok"), o.optLong("at"))

    fun truth(o: JSONObject): LTruth {
        val pc = o.optJSONObject("pc") ?: JSONObject()
        val counts = o.optJSONObject("counts") ?: JSONObject()
        val engine = o.optJSONObject("engine") ?: JSONObject()
        val tasks = o.optJSONArray("tasks") ?: JSONArray()
        val projects = o.optJSONArray("projects") ?: JSONArray()
        val usage = o.optJSONObject("usage")
        fun long(x: JSONObject, k: String): Long? = if (x.isNull(k) || !x.has(k)) null else x.optLong(k)
        return LTruth(
            runId = o.optString("run_id"),
            rev = o.optLong("rev"),
            asOf = o.optLong("as_of"),
            pc = LTruthPc(pc.optString("name"), pc.optString("level", "Free"), pc.optString("line")),
            tasks = (0 until tasks.length()).map { i ->
                val t = tasks.getJSONObject(i)
                LTruthTask(t.getString("id"), t.optString("project"), t.optString("title"), t.optString("state", "Done"), t.optBoolean("background"), long(t, "since"), t.optBoolean("from_phone"), t.optBoolean("failed"))
            },
            working = counts.optInt("working"),
            needsYou = counts.optInt("needs_you"),
            projects = (0 until projects.length()).map { i ->
                val p = projects.getJSONObject(i)
                LTruthProject(p.getString("path"), p.optString("name"), long(p, "last_activity"), p.optInt("working"), p.optInt("needs_you"), p.optJSONObject("checks")?.let(::checks))
            },
            engine = LTruthEngine(engine.optString("chats_where"), engine.optString("chats_model_words"), engine.optBoolean("answering"), engine.optBoolean("paid_on")),
            usage = usage?.let { u ->
                LTruthUsage(
                    weekly = u.optJSONObject("weekly")?.let { it.optDouble("used") to it.optLong("resets_at") },
                    fiveHour = u.optJSONObject("five_hour")?.let { it.optDouble("used") to it.optLong("resets_at") },
                    asOf = long(u, "as_of"),
                )
            },
            stats = o.optJSONObject("stats")?.let { s ->
                LTruthStats(s.optDouble("cpu"), s.optLong("mem_used"), s.optLong("mem_total"), if (s.isNull("gpu") || !s.has("gpu")) null else s.optDouble("gpu"), s.optInt("threads", 0).takeIf { it > 0 }, s.optInt("cpu_temp_c", 0).takeIf { it > 0 }, s.optInt("gpu_temp_c", 0).takeIf { it > 0 })
            },
        )
    }

    fun sync(since: Long): String = obj("Sync") { put("since", since) }

    fun projects(): String = JSONObject.quote("Projects")

    /** The PC's Claude Code sessions (feature [PC_SESSIONS]); the reply is a list, as in [Ev.PcSessions]. */
    fun pcSessions(): String = JSONObject.quote("PcSessions")

    /** Opens one of them as a task; the reply is the task's id. */
    fun openPcSession(session: String): String = obj("OpenPcSession") { put("session", session) }

    fun pcSession(o: JSONObject): LPcSession = LPcSession(
        o.getString("id"), o.optString("title"), o.optString("project"), o.optBoolean("here"), o.optBoolean("app"),
        if (o.isNull("task") || !o.has("task")) null else o.optString("task"),
        when (o.optString("state")) { "working" -> LPcState.Working; "idle" -> LPcState.Idle; else -> LPcState.Ended }, o.optLong("last"),
    )

    fun pcSessionList(a: JSONArray): List<LPcSession> = (0 until a.length()).map { pcSession(a.getJSONObject(it)) }

    /** A task from the phone always asks first. A message id makes sending it again harmless. */
    fun newTask(
        project: String, text: String, msgId: String? = null, model: String? = null, plan: Boolean = false, rules: String? = null,
        pictures: List<LPicture> = emptyList(), files: List<LFile> = emptyList(), effort: String? = null, typed: Boolean = false,
    ): String = obj("NewTask") {
        typed(typed)
        put("project", project); put("text", text); put("pictures", pictureArray(pictures)); put("mode", if (plan) "Plan" else "Ask")
        files(files)
        put("model", model ?: JSONObject.NULL); put("effort", effort ?: JSONObject.NULL); put("separate_copy", false)
        put("msg_id", msgId ?: JSONObject.NULL)
        rules(rules)
    }

    /**
     * The person typed these words into a message box: the PC tells Claude Code so. Left out when false
     * (everything a button or Mímir writes), so the call is the one an older PC knows.
     */
    private fun JSONObject.typed(typed: Boolean) { if (typed) put("typed", true) }

    /** The phone's ground rules, which the PC uses for that task instead of its own (left out when off). */
    private fun JSONObject.rules(rules: String?) { if (rules != null) put("ground_rules", rules) }

    /** The pictures (all kept for the task) and files ("task_files") that go with a task's message; empty lists leave the call as it was. */
    fun send(task: String, text: String, msgId: String? = null, pictures: List<LPicture> = emptyList(), files: List<LFile> = emptyList(), typed: Boolean = false): String = obj("Send") {
        typed(typed)
        put("task", task); put("text", text); put("pictures", pictureArray(pictures)); files(files); put("msg_id", msgId ?: JSONObject.NULL)
    }

    private fun pictureArray(pictures: List<LPicture>) = JSONArray(pictures.map { JSONObject().put("media_type", it.mediaType).put("base64", it.base64) })
    private fun JSONObject.files(files: List<LFile>) {
        if (files.isNotEmpty()) put("files", JSONArray(files.map { JSONObject().put("name", it.name).put("media_type", it.mediaType).put("base64", it.base64) }))
    }

    /** A general-chat message (a PC without "talk"): the PC files it (status, idea, request, Inbox); the reply is a [Filed]. */
    fun chat(msgId: String, text: String, rules: String? = null, typed: Boolean = false): String = obj("Chat") { put("msg_id", msgId); put("text", text); rules(rules); typed(typed) }

    /** An answer to a needs-you item: a plain one (`"Allow"`, `"Works"`, ...), a choice or text. */
    fun answerItem(askId: String, answer: ItemAnswer): String = obj("AnswerItem") {
        put("ask_id", askId)
        put("answer", when (answer) {
            is ItemAnswer.Plain -> answer.name
            is ItemAnswer.Option -> JSONObject().put("Option", answer.index)
            is ItemAnswer.Text -> JSONObject().put("Text", answer.text)
        })
    }

    fun status(): String = JSONObject.quote("Status")
    fun inbox(): String = JSONObject.quote("Inbox")
    fun ideas(project: String): String = obj("Ideas") { put("project", project) }
    fun webSearch(query: String, max: Int): String = obj("WebSearch") { put("query", query); put("max", max) }
    /** One of Mímir's lookups fetched by the PC (the weather or an encyclopedia; the PC checks the address). */
    fun lookup(url: String): String = obj("Lookup") { put("url", url) }
    fun fileInbox(inboxId: String, project: String): String = obj("FileInbox") { put("inbox_id", inboxId); put("project", project) }
    fun moveTask(task: String, project: String): String = obj("MoveTask") { put("task", task); put("project", project) }
    /** "Move to another project": the task carries on there, conversation and all. */
    fun moveTo(task: String, project: String): String = obj("MoveTo") { put("task", task); put("project", project) }
    fun undoIdea(project: String, idea: String): String = obj("UndoIdea") { put("project", project); put("idea", idea) }
    fun stop(task: String): String = obj("Stop") { put("task", task) }
    /** Back to just before the owner's message at [entry] (the PC puts the files back as they were). */
    fun undo(task: String, entry: Int): String = obj("Undo") { put("task", task); put("entry", entry) }
    fun pause(task: String): String = obj("Pause") { put("task", task) }
    fun continueTask(task: String): String = obj("Continue") { put("task", task) }
    fun rename(task: String, title: String): String = obj("Rename") { put("task", task); put("title", title) }
    /** The task, its conversation and its files go from the PC (refused while it works). */
    fun deleteTask(task: String): String = obj("DeleteTask") { put("task", task) }
    fun deleteProject(path: String): String = obj("DeleteProject") { put("project", path) }
    fun archive(task: String, archived: Boolean): String = obj("Archive") { put("task", task); put("archived", archived) }
    /** A phone may only switch a task between asking first and planning first. */
    fun setMode(task: String, plan: Boolean): String = obj("SetMode") { put("task", task); put("mode", if (plan) "Plan" else "Ask") }
    /** YOLO ("Never ask"): the PC allows it only when its Settings allow YOLO from the phone. */
    fun setYolo(task: String): String = obj("SetMode") { put("task", task); put("mode", "NoAsk") }
    /** The effort level for the task's next messages (`null`: the usual one); "task_reply_options". */
    fun setEffort(task: String, effort: String?): String = obj("SetEffort") { put("task", task); put("effort", effort ?: JSONObject.NULL) }
    fun setModel(task: String, model: String?): String = obj("SetModel") { put("task", task); put("model", model ?: JSONObject.NULL) }

    /** How the phone sets engine selection in Settings › Who answers. */
    fun setEngines(chats: String?, project: String?): String = obj("SetEngines") {
        put("chats", chats ?: JSONObject.NULL); put("project", project ?: JSONObject.NULL)
    }

    /** Which Claude model answers chats on the PC ("auto", "haiku", "sonnet", "opus", "fable"); every chat, not one. */
    fun setChatModel(id: String): String = obj("SetEngines") {
        put("chats", JSONObject.NULL); put("project", JSONObject.NULL); put("chat_model", id)
    }

    /** The PC's model and effort for new work ("" Claude's own choice); null: unchanged. */
    fun setNewWork(model: String?, effort: String?): String = obj("SetEngines") {
        put("chats", JSONObject.NULL); put("project", JSONObject.NULL)
        model?.let { put("default_model", it) }; effort?.let { put("default_effort", it) }
    }

    fun updates(): String = JSONObject.quote("Updates")
    fun models(): String = JSONObject.quote("Models")
    fun setFreeChoice(switchToFree: Boolean?, freeModel: String?): String = obj("SetFreeChoice") {
        put("switch_to_free", switchToFree ?: JSONObject.NULL); put("free_model", freeModel ?: JSONObject.NULL)
    }
    /** "Use my API credits when my plan runs out" (the owner's; the key itself stays on the PC). */
    fun setApiCredits(on: Boolean): String = obj("SetFreeChoice") {
        put("switch_to_free", JSONObject.NULL); put("free_model", JSONObject.NULL); put("api_credits", on)
    }
    fun setWeeklyCheck(on: Boolean): String = obj("SetWeeklyCheck") { put("on", on) }
    fun phoneSettings(): String = JSONObject.quote("PhoneSettings")
    fun setPcName(name: String): String = obj("SetPcName") { put("name", name) }
    fun setRelays(on: Boolean): String = obj("SetRelays") { put("on", on) }
    fun alwaysAllowed(task: String): String = obj("AlwaysAllowed") { put("task", task) }
    fun stopAllowing(task: String, file: String): String = obj("StopAllowing") { put("task", task); put("file", file) }
    fun continueOnClaude(task: String): String = obj("ContinueOnClaude") { put("task", task) }
    fun startFresh(task: String): String = obj("StartFresh") { put("task", task) }
    /** "Fresh start": only to a PC that lists [FRESH_START]; an older one would make a new task. */
    fun freshStart(task: String): String = obj("StartFresh") { put("task", task); put("same_chat", true) }
    /** An Incognito task always asks first, on a free model. */
    fun newIncognito(project: String, text: String, model: String, rules: String? = null, typed: Boolean = false): String = obj("NewIncognito") {
        typed(typed)
        put("project", project); put("text", text); put("model", model); put("mode", "Ask")
        rules(rules)
    }
    fun endIncognito(task: String): String = obj("EndIncognito") { put("task", task) }

    /** The PC's model list (the reply to [models]). */
    fun modelList(v: Any?): LModels? {
        val o = v as? JSONObject ?: return null
        val a = o.optJSONArray("free")
        val free = (0 until (a?.length() ?: 0)).map { i ->
            a!!.getJSONObject(i).let { m -> LFree(m.getString("id"), m.getString("name"), m.optString("note"), m.optBoolean("refusals_removed")) }
        }
        return LModels(
            free, if (o.isNull("free_model")) null else o.optString("free_model"), o.optBoolean("switch_to_free"), o.optString("ollama"),
            o.optJSONObject("standalone")?.toString(),
            weeklyCheck = o.optBoolean("weekly_check", true),
            apiCredits = o.optBoolean("api_credits"),
            apiKeySaved = if (o.has("api_key_saved")) o.optBoolean("api_key_saved") else null,
            power = if (o.isNull("power")) null else o.optString("power").ifEmpty { null },
            chatsEngine = if (o.isNull("chats_engine")) null else o.optString("chats_engine").ifEmpty { null },
            projectEngine = if (o.isNull("project_engine")) null else o.optString("project_engine").ifEmpty { null },
            chatsModel = if (o.isNull("chats_model")) null else o.optString("chats_model").ifEmpty { null },
            seesPictures = o.optBoolean("sees_pictures", false),
            defaultModel = if (o.isNull("default_model")) null else o.optString("default_model"),
            defaultEffort = if (o.isNull("default_effort")) null else o.optString("default_effort"),
            chatModels = o.optJSONArray("chat_models").let { a -> (0 until (a?.length() ?: 0)).map { i -> a!!.getJSONObject(i).let { LChoice(it.getString("id"), it.optString("name"), it.optString("about")) } } },
        )
    }
    fun getFile(name: String): String = obj("GetFile") { put("name", name) }
    fun newProject(name: String): String = obj("NewProject") { put("name", name) }
    fun speak(task: String, entry: Int): String = obj("Speak") { put("task", task); put("entry", entry) }
    fun listen(wavBase64: String): String = obj("Listen") { put("wav_base64", wavBase64) }

    private fun obj(name: String, body: JSONObject.() -> Unit): String =
        JSONObject().put(name, JSONObject().apply(body)).toString()

    /** One event with its number; unknown ones (from a newer PC) are [Ev.Other]. */
    fun event(json: String): Pair<Long, Ev> {
        val o = JSONObject(json)
        val seq = o.optLong("seq")
        val e = o.get("event")
        if (e is String) {
            return seq to when (e) {
                "Ready" -> Ev.Ready
                "ModelsChanged" -> Ev.ModelsChanged
            "TestNoticeOff" -> Ev.TestNoticeOff
                else -> Ev.Other(e)
            }
        }
        e as JSONObject
        val name = e.keys().next()
        val v = e.get(name)
        val ev = when (name) {
            "Welcome" -> (v as JSONObject).let {
                Ev.Welcome(it.getString("pc_name"), it.getString("run_id"), it.getInt("proto"), it.optString("pc_version"),
                    it.optJSONArray("features")?.let { a -> (0 until a.length()).map { i -> a.getString(i) }.toSet() }.orEmpty(),
                    it.optString("person"))
            }
            "Snapshot" -> Ev.Snapshot((v as JSONObject).getString("run_id"))
            "Task" -> Ev.TaskHeader(header((v as JSONObject).getJSONObject("header")))
            "Removed" -> Ev.Removed((v as JSONObject).getString("task"))
            "Entries" -> (v as JSONObject).let { o2 ->
                val list = o2.getJSONArray("entries")
                val meta = o2.optJSONArray("meta")
                Ev.Entries(o2.getString("task"), o2.getInt("from"), (0 until list.length()).map { entry(list.get(it)) },
                    meta?.let { m -> (0 until m.length()).map { meta(m.optJSONObject(it)) } }.orEmpty())
            }
            "Live" -> (v as JSONObject).let { Ev.Live(it.getString("task"), it.getString("text")) }
            "Problem" -> Ev.Problem((v as JSONObject).getString("text"))
            "NeedsYou" -> (v as JSONObject).getJSONArray("items").let { a -> Ev.NeedsYou((0 until a.length()).map { item(a.getJSONObject(it)) }) }
            "Finished" -> Ev.Finished(finishedItem((v as JSONObject).getJSONObject("item")))
            "RecentFinished" -> (v as JSONObject).getJSONArray("items").let { a -> Ev.RecentFinished((0 until a.length()).map { finishedItem(a.getJSONObject(it)) }) }
            "PcSessions" -> Ev.PcSessions(pcSessionList((v as JSONObject).getJSONArray("sessions")))
            "Usage" -> Ev.Other(name)
            "TestNotice" -> (v as JSONObject).let { Ev.TestNotice(it.getString("name"), it.optString("what"), it.optString("until")) }
            "TalkPiece" -> (v as JSONObject).let { Ev.TalkPiece(it.getString("chat_id"), it.getString("turn_id"), it.optInt("n"), it.optString("text"), it.optBoolean("thinking")) }
            "TalkDone" -> (v as JSONObject).let { d ->
                val r = d.getJSONObject("result")
                if (r.has("Ok")) Ev.TalkDone(d.getString("chat_id"), d.getString("turn_id"), chatTurn(r.getJSONObject("Ok")), null)
                else Ev.TalkDone(d.getString("chat_id"), d.getString("turn_id"), null, r.optString("Err", "Your PC couldn't answer."))
            }
            "ChatChanged" -> Ev.ChatChanged(chatRecord((v as JSONObject).getJSONObject("chat")))
            "Truth" -> Ev.Truth(truth((v as JSONObject).getJSONObject("truth")))
            "Updates" -> (v as JSONObject).getJSONArray("cards").let { a -> Ev.Updates((0 until a.length()).map { card(a.getJSONObject(it)) }) }
            "Reply" -> (v as JSONObject).let { r ->
                val res = r.getJSONObject("result")
                if (res.has("Ok")) Ev.Reply(r.getLong("call_id"), res.get("Ok"), null)
                else Ev.Reply(r.getLong("call_id"), null, res.optString("Err", "The PC refused."))
            }
            else -> Ev.Other(name)
        }
        return seq to ev
    }

    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

    private fun opt(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }

    fun card(o: JSONObject) = LCard(
        id = o.getString("id"),
        project = o.optString("project"),
        text = o.optString("text"),
        time = o.optLong("time"),
        task = opt(o, "task"),
        audio = opt(o, "audio"),
        audioNote = opt(o, "audio_note"),
    )

    private fun item(o: JSONObject) = LItem(
        askId = o.getString("ask_id"),
        kind = o.getString("kind"),
        project = o.getString("project"),
        task = if (o.isNull("task")) null else o.optString("task"),
        title = o.optString("title"),
        text = o.optString("text"),
        preview = strings(o.optJSONArray("preview")),
        choices = strings(o.optJSONArray("choices")),
        serious = o.optBoolean("serious"),
        created = o.optLong("created"),
        link = if (o.isNull("link")) null else o.optString("link").ifEmpty { null },
        leavesPC = o.optBoolean("leaves_pc"),
    )

    /** How the PC filed a general-chat message (the reply to [chat] or [fileInbox]). */
    fun filed(v: Any?): Filed? {
        val o = v as? JSONObject ?: return null
        val name = o.keys().asSequence().firstOrNull() ?: return null
        val b = o.getJSONObject(name)
        return when (name) {
            "NewTask" -> Filed.NewTask(b.getString("project"), b.getString("task"))
            "SameTask" -> Filed.SameTask(b.getString("project"), b.getString("task"))
            "Idea" -> Filed.Idea(b.getString("project"), b.getString("idea"))
            "Status" -> Filed.Status(strings(b.getJSONArray("reply")))
            "Inbox" -> Filed.Inbox(b.getString("inbox_id"), strings(b.optJSONArray("candidates")))
            "Talk" -> Filed.Talk(
                b.getString("answer"), b.optString("ran", "pc"), b.optString("model_words"), opt(b, "waiting"),
                b.optJSONArray("files_read").let { a -> (0 until (a?.length() ?: 0)).map { i -> a!!.getJSONObject(i).let { LFileNote(it.getString("name"), it.optString("note")) } } },
            )
            "OnPhone" -> Filed.OnPhone(b.optString("why"))
            else -> null
        }
    }

    /** The status page (the reply to [status]). */
    fun statusPage(v: Any?): LStatus? {
        val o = v as? JSONObject ?: return null
        val list = o.optJSONArray("projects") ?: JSONArray()
        val projects = (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            LProject(
                path = p.getString("project"),
                name = p.getString("name"),
                version = if (p.isNull("version")) null else p.optString("version"),
                checks = p.optJSONObject("checks")?.let(::checks),
                tasksRunning = p.optInt("tasks_running"),
                needsYou = p.optInt("needs_you"),
                line = p.optString("line"),
                lastActivity = if (p.isNull("last_activity")) null else p.optLong("last_activity"),
            )
        }
        val weekly = o.optJSONObject("weekly")
        return LStatus(
            projects = projects,
            weeklyUsed = weekly?.optDouble("used"),
            weeklyResets = weekly?.optLong("resets_at"),
            reply = strings(o.optJSONArray("reply")),
            dataTime = o.optLong("data_time"),
            pcLine = if (o.isNull("pc_line")) null else o.optString("pc_line").ifBlank { null },
        )
    }

    /** A project's core checks (crates/proto's `CheckFacts`). */
    private fun checks(c: JSONObject): LChecks {
        fun pairs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getJSONArray(it).let { x -> x.getString(0) to x.getString(1) } }
        val f = c.optJSONArray("features") ?: JSONArray()
        return LChecks(
            passing = c.optInt("passing"),
            total = c.optInt("total"),
            failing = pairs(c.optJSONArray("failing")),
            features = (0 until f.length()).map { j -> f.getJSONArray(j).let { x -> Triple(x.getString(0), x.getString(1), x.getBoolean(2)) } },
            failingSince = if (c.isNull("failing_since")) null else c.optLong("failing_since"),
        )
    }

    private fun header(h: JSONObject): LinkTask {
        val asks = h.optJSONArray("asks") ?: JSONArray()
        return LinkTask(
            id = h.getString("id"),
            title = h.getString("title"),
            project = h.getString("project"),
            status = h.optString("status", "Done"),
            busy = h.optBoolean("busy"),
            running = h.optBoolean("running", true),
            model = if (h.isNull("model")) null else h.optString("model"),
            effort = if (!h.has("effort")) null else if (h.isNull("effort")) "" else h.optString("effort"),
            createdUnix = h.optLong("created_unix"),
            asks = (0 until asks.length()).map { i ->
                val a = asks.getJSONObject(i)
                Ask(a.optString("tool_name"), a.optJSONObject("tool_input") ?: JSONObject())
            },
            incognito = h.optBoolean("incognito"),
            checklist = h.optJSONArray("plan")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { io.github.odnwks.mimir.data.PlanStep(it.optString("text"), it.optBoolean("done"), it.optBoolean("doing")) }
            }.orEmpty(),
            viaApi = h.optBoolean("via_api"),
            archived = h.optBoolean("archived"),
            plan = h.optString("mode") == "Plan",
            mode = h.optString("mode", "Ask"),
            activeUnix = h.optLong("active_unix"),
            youMs = h.optLong("you_ms"),
            liveElsewhere = h.optBoolean("live_elsewhere"),
            outside = if (h.has("outside")) h.optBoolean("outside") else null,
            long = h.optJSONObject("long")?.let { l ->
                LongNotice(very = l.optBoolean("very"), own = l.optString("why") == "Own", elsewhere = l.optBoolean("elsewhere"), pages = l.optInt("pages"))
            },
            elsewhereIn = h.optString("elsewhere_in"),
            doing = h.optString("doing"),
            turnUnix = h.optLong("turn_unix"),
            failed = h.optBoolean("failed"),
        )
    }

    /** One entry's small print (P11); an empty one for a missing or odd value. */
    private fun meta(o: JSONObject?): LMeta {
        if (o == null) return LMeta()
        fun str(k: String) = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
        return LMeta(
            atMs = if (o.isNull("at")) null else o.optLong("at"),
            receipt = str("receipt")?.let { r -> LReceipt.entries.firstOrNull { it.name == r } },
            model = str("model"),
            effort = str("effort"),
            switched = str("switched"),
            switchedWhy = str("switched_why"),
        )
    }

    private fun entry(v: Any): LEntry {
        if (v !is JSONObject) return LEntry.Note(v.toString())
        val name = v.keys().next()
        return when (name) {
            "You" -> LEntry.You(v.getString(name))
            "Claude" -> LEntry.Claude(v.getString(name))
            "Note" -> LEntry.Note(v.getString(name))
            "Step" -> v.getJSONObject(name).let { s ->
                LEntry.Step(s.optString("what"), if (s.isNull("ok")) null else s.optBoolean("ok"))
            }
            else -> LEntry.Note("")
        }
    }
}

sealed interface Ev {
    /** [features]: calls this PC knows beyond the protocol ("search", "lookup"); an older PC lists none. */
    /** [person]: whose phone the PC says this is (an id; "" from an older PC). */
    data class Welcome(val pcName: String, val runId: String, val proto: Int, val pcVersion: String = "", val features: Set<String> = emptySet(), val person: String = "") : Ev
    data class Snapshot(val runId: String) : Ev
    data object Ready : Ev
    data class TaskHeader(val task: LinkTask) : Ev
    data class Removed(val task: String) : Ev
    /** [meta]: the small print of the same entries, one each; empty from an older PC or when none has any. */
    data class Entries(val task: String, val from: Int, val entries: List<LEntry>, val meta: List<LMeta> = emptyList()) : Ev
    data class Live(val task: String, val text: String) : Ev
    data class Problem(val text: String) : Ev
    data class Reply(val callId: Long, val ok: Any?, val err: String?) : Ev
    data class NeedsYou(val items: List<LItem>) : Ev
    data class Updates(val cards: List<LCard>) : Ev
    /** Labs: a test is using the phones; show the don't-touch notice (if its switch is on). */
    data class TestNotice(val name: String, val what: String, val until: String) : Ev
    data object TestNoticeOff : Ev
    /** What this phone's person may choose changed on the PC. */
    data object ModelsChanged : Ev
    /** The next words of a chat answer the PC writes ([n] counts from 0; [thinking] is never the answer). */
    data class TalkPiece(val chatId: String, val turnId: String, val n: Int, val text: String, val thinking: Boolean) : Ev
    /** A chat answer is finished and saved ([turn]), or couldn't be given ([err]). */
    data class TalkDone(val chatId: String, val turnId: String, val turn: LChatTurn?, val err: String?) : Ev
    /** A chat changed on the PC: its changed turns, its name, or its deletion. */
    data class ChatChanged(val chat: LChatRecord) : Ev
    /** The PC's one truth about its work. */
    data class Truth(val truth: LTruth) : Ev
    /** A task's work ended (feature "finished_events"). */
    data class Finished(val item: LFinished) : Ev
    /** The PC's last few finished items, sent with a snapshot (a phone shows each id once). */
    data class RecentFinished(val items: List<LFinished>) : Ev
    /** Every Claude Code session on the PC, the whole list each time it changes (feature [Wire.PC_SESSIONS]). */
    data class PcSessions(val sessions: List<LPcSession>) : Ev
    data class Other(val name: String) : Ev
}

/** Where a PC session is at. */
enum class LPcState { Working, Idle, Ended }

/** One Claude Code session on the PC: [here] started in Mímir (its task is [task]), [app] came from the Claude app, [last] is unix seconds. */
data class LPcSession(
    val id: String, val title: String, val project: String, val here: Boolean, val app: Boolean, val task: String?,
    val state: LPcState, val last: Long,
)

/** How a task's work ended: [how] is "Ok", "Stopped" or "Failed". No words from the task. */
data class LFinished(val id: String, val task: String, val how: String, val at: Long)

/** One needs-you item from the PC (crates/proto's `NeedsYouItem`); `kind` is its name, e.g. "Permission". */
/** One Claude model chats can use, as the PC lists it. */
data class LChoice(val id: String, val name: String, val about: String)

/** A free model on the PC (Ollama). */
data class LFree(val id: String, val name: String, val note: String, val refusalsRemoved: Boolean)

/** The PC's models: its free ones, the near-limit choice, and why free ones can't run now (or ""). */
data class LModels(
    val free: List<LFree>,
    val freeModel: String?,
    val switchToFree: Boolean,
    val why: String,
    /** The PC's list of standalone models (`{"models": [...]}`), when it sends one. */
    val standalone: String? = null,
    /** The weekly look for a better phone model. */
    val weeklyCheck: Boolean = true,
    /** What powers tasks on the PC: "backup", "claude" or "free" (null: an older PC). */
    val power: String? = null,
    /** What answers chats and lookups on the PC: "free" or "paid" (null: an older PC, no "engines"). */
    val chatsEngine: String? = null,
    /** The PC's model and effort for new work ("" Claude's own; null: an older PC, or not the owner). */
    val defaultModel: String? = null,
    val defaultEffort: String? = null,
    /** Which Claude model chats use ("auto" or an alias; null: an older PC) and the choices it offers. */
    val chatsModel: String? = null,
    val chatModels: List<LChoice> = emptyList(),
    /** What does project work on the PC: "free" or "claude" (null: an older PC). */
    val projectEngine: String? = null,
    /** Whether what answers this person's chats on the PC can look at pictures (false: no, or an older PC). */
    val seesPictures: Boolean = false,
    /** "Use my API credits when my plan runs out" is on. */
    val apiCredits: Boolean = false,
    /** A key is saved on the PC (null: an older PC, which has no such setting). */
    val apiKeySaved: Boolean? = null,
)

/** An update card from the PC. */
data class LCard(
    val id: String,
    val project: String,
    val text: String,
    val time: Long,
    val task: String?,
    val audio: String?,
    val audioNote: String?,
)

data class LItem(
    val askId: String,
    val kind: String,
    val project: String,
    val task: String?,
    val title: String,
    val text: String,
    val preview: List<String>,
    val choices: List<String>,
    val serious: Boolean,
    val created: Long,
    val link: String?,
    /** PCS-10: action leaves this PC and cannot be undone. */
    val leavesPC: Boolean = false,
)

/** An answer to a needs-you item, as crates/proto's `ItemAnswer`. */
sealed interface ItemAnswer {
    /** A unit answer: Allow, DontAllow, AllowFileThisTask, KeepForLater, Works, StillBroken, Approve, Release, AskClaudeToFix. */
    data class Plain(val name: String) : ItemAnswer
    data class Option(val index: Int) : ItemAnswer
    data class Text(val text: String) : ItemAnswer
}

/** How a general-chat message was filed. */
sealed interface Filed {
    data class NewTask(val project: String, val task: String) : Filed
    data class SameTask(val project: String, val task: String) : Filed
    data class Idea(val project: String, val idea: String) : Filed
    data class Status(val reply: List<String>) : Filed
    data class Inbox(val inboxId: String, val candidates: List<String>) : Filed
    /** The PC answers it: [answer] is the answer's id, [ran] "pc" or "paid", [waiting] a line to show meanwhile. */
    data class Talk(val answer: String, val ran: String, val modelWords: String, val waiting: String? = null, val filesRead: List<LFileNote> = emptyList()) : Filed
    /** The PC can't answer this one (busy, no model that fits): the phone answers it itself. */
    data class OnPhone(val why: String) : Filed
}

/** A source under a chat answer (crates/proto's `ChatSource`). */
data class LChatSource(val site: String, val url: String, val words: String = "", val results: Boolean = false, val via: String = "") {
    fun json(): JSONObject = JSONObject().put("site", site).put("url", url).put("words", words).put("results", results).put("via", via)
}

/**
 * One message of a general chat, the same on the phone and the PC (crates/proto's `ChatTurn`).
 * The side where a message is typed makes its id; an answer's id is `<the message's id>.a`.
 */
data class LChatTurn(
    val id: String,
    val user: Boolean,
    val text: String,
    val sources: List<LChatSource> = emptyList(),
    /** Where an answer ran: "pc", "phone" or "paid" (null for the owner's messages). */
    val ran: String? = null,
    val modelWords: String? = null,
    /** Milliseconds since 1970 on the device that made it. */
    val at: Long = 0,
    /** An answer given again on the phone has a higher version; the highest is kept. */
    val version: Int = 1,
    /** The PC's change number for it (0 until the PC has it). */
    val seq: Long = 0,
    /** Cut away by an edit of an earlier message: a tombstone (higher version, no words). Older apps never see it. */
    val gone: Boolean = false,
    /** Pictures for the PC's model ("chat_pictures"): only in a Talk, never kept in the chat's turns. */
    val pictures: List<LPicture> = emptyList(),
    /** Files for the PC to read ("chat_files"): only in a Talk, never kept in the chat's turns. */
    val files: List<LFile> = emptyList(),
    /** A short plain note when a different model than the usual one answered (older PCs never send it). */
    val switchNote: String? = null,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("user", user).put("text", text)
        .put("sources", JSONArray(sources.map { it.json() })).put("ran", ran ?: JSONObject.NULL)
        .put("model_words", modelWords ?: JSONObject.NULL).put("at", at).put("version", version).put("seq", seq)
        .also { if (gone) it.put("gone", true) }
        .also { if (switchNote != null) it.put("switch_note", switchNote) }
        .also { if (pictures.isNotEmpty()) it.put("pictures", JSONArray(pictures.map { p -> JSONObject().put("media_type", p.mediaType).put("base64", p.base64) })) }
        .also { if (files.isNotEmpty()) it.put("files", JSONArray(files.map { f -> JSONObject().put("name", f.name).put("media_type", f.mediaType).put("base64", f.base64) })) }
}

/** A file sent to the PC to be read: its name, type ("application/pdf", may be empty) and bytes as base64. */
data class LFile(val name: String, val mediaType: String, val base64: String)

/** How much of a file the PC read, in its words ("Read all of it"). */
data class LFileNote(val name: String, val note: String)

/** A picture sent to the PC: its type ("image/jpeg") and its bytes as base64. */
data class LPicture(val mediaType: String, val base64: String)

/** A general chat as the PC keeps it; in [Wire.chatsPage] and [Ev.ChatChanged], only its changed turns. */
data class LChatRecord(val id: String, val name: String?, val at: Long, val seq: Long, val turns: List<LChatTurn>, val deleted: Long? = null)

/** [more]: the PC has more changes than fit one page; ask again from [seq]. */
data class LChatsPage(val seq: Long, val chats: List<LChatRecord>, val more: Boolean = false)
data class LChatPutDone(val seq: Long, val deleted: Boolean)
data class LGroundRules(val text: String, val at: Long)

/** How an answer the PC was asked for stands (the reply to [Wire.talkTurn]). */
sealed interface LTalkState {
    data class Answering(val text: String) : LTalkState
    data class Done(val turn: LChatTurn) : LTalkState
    data class Failed(val why: String) : LTalkState
    data object Unknown : LTalkState
}

/** The PC in one line: [level] is "Free", "Background" or "DontTouch". */
data class LTruthPc(val name: String, val level: String, val line: String)

/** A task's state, worked out once on the PC: [state] is "Working", "NeedsYou", "LiveElsewhere", "Stopped" or "Done". */
data class LTruthTask(val id: String, val project: String, val title: String, val state: String, val background: Boolean, val since: Long?, val fromPhone: Boolean, val failed: Boolean = false)

data class LTruthProject(val path: String, val name: String, val lastActivity: Long?, val working: Int, val needsYou: Int, val checks: LChecks?)
data class LTruthEngine(val chatsWhere: String, val chatsModelWords: String, val answering: Boolean, val paidOn: Boolean)

/** Claude's use in percent and when it resets, whenever the PC has a reading. */
data class LTruthUsage(val weekly: Pair<Double, Long>?, val fiveHour: Pair<Double, Long>?, val asOf: Long?)

/** The PC's load: percent for the processor and graphics ([gpu] null when the PC can't tell), bytes for memory. */
data class LTruthStats(val cpu: Double, val memUsed: Long, val memTotal: Long, val gpu: Double?, val threads: Int? = null, val cpuTempC: Int? = null, val gpuTempC: Int? = null)

/** One snapshot of everything the owner sees about the PC's work (crates/proto's `Truth`). */
data class LTruth(
    val runId: String,
    val rev: Long,
    val asOf: Long,
    val pc: LTruthPc,
    val tasks: List<LTruthTask>,
    val working: Int,
    val needsYou: Int,
    val projects: List<LTruthProject>,
    val engine: LTruthEngine,
    val usage: LTruthUsage?,
    /** The PC's processor, memory and graphics load (absent from an older PC). */
    val stats: LTruthStats? = null,
) {
    /** Whether this snapshot is newer than [old]: a new run of the PC's service, or a higher revision in the same run. */
    fun newerThan(old: LTruth?): Boolean = old == null || runId != old.runId || rev > old.rev
}

data class LChecks(
    val passing: Int,
    val total: Int,
    val failing: List<Pair<String, String>>,
    val features: List<Triple<String, String, Boolean>>,
    val failingSince: Long?,
)

data class LProject(
    val path: String,
    val name: String,
    val version: String?,
    val checks: LChecks?,
    val tasksRunning: Int,
    val needsYou: Int,
    val line: String,
    val lastActivity: Long?,
)

data class LStatus(
    val projects: List<LProject>,
    val weeklyUsed: Double?,
    val weeklyResets: Long?,
    val reply: List<String>,
    val dataTime: Long,
    /** Labs on the PC: what Claude is doing there, in one line (null while switched off). */
    val pcLine: String? = null,
)

data class LinkTask(
    val id: String,
    val title: String,
    val project: String,
    val status: String,
    val busy: Boolean,
    val model: String?,
    val createdUnix: Long,
    val asks: List<Ask>,
    /** An Incognito task: kept in memory only, never in the general chat. */
    val incognito: Boolean = false,
    /** Claude's checklist for it, as the PC keeps it (empty: none). */
    val checklist: List<io.github.odnwks.mimir.data.PlanStep> = emptyList(),
    /** Its latest run was started on the owner's API credits. */
    val viaApi: Boolean = false,
    /** Archived on the PC: shown only under All tasks › Archived. */
    val archived: Boolean = false,
    /** Plan first (changes nothing yet). */
    val plan: Boolean = false,
    /** The PC's permission mode: "Ask", "AcceptEdits", "Plan", "Auto" or "NoAsk". */
    val mode: String = "Ask",
    /** When its conversation last changed, wherever it went on (0: not known). */
    val activeUnix: Long = 0,
    /** Its conversation is going on outside Mímir right now (a terminal, the Claude app). */
    val liveElsewhere: Boolean = false,
    /** The PC says Mímir found it running outside it (null: an older PC doesn't say). */
    val outside: Boolean? = null,
    /** The conversation has run long (the PC's one rule); null: not long, or an older PC that doesn't say. */
    val long: LongNotice? = null,
    /** What Claude is doing right now, in the PC's plain words; "" when it is not working (or an older PC). */
    val doing: String = "",
    /** When the working turn began (seconds since 1970); 0 when not said. */
    val turnUnix: Long = 0,
    /** Where it goes on, when the PC says: "app" (the Claude app) or "terminal"; "" from an older PC. */
    val elsewhereIn: String = "",
    /** With status Stopped: it stopped unexpectedly (its Claude Code died), not by the owner's Stop. */
    val failed: Boolean = false,
    /** The task's effort as the PC keeps it ("" the default; null: the PC doesn't say). */
    val effort: String? = null,
    /** A Claude Code process of the task runs on the PC: something there can be stopped (true when an older PC doesn't say). */
    val running: Boolean = true,
    /** When the person last sent a message in it (ms since 1970); 0 from an older PC or with none. */
    val youMs: Long = 0,
)

/** A task whose conversation has run long (core's one rule, sent by the PC). */
data class LongNotice(
    /** Very long (80% of the model's room): a little stronger, and back after a dismissal. */
    val very: Boolean,
    /** On the PC's own model (slower answers) rather than online (uses more of the plan). */
    val own: Boolean,
    /** Going on in a terminal or the Claude app: it can't be handed over from here. */
    val elsewhere: Boolean,
    /** About how many book pages it is (0: too short to say). */
    val pages: Int,
) {
    /** The line, in the PC window's words. */
    val line: String get() = buildString {
        append("This conversation is long. ")
        append(if (own) "Answers get slower, and the start may be forgotten." else "Each reply re-reads all of it, so it uses more of your plan.")
        if (very) append(" Starting fresh soon will save a lot.")
        if (elsewhere) append(" It is going on in another app, so it can't be started fresh from here.")
    }
    val size: String? get() = if (pages >= 100) "About the length of a $pages-page book." else null
}

/** A permission request waiting: the tool and what it would do. */
data class Ask(val tool: String, val input: JSONObject) {
    /** Plain words, as the PC's orange bar says them. */
    val title: String get() = when (tool) {
        "Bash" -> "Run a command on your PC"
        "Edit", "MultiEdit" -> "Change a file"
        "Write" -> "Write a file"
        "NotebookEdit" -> "Change a notebook"
        else -> "Use $tool"
    }
    /** The command or file it's about. */
    val subject: String get() = input.optString("command").ifEmpty {
        input.optString("file_path").ifEmpty { input.optString("notebook_path").ifEmpty { input.optString("url") } }
    }
}

/** What became of the owner's message on the PC (P11): it has it, wrote it to Claude Code's input, or Claude Code took it in. */
enum class LReceipt { Sent, Delivered, Read }

/**
 * The small print the PC sends with one entry (P11). Every field is null from an older PC and for entries made
 * before the PC kept them. [atMs]: when it was made (unix ms). [receipt]: the owner's messages only. [model] and
 * [effort]: what answered a reply, or what a message was sent to ([effort] null with a model: the PC's default).
 * [switched] and [switchedWhy]: a reply whose model or effort differs from the one before, why (short and in full).
 */
data class LMeta(
    val atMs: Long? = null,
    val receipt: LReceipt? = null,
    val model: String? = null,
    val effort: String? = null,
    val switched: String? = null,
    val switchedWhy: String? = null,
)

sealed interface LEntry {
    data class You(val text: String) : LEntry
    data class Claude(val text: String) : LEntry
    data class Step(val what: String, val ok: Boolean?) : LEntry
    data class Note(val text: String) : LEntry
}
