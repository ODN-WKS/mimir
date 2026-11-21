package io.github.odnwks.mimir.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.TimeZone

/** The Model Store's Refresh: the ready-made search, with recorded answers of Hugging Face's API (no network). */
@RunWith(RobolectricTestRunner::class)
class ModelSearchTest {
    private val gb = 1_000_000_000L
    /** A 12 GB phone (reports about 11 GB) with plenty of room. */
    private val big = ModelSearch.Phone(11 * gb, 80 * gb)

    private fun hit(id: String, downloads: Long, created: String = "2026-01-01T00:00:00.000Z") =
        """{"_id":"x","id":"$id","likes":1,"private":false,"downloads":$downloads,"tags":["gguf"],"createdAt":"$created"}"""

    private fun file(path: String, size: Long, oid: String) =
        """{"type":"file","oid":"aa","size":1000,"path":"$path","lfs":{"oid":"$oid","size":$size,"pointerSize":134}}"""

    private val sha4b = "423f10b6ec2d99c3378143d7cd3b80eb4887b3ed92103103ac59173b404f4f7c"
    private val shaMm = "f14643c11f1e8244c59bca670e2a4c843be4234aa6bb88d0647dae7297798686"
    private val shaG = "d0027dd3a9128d9323e9f282c8bf010a8526c46477584535991dc1a869b56e96"

    /** Recorded file lists (real names, sizes and hashes). */
    private val qwen4b = "[" + listOf(
        file("README.md", 0, "").replace(""","lfs":{"oid":"","size":0,"pointerSize":134}""", ""),
        file("Huihui-Qwen3.5-4B-abliterated.Q8_0.gguf", 4_482_403_648, "1e78f9b48c8b69242fa45bbbbeb439ed818a71381a6362130d62a38561c3756d"),
        file("Huihui-Qwen3.5-4B-abliterated.Q4_K_S.gguf", 2_557_007_168, "3215d8dc35d2" + "0".repeat(52)),
        file("Huihui-Qwen3.5-4B-abliterated.Q4_K_M.gguf", 2_707_514_688, sha4b),
        file("Huihui-Qwen3.5-4B-abliterated.mmproj-f16.gguf", 672_423_552, shaMm),
        file("Huihui-Qwen3.5-4B-abliterated.mmproj-Q8_0.gguf", 366_894_720, "6".repeat(64)),
    ).joinToString(",") + "]"
    private val gemmaE4b = "[" + listOf(
        file("Gemma-4-E4B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf", 5_335_285_728, shaG),
        file("mmproj-Gemma-4-E4B-Uncensored-HauhauCS-Aggressive-f16.gguf", 990_288_832, "d".repeat(64)),
    ).joinToString(",") + "]"

    private fun fake(searches: Map<String, String>, trees: Map<String, String>, log: MutableList<String> = mutableListOf()): (String) -> String = { url ->
        log += url
        when {
            "/tree/main" in url -> trees[url.substringAfter("/api/models/").substringBefore("/tree/main")] ?: throw java.io.IOException("404")
            else -> searches[url.substringAfter("search=").substringBefore("&")] ?: "[]"
        }
    }

    @Test fun theThreeSearchesAreReadyMadeAndCarryNothingPersonal() {
        val log = mutableListOf<String>()
        ModelSearch.search(big, emptyList(), 1, fake(emptyMap(), emptyMap(), log))
        assertEquals(
            listOf("abliterated", "uncensored", "heretic").map { "https://huggingface.co/api/models?search=$it&filter=gguf&sort=downloads&direction=-1&limit=40" },
            log,
        )
    }

    @Test fun aModelIsTakenWithItsQ4KMFileSizeAndHash() {
        val m = ModelSearch.search(big, emptyList(), 42, fake(mapOf("abliterated" to "[" + hit("mradermacher/Huihui-Qwen3.5-4B-abliterated-GGUF", 163876) + "]"), mapOf("mradermacher/Huihui-Qwen3.5-4B-abliterated-GGUF" to qwen4b))).single()
        assertEquals("https://huggingface.co/mradermacher/Huihui-Qwen3.5-4B-abliterated-GGUF/resolve/main/Huihui-Qwen3.5-4B-abliterated.Q4_K_M.gguf", m.url)
        assertEquals(2_707_514_688, m.bytes)
        assertEquals(sha4b, m.sha256)
        assertEquals(Format.ChatMl, m.format)
        assertTrue(m.refusalsRemoved)
        assertEquals("mradermacher/Huihui-Qwen3.5-4B-abliterated-GGUF", m.source)
        assertEquals(42, m.foundAt)
        // Its vision part (the f16 one) comes with it, with its own hash, and the plain name says so.
        assertEquals(shaMm, m.vision!!.sha256)
        assertEquals("Balanced · sees photos", m.plainName)
        assertEquals("Uncensored", m.chip)
        assertTrue(m.id.all { it.isLetterOrDigit() || it in "-_." })
    }

    @Test fun anotherQuantIsUsedWhenThereIsNoQ4KMAndNoneWhenThereIsNoQ4() {
        val s = ModelSearch.Phone(11 * gb, 80 * gb)
        val ks = "[" + file("a.Q4_K_S.gguf", 2 * gb, "1".repeat(64)) + "," + file("a.Q8_0.gguf", 3 * gb, "2".repeat(64)) + "]"
        assertTrue(ModelSearch.modelFrom("x/Qwen3-4B-heretic-GGUF", ks, s, 1)!!.url.endsWith("a.Q4_K_S.gguf"))
        val q40 = "[" + file("a.Q4_0.gguf", 2 * gb, "1".repeat(64)) + "]"
        assertTrue(ModelSearch.modelFrom("x/Qwen3-4B-heretic-GGUF", q40, s, 1)!!.url.endsWith("a.Q4_0.gguf"))
        assertNull(ModelSearch.modelFrom("x/Qwen3-4B-heretic-GGUF", "[" + file("a.Q8_0.gguf", 3 * gb, "2".repeat(64)) + "]", s, 1))
        // A file without a SHA-256 can't be checked: not offered.
        assertNull(ModelSearch.modelFrom("x/Qwen3-4B-heretic-GGUF", """[{"type":"file","path":"a.Q4_K_M.gguf","size":2000000000}]""", s, 1))
    }

    @Test fun aHelperFileIsNotAModel() {
        val t = "[" + file("mtp-Qwen3.8-Flash-Next-Q4_0.gguf", 2 * gb, "1".repeat(64)) + "," + file("m-00001-of-00002.Q4_K_M.gguf", 2 * gb, "2".repeat(64)) + "]"
        assertNull(ModelSearch.modelFrom("x/Qwen3-4B-heretic-GGUF", t, big, 1))
    }

    @Test fun onlyWhatFitsTheMemoryAndTheFreeSpaceIsKept() {
        val e4b = mapOf("HauhauCS/Gemma-4-E4B-Uncensored-HauhauCS-Aggressive" to gemmaE4b)
        val search = mapOf("uncensored" to "[" + hit("HauhauCS/Gemma-4-E4B-Uncensored-HauhauCS-Aggressive", 5) + "]")
        // 5.3 GB + 1 GB vision is over what an 11 GB phone can run (about 5.5 GB).
        assertTrue(ModelSearch.search(big, emptyList(), 1, fake(search, e4b)).isEmpty())
        // A 16 GB phone can.
        assertEquals(1, ModelSearch.search(ModelSearch.Phone(15 * gb, 80 * gb), emptyList(), 1, fake(search, e4b)).size)
        // Free space must be the size + 1 GB: 6.3 GB for this one.
        assertTrue(ModelSearch.search(ModelSearch.Phone(15 * gb, 6 * gb), emptyList(), 1, fake(search, e4b)).isEmpty())
        // A 4 GB phone (the S8) can run almost nothing: nothing fits, no error.
        val q = mapOf("a/Qwen3.5-4B-x-GGUF" to qwen4b)
        assertTrue(ModelSearch.search(ModelSearch.Phone(3_500_000_000, 20 * gb), emptyList(), 1, fake(mapOf("heretic" to "[" + hit("a/Qwen3.5-4B-x-GGUF", 9) + "]"), q)).isEmpty())
    }

    @Test fun theLimitIsTheSameRuleTheAppUsesToDecideWhatFits() {
        for (ram in listOf(3_500_000_000L, 7_500_000_000L, 11_000_000_000L, 15_000_000_000L)) {
            val limit = ModelSearch.limitBytes(ram)
            assertNotEquals(Fit.TooBig, Fit.of(limit, ram, 0))
            assertEquals(Fit.TooBig, Fit.of(limit + 50_000_000, ram, 0))
        }
        assertEquals("5.5", ModelSearch.limitWords(11 * gb))
    }

    @Test fun otherFamiliesAndModelsAlreadyInTheListAreDropped() {
        val tree = mapOf("a/Llama-3-8B-heretic-GGUF" to qwen4b, "a/Gemma-3-4B-heretic-GGUF" to qwen4b, "a/Qwen-Image-2-Uncensored-GGUF" to qwen4b, "a/Qwen3.5-4B-new-GGUF" to qwen4b)
        val ids = listOf("a/Llama-3-8B-heretic-GGUF", "a/Gemma-3-4B-heretic-GGUF", "a/Qwen-Image-2-Uncensored-GGUF", "a/Qwen3.5-4B-new-GGUF")
        val search = mapOf("abliterated" to "[" + ids.joinToString(",") { hit(it, 10) } + "]")
        assertEquals(listOf("a/Qwen3.5-4B-new-GGUF"), ModelSearch.search(big, emptyList(), 1, fake(search, tree)).map { it.source })
        // Already offered (by repository, found earlier or built in): not shown again.
        val old = ModelSearch.search(big, emptyList(), 1, fake(search, tree)).single()
        assertTrue(ModelSearch.search(big, listOf(old), 2, fake(search, tree)).isEmpty())
        val builtIn = Catalog.builtIn.first { it.id.startsWith("qwen35-9b-heretic") }
        val s2 = mapOf("heretic" to "[" + hit("mradermacher/Qwen3.5-9B-heretic-v2-i1-GGUF", 99) + "]")
        assertTrue(ModelSearch.search(ModelSearch.Phone(15 * gb, 80 * gb), Catalog.builtIn, 1, fake(s2, mapOf("mradermacher/Qwen3.5-9B-heretic-v2-i1-GGUF" to qwen4b))).isEmpty())
        assertTrue(builtIn.url.contains("mradermacher/Qwen3.5-9B-heretic-v2-i1-GGUF"))
        // A Gemma 4 is kept, with its own format.
        assertEquals(Format.Gemma4, ModelSearch.modelFrom("a/gemma-4-E4B-heretic-GGUF", gemmaE4b, ModelSearch.Phone(15 * gb, 80 * gb), 1)!!.format)
    }

    @Test fun rankedByDownloadsThenNewerAndAtMostSix() {
        val ids = (1..9).map { "a/Qwen3.5-4B-m$it-GGUF" }
        // m1..m9 with downloads 10..90; m8 and m9 tie at 90 but m8 is newer.
        val hits = ids.mapIndexed { i, id -> hit(id, if (i >= 7) 90 else (i + 1) * 10L, if (id.contains("m8")) "2026-11-01T00:00:00.000Z" else "2026-01-01T00:00:00.000Z") }
        val tree = ids.associateWith { qwen4b.replace("Q4_K_M", "Q4_K_M").replace(sha4b, it.last { c -> c.isDigit() }.toString().repeat(64)) }
        // Same repo appears under two searches: still once.
        val out = ModelSearch.search(big, emptyList(), 1, fake(mapOf("abliterated" to "[" + hits.joinToString(",") + "]", "heretic" to "[" + hits.take(2).joinToString(",") + "]"), tree))
        assertEquals(ModelSearch.SHOW_AT_MOST, out.size)
        assertEquals(listOf("m8", "m9", "m7", "m6", "m5", "m4").map { "a/Qwen3.5-4B-$it-GGUF" }, out.map { it.source })
    }

    @Test fun aRepoThatCantBeReadIsSkippedButNoSearchAtAllIsAnError() {
        val search = mapOf("abliterated" to "[" + hit("a/Qwen3.5-4B-gone-GGUF", 50) + "," + hit("a/Qwen3.5-4B-ok-GGUF", 40) + "]")
        assertEquals(listOf("a/Qwen3.5-4B-ok-GGUF"), ModelSearch.search(big, emptyList(), 1, fake(search, mapOf("a/Qwen3.5-4B-ok-GGUF" to qwen4b))).map { it.source })
        try { ModelSearch.search(big, emptyList(), 1) { throw java.io.IOException("no signal") }; org.junit.Assert.fail() } catch (_: java.io.IOException) {}
    }

    @Test fun refreshAddsNewOnesOnTopMarksThemNewAndKeepsThemAcrossRestarts() {
        ModelSearch.reset(); Catalog.reset()
        val f = File.createTempFile("found", ".json").also { it.delete() }
        ModelSearch.load(f)
        val search = mapOf("abliterated" to "[" + hit("a/Qwen3.5-4B-new-GGUF", 50) + "]")
        val before = Catalog.models.map { it.id }
        ModelSearch.refresh(big, now = { 1_700_000_000_000 }, fetch = fake(search, mapOf("a/Qwen3.5-4B-new-GGUF" to qwen4b)), run = { it() })
        val s = ModelSearch.state.value
        assertFalse(s.searching)
        assertEquals(1, s.newIds.size)
        assertEquals(1_700_000_000_000, s.lastSearched)
        assertNull(s.message)
        // It is in the list (downloadable by the same checked download), and the listed ones are untouched.
        assertEquals(before + s.newIds, Catalog.models.map { it.id })
        assertTrue(Catalog.has(s.newIds.single()))
        // After a restart it is still there, with where it came from.
        Catalog.reset(); ModelSearch.reset(); ModelSearch.load(f)
        val m = Catalog.found.single()
        assertEquals("a/Qwen3.5-4B-new-GGUF", m.source); assertEquals(sha4b, m.sha256); assertEquals(1_700_000_000_000, m.foundAt)
        assertEquals("Balanced · sees photos", m.plainName)
        assertEquals(1_700_000_000_000, ModelSearch.state.value.lastSearched)
        // The PC sending its own list doesn't drop it.
        assertTrue(Catalog.use("""{"models":[${Catalog.builtIn.first().toJson()}]}"""))
        assertTrue(Catalog.has(m.id))
        f.delete(); ModelSearch.reset(); Catalog.reset()
    }

    @Test fun aSecondFinishedSearchOnlyAddsWhatIsNew() {
        ModelSearch.reset(); Catalog.reset()
        val search = mapOf("abliterated" to "[" + hit("a/Qwen3.5-4B-new-GGUF", 50) + "]")
        val tree = mapOf("a/Qwen3.5-4B-new-GGUF" to qwen4b)
        ModelSearch.refresh(big, fetch = fake(search, tree), run = { it() })
        ModelSearch.refresh(big, fetch = fake(search, tree), run = { it() })
        assertEquals(1, Catalog.found.size)
        assertEquals(ModelSearch.NOTHING, ModelSearch.state.value.message)
        assertFalse(ModelSearch.state.value.error)
        ModelSearch.reset(); Catalog.reset()
    }

    @Test fun whenItFailsItSaysSoPlainlyAndTheListIsUnchanged() {
        ModelSearch.reset(); Catalog.reset()
        val before = Catalog.models
        ModelSearch.refresh(big, fetch = { throw java.io.IOException("boom") }, run = { it() })
        val s = ModelSearch.state.value
        assertEquals("Couldn't look just now. Your list is unchanged: tap Refresh to try again.", s.message)
        assertTrue(s.error); assertFalse(s.searching)
        assertEquals(0, s.lastSearched)
        assertEquals(before, Catalog.models)
        ModelSearch.reset()
    }

    @Test fun aSearchAlreadyRunningIsNotStartedTwice() {
        ModelSearch.reset(); Catalog.reset()
        var started = 0
        val later = mutableListOf<() -> Unit>()
        ModelSearch.refresh(big, fetch = { "[]" }, run = { started++; later += it })
        assertTrue(ModelSearch.state.value.searching)
        ModelSearch.refresh(big, fetch = { "[]" }, run = { started++; later += it })
        assertEquals(1, started)
        later.single()()
        assertFalse(ModelSearch.state.value.searching)
        ModelSearch.reset()
    }

    @Test fun theLastSearchedLineSaysTodayYesterdayADateOrNever() {
        val utc = TimeZone.getTimeZone("UTC")
        val at = 1_700_000_000_000 // 14 Nov 2023, 22:13 UTC
        fun w(now: Long) = ModelSearch.lastSearchedWords(at, now, Locale.US, utc)
        assertEquals("today at 10:13 pm", w(at + 60_000))
        assertEquals("yesterday at 10:13 pm", w(at + 86_400_000))
        assertEquals("14 Nov at 10:13 pm", w(at + 5 * 86_400_000))
        assertEquals("never", ModelSearch.lastSearchedWords(0, at, Locale.US, utc))
    }

    @Test fun nothingIsScheduled() {
        // The search only runs from a tap: no WorkManager, no alarm, no periodic job in it or in the store.
        val src = listOf("standalone/ModelSearch.kt", "ui/ModelStore.kt").joinToString("\n") { File("src/main/java/io/github/odnwks/mimir/$it").readText() }
        assertFalse("WorkManager" in src || "PeriodicWorkRequest" in src || "AlarmManager" in src || "JobScheduler" in src)
        assertFalse("ModelSearch.refresh" in File("src/main/java/io/github/odnwks/mimir/standalone/Engine.kt").readText())
    }
}
