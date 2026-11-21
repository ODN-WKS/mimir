package io.github.odnwks.mimir.standalone

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.odnwks.mimir.link.Vault
import java.io.File

/**
 * Check build only: the phone gate's "kill the app mid-save" check (PHE-01, PHD-01). The real chat
 * store and the real pairing store, with the phone's real Keystore, in files of their own:
 *
 * `am broadcast -a io.github.odnwks.mimir.STANDALONE_ASK -n .../CheckAsk --es kill_test store|vault --es phase run|count|reset`
 *
 * `run` saves a longer list each time (one more chat, or one more waiting message) as fast as it can and logs
 * `kill_test <what> saved N` after each save returned, until the gate kills the app. `count` loads
 * what is there and logs how many it holds, whether it was unreadable, and the identity's fingerprint.
 * After every kill the count must be the last N logged, or one more (the save that was in flight).
 */
object CheckKill {
    private const val TAG = "MimirStandalone"
    private const val MAX = 400

    fun handle(context: Context, intent: Intent) {
        val what = intent.getStringExtra("kill_test") ?: return
        val dir = context.filesDir
        val file = File(dir, if (what == "vault") "killtest.vault" else "killtest.bin")
        when (intent.getStringExtra("phase") ?: "run") {
            "reset" -> {
                dir.listFiles()?.filter { it.name.startsWith(file.name) }?.forEach { it.delete() }
                Log.i(TAG, "kill_test $what reset")
            }
            "count" -> count(what, file)
            "size" -> sizes()
            else -> Thread { run(what, file) }.start()
        }
    }

    /** Whether the Keystore codecs seal and open texts of growing sizes (`--es kill_test store --es phase size`). */
    private fun sizes() {
        val codec = KeystoreCodec("mimir-killtest")
        for (kb in listOf(8, 32, 63, 64, 65, 66, 100, 128, 300, 1000)) {
            val text = "x".repeat(kb * 1024)
            val r = runCatching { codec.open(codec.seal(text)) == text }
            Log.i(TAG, "kill_test size ${kb} KB: " + r.fold({ if (it) "round trip ok" else "WRONG TEXT" }, { "FAILED ${it.javaClass.simpleName}" }))
        }
        Log.i(TAG, "kill_test size done")
    }

    private fun temps(file: File) = file.parentFile?.listFiles()?.count { it.name.startsWith(file.name + ".") && it.name.endsWith(".tmp") } ?: 0

    private fun count(what: String, file: File) {
        val tmps = temps(file)
        if (what == "vault") {
            val v = Vault(file)
            val d = v.load()
            val fp = d.key.fold(7) { a, b -> a * 31 + b }
            Log.i(TAG, "kill_test vault count=${d.outbox.size} unreadable=${v.unreadable} bad=${File(file.path + ".bad").exists()} key=$fp temps_before=$tmps")
        } else {
            val s = Store(file)
            val saved = s.load()
            Log.i(TAG, "kill_test store count=${saved.chats.size} unreadable=${s.unreadable} bad=${File(file.path + ".bad").exists()} temps_before=$tmps")
        }
    }

    private fun run(what: String, file: File) {
        if (what == "vault") {
            val v = Vault(file)
            var d = v.load()
            var k = d.outbox.size
            while (k < MAX) {
                k++
                d = d.copy(outbox = (1..k).map { "m-$it" to "a message waiting for the PC, number $it " + "words ".repeat(40) })
                v.save(d)
                if (!v.lastSaveOk) { Log.i(TAG, "kill_test vault save FAILED at $k"); return }
                Log.i(TAG, "kill_test vault saved $k")
            }
        } else {
            val s = Store(file)
            var k = s.load().chats.size
            while (k < MAX) {
                k++
                val chats = (1..k).map { i ->
                    LocalChat("c$i", Catalog.default.id, listOf(Turn(true, "question number $i " + "words ".repeat(30)), Turn(false, "answer number $i " + "words ".repeat(60))), at = i.toLong())
                }
                runCatching { s.save(Saved(Catalog.default.id, chats)) }.onFailure { Log.i(TAG, "kill_test store save FAILED at $k: ${it.message}"); return }
                Log.i(TAG, "kill_test store saved $k")
            }
        }
        Log.i(TAG, "kill_test $what reached $MAX")
    }
}
