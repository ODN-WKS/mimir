package io.github.odnwks.mimir.data.store

import android.content.Context
import io.github.odnwks.mimir.link.SafeFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ModelLedger(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "model_ledger.json"))

    private val lock = ReentrantLock()
    @Volatile private var chosenId: ModelId? = null
    private val ownedIds = mutableSetOf<ModelId>()
    var guestMode: Boolean = false

    fun recordDownload(id: ModelId) = lock.withLock {
        ownedIds.add(id)
        persist()
    }

    fun recordDelete(id: ModelId) = lock.withLock {
        if (guestMode) return@withLock
        if (id == chosenId) return@withLock
        if (!ownedIds.contains(id)) return@withLock
        ownedIds.remove(id)
        persist()
    }

    fun setChosen(id: ModelId) = lock.withLock {
        chosenId = id
        persist()
    }

    fun chosen(): ModelId? = chosenId

    fun mirimOwns(id: ModelId): Boolean = lock.withLock { ownedIds.contains(id) }

    private fun load() = lock.withLock {
        if (!file.exists()) return@withLock
        runCatching {
            val o = JSONObject(file.readText())
            chosenId = o.optString("chosen").ifEmpty { null }
            val arr = o.optJSONArray("owned") ?: JSONArray()
            ownedIds.clear()
            for (i in 0 until arr.length()) ownedIds.add(arr.getString(i))
        }
    }

    private fun persist() {
        val o = JSONObject()
        chosenId?.let { o.put("chosen", it) }
        o.put("owned", JSONArray(ownedIds.toList()))
        runCatching { file.parentFile?.mkdirs(); file.writeText(o.toString()) }
    }

    init { load() }
}
