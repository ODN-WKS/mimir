package io.github.odnwks.mimir.update

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONArray

/** The phone app's own update check: the same signed list the PC app reads, then the phone app's file. */
object UpdateKey {
    /** The release key's public half, the same one built into the PC app (crates/ui/src/release-key.pub). */
    const val HEX = "086dfc0ce8dfa47a40386bf299351af9b95fd50b98fd36f8205ce86581190142"
    const val MANIFEST_URL = "https://github.com/ODN-WKS/mimir/releases/latest/download/manifest.json"

    fun bytes(hex: String = HEX): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** "0.01" < "0.02" < "0.10"; three parts ("0.18.1") work too. */
object Versions {
    fun parse(v: String): Triple<Int, Int, Int>? {
        val p = v.trim().removePrefix("v").split('.')
        if (p.size !in 2..3) return null
        val n = p.map { it.toIntOrNull() ?: return null }
        return Triple(n[0], n[1], n.getOrElse(2) { 0 })
    }

    fun newer(candidate: String, current: String): Boolean {
        val c = parse(candidate) ?: return false
        val n = parse(current) ?: return false
        return compareValuesBy(c, n, { it.first }, { it.second }, { it.third }) > 0
    }
}

/** What the signed list says about the newest release. */
data class Release(val version: String, val apkUrl: String, val apkSha256: String, val notes: String)

/** What a check found. */
sealed interface Found {
    data object Newest : Found
    data class Newer(val release: Release) : Found
    /** Something didn't check out or couldn't be reached; [words] is plain and ready to show. */
    data class Failed(val words: String, val notSigned: Boolean = false) : Found
}

fun interface Net {
    /** Opens [url] for reading (a plain GET, nothing attached). */
    fun open(url: String): InputStream
}

object UpdateWords {
    const val DIDNT_CHECK_OUT = "The update didn't check out, so it wasn't installed."
    const val COULDNT_CHECK = "Couldn't check just now."
}

/** The once-a-day and mobile-data rules. */
object UpdatePolicy {
    const val DAY_MS = 24L * 60 * 60 * 1000

    fun shouldCheck(now: Long, last: Long, enabled: Boolean, metered: Boolean, mobileOk: Boolean, force: Boolean): Boolean {
        if (force) return true
        if (!enabled) return false
        if (metered && !mobileOk) return false
        return last == 0L || now - last >= DAY_MS || now < last
    }
}

class Updater(
    private val net: Net,
    private val publicKey: ByteArray = UpdateKey.bytes(),
    private val manifestUrl: String = UpdateKey.MANIFEST_URL,
) {
    private fun read(url: String, cap: Int): ByteArray = net.open(url).use { i ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > cap) throw java.io.IOException("too big")
        }
        out.toByteArray()
    }

    /** Fetches the signed list, trusts it only if the signature matches, and compares versions. */
    fun check(current: String): Found {
        val body: ByteArray
        val sig: ByteArray
        try {
            body = read(manifestUrl, 1 shl 20)
        } catch (e: Exception) {
            return Found.Failed(UpdateWords.COULDNT_CHECK)
        }
        // A list with no signature next to it is never trusted.
        try {
            sig = read("$manifestUrl.sig", 1024)
        } catch (e: Exception) {
            return Found.Failed(UpdateWords.DIDNT_CHECK_OUT, true)
        }
        val rel = try { parse(body, sig) } catch (e: Exception) { return Found.Failed(UpdateWords.DIDNT_CHECK_OUT, true) }
            ?: return Found.Newest
        return if (Versions.newer(rel.version, current)) Found.Newer(rel) else Found.Newest
    }

    /** Verifies and reads the newest release; null when the list has none. Throws when the signature is wrong. */
    fun parse(body: ByteArray, sig: ByteArray): Release? {
        val arr = JSONArray(String(body, Charsets.UTF_8))
        var best: Pair<Triple<Int, Int, Int>, org.json.JSONObject>? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val v = Versions.parse(o.optString("tagName")) ?: continue
            if (best == null || compareValuesBy(v, best.first, { it.first }, { it.second }, { it.third }) > 0) best = v to o
        }
        val (_, o) = best ?: return null
        val version = o.getString("tagName").removePrefix("v")
        // The version is part of what is signed, so an old list can't pass as a new one.
        val msg = "mimir $version\n".toByteArray() + MessageDigest.getInstance("SHA-256").digest(body)
        require(Ed25519.verify(publicKey, msg, sig)) { "signature" }
        val url = o.optString("apkUrl")
        val sha = o.optString("apkSha256").lowercase()
        // A release made before phone files were listed has nothing for the phone.
        if (url.isEmpty() && sha.isEmpty()) return null
        require(url.startsWith("http") && sha.length == 64) { "no phone file" }
        return Release(version, url, sha, o.optString("notes").trim())
    }

    /**
     * Downloads the phone file into [dir] (the only copy kept) and checks it: its SHA-256 against the
     * signed list, and its signer against the installed app's ([signerOk]). Anything off: the file is
     * deleted and null returned.
     */
    fun download(release: Release, dir: File, signerOk: (File) -> Boolean): File? {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "mimir-${release.version}.apk")
        val part = File(dir, "download.part")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            net.open(release.apkUrl).use { i ->
                part.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n); o.write(buf, 0, n)
                        total += n
                        if (total > 400L shl 20) throw java.io.IOException("too big")
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != release.apkSha256 || !part.renameTo(file) || !signerOk(file)) throw SecurityException("mismatch")
            return file
        } catch (e: Exception) {
            part.delete(); file.delete()
            return null
        }
    }
}
