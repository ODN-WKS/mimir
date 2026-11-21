package io.github.odnwks.mimir.labs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scrub function against nasty lines. The PC's Rust copy (mimir_core::scrub) has the same cases. */
class ScrubTest {
    private fun gone(line: String, vararg secrets: String, names: List<String> = emptyList()) {
        val out = Scrub.line(line, names)
        secrets.forEach { assertFalse("'$it' still in: $out", out.contains(it)) }
    }

    private fun kept(line: String, vararg words: String) {
        val out = Scrub.line(line)
        words.forEach { assertTrue("'$it' missing from: $out", out.contains(it)) }
    }

    @Test fun emailsGo() = gone("sent to jane.doe+work@example.co.uk now", "jane", "example.co.uk")
    @Test fun emailInQuotes() = gone("owner=\"bob_smith@mail.example.com\"", "bob_smith")
    @Test fun ipv4Goes() = gone("connect to 10.20.30.40:5555 failed", "10.20.30.40")
    @Test fun ipv4InUrl() = gone("GET http://10.0.0.5:8080/api", "10.0.0.5")
    @Test fun ipv6Goes() = gone("peer 2001:0db8:85a3:0000:0000:8a2e:0370:7334 up", "2001:0db8")
    @Test fun ipv6ShortGoes() = gone("peer fe80::1ff:fe23:4567:890a up", "fe80")
    @Test fun ipv6LoopbackGoes() = gone("listening on ::1 port 5", "::1")
    @Test fun macGoes() = gone("wifi mac 3C:5A:B4:12:9F:01 joined", "3C:5A:B4")
    @Test fun phoneInternational() = gone("call +44 7700 900123 failed", "7700 900123")
    @Test fun phoneUs() = gone("number (555) 123-4567 saved", "123-4567")
    @Test fun phonePlain() = gone("number 07700900123 saved", "07700900123")
    @Test fun urlQueryGoes() = gone("opened https://example.com/search?q=my+secret+thing&user=bob#top", "secret", "bob", "#top")
    @Test fun urlKeepsHostAndPath() = kept("opened https://example.com/wiki/Moon?x=1", "https://example.com/wiki/Moon")
    @Test fun urlUserInfoGoes() = gone("https://alice:hunter2@example.com/x", "alice", "hunter2")
    @Test fun homePathGoes() = gone("wrote /home/alex/Projects/Garden planner/notes.txt ok", "alex", "Garden")
    @Test fun storagePathGoes() = gone("read /storage/emulated/0/Download/taxes.pdf", "taxes", "emulated")
    @Test fun dataPathGoes() = gone("open /data/user/0/io.github.odnwks.mimir.dev/files/link.vault", "link.vault")
    @Test fun sdcardPathGoes() = gone("saved to /sdcard/DCIM/me.jpg", "DCIM")
    @Test fun windowsPathGoes() = gone("opened C:\\Users\\Someone\\Documents\\a.txt", "Someone")
    @Test fun fileUrlGoes() = gone("opened file:///home/alex/x.txt", "alex")
    @Test fun skKeyGoes() = gone("key sk-ant-api03-AbCdEfGhIjKlMnOpQrStUv failed", "AbCdEf")
    @Test fun groqKeyGoes() = gone("using gsk_9aB8cD7eF6gH5iJ4kL3m", "9aB8cD")
    @Test fun githubTokenGoes() = gone("ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789", "abcdefghij")
    @Test fun bearerGoes() = gone("Authorization: Bearer abc123.def456.ghi789", "abc123")
    @Test fun apiKeyPairGoes() = gone("api_key=Zx81QpLm0912 more", "Zx81QpLm0912")
    @Test fun passwordPairGoes() = gone("password: correct-horse-battery", "correct-horse")
    @Test fun longHexGoes() = gone("hash 3f786850e387550fdab836ed7e6dc881de23001b done", "3f786850e387")
    @Test fun longBase64Goes() = gone("blob dGhpcyBpcyBhIHNlY3JldCB2YWx1ZSAxMjM0NTY3OA== end", "dGhpcyBpcyBh")
    @Test fun serialGoes() = gone("serial=4c1f09ab7d3e2658a1 attached", "4c1f09ab7d3e")
    @Test fun bareSerialLikeHexGoes() = gone("device 4c1f09ab7d3e2658a1 attached", "4c1f09ab7d3e")
    @Test fun imeiGoes() = gone("IMEI: 356938035643809", "356938035643809")
    @Test fun androidIdGoes() = gone("android_id=9774d56d682e549c", "9774d56d")
    @Test fun uuidGoes() = gone("chat 123e4567-e89b-12d3-a456-426614174000 saved", "123e4567")
    @Test fun chatLineGoes() = assertEquals("CHAT ${Scrub.MESSAGE}", Scrub.line("CHAT abc user 2026-11-21 10:00:00 my private diary"))
    @Test fun textPairGoes() = gone("sending text=\"buy a surprise gift for Sam\" now", "surprise", "Sam")
    @Test fun messagePairRestOfLine() = gone("send message: what is my bank pin 1234", "bank pin")
    @Test fun jsonTextGoes() = gone("""{"text":"my secret plan","id":7}""", "secret plan")
    @Test fun jsonTitleGoes() = gone("""{"title":"Tax return 2025","id":7}""", "Tax return")
    @Test fun titlePairGoes() = gone("chat title=Holiday plans saved", "Holiday")
    @Test fun pcNameByPairGoes() = gone("pc_name=OFFICE-PC online", "OFFICE")
    @Test fun hostnameGoes() = gone("hostname: mypc.local up", "mypc")
    @Test fun ssidGoes() = gone("joined ssid=\"Home Wifi 5G\"", "Home Wifi")
    @Test fun placeGoes() = gone("weather lat=51.5072 lon=-0.1276", "51.5072", "0.1276")
    @Test fun givenNamesGo() = gone("Opened Garden planner for Rowan on Rowan's PC", "Garden planner", "Rowan", names = listOf("Garden planner", "Rowan"))
    @Test fun givenNamesAnyCase() = gone("garden PLANNER opened", "garden", names = listOf("Garden planner"))
    @Test fun shortNamesIgnored() = assertEquals("a b c", Scrub.line("a b c", listOf("a")))

    // What must survive: the report is useless if everything is cut.
    @Test fun timestampSurvives() = kept("11-21 12:34:56.789  1234  5678 E MimirLookup: lookup timed out", "11-21 12:34:56.789", "lookup timed out")
    @Test fun versionSurvives() = kept("app 0.01.2 (dev) built 2026-11-21", "0.01.2", "2026-11-21")
    @Test fun errorWordsSurvive() = kept("E/Net: connection refused after 3 tries", "connection refused", "3 tries")
    @Test fun epochMillisSurvives() = kept("at 1763712000000 stalled", "1763712000000")
    @Test fun classNamesSurvive() = kept("io.github.odnwks.mimir.link.Wire failed", "io.github.odnwks.mimir.link.Wire")
    @Test fun skippedFramesSurvive() = kept("Choreographer: Skipped 45 frames!", "Skipped 45 frames")

    @Test fun linesDropChatAndBlankAndKeepNewest50() {
        val raw = (1..80).map { "line $it error" } + listOf("", "CHAT x user t secret words")
        val out = Scrub.lines(raw)
        assertEquals(50, out.size)
        assertEquals("line 80 error", out.last())
        assertTrue(out.none { it.contains("secret words") })
    }

    @Test fun linesAreCut() = assertTrue(Scrub.lines(listOf("x".repeat(2000))).single().length <= 400)
}
