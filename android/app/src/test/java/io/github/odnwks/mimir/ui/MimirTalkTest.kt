package io.github.odnwks.mimir.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.odnwks.mimir.audio.PhoneSpeech
import io.github.odnwks.mimir.audio.SpeechSource
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.onAllNodesWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A recognizer that hears what the check says it hears. */
class FakeSpeech : SpeechSource {
    var listener: SpeechSource.Listener? = null
    var stopped = false
    var cancelled = false
    override fun start(listener: SpeechSource.Listener) { this.listener = listener }
    override fun stop() { stopped = true }
    override fun cancel() { cancelled = true }
}

/** Talking a message with no PC: the phone's own speech recognition, on the phone only in Whisper mode. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class MimirTalkTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The phone's speech for a check: [kind] says whether it runs on the phone itself. */
    private fun phone(onDevice: Boolean, have: Boolean = true, asked: MutableList<Boolean> = mutableListOf(), source: FakeSpeech = FakeSpeech()) =
        { onDeviceOnly: Boolean ->
            asked += onDeviceOnly
            when {
                !have -> null to (if (onDeviceOnly) PhoneSpeech.WHISPER_NEEDS_ON_DEVICE else PhoneSpeech.NONE)
                onDeviceOnly && !onDevice -> null to PhoneSpeech.WHISPER_NEEDS_ON_DEVICE
                else -> source to null
            }
        }

    @Test fun mimirTurnsSpeechIntoWordsOnThePhoneAndReadsThemBack() {
        val speech = FakeSpeech()
        val asked = mutableListOf<Boolean>()
        val voice = VoiceInput(context, station = null, phoneSpeech = phone(onDevice = true, asked = asked, source = speech))
        assertTrue(voice.start())
        assertEquals(listOf(false), asked)
        assertEquals(Speaking.Listening, voice.state)
        assertTrue(voice.onPhone)
        speech.listener!!.partial("three easy")
        assertEquals("three easy", voice.heardSoFar)
        voice.stop()
        assertTrue(speech.stopped)
        assertEquals(Speaking.Hearing, voice.state)
        speech.listener!!.done("three easy dinner ideas", null)
        assertEquals(Speaking.Heard("three easy dinner ideas"), voice.state)
        assertEquals("", voice.heardSoFar)
    }

    @Test fun inWhisperModeOnlyTheRecognizerOnThePhoneItselfIsUsed() {
        val asked = mutableListOf<Boolean>()
        val voice = VoiceInput(context, station = null, whisper = true, phoneSpeech = phone(onDevice = false, asked = asked))
        assertFalse(voice.start())
        assertEquals(listOf(true), asked)
        assertEquals(Speaking.Failed(PhoneSpeech.WHISPER_NEEDS_ON_DEVICE), voice.state)
        assertTrue(PhoneSpeech.WHISPER_NEEDS_ON_DEVICE.contains("nothing was sent"))
        // With recognition on the phone itself, Whisper mode can talk.
        val ok = VoiceInput(context, station = null, whisper = true, phoneSpeech = phone(onDevice = true))
        assertTrue(ok.start())
        assertEquals(Speaking.Listening, ok.state)
    }

    @Test fun aPhoneWithNoVoiceTypingSaysSo() {
        val voice = VoiceInput(context, station = null, phoneSpeech = phone(onDevice = false, have = false))
        assertFalse(voice.start())
        assertEquals(Speaking.Failed(PhoneSpeech.NONE), voice.state)
    }

    @Test fun recognitionProblemsAreSaidPlainlyAndCancelForgetsWhatWasHeard() {
        val speech = FakeSpeech()
        val voice = VoiceInput(context, station = null, phoneSpeech = phone(onDevice = true, source = speech))
        voice.start()
        speech.listener!!.done(null, PhoneSpeech.problem(android.speech.SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(Speaking.Failed("Nothing was heard. Try again a little closer."), voice.state)
        voice.cancel()
        voice.start()
        speech.listener!!.partial("hello")
        voice.cancel()
        assertTrue(speech.cancelled)
        assertEquals(Speaking.Idle, voice.state)
        // A late answer from a cancelled recognizer changes nothing.
        speech.listener!!.done("hello", null)
        assertEquals(Speaking.Idle, voice.state)
    }

    @Test fun withAPcThatIsThereThePcStillDoesIt() {
        val asked = mutableListOf<Boolean>()
        val voice = VoiceInput(context, FakeStation(), phoneSpeech = phone(onDevice = true, asked = asked))
        voice.start()
        voice.cancel()
        assertTrue("the phone's recognizer isn't asked while the PC is there", asked.isEmpty())
        assertFalse(voice.onPhone)
    }

    @Test fun withThePcAwayThePhoneDoesIt() {
        val asked = mutableListOf<Boolean>()
        val voice = VoiceInput(context, FakeStation(ExampleData.asleep()), phoneSpeech = phone(onDevice = true, asked = asked))
        assertTrue(voice.start())
        assertEquals(listOf(false), asked)
        assertTrue(voice.onPhone)
        voice.cancel()
    }

    @Test fun thePanelShowsWhatIsHeardAndPutsItInTheBoxWithoutSending() {
        val speech = FakeSpeech()
        val voice = VoiceInput(context, station = null, phoneSpeech = phone(onDevice = true, source = speech))
        val boxes = mutableStateListOf<String>()
        compose.setContent { MimirTheme { VoicePanel(voice, heard = { boxes += it }) } }
        compose.runOnIdle { voice.start(); speech.listener!!.partial("three easy") }
        compose.onNodeWithText("“three easy”…").assertIsDisplayed()
        compose.runOnIdle { voice.stop() }
        compose.onNodeWithText("Turning it into words on this phone…").assertIsDisplayed()
        compose.runOnIdle { speech.listener!!.done("three easy dinner ideas", null) }
        compose.runOnIdle {
            assertEquals(listOf("three easy dinner ideas"), boxes.toList())
            assertEquals(Speaking.Idle, voice.state)
        }
        compose.onNodeWithText("sending", substring = true).assertDoesNotExist()
    }

    /**
     * The privacy rule (QA A1): listening never ends in a sent message without a tap on Send. The
     * words wait in the box however long it takes, and the mic is its own button, not Send's.
     */
    @Test fun listeningNeverEndsInASentMessageWithoutASendTap() {
        val speech = FakeSpeech()
        val voice = VoiceInput(context, station = null, phoneSpeech = phone(onDevice = true, source = speech))
        val sent = mutableStateListOf<String>()
        compose.setContent {
            MimirTheme {
                var draft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
                androidx.compose.foundation.layout.Column {
                    VoicePanel(voice, heard = { draft = withHeard(draft, it) })
                    Composer(draft, { draft = it }, hint = "Message Mímir", label = "Message Mímir", voice = voice,
                        micLabel = "Talk (voice typing): speak a message", onSend = { sent += draft; draft = "" })
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        // Empty: Send is there but does nothing. No mic waits in the box (P12: voice typing is in the three dots).
        assertTrue("no mic in the box", compose.onAllNodesWithContentDescription("Talk (voice typing): speak a message").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Send").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(Speaking.Idle, voice.state)
        // Tap the mic, talk, tap again: the words land in the box.
        compose.runOnIdle { voice.start() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithContentDescription("Stop listening").assertIsDisplayed()
        compose.runOnIdle { voice.stop(); speech.listener!!.done("what's on the TV tonight", null) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("what's on the TV tonight").assertIsDisplayed()
        // However long it waits, nothing goes by itself.
        repeat(30) { compose.mainClock.advanceTimeBy(1000) }
        compose.runOnIdle { assertTrue("nothing sent without a Send tap", sent.isEmpty()) }
        // A second capture adds to the words rather than replacing or sending them.
        compose.runOnIdle { voice.start(); voice.stop(); speech.listener!!.done("and tomorrow", null) }
        repeat(10) { compose.mainClock.advanceTimeBy(1000) }
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithContentDescription("Send").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(listOf("what's on the TV tonight and tomorrow"), sent.toList()) }
    }

    @Test fun aRecordingWithThePcAwayIsKeptByItselfAndGoesWhenItIsBack() {
        val station = FakeStation(ExampleData.asleep())
        val voice = VoiceInput(context, station)
        val chatBefore = station.state.value.chat.size
        compose.setContent { MimirTheme { VoicePanel(voice, heard = {}) } }
        val wav = ByteArray(44 + 32000 * 2)
        compose.runOnIdle { voice.recorded(wav) }
        compose.runOnIdle {
            assertEquals(Speaking.Idle, voice.state)
            assertEquals("kept, nothing to confirm", chatBefore + 1, station.state.value.chat.size)
        }
    }

    @Test fun heardWordsJoinWhatIsAlreadyInTheBox() {
        assertEquals("hello", withHeard("", " hello"))
        assertEquals("Fix the bug and test it", withHeard("Fix the bug ", "and test it"))
    }

    @Test fun mimirsEmptyBoxOffersTheMicAndWordsBringSend() {
        val unpaired = FakeStation(io.github.odnwks.mimir.data.LinkStation.empty("test").copy(paired = false))
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalPhoneEngine provides testEngine(tmp.newFolder(), ready = true)) {
                MimirTheme { AppRoot(unpaired, io.github.odnwks.mimir.nav.Navigator()) }
            }
        }
        compose.onNodeWithContentDescription("Talk instead of typing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Message $AI_NAME").performTextInput("Hello")
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
        // The mic keeps its own spot: it never turns into Send, or Send into it.
        compose.onNodeWithContentDescription("Talk instead of typing").assertIsDisplayed()
    }
}
