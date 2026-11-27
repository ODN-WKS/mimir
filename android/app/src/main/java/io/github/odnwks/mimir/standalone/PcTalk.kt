package io.github.odnwks.mimir.standalone

import io.github.odnwks.mimir.link.Filed
import io.github.odnwks.mimir.link.LChatSource
import io.github.odnwks.mimir.link.LChatTurn
import io.github.odnwks.mimir.link.LTalkState

/**
 * The PC, as the one chat sees it (Stage 1, 2.1): when the phone is paired, connected and the PC
 * answers chats ("talk"), [Engine] sends each message there instead of to this phone's model.
 * Lookups stay on the phone (they already go through the PC when paired).
 */
interface PcTalk {
    /** Paired, connected, and the PC answers chats. */
    fun canTalk(): Boolean

    /** Paired, and this person has project work there (the owner): only then may a chat offer it (P38). */
    fun projectWork(): Boolean = false

    /** Which of its answerers the PC uses for chats (its own model or Online) has been read since the app started. */
    fun choiceRead(): Boolean = true

    /** Who the PC uses for chats, as last read there: "paid" (Online), "free", "" (no choice) or null (not read). */
    fun chatsEngine(): String? = ""

    /** The same as the screens show it now (before the PC is read: what it said last time; null: never heard). */
    fun chatsShown(): String? = chatsEngine()

    /** The PC is connected and ready but doesn't answer chats at all (an older one). */
    fun cannotChat(): Boolean = false

    /** Whether the PC takes pictures with a message and its answerer can look at them ("chat_pictures"). */
    fun canSeePictures(): Boolean = false

    /** The PC takes pictures with a message (an older one doesn't know the word) but its answerer can't look at them. */
    fun picturesRefused(): Boolean = false

    /** Asks the PC again what its models see (after an answer that switched models). */
    fun refreshSight() {}

    /** The PC takes files with a message and reads them (PDFs, documents, sheets, recordings: "chat_files"). */
    fun canSendFiles(): Boolean = false

    /** The PC's name as the owner sees it ("Your PC" or the name given to it). */
    fun pcName(): String

    /**
     * Sends a message ([turnId], last of [turns]) for the PC to answer. What follows comes to
     * [listener]: how it was filed, the answer's words, the finished answer, and the link going and
     * coming back. False if it couldn't be sent at all.
     */
    fun talk(request: TalkRequest, listener: TalkListener): Boolean

    /** Stops the PC's answer to the message [turnId]. */
    fun stopTalk(chatId: String, turnId: String)

    /** Asks how the answer to [turnId] stands (after the link came back); false if it couldn't be asked. */
    fun talkTurn(chatId: String, turnId: String, done: (LTalkState?) -> Unit): Boolean

    /** Turns a recording into words on the PC; false if it couldn't be sent now (the PC is away). */
    fun hear(wav: ByteArray, done: (text: String?, error: String?) -> Unit): Boolean = false

    /** The listener for [answerId] is no longer wanted (answered here instead, or finished). */
    fun letGo(answerId: String)

    // ---- One history ("chat_sync", Stage 1, 2.5) ----

    /** Connected, and the PC keeps chats. */
    fun canSync(): Boolean = false

    /** Whether the PC understands cut-away turns (an edit of an earlier message); an older one is not told. */
    fun canCut(): Boolean = false

    /** New or changed turns of a chat (only those), or its deletion; [done] gets the PC's answer (null: failed). */
    fun chatPut(chatId: String, name: String?, turns: List<LChatTurn>, deleted: Boolean, done: (io.github.odnwks.mimir.link.LChatPutDone?) -> Unit): Boolean = false

    /** Every chat the PC changed after [seq] (its own numbering). */
    fun chatsSince(seq: Long, done: (io.github.odnwks.mimir.link.LChatsPage?) -> Unit): Boolean = false

    /** The first sync is done: the old paired chat list (the PC's tasks) is no longer kept here. */
    fun oldChatsDone() {}

    // ---- A project named in the chat (Stage 1, 2.3) ----

    /** Whether the task [id] is done (the chat then leaves its project). */
    fun taskDone(id: String): Boolean = false

    /** The project [text] names on purpose, from the PC's project list kept here (null: none). */
    fun projectNamed(text: String): String? = null

    /** The PC can be told to answer a message as a chat ("Just chat"). */
    fun canChatOnly(): Boolean = false

    /**
     * "talk_once": this PC never does a message twice. Sent again ([TalkRequest.again]) it gives the first
     * reply back, and it says it doesn't know a message only when it never took it. An older PC can't
     * say: a message is never sent to it again (P40).
     */
    fun talksOnce(): Boolean = false

    /** The task started by mistake ("Just chat") is archived on the PC. */
    fun dropTask(id: String) {}
}

/** An answerer kept with a message (see Turn.saw), in plain words. */
fun answererName(who: String): String = when {
    who == "phone" -> "this phone"
    who == "pc" -> "your PC"
    who == "paid" -> "Online (Anthropic)"
    else -> who.removePrefix("own:")
}

/** Whether what this answerer gets leaves the person's own devices. */
fun goesOnline(who: String): Boolean = who == "paid" || who.startsWith("own:")

/** One message for the PC. */
data class TalkRequest(
    val chatId: String,
    val turnId: String,
    val turns: List<LChatTurn>,
    val lookup: String?,
    val sources: List<LChatSource>,
    val effort: String?,
    val rules: String?,
    val task: String?,
    /** "Just chat" was picked: the PC answers it as a chat even if it names a project. */
    val chatOnly: Boolean = false,
    /** The person typed the new message into a message box. */
    val typed: Boolean = false,
    /** The same message sent again because its first reply never came ("talk_once"). */
    val again: Boolean = false,
)

/** What the PC does with a message (called on the link's thread). */
interface TalkListener {
    /** How the PC filed it ([err]: refused). */
    fun filed(f: Filed?, err: String?)
    /** The next words of the answer (thinking pieces are never the answer). */
    fun piece(text: String, thinking: Boolean)
    /** The answer is finished and saved on the PC ([turn]), or couldn't be given ([err]). */
    fun done(turn: LChatTurn?, err: String?)
    /** The link to the PC dropped while waiting. */
    fun lost()
    /** The link to the PC is back. */
    fun back()
    /** The phone's network changed: the answer may have been missed, so it's asked for at once. */
    fun nudge() {}
}
