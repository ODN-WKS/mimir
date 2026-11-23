package io.github.odnwks.mimir

import io.github.odnwks.mimir.audio.NaturalReader
import io.github.odnwks.mimir.audio.Sink
import io.github.odnwks.mimir.audio.Synth
import io.github.odnwks.mimir.audio.VoiceCatalog
import io.github.odnwks.mimir.link.NativeLink
import io.github.odnwks.mimir.online.TorLink
import io.github.odnwks.mimir.standalone.Llm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
/** A native library that isn't on the phone turns its feature off with one plain line; the app keeps running. */
class NativeGuardTest {
    @get:Rule val tmp = TemporaryFolder()
    private val original = NativeGuard.loader

    @Before fun missing() {
        NativeGuard.reset()
        NativeGuard.loader = { throw UnsatisfiedLinkError("dlopen failed: library \"lib$it.so\" not found") }
    }

    @After fun restore() { NativeGuard.loader = original; NativeGuard.reset() }

    @Test fun aMissingLibraryIsReportedNotThrown() {
        assertFalse(NativeGuard.available("anything"))
    }

    @Test fun voiceThatCannotLoadHandsBackToThePhonesVoice() {
        val synth = { _: io.github.odnwks.mimir.audio.NaturalVoice, _: File ->
            check(NativeGuard.available(NativeGuard.VOICE)) { "no voice library" }
            object : Synth { override val sampleRate = 1; override fun make(text: String) = FloatArray(0); override fun close() {} }
        }
        val r = NaturalReader(synth, { object : Sink { override fun start(sampleRate: Int, speed: Float) {}; override fun write(samples: FloatArray) {}; override fun drain() {}; override fun stop() {} } })
        val failed = CountDownLatch(1)
        r.speak("Hello.", VoiceCatalog.ingrid, tmp.root, 1f, {}, { failed.countDown() })
        assertTrue(failed.await(5, TimeUnit.SECONDS))
    }

    @Test fun aLinkingErrorInTheVoiceEngineAlsoHandsBack() {
        val r = NaturalReader({ _, _ -> throw UnsatisfiedLinkError("no sherpa") }, { error("unused") })
        val failed = CountDownLatch(1)
        r.speak("Hello.", VoiceCatalog.ingrid, tmp.root, 1f, {}, { failed.countDown() })
        assertTrue(failed.await(5, TimeUnit.SECONDS))
    }

    @Test fun theLinkStaysClosedWithOnePlainLine() {
        assertEquals(0L, NativeLink.start(ByteArray(32), true))
        assertEquals(NativeGuard.LINK_OFF, NativeLink.connect(0, "pc"))
        assertTrue(NativeLink.pairStart(0, "1", "p").contains(NativeGuard.LINK_OFF))
        assertFalse(NativeLink.connected(0))
        assertEquals(0L, TorLink.Native.new("a", "b"))
    }

    @Test fun theModelRunnerIsOffWhenItsLibraryIsMissing() {
        val cpu = tmp.newFile("cpuinfo").apply { writeText("Features: asimddp i8mm") }
        assertFalse(Llm.usable(cpu))
        assertEquals(0L, Llm.open("m", 1, 1, 0f))
    }
}
