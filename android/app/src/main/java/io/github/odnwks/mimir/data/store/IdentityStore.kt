package io.github.odnwks.mimir.data.store

import android.content.Context
import io.github.odnwks.mimir.link.SafeFile
import io.github.odnwks.mimir.standalone.KeystoreCodec
import org.json.JSONObject
import java.io.File

class IdentityStore(private val file: File, private val codec: Codec) {

    constructor(context: Context) : this(
        File(context.filesDir, "identity.bin"),
        object : Codec {
            private val inner = KeystoreCodec("mimir-identity")
            override fun seal(text: String) = inner.seal(text)
            override fun open(bytes: ByteArray) = inner.open(bytes)
        }
    )

    interface Codec {
        fun seal(text: String): ByteArray
        fun open(bytes: ByteArray): String
    }

    @Volatile var repairNeeded: Boolean = false
        private set

    fun load(): Identity? {
        SafeFile.sweep(file)
        if (!file.exists()) return null
        var last: Throwable? = null
        for (attempt in 1..2) {
            val got = runCatching {
                val text = codec.open(file.readBytes())
                val o = JSONObject(text)
                Identity(
                    key = java.util.Base64.getDecoder().decode(o.getString("key")),
                    pcId = o.optString("pc_id").ifEmpty { null },
                    pcName = o.optString("pc_name").ifEmpty { null },
                )
            }
            got.getOrNull()?.let { return it }
            last = got.exceptionOrNull()
        }
        if (SafeFile.isDamage(last)) {
            SafeFile.setAside(file)
            repairNeeded = true
        }
        return null
    }

    fun save(identity: Identity) {
        val o = JSONObject()
            .put("key", java.util.Base64.getEncoder().encodeToString(identity.key))
            .apply { identity.pcId?.let { put("pc_id", it) } }
            .apply { identity.pcName?.let { put("pc_name", it) } }
        SafeFile.write(file, codec.seal(o.toString()))
    }
}
