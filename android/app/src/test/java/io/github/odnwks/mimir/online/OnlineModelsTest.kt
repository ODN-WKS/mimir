package io.github.odnwks.mimir.online

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.odnwks.mimir.devlog.DevLog
import io.github.odnwks.mimir.ui.cleanKey
import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** Free online models: the list, the best free one, talking to a fake service, the key vault, and that the key never leaks. */
@RunWith(RobolectricTestRunner::class)
class OnlineModelsTest {
    private val key = "sk-or-v1-ABCDEFGHIJKLMNOP1234567890wxyz"
    private lateinit var server: HttpServer
    private val seen = mutableListOf<Pair<String, String>>()
    /** What the fake service does for a path and method: status and body (an SSE body for a chat). */
    private var route: (String, String, String) -> Pair<Int, String> = { _, _, _ -> 404 to "" }
    private val base get() = "http://127.0.0.1:${server.address.port}/v1"

    @Before fun up() {
        ShadowLog.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += (ex.requestMethod + " " + ex.requestURI.path + " " + (ex.requestHeaders.getFirst("Authorization") ?: "")) to body
            val (status, out) = route(ex.requestMethod, ex.requestURI.path, body)
            val bytes = out.toByteArray()
            ex.responseHeaders.add("Content-Type", if (out.startsWith("data:")) "text/event-stream" else "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        OnlineModels.guard = { }
    }

    @After fun down() { server.stop(0); OnlineModels.guard = Net::answerGuard; OnlineModels.transport = OnlineModels.transport; OnlineModels.init(java.nio.file.Files.createTempDirectory("vault").toFile(), PlainSealer()) } // no key left behind for later tests

    private val openRouterList = """{"data":[
        {"id":"meta-llama/llama-3.3-70b-instruct:free","pricing":{"prompt":"0","completion":"0"}},
        {"id":"google/gemma-3-27b-it:free","pricing":{"prompt":"0","completion":"0"}},
        {"id":"vendor/paid-model","pricing":{"prompt":"0.000002","completion":"0.00001"}},
        {"id":"vendor/priced-zero","pricing":{"prompt":"0","completion":"0"}},
        {"id":"vendor/picture-maker:free","pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["image"]}}]}"""

    private fun sse(vararg pieces: String) = pieces.joinToString("") { """data: {"choices":[{"delta":{"content":${org.json.JSONObject.quote(it)}}}]}""" + "\n\n" } + "data: [DONE]\n\n"

    private fun setup(model: String? = "meta-llama/llama-3.3-70b-instruct:free", auto: Boolean = true) = Setup("openrouter", "OpenRouter", base, key, model, auto)
    private val ask = listOf("system" to "Be kind.", "user" to "Hello")

    // ---------- Which models ----------

    @Test fun openRouterListsOnlyFreeChatModels() {
        val ids = OnlineModels.usable("openrouter", openRouterList)
        assertEquals(listOf("meta-llama/llama-3.3-70b-instruct:free", "google/gemma-3-27b-it:free", "vendor/priced-zero"), ids)
    }

    @Test fun groqListsEveryActiveChatModelButNotSpeechOrGuards() {
        val ids = OnlineModels.usable("groq", """{"data":[{"id":"llama-3.3-70b-versatile","active":true},{"id":"whisper-large-v3","active":true},
            {"id":"llama-guard-4-12b","active":true},{"id":"gone-model","active":false},{"id":"openai/gpt-oss-20b","active":true}]}""")
        assertEquals(listOf("llama-3.3-70b-versatile", "openai/gpt-oss-20b"), ids)
    }

    @Test fun theBestFreeOneIsTheBiggestThenTheNewest() {
        val ids = listOf("small-8b-instruct:free", "llama-3.1-70b-instruct:free", "llama-3.3-70b-instruct:free", "gpt-oss-120b:free", "unsized-chat:free")
        assertEquals(listOf("gpt-oss-120b:free", "llama-3.3-70b-instruct:free", "llama-3.1-70b-instruct:free", "small-8b-instruct:free", "unsized-chat:free"), OnlineModels.ranked(ids))
        assertEquals("llama-3.1-70b-instruct:free", OnlineModels.best(ids, setOf("gpt-oss-120b:free", "llama-3.3-70b-instruct:free")))
        assertNull(OnlineModels.best(emptyList()))
    }

    // ---------- Testing a pasted key ----------

    @Test fun aKeyThatWorksListsTheFreeModelsAndSendsTheKeyOnlyAsABearer() {
        route = { _, _, _ -> 200 to openRouterList }
        val r = OnlineModels.test(Service("openrouter", "OpenRouter", "OpenRouter", base, null), key)
        assertEquals(3, (r as KeyTest.Works).models.size)
        assertEquals(listOf("GET /v1/key Bearer $key", "GET /v1/models Bearer $key"), seen.map { it.first })
        assertTrue(seen.all { it.second.isEmpty() })
    }

    @Test fun openRouterListsItsModelsToAnyoneSoAFakeKeyIsCaughtByItsKeyCheck() {
        route = { _, path, _ -> if (path.endsWith("/key")) 401 to "{}" else 200 to openRouterList }
        assertEquals(KeyTest.BadKey, OnlineModels.test(Service("openrouter", "OpenRouter", "OpenRouter", base, null), key))
    }

    @Test fun aWrongKeyIsSaidPlainly() {
        route = { _, _, _ -> 401 to """{"error":{"message":"No auth"}}""" }
        assertEquals(KeyTest.BadKey, OnlineModels.test(Service("groq", "Groq", "Groq", base, null), key))
        assertEquals("That key didn't work. Copy it again from their website.", OnlineModels.BAD_KEY)
    }

    @Test fun aServiceThatCantBeReachedIsAway() {
        val dead = Service("groq", "Groq", "Groq", "http://127.0.0.1:1/v1", null)
        assertEquals(KeyTest.Away, OnlineModels.test(dead, key))
        assertEquals("Couldn't reach Groq just now. Check the connection and try again.", OnlineModels.away("Groq"))
    }

    @Test fun aKeyThatWorksButHasNothingFreeSaysSo() {
        route = { _, _, _ -> 200 to """{"data":[{"id":"x/paid","pricing":{"prompt":"1","completion":"1"}}]}""" }
        assertTrue(OnlineModels.test(Service("openrouter", "OpenRouter", "OpenRouter", base, null), key) is KeyTest.Other)
    }

    @Test fun theRealGuardOnlyLetsTheSetUpHostsHaveASecureConnection() {
        OnlineModels.guard = Net::answerGuard
        Net.answerGuard(URL("https://openrouter.ai/api/v1/models"))
        Net.answerGuard(URL("https://api.groq.com/openai/v1/chat/completions"))
        for (bad in listOf("http://openrouter.ai/api/v1", "https://evil.example/v1", "https://10.0.0.5/v1")) {
            try { Net.answerGuard(URL(bad)); error("$bad should be refused") } catch (e: Net.Refused) { }
        }
        // The owner's own service is allowed once set up, and only if it is secure and not on the home network.
        Net.allowAnswerHost("router.example.org")
        Net.allowAnswerHost("10.0.0.9")
        try { Net.answerGuard(URL("https://10.0.0.9/v1")); error("home network") } catch (e: Net.Refused) { }
        try { Net.answerGuard(URL("http://router.example.org/v1")); error("not secure") } catch (e: Net.Refused) { }
        // Shared state: leave it as found, so other tests (R12OnlineTest) see these hosts as not set up.
        Net.forgetAnswerHost("router.example.org"); Net.forgetAnswerHost("10.0.0.9")
    }

    // ---------- Chatting ----------

    @Test fun aReplyStreamsInAndOnlyTheChatIsSent() {
        route = { _, _, _ -> 200 to sse("Rain", "bows ", "form.") }
        val got = mutableListOf<String>()
        val a = OnlineModels.chat(setup(), ask, { false }) { got += it }
        assertEquals("Rainbows form.", a.text)
        assertEquals(listOf("Rain", "Rainbows ", "Rainbows form."), got)
        val (head, body) = seen.single()
        assertEquals("POST /v1/chat/completions Bearer $key", head)
        val o = org.json.JSONObject(body)
        assertEquals("meta-llama/llama-3.3-70b-instruct:free", o.getString("model"))
        assertEquals(2, o.getJSONArray("messages").length())
        assertEquals(setOf("model", "stream", "messages"), o.keys().asSequence().toSet())
    }

    @Test fun aServiceThatIgnoresStreamingStillAnswers() {
        route = { _, _, _ -> 200 to """{"choices":[{"message":{"content":"All at once."}}]}""" }
        assertEquals("All at once.", OnlineModels.chat(setup(), ask, { false }) { }.text)
    }

    @Test fun aModelThatIsGoneIsPickedAgainOnceAndRetried() {
        OnlineModels.init(Files.createTempDirectory("vault").toFile(), PlainSealer())
        OnlineModels.save(setup())
        var chats = 0
        route = { m, path, body ->
            when {
                path.endsWith("/models") -> 200 to openRouterList
                else -> if (org.json.JSONObject(body).getString("model").contains("llama-3.3")) { chats++; 404 to """{"error":{"message":"No endpoints found for model"}}""" } else { chats++; 200 to sse("Hi.") }
            }
        }
        val a = OnlineModels.chat(setup(), ask, { false }) { }
        assertEquals("Hi.", a.text)
        assertEquals("google/gemma-3-27b-it:free", a.model)
        assertEquals(2, chats)
        // The new pick is remembered.
        assertEquals("google/gemma-3-27b-it:free", OnlineModels.setup("openrouter")?.model)
    }

    @Test fun aChosenModelThatIsGoneIsNotSwappedBehindTheOwnersBack() {
        route = { _, _, _ -> 404 to "" }
        try { OnlineModels.chat(setup(auto = false), ask, { false }) { }; error("should fail") } catch (e: Sorry) { assertEquals(OnlineModels.gone(), e.words) }
        assertEquals(1, seen.size)
    }

    @Test fun aGoneModelIsOnlyRetriedOnce() {
        OnlineModels.init(Files.createTempDirectory("vault").toFile(), PlainSealer())
        route = { _, path, _ -> if (path.endsWith("/models")) 200 to openRouterList else 404 to "" }
        try { OnlineModels.chat(setup(), ask, { false }) { }; error("should fail") } catch (e: Sorry) { assertEquals(OnlineModels.gone(), e.words) }
        assertEquals(2, seen.count { it.first.startsWith("POST") })
    }

    @Test fun problemsAreSaidInPlainWords() {
        route = { _, _, _ -> 401 to "" }
        assertEquals(OnlineModels.BAD_KEY, runCatching { OnlineModels.chat(setup(), ask, { false }) { } }.exceptionOrNull()?.message)
        route = { _, _, _ -> 429 to "" }
        assertEquals("OpenRouter says today's free limit is used up. Try again later, or pick another answerer.", runCatching { OnlineModels.chat(setup(), ask, { false }) { } }.exceptionOrNull()?.message)
        route = { _, _, _ -> 500 to "" }
        assertEquals("OpenRouter had a problem (it answered 500).", runCatching { OnlineModels.chat(setup(), ask, { false }) { } }.exceptionOrNull()?.message)
        server.stop(0)
        assertEquals("Couldn't reach OpenRouter just now. Check the connection and try again.", runCatching { OnlineModels.chat(setup(), ask, { false }) { } }.exceptionOrNull()?.message)
    }

    // ---------- The key: kept locked, never shown or logged ----------

    @Test fun aKeyShowsOnlyItsFirstThreeAndLastFourCharacters() {
        val m = KeyVault.mask(key)
        assertTrue(m.startsWith("sk-") && m.endsWith("wxyz"))
        assertFalse(m.contains("ABCDEFGHIJ"))
        assertEquals("sk-••••••wxyz", m)
        assertFalse(KeyVault.mask("short").contains("short"))
        assertFalse(setup().toString().contains(key))
        assertEquals(key, cleanKey("  Bearer $key\n"))
    }

    @Test fun theVaultKeepsAKeyLockedAndGivesItBack() {
        val dir = Files.createTempDirectory("vault").toFile()
        val v = KeyVault(File(dir, "online-keys.bin"), PlainSealer())
        v.put(setup())
        v.put(Setup("groq", "Groq", "https://api.groq.com/openai/v1", "gsk_secretsecretsecret", null, true))
        assertEquals(key, KeyVault(File(dir, "online-keys.bin"), PlainSealer()).get("openrouter")?.key)
        // What is on the disk doesn't hold the key in the clear, and the wrong lock opens nothing.
        assertFalse(File(dir, "online-keys.bin").readText(Charsets.ISO_8859_1).contains("gsk_secret"))
        assertTrue(KeyVault(File(dir, "online-keys.bin"), PlainSealer(0x11)).all().isEmpty())
        v.remove("openrouter")
        assertEquals(listOf("groq"), v.all().map { it.id })
        v.remove("groq")
        assertFalse(File(dir, "online-keys.bin").exists())
    }

    @Test fun removingTheKeyStopsItAnswering() {
        OnlineModels.init(Files.createTempDirectory("vault").toFile(), PlainSealer())
        OnlineModels.save(setup()); OnlineModels.answerWith("openrouter")
        assertEquals("openrouter", OnlineModels.answering()?.id)
        OnlineModels.remove("openrouter")
        assertNull(OnlineModels.answering())
        assertTrue(OnlineModels.state.value.setups.isEmpty())
        assertNull(runCatching { OnlineModels.answerWith("openrouter") }.exceptionOrNull())
        assertNull(OnlineModels.answering())
    }

    @Test fun theKeyIsNeverInTheLogs() {
        OnlineModels.init(Files.createTempDirectory("vault").toFile(), PlainSealer())
        route = { _, path, _ -> if (path.endsWith("/models")) 200 to openRouterList else 401 to "" }
        OnlineModels.test(Service("openrouter", "OpenRouter", "OpenRouter", base, null), key)
        runCatching { OnlineModels.chat(setup(), ask, { false }) { } }
        route = { _, _, _ -> 404 to "" }
        runCatching { OnlineModels.chat(setup(), ask, { false }) { } }
        OnlineModels.save(setup())
        // Everything the app logged goes through the developer log: neither it nor anything in it holds the key.
        val dir = Files.createTempDirectory("devlog").toFile()
        val log = DevLog(dir, bufferBytes = 0)
        val lines = ShadowLog.getLogs().map { "11-19 12:00:00.000  1234  1240 ${it.type.toString().take(1)} ${it.tag}: ${it.msg}" }
        assertTrue(lines.any { it.contains("MimirOnlineModel") })
        lines.forEach(log::write); log.flush()
        val all = dir.listFiles().orEmpty().joinToString("\n") { it.readText() } + lines.joinToString("\n")
        assertFalse(all.contains(key)); assertFalse(all.contains("ABCDEFGHIJ"))
    }

    @Test fun aRetryAfterAGoneModelDoesNotBringBackAKeyRemovedMeanwhile() {
        val dir = Files.createTempDirectory("vault").toFile()
        OnlineModels.init(dir, PlainSealer())
        OnlineModels.save(setup())
        route = { _, path, _ ->
            if (path.endsWith("/models")) { OnlineModels.remove("openrouter"); 200 to openRouterList } else if (seen.count { it.first.startsWith("POST") } == 1) 404 to "" else 200 to sse("Hi.")
        }
        runCatching { OnlineModels.chat(OnlineModels.setup("openrouter")!!, ask, { false }) { } }
        assertTrue(OnlineModels.state.value.setups.isEmpty())
        assertFalse(File(dir, "online-keys.bin").exists())
        assertNull(OnlineModels.answering())
    }

    @Test fun aKeyFileThatCantBeOpenedIsKeptNotWipedAndSaysWhichKeyNeedsSettingUpAgain() {
        val dir = Files.createTempDirectory("vault").toFile()
        val file = File(dir, "online-keys.bin")
        KeyVault(file, PlainSealer()).apply { put(setup()); put(Setup("groq", "Groq", "https://api.groq.com/openai/v1", "gsk_x", null, true)) }
        File(dir, "online-answers.txt").writeText("groq")
        val before = file.readBytes()
        // The lock fails (a Keystore problem): nothing answers, the flag goes, the file stays as it was.
        OnlineModels.init(dir, PlainSealer(0x22))
        assertEquals(setOf("openrouter", "groq"), OnlineModels.state.value.broken)
        assertTrue(OnlineModels.state.value.setups.isEmpty())
        assertNull(OnlineModels.answering())
        assertFalse(File(dir, "online-answers.txt").exists())
        assertTrue(file.readBytes().contentEquals(before))
        // Setting one up again never silently overwrites: the old file is put aside.
        OnlineModels.save(Setup("groq", "Groq", "https://api.groq.com/openai/v1", "gsk_new", null, true))
        assertTrue(File(dir, "online-keys.bin.unreadable").readBytes().contentEquals(before))
        assertEquals(setOf("openrouter"), OnlineModels.state.value.broken)
    }

    @Test fun aServiceOfTheOwnersIsAllowedOnlyWhileItIsSetUp() {
        OnlineModels.init(Files.createTempDirectory("vault").toFile(), PlainSealer())
        val own = Setup("own", "router.example.org", "https://router.example.org/v1", key, "m", true)
        assertFalse(Net.answerHostAllowed("router.example.org"))
        OnlineModels.save(own)
        assertTrue(Net.answerHostAllowed("router.example.org"))
        OnlineModels.remove("own")
        assertFalse(Net.answerHostAllowed("router.example.org"))
    }

    /** A lock for checks (the phone's Keystore isn't there): the bytes are turned so they are not readable on the disk. */
    class PlainSealer(private val turn: Int = 0x5a) : Sealer {
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor turn).toByte() }.toByteArray() + byteArrayOf(turn.toByte())
        override fun open(sealed: ByteArray): ByteArray {
            require(sealed.last().toInt() == turn) { "wrong lock" }
            return sealed.dropLast(1).map { (it.toInt() xor turn).toByte() }.toByteArray()
        }
    }
}
