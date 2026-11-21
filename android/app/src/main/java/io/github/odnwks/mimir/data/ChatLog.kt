package io.github.odnwks.mimir.data

import io.github.odnwks.mimir.link.Filed
import org.json.JSONArray
import org.json.JSONObject

/**
 * The general chat as it is kept on the phone (inside the encrypted [io.github.odnwks.mimir.link.Vault]),
 * so closing the app doesn't lose it. Each of your messages keeps where the PC filed it, so
 * "Move it", "Undo" and the Inbox's project buttons still work after the app comes back.
 * Incognito's note is never kept.
 */
object ChatLog {
    fun write(chat: List<ChatEntry>, filed: Map<String, Filed>): String {
        val a = JSONArray()
        for (e in chat) {
            val o = JSONObject().put("id", e.id)
            when (e) {
                is ChatEntry.Mine -> {
                    o.put("kind", "mine").put("text", e.text)
                    e.filing?.let { f ->
                        o.put("filing", JSONObject().put("kind", f.kind.name).put("where", f.where).put("choices", JSONArray(f.choices)).put("task", f.taskId ?: ""))
                    }
                    filed[e.id]?.let { o.put("filed", filedJson(it)) }
                }
                is ChatEntry.Reply -> o.put("kind", "reply").put("paragraphs", JSONArray(e.paragraphs)).put("play", e.playLabel)
                // A problem is about the moment it happened: it isn't kept, so none stays stuck.
                is ChatEntry.Note -> if (e.problem) continue else o.put("kind", "note").put("text", e.text)
                is ChatEntry.IncognitoRunning -> continue
            }
            a.put(o)
        }
        return a.toString()
    }

    /** The kept chat and where each of its messages was filed; an unreadable log is an empty chat. */
    fun read(json: String): Pair<List<ChatEntry>, Map<String, Filed>> = runCatching {
        val a = JSONArray(json)
        val chat = ArrayList<ChatEntry>()
        val filed = HashMap<String, Filed>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val id = o.getString("id")
            when (o.optString("kind")) {
                "mine" -> {
                    val f = o.optJSONObject("filing")?.let {
                        Filing(FilingKind.valueOf(it.getString("kind")), it.optString("where"), strings(it.optJSONArray("choices")), it.optString("task").ifEmpty { null })
                    }
                    chat += ChatEntry.Mine(id, o.getString("text"), f)
                    o.optJSONObject("filed")?.let { filedOf(it) }?.let { filed[id] = it }
                }
                "reply" -> chat += ChatEntry.Reply(id, strings(o.optJSONArray("paragraphs")), o.optString("play"))
                // Problems kept by an older version are dropped: they were stale.
                "note" -> if (!o.optBoolean("problem")) chat += ChatEntry.Note(id, o.getString("text"))
            }
        }
        chat to filed
    }.getOrDefault(emptyList<ChatEntry>() to emptyMap())

    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

    private fun filedJson(f: Filed): JSONObject = when (f) {
        is Filed.NewTask -> JSONObject().put("kind", "new").put("project", f.project).put("task", f.task)
        is Filed.SameTask -> JSONObject().put("kind", "same").put("project", f.project).put("task", f.task)
        is Filed.Idea -> JSONObject().put("kind", "idea").put("project", f.project).put("idea", f.idea)
        is Filed.Status -> JSONObject().put("kind", "status").put("reply", JSONArray(f.reply))
        is Filed.Inbox -> JSONObject().put("kind", "inbox").put("inbox_id", f.inboxId).put("candidates", JSONArray(f.candidates))
        // A chat answer lives in the chat itself, not as a filing.
        is Filed.Talk, is Filed.OnPhone -> JSONObject().put("kind", "talk")
    }

    private fun filedOf(o: JSONObject): Filed? = when (o.optString("kind")) {
        "new" -> Filed.NewTask(o.getString("project"), o.getString("task"))
        "same" -> Filed.SameTask(o.getString("project"), o.getString("task"))
        "idea" -> Filed.Idea(o.getString("project"), o.getString("idea"))
        "status" -> Filed.Status(strings(o.optJSONArray("reply")))
        "inbox" -> Filed.Inbox(o.getString("inbox_id"), strings(o.optJSONArray("candidates")))
        else -> null
    }
}
