package io.github.odnwks.mimir.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** R27: the phone app's own updates: versions, the signed list, the file's three checks, the day and data rules. */
@RunWith(RobolectricTestRunner::class)
class R27UpdateTest {
    @get:Rule val tmp = TemporaryFolder()

    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val pub = keys.public.encoded.takeLast(32).toByteArray()

    private fun sign(k: java.security.KeyPair, version: String, body: ByteArray): ByteArray {
        val s = Signature.getInstance("Ed25519"); s.initSign(k.private)
        s.update("mimir $version\n".toByteArray() + MessageDigest.getInstance("SHA-256").digest(body))
        return s.sign()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val apk = "pretend phone app".toByteArray()

    private fun list(version: String = "0.03", apkSha: String = sha(apk), notes: String = "Faster start.") =
        """[{"tagName":"v$version","file":"Mimir-$version.flatpak","sha256":"x","url":"u","sigUrl":"s","apkUrl":"https://h/mimir-$version.apk","apkSha256":"$apkSha","notes":"$notes"},{"tagName":"v0.01"}]""".toByteArray()

    private fun net(files: Map<String, ByteArray>) = Net { url -> ByteArrayInputStream(files[url] ?: throw IOException("404")) }

    private val url = "https://h/manifest.json"
    private fun updater(files: Map<String, ByteArray>) = Updater(net(files), pub, url)

    @Test fun versionsCompareAsTwoParts() {
        assertTrue(Versions.newer("0.02", "0.01"))
        assertTrue(Versions.newer("0.10", "0.02"))
        assertFalse(Versions.newer("0.02", "0.10"))
        assertFalse(Versions.newer("0.02", "0.02"))
        assertTrue(Versions.newer("0.02.1", "0.02"))
        assertFalse(Versions.newer("junk", "0.02"))
    }

    @Test fun theBuiltInCheckerAgreesWithTheStandardTestVector() {
        // RFC 8032, test 1 (empty message).
        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val pk = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(Ed25519.verify(pk, ByteArray(0), sig))
        assertFalse(Ed25519.verify(pk, "x".toByteArray(), sig))
        val bad = sig.copyOf(); bad[10] = (bad[10].toInt() xor 1).toByte()
        assertFalse(Ed25519.verify(pk, ByteArray(0), bad))
    }

    @Test fun aGoodSignatureIsTrustedAndANewerVersionFound() {
        val body = list()
        val f = updater(mapOf(url to body, "$url.sig" to sign(keys, "0.03", body))).check("0.02")
        assertTrue(f is Found.Newer)
        val r = (f as Found.Newer).release
        assertEquals("0.03", r.version)
        assertEquals("Faster start.", r.notes)
        assertTrue(updater(mapOf(url to body, "$url.sig" to sign(keys, "0.03", body))).check("0.03") is Found.Newest)
    }

    @Test fun aBadSignatureIsRefused() {
        val body = list()
        val wrongKey = updater(mapOf(url to body, "$url.sig" to sign(other, "0.03", body))).check("0.02")
        assertTrue(wrongKey is Found.Failed)
        assertEquals(UpdateWords.DIDNT_CHECK_OUT, (wrongKey as Found.Failed).words)
        // An old signature on a changed list, and a list signed for another version, both fail.
        val sig = sign(keys, "0.03", body)
        assertTrue(updater(mapOf(url to list(notes = "Changed."), "$url.sig" to sig)).check("0.02") is Found.Failed)
        assertTrue(updater(mapOf(url to body, "$url.sig" to sign(keys, "0.04", body))).check("0.02") is Found.Failed)
    }

    @Test fun anUnsignedListIsNotTrusted() {
        val f = updater(mapOf(url to list())).check("0.02")
        assertTrue(f is Found.Failed && f.notSigned)
    }

    @Test fun noConnectionIsSaidPlainly() {
        assertEquals(UpdateWords.COULDNT_CHECK, (updater(emptyMap()).check("0.02") as Found.Failed).words)
    }

    private val release = Release("0.03", "https://h/mimir-0.03.apk", sha(apk), "")

    @Test fun aGoodFileIsKeptAlone() {
        val dir = tmp.newFolder("d")
        File(dir, "old.apk").writeText("old")
        val f = updater(mapOf(release.apkUrl to apk)).download(release, dir) { true }
        assertNotNull(f)
        assertEquals(listOf("mimir-0.03.apk"), dir.list()!!.toList())
    }

    @Test fun aWrongHashDeletesTheFile() {
        val dir = tmp.newFolder("d")
        val f = updater(mapOf(release.apkUrl to "tampered".toByteArray())).download(release, dir) { true }
        assertNull(f)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun aWrongSignerDeletesTheFile() {
        val dir = tmp.newFolder("d")
        val f = updater(mapOf(release.apkUrl to apk)).download(release, dir) { false }
        assertNull(f)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun aBrokenDownloadLeavesNothing() {
        val dir = tmp.newFolder("d")
        assertNull(updater(emptyMap()).download(release, dir) { true })
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun signersMustMatchExactly() {
        val a = listOf(byteArrayOf(1, 2, 3)); val b = listOf(byteArrayOf(1, 2, 3)); val c = listOf(byteArrayOf(9))
        assertTrue(AppUpdater.sameSigners(a, b))
        assertFalse(AppUpdater.sameSigners(a, c))
        assertFalse(AppUpdater.sameSigners(emptyList(), emptyList()))
        assertFalse(AppUpdater.sameSigners(a, a + c))
    }

    @Test fun onceADayAndNotOnMobileDataUnlessAsked() {
        val day = UpdatePolicy.DAY_MS
        fun ok(now: Long, last: Long, enabled: Boolean = true, metered: Boolean = false, mobile: Boolean = false, force: Boolean = false) =
            UpdatePolicy.shouldCheck(now, last, enabled, metered, mobile, force)
        assertTrue(ok(1000, 0))
        assertFalse(ok(day - 1 + 5, 5))
        assertTrue(ok(day + 5, 5))
        assertFalse(ok(day * 3, 5, enabled = false))
        assertFalse(ok(day * 3, 5, metered = true))
        assertTrue(ok(day * 3, 5, metered = true, mobile = true))
        assertTrue("Check now ignores the day rule", ok(10, 5, force = true))
        assertTrue("a clock set back doesn't block checks", ok(5, 1000))
    }
}
