package io.github.odnwks.mimir.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The built-in voice without the phone: the store with stand-in app files, the reader with a stand-in engine. */
@RunWith(RobolectricTestRunner::class)
class VoicesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The app's carried files: an espeak folder. */
    private val carried = object : Carried {
        val tree = mapOf(
            "voices/espeak-ng-data/phontab" to "ph".toByteArray(),
            "voices/espeak-ng-data/lang/gmw/en" to "en".toByteArray(),
        )
        override fun open(path: String) = ByteArrayInputStream(tree.getValue(path))
        override fun list(path: String) = tree.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct()
    }

    private val released = ArrayList<String>()
    private fun store(dir: File) = VoiceStore(dir, carried, release = { released += it })
    private val ingrid = VoiceCatalog.ingrid

    // ---------- Built in, ready on first launch ----------

    @Test fun ingridIsTheVoiceOnFirstLaunchWithNothingToDownload() {
        val dir = tmp.newFolder()
        val s = store(dir)
        assertEquals("alba", s.state.value.active?.id)
        assertEquals("Ingrid", s.state.value.active?.name)
        // Its folder files are put in place the first time it reads.
        assertTrue(s.ready(ingrid))
        assertEquals("en", File(s.folder(ingrid), "espeak-ng-data/lang/gmw/en").readText())
        assertFalse(dir.walk().any { it.name.endsWith(".new") || it.name.endsWith(".tmp") })
    }

    @Test fun thePhonesOwnVoiceCanBeChosenAndIsKept() {
        val dir = tmp.newFolder()
        store(dir).choose(null)
        assertNull(store(dir).state.value.active)
        store(dir).choose("alba")
        assertEquals("alba", store(dir).state.value.active?.id)
        // Nothing that isn't offered can be chosen.
        val s = store(dir)
        s.choose("daniel")
        assertEquals("alba", s.state.value.chosen)
    }

    @Test fun anUpdateDeletesDownloadedVoicesAndAnEinarListenerGetsIngrid() {
        val dir = tmp.newFolder()
        // What older versions left: Scottish Daniel (Einar) in use, a downloaded Alba, a half download.
        File(dir, "daniel").apply { mkdirs(); resolve("model.onnx").writeText("325 MB"); resolve("lexicon.txt").writeText("x") }
        File(dir, "alba").apply { mkdirs(); resolve("model.onnx").writeText("63 MB") }
        File(dir, "alba.new").apply { mkdirs(); resolve("model.onnx.part").writeText("half") }
        File(dir, "voices.txt").writeText("chosen=daniel\nfiles.daniel=abc\ncarried.daniel=1\nfiles.alba=def\n")
        val s = store(dir)
        assertEquals(listOf("voices.txt"), dir.list()!!.sorted())
        assertEquals("alba", s.state.value.active?.id)
        assertFalse("daniel" in File(dir, "voices.txt").readText())
        // Someone who used the phone's own voice before (an empty choice) gets Ingrid too.
        File(dir, "voices.txt").writeText("chosen=\n")
        assertEquals("alba", store(dir).state.value.active?.id)
    }

    @Test fun theVoicesFilesAreCopiedAgainWhenTheAppBringsNewOnes() {
        val dir = tmp.newFolder()
        val s = store(dir)
        assertTrue(s.ready(ingrid))
        File(s.folder(ingrid), "espeak-ng-data/phontab").writeText("old")
        // Same app version: kept as they are.
        assertTrue(s.ready(ingrid))
        assertEquals("old", File(s.folder(ingrid), "espeak-ng-data/phontab").readText())
        // An app that carries newer files (another version number) copies them again, once.
        File(dir, "voices.txt").writeText("carried.alba=1\n")
        assertTrue(store(dir).ready(ingrid))
        assertEquals("ph", File(s.folder(ingrid), "espeak-ng-data/phontab").readText())
        assertTrue("alba" in released)
    }

    @Test fun ingridIsTenPercentQuickerAndFoundByHerNamesAndOldOnes() {
        assertEquals(listOf("alba"), VoiceCatalog.all.map { it.id })
        assertEquals("Ingrid", VoiceCatalog.byId("alba")?.name)
        assertNull(VoiceCatalog.byId("daniel"))
        assertTrue(ingrid.sample.startsWith("Hello, I'm Ingrid."))
        assertEquals("Female, soft Scottish · calm and clear", ingrid.note)
        assertEquals(1.10f, ingrid.tempo)
        for (w in listOf("ingrid", "alba", "scottish", "reading voice")) {
            assertTrue(w, io.github.odnwks.mimir.ui.findSettings(w, paired = false).any { it.title == "Other voices" })
        }
    }

    @Test fun everyFileTheVoiceNeedsIsInTheApp() {
        val assets = File("src/main/assets")
        ingrid.carried.keys.forEach { name -> assertTrue("$name is missing", File(assets, "voices/$name").exists()) }
        assertTrue(File(assets, ingrid.tokens).exists())
        // The model is added by the build (one address and checksum in build.gradle.kts).
        val build = File("build.gradle.kts").readText()
        assertTrue("val ingridModel = " in build && "val ingridModelSha = " in build)
        assertEquals("voices/ingrid-model.ort", ingrid.model)
        // Nothing of Scottish Daniel is carried any more.
        assertFalse(File(assets, "voices/daniel-lexicon.txt.gz").exists())
        assertFalse(File(assets, "voices/kokoro-tokens.txt").exists())
    }

    // ---------- The reader ----------

    private class StandInSynth(val fail: Boolean = false) : Synth {
        val made = ArrayList<String>()
        var closed = false
        init { if (fail) error("no engine") }
        override val sampleRate = 1000
        override fun make(text: String): FloatArray { made += text; return FloatArray(10) { 0.5f } }
        override fun close() { closed = true }
    }

    private class StandInSink : Sink {
        var speed = 0f
        val heard = ArrayList<FloatArray>()
        override fun start(sampleRate: Int, speed: Float) { this.speed = speed }
        override fun write(samples: FloatArray) { heard += samples }
        override fun drain() {}
        override fun stop() {}
    }

    @Test fun theReaderSpeaksPhraseByPhraseWithThePcsPausesAndSpeed() {
        val synth = StandInSynth()
        val sink = StandInSink()
        val r = NaturalReader({ _, _ -> synth }, { sink })
        val done = CountDownLatch(1)
        r.speak("Hi, there. All one hundred checks pass, and nothing broke.", VoiceCatalog.ingrid, tmp.root, 1.5f, { done.countDown() }, {})
        assertTrue(done.await(5, TimeUnit.SECONDS))
        // Each phrase is followed by its pause, at the voice's pace times the chosen speed.
        assertEquals(listOf("Hi,", "there.", "All one hundred checks pass,", "and nothing broke."), synth.made)
        assertEquals(listOf(10 + 250, 10 + 500, 10 + 250, 10), sink.heard.map { it.size })
        assertEquals(1.10f * 1.5f, sink.speed, 0.001f)
    }

    @Test fun aVoiceThatWontLoadHandsBackToThePhonesVoice() {
        val r = NaturalReader({ _, _ -> StandInSynth(fail = true) }, { StandInSink() })
        val failed = CountDownLatch(1)
        r.speak("Hello.", VoiceCatalog.ingrid, tmp.root, 1f, {}, { failed.countDown() })
        assertTrue(failed.await(5, TimeUnit.SECONDS))
    }

    @Test fun theVoiceIsLetGoWhenIdleAndWhenItsFilesGo() {
        var synth: StandInSynth? = null
        val r = NaturalReader({ _, _ -> StandInSynth().also { synth = it } }, { StandInSink() }, idleMs = 200)
        val done = CountDownLatch(1)
        r.speak("Hello.", VoiceCatalog.ingrid, tmp.root, 1f, { done.countDown() }, {})
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(r.isLoaded)
        Thread.sleep(500)
        assertFalse(r.isLoaded)
        assertTrue(synth!!.closed)
        val again = CountDownLatch(1)
        r.speak("Hello.", VoiceCatalog.ingrid, tmp.root, 1f, { again.countDown() }, {})
        assertTrue(again.await(5, TimeUnit.SECONDS))
        r.release("alba")
        assertFalse(r.isLoaded)
    }

    @Test fun theFirstReadAfterInstallCopiesTheFilesAndSpeaksInsteadOfWaitingForever() {
        // As on the phone: the store lets go of the voice through the reader while it readies the
        // files on the reader's own thread. That once waited on itself and Ingrid stayed silent.
        val sink = StandInSink()
        val r = NaturalReader({ _, _ -> StandInSynth() }, { sink })
        val s = VoiceStore(tmp.newFolder(), carried, release = r::release)
        val done = CountDownLatch(1)
        r.speak("Hello there.", ingrid, s.folder(ingrid), 1f, { done.countDown() }, {}, prepare = { s.ready(ingrid) })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(sink.heard.isNotEmpty())
        assertTrue(File(s.folder(ingrid), "espeak-ng-data/phontab").isFile)
    }

    @Test fun aVoiceThatTakesTooLongToGetReadyHandsBackToThePhonesVoice() {
        val stuck = CountDownLatch(1)
        val r = NaturalReader({ _, _ -> stuck.await(); StandInSynth() }, { StandInSink() }, soundLimitMs = 300)
        val failed = CountDownLatch(1)
        var ended = false
        r.speak("Hello.", ingrid, tmp.root, 1f, { ended = true }, { failed.countDown() })
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        stuck.countDown()
        r.settle()
        Thread.sleep(200)
        assertFalse(ended)
        // It finished loading all the same, so the next message starts at once.
        assertTrue(r.isReady("alba"))
    }

    @Test fun noSoundWithinTheLimitHandsTheMessageToThePhonesVoiceAndPlaysNothing() {
        val slow = object : Synth {
            override val sampleRate = 1000
            override fun make(text: String): FloatArray { Thread.sleep(600); return FloatArray(10) }
            override fun close() {}
        }
        val sink = StandInSink()
        val r = NaturalReader({ _, _ -> slow }, { sink }, soundLimitMs = 300)
        val failed = CountDownLatch(1)
        var ended = false
        r.speak("Hello there.", ingrid, tmp.root, 1f, { ended = true }, { failed.countDown() })
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        Thread.sleep(1000)
        r.settle()
        assertTrue(sink.heard.isEmpty())
        assertFalse(ended)
    }

    @Test fun aLongTextIsReadToTheEndEvenWhenTheVoiceFallsFarBehindMidRead() {
        // The phone slowed the voice down off screen: later phrases took far longer than the
        // sound limit, and the reading must still go on to the last one (no hand-over, no early end).
        var n = 0
        val slow = object : Synth {
            override val sampleRate = 1000
            override fun make(text: String): FloatArray { if (++n > 5) Thread.sleep(150); return FloatArray(10) { 0.5f } }
            override fun close() {}
        }
        val sink = StandInSink()
        val r = NaturalReader({ _, _ -> slow }, { sink }, soundLimitMs = 300)
        val text = (1..30).joinToString(" ") { "Sentence number $it goes on." }
        val done = CountDownLatch(1)
        var failed = false
        r.speak(text, ingrid, tmp.root, 1f, { done.countDown() }, { failed = true })
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertFalse(failed)
        assertEquals(30, sink.heard.size)
    }

    @Test fun theVoiceLoadsAheadAndStaysWhileTheAppIsOnScreen() {
        var made = 0
        val r = NaturalReader({ _, _ -> made++; StandInSynth() }, { StandInSink() }, idleMs = 100)
        assertFalse(r.isReady("alba"))
        r.keep(ingrid, tmp.root)
        r.settle()
        assertTrue(r.isReady("alba"))
        val heard = CountDownLatch(1)
        val done = CountDownLatch(1)
        r.speak("Hello.", ingrid, tmp.root, 1f, { done.countDown() }, {}, started = { heard.countDown() })
        assertTrue(heard.await(5, TimeUnit.SECONDS))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        // Kept past the idle time while on screen, then let go once the app leaves it.
        Thread.sleep(300)
        assertTrue(r.isLoaded)
        r.letGo()
        Thread.sleep(400)
        assertFalse(r.isLoaded)
        assertEquals(1, made)
    }

    @Test fun nothingToSayEndsWithoutTheFallback() {
        val r = NaturalReader({ _, _ -> StandInSynth() }, { StandInSink() }, soundLimitMs = 300)
        val done = CountDownLatch(1)
        var failed = false
        r.speak("   ", ingrid, tmp.root, 1f, { done.countDown() }, { failed = true })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        Thread.sleep(500)
        assertFalse(failed)
    }

    @Test fun aChosenVoiceThatWontLoadFallsBackToThePhonesOwnVoice() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = store(tmp.newFolder())
        val reader = NaturalReader({ _, _ -> StandInSynth(fail = true) }, { StandInSink() })
        val pv = PhoneVoice(ctx, Voices(s, reader))
        val tts = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance()
        org.robolectric.Shadows.shadowOf(tts).onInitListener.onInit(android.speech.tts.TextToSpeech.SUCCESS)
        assertEquals("Ingrid", pv.voiceName)
        pv.toggle("m1", "Hello there.")
        reader.settle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("Hello there.", org.robolectric.Shadows.shadowOf(tts).lastSpokenText)
        // With the phone's own voice chosen, it reads and says so.
        s.choose(null)
        assertTrue(pv.voiceName != "Ingrid")
    }

    // ---------- The text rules (as the PC's say-update) ----------

    @Test fun phrasesFollowThePcsRules() {
        val t = "Hi, **Sam** — see https://x.y/z now.\nVersion 0.18 is out. The Galaxy S25 Ultra reconnected in 14 milliseconds, and `Whisper` mode fixed both bugs at 9:45 PM on Wednesday.\n\nCafé déjà vu; done!"
        val p = Phrases.of(t)
        // The same phrases and pauses the PC's say-update makes from this text.
        assertEquals(
            listOf(
                "Hi," to 250, "Sam ," to 250, "see link now." to 500, "Version 0.18 is out." to 500,
                "The Galaxy S25 Ultra reconnected in 14 milliseconds," to 250,
                "and Whisper mode fixed both bugs at 9:45 PM on Wednesday." to 500, "Café déjà vu;" to 250, "done!" to 0,
            ),
            p.map { it.text to it.pauseMs },
        )
        assertEquals(emptyList<Phrases.Phrase>(), Phrases.of("**  **\n\n"))
    }

    @Test fun theFirstReadAfterARestartHasTwelveSecondsBeforeThePhonesVoiceTakesOver() {
        assertEquals(12_000L, NaturalReader.SOUND_LIMIT_MS)
    }
}
