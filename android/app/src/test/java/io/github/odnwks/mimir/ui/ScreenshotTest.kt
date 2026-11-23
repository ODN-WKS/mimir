package io.github.odnwks.mimir.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.odnwks.mimir.data.ExampleData
import io.github.odnwks.mimir.data.FakeStation
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.Sheet
import io.github.odnwks.mimir.nav.Tab
import io.github.odnwks.mimir.ui.theme.MimirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Run checks' results as Labs shows them after a run (one problem, one skipped). */
private val labsChecksExample = listOf(
    io.github.odnwks.mimir.labs.CheckLine("Your PC", io.github.odnwks.mimir.labs.CheckLine.Result.Good, "Reached in 84 ms"),
    io.github.odnwks.mimir.labs.CheckLine("Your PC's model", io.github.odnwks.mimir.labs.CheckLine.Result.Good, "Answered a test in 1.8 s"),
    io.github.odnwks.mimir.labs.CheckLine("Ingrid's voice", io.github.odnwks.mimir.labs.CheckLine.Result.Problem, "Didn't load", "Close other apps and try again"),
    io.github.odnwks.mimir.labs.CheckLine("Looking things up", io.github.odnwks.mimir.labs.CheckLine.Result.Skipped, "Turned off, so not checked"),
    io.github.odnwks.mimir.labs.CheckLine("Space on this phone", io.github.odnwks.mimir.labs.CheckLine.Result.Good, "12.4 GB free"),
    io.github.odnwks.mimir.labs.CheckLine("App version", io.github.odnwks.mimir.labs.CheckLine.Result.Good, "0.01 (dev)"),
)

/** One screen as a board shows it: how to reach it, and which stand-in data. */
class Case(
    val name: String,
    val asleep: Boolean = false,
    val chatDraft: String = "",
    /** A change to the example data (an empty chat, no projects, not paired). */
    val data: (io.github.odnwks.mimir.data.StationState) -> io.github.odnwks.mimir.data.StationState = { it },
    /** The AI on this phone for the first run (a folder to keep it in comes with it). */
    val engine: ((java.io.File) -> io.github.odnwks.mimir.standalone.Engine)? = null,
    /** The phone's models as the Model Store shows them (null: the default one, in use). */
    val phone: (() -> PhoneModels)? = null,
    /** The downloadable voices (null: none downloaded yet). */
    val voices: (() -> VoicesUi)? = null,
    /** Settings › General › Size, and the pinched text size in chats. */
    val scale: Float = 1f,
    val chatText: Float = 1f,
    /** The whole screen, dialogs included (a dialog is a window of its own). */
    val dialog: Boolean = false,
    /** The eye's menu open (Chats, New chat, New Whisper). */
    val eyeMenu: Boolean = false,
    val go: Navigator.() -> Unit = {},
) {
    override fun toString() = name
}

/** Before pairing: nothing from a PC. */
private val notPaired: (io.github.odnwks.mimir.data.StationState) -> io.github.odnwks.mimir.data.StationState =
    { io.github.odnwks.mimir.data.LinkStation.empty("0.18").copy(paired = false) }

/** Paired, with no Whisper task already running on the PC. */
private val noWhisperRunning: (io.github.odnwks.mimir.data.StationState) -> io.github.odnwks.mimir.data.StationState =
    { s -> s.copy(incognito = null, chat = s.chat.filterNot { it is io.github.odnwks.mimir.data.ChatEntry.IncognitoRunning }) }

/** The 19 boards (Confirms as its sheets, Motion as the timings) on Robolectric, no device. */
val cases = listOf(
    Case("01_Chat"),
    Case("02_NeedsYou") { open(Screen.NeedsYou) },
    Case("03_Task") { open(Screen.NeedsYou); open(Screen.Task(ExampleData.TASK_ALLOW)) },
    Case("04_Change") { open(Screen.Task(ExampleData.TASK_ALLOW)); open(Screen.Change(ExampleData.TASK_ALLOW)) },
    Case("05a_Confirms_TaskOptions") { open(Screen.Task(ExampleData.TASK_ALLOW)); openSheet(Sheet.TaskOptions(ExampleData.TASK_ALLOW)) },
    Case("05b_Confirms_Stop") { open(Screen.Task(ExampleData.TASK_ALLOW)); openSheet(Sheet.StopTask(ExampleData.TASK_ALLOW)) },
    Case("05c_Confirms_AlwaysAllow") { open(Screen.Task(ExampleData.TASK_ALLOW)); openSheet(Sheet.AlwaysAllow(ExampleData.TASK_ALLOW)) },
    Case("05d_Confirms_Release") { open(Screen.Task(ExampleData.TASK_ALLOW)); openSheet(Sheet.Release(ExampleData.TASK_ALLOW)) },
    Case("05e_Confirms_EndIncognito") { open(Screen.IncognitoTask); openSheet(Sheet.EndIncognito) },
    Case("05f_Confirms_Unpair") { open(Screen.Settings); open(Screen.PhonePc); openSheet(Sheet.Unpair) },
    Case("06_Status") { selectTab(Tab.Status) },
    Case("07_Project") { selectTab(Tab.Status); open(Screen.Project("Budget-App")) },
    Case("08_Updates") { selectTab(Tab.Updates) },
    Case("09_Notifications") { open(Screen.Settings); open(Screen.Notifications) },
    Case("10_WhereTo") { openSheet(Sheet.WhereTo) },
    Case("10c_WhereTo_Effort") { composeTo = io.github.odnwks.mimir.nav.ComposeTo(project = "Recipe box", effort = "high"); openSheet(Sheet.WhereTo) },
    Case("10d_ComposeEffort") { composeTo = io.github.odnwks.mimir.nav.ComposeTo(project = "Recipe box", effort = "high"); openSheet(Sheet.ComposeEffort) },
    Case("05g_TaskModelAndEffort") { open(Screen.Task(ExampleData.TASK_ALLOW)); openSheet(Sheet.TaskModel(ExampleData.TASK_ALLOW)) },
    Case("10c_WhereTo_Effort") { composeTo = io.github.odnwks.mimir.nav.ComposeTo(project = "Recipe box", effort = "high"); openSheet(Sheet.WhereTo) },
    Case("10d_ComposeEffort") { composeTo = io.github.odnwks.mimir.nav.ComposeTo(project = "Recipe box", effort = "high"); openSheet(Sheet.ComposeEffort) },
    Case("05g_TaskModelAndEffort") { open(Screen.Task(ExampleData.TASK_ALLOW)); openSheet(Sheet.TaskModel(ExampleData.TASK_ALLOW)) },
    Case("10b_WhereTo_MoreOptions") { composeTo = io.github.odnwks.mimir.nav.ComposeTo(project = "Recipe box", plan = true, whisper = true); openSheet(Sheet.WhereTo) },
    Case("11_IncognitoTask") { open(Screen.IncognitoTask) },
    Case("12_Settings") { open(Screen.Settings) },
    Case("13_Models") { open(Screen.Settings); open(Screen.Models) },
    Case("13b_Models_NotPaired", data = { io.github.odnwks.mimir.data.LinkStation.empty("0.18").copy(paired = false) }) { open(Screen.Settings); open(Screen.Models) },
    Case("13c_Models_NoFreeModels", data = { it.copy(models = it.models.copy(local = emptyList())) }) { open(Screen.Settings); open(Screen.Models) },
    Case("14_PhonePC") { open(Screen.Settings); open(Screen.PhonePc) },
    Case("15_Pair") { open(Screen.Settings); open(Screen.PhonePc); open(Screen.Pair) },
    Case("16_PairDone") { open(Screen.Pair); open(Screen.PairDone) },
    Case("17_PairError") { open(Screen.Pair); open(Screen.PairError) },
    Case("18_Trouble", asleep = true, chatDraft = "And check the"),
    Case("19_ProjectPicker") { openSheet(Sheet.ProjectPicker("Recipe box", back = Sheet.WhereTo)) },
    Case("20_NewProject") { openSheet(Sheet.ProjectPicker("Recipe box", newProject = true, back = Sheet.WhereTo)) },
    Case("30_Status_PcAsleep", asleep = true) { selectTab(Tab.Status) },
    Case("31_Task_PcAsleep", asleep = true) { open(Screen.Task(ExampleData.TASK_ALLOW)) },
    Case("32_Task_Working") { open(Screen.Task(ExampleData.TASK_CELL)) },
    Case("33_Task_Starting") { open(Screen.Task(ExampleData.TASK_TABLET)) },
    Case("34_Task_Stopped") { open(Screen.Task(ExampleData.TASK_ORDER)) },
    Case("35_Updates_PcAsleep", asleep = true) { selectTab(Tab.Updates) },
    Case("40_Chat_Empty", data = { it.copy(chat = emptyList()) }),
    Case("41_Updates_Empty", data = { it.copy(updates = emptyList()) }) { selectTab(Tab.Updates) },
    Case("42_NeedsYou_Empty", data = { s -> s.copy(needsYou = emptyList(), tasks = s.tasks.map { it.copy(permission = null, question = null) }) }) { open(Screen.NeedsYou) },
    Case("43_Status_NoProjects", data = { it.copy(projects = emptyList(), tasks = emptyList()) }) { selectTab(Tab.Status) },
    Case("50_FirstRun", data = notPaired, engine = { testEngine(it, ready = false) }),
    Case("51_FirstRun_Waiting", data = notPaired, engine = { testEngine(it, ready = false).apply { ask("Help me write a kind birthday message") } }),
    Case(
        "52_FirstRun_Chatting", data = notPaired,
        engine = { testEngine(it, ready = true, turns = listOf(io.github.odnwks.mimir.standalone.Turn(true, "Give me three easy dinner ideas"), io.github.odnwks.mimir.standalone.Turn(false, "1. Pasta with tomatoes and garlic. 2. Eggs fried rice with peas. 3. A tray of roast vegetables and chicken."))) },
    ),
    Case("53_FirstRun_Settings", data = notPaired, engine = { testEngine(it, ready = false) }) { open(Screen.Settings) },
    Case("45_AllTasks") { selectTab(Tab.Status); open(Screen.AllTasks()) },
    Case("22_TaskMenu") { open(Screen.Task(ExampleData.TASK_ALLOW)); openSheet(Sheet.TaskOptions(ExampleData.TASK_ALLOW)) },
    // Findability: each thing where people look first.
    Case("60_Models_WorthSwitching", data = { it.copy(updates = listOf(worthSwitching) + it.updates) }) { open(Screen.Settings); open(Screen.Models) },
    Case("62_Storage", engine = { testEngine(it, ready = true) }) { open(Screen.Settings); open(Screen.Storage) },
    Case("63_Voice") { open(Screen.Settings); open(Screen.Voice) },
    Case("63b_GroundRules", data = notPaired) { open(Screen.Settings); open(Screen.Models); open(Screen.GroundRules) },
    Case("63c_PrivacyCheck", data = notPaired) { open(Screen.Settings); open(Screen.PrivacyCheck) },
    Case("64_About_NewVersion", data = { it.copy(pcVersion = "0.19") }) { open(Screen.Settings); open(Screen.About) },
    Case("65_Help") { open(Screen.Settings); open(Screen.Help) },
    // Labs: the notes switches, Run checks (not run, then with results) and the report preview.
    Case("68_Labs") { io.github.odnwks.mimir.ui.LastChecks.lines = null; open(Screen.Settings); open(Screen.Help); open(Screen.Labs) },
    Case("68b_Labs_Checks") {
        io.github.odnwks.mimir.ui.LastChecks.lines = labsChecksExample
        open(Screen.Settings); open(Screen.Help); open(Screen.Labs)
    },
    Case("66_AllTasks_Archived", data = { s -> s.copy(tasks = s.tasks.drop(1), archived = s.tasks.take(1)) }) { selectTab(Tab.Status); open(Screen.AllTasks(archived = true)) },
    Case("67_AlwaysAllowed") { open(Screen.Task(ExampleData.TASK_ALLOW)); openSheet(Sheet.AlwaysAllowed(ExampleData.TASK_ALLOW)) },
    // The Model Store (Settings › Who answers) and what powers tasks.
    Case("70_ModelStore", data = notPaired, phone = { storePhoneModels() }) { open(Screen.Settings); open(Screen.Models) },
    Case("71_ModelDetails_Tested", phone = { storePhoneModels() }) { open(Screen.Settings); open(Screen.Models); open(Screen.ModelDetails(io.github.odnwks.mimir.standalone.Catalog.default.id)) },
    Case("72_ModelDetails_Testing", phone = { storePhoneModels(testing = true) }) { open(Screen.Settings); open(Screen.Models); open(Screen.ModelDetails("gemma4-e2b-heretic-q4km")) },
    Case("73_ModelStore_OwnModels", data = notPaired, phone = { ownPhoneModels() }) { open(Screen.Settings); open(Screen.Models) },
    // Voices: Ingrid, built into the app, or the phone's own voice (Settings › Voice, Voices, and in Models).
    Case("75_Voices_Ingrid") { open(Screen.Settings); open(Screen.Voice); open(Screen.Voices) },
    Case("76_Voice_PhonesOwn", data = notPaired, voices = { exampleVoices(io.github.odnwks.mimir.audio.VoicesState(chosen = null)) }) { open(Screen.Settings); open(Screen.Voice) },
    Case("77_Voice_Ingrid", data = notPaired) { open(Screen.Settings); open(Screen.Voice) },
    Case("73_Models_FreeOnly", data = { it.copy(models = it.models.copy(power = io.github.odnwks.mimir.data.Power.FreeOnly)) }) { open(Screen.Settings); open(Screen.Models) },
    // Settings › General › Size at its ends, and pinched chat text: nothing clipped.
    Case("80_General_Size") { open(Screen.Settings); open(Screen.General) },
    Case("81_Chat_Size50", scale = 0.5f),
    Case("89_FirstRun_Size150", data = notPaired, scale = 1.5f, engine = { testEngine(it, ready = true) }),
    Case("82_Chat_Size150", scale = 1.5f),
    Case("83_Task_Size50", scale = 0.5f) { open(Screen.Task(ExampleData.TASK_ALLOW)) },
    Case("84_Task_Size150", scale = 1.5f) { open(Screen.Task(ExampleData.TASK_ALLOW)) },
    Case("85_Status_Size150", scale = 1.5f) { selectTab(Tab.Status) },
    Case("86_General_Size150", scale = 1.5f) { open(Screen.Settings); open(Screen.General) },
    Case("87_General_Size50", scale = 0.5f) { open(Screen.Settings); open(Screen.General) },
    Case(
        "88_FirstRun_ChatTextPinched", data = notPaired, chatText = 1.6f,
        engine = { testEngine(it, ready = true, turns = listOf(io.github.odnwks.mimir.standalone.Turn(true, "Give me three easy dinner ideas"), io.github.odnwks.mimir.standalone.Turn(false, "1. Pasta with tomatoes and garlic. 2. Eggs fried rice with peas."))) },
    ),
    Case(
        "54_FirstRun_EarlierChats", data = notPaired,
        engine = { testEngine(it, ready = true, turns = listOf(io.github.odnwks.mimir.standalone.Turn(true, "Give me three easy dinner ideas"), io.github.odnwks.mimir.standalone.Turn(false, "Pasta."))).apply { newChat() } },
    ),
    Case("57_FirstRun_Status", data = notPaired, engine = { testEngine(it, ready = true) }) { selectTab(Tab.Status) },
    Case("58_FirstRun_Updates", data = notPaired, engine = { testEngine(it, ready = true) }) { selectTab(Tab.Updates) },
    Case("55_FirstRun_Whisper", data = notPaired, engine = { testEngine(it, ready = true).apply { startWhisper() } }),
    Case(
        "56_FirstRun_TopicSheet", data = notPaired,
        engine = { testEngine(it, ready = true, turns = listOf(io.github.odnwks.mimir.standalone.Turn(true, "Give me three easy dinner ideas"), io.github.odnwks.mimir.standalone.Turn(false, "Pasta."))) },
    ) { openSheet(Sheet.ChatOptions("Three easy dinner ideas", canRename = true, whisper = false, canWhisper = true)) },
    // Paired, the Whisper eye: the choice (this phone or the PC), and a Whisper to the PC (teal).
    Case("59a_Chat_WhisperChoice", data = noWhisperRunning, engine = { testEngine(it, ready = true) }, dialog = true) { askChat(io.github.odnwks.mimir.nav.ChatAsk.StartWhisper) },
    Case("59b_Chat_WhisperOnPc", data = noWhisperRunning, engine = { testEngine(it, ready = true) }) { composeTo = io.github.odnwks.mimir.nav.ComposeTo(whisper = true) },
    Case("59c_Chat_WhisperNeedsAModel", data = { noWhisperRunning(it).let { s -> s.copy(models = s.models.copy(local = emptyList())) } }, engine = { testEngine(it, ready = false) }, dialog = true) { askChat(io.github.odnwks.mimir.nav.ChatAsk.StartWhisper) },
    // Mímir online: the weather's one-time place question, and an answer with its source line.
    Case("60a_FirstRun_WeatherWhere", data = notPaired, engine = {
        io.github.odnwks.mimir.online.Online.set(io.github.odnwks.mimir.online.Online.Settings())
        testEngine(it, ready = true).apply {
            send("What is the weather forecast for today")
            val until = System.currentTimeMillis() + 5_000
            Thread.sleep(20)
            while (state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        }
    }),
    Case("60b_FirstRun_WeatherAnswer", data = notPaired, engine = {
        testEngine(it, ready = true, turns = listOf(
            io.github.odnwks.mimir.standalone.Turn(true, "What is the weather forecast for today"),
            io.github.odnwks.mimir.standalone.Turn(false, "Light rain in Leeds today, 4 to 9 °C, with an 80% chance of rain. Tomorrow is overcast and drier.",
                sources = listOf(io.github.odnwks.mimir.online.Source("open-meteo.com", "https://open-meteo.com/", "Leeds"))),
        ))
    }),
    // The eye's menu, Chats, and the paired chat answering the weather itself (owner, 2026-11-19).
    Case("61a_Chat_EyeMenu", data = noWhisperRunning, engine = { testEngine(it, ready = true) }, dialog = true, eyeMenu = true),
    Case("61b_FirstRun_EyeMenu", data = notPaired, engine = { testEngine(it, ready = true) }, dialog = true, eyeMenu = true),
    Case("61c_Chats", data = { s -> noWhisperRunning(s).copy(pastChats = listOf(
        io.github.odnwks.mimir.data.PastChat("p1", "Make the tablet layout faster", 1_763_500_000_000, 6),
        io.github.odnwks.mimir.data.PastChat("p2", "Why does the Recipe box sync stop", 1_763_300_000_000, 4),
    )) }, engine = { testEngine(it, ready = true, turns = listOf(
        io.github.odnwks.mimir.standalone.Turn(true, "Give me three easy dinner ideas"),
        io.github.odnwks.mimir.standalone.Turn(false, "Pasta, a stir fry, and soup."),
    )) }) { open(Screen.Chats) },
    Case("61d_Chat_WeatherHere", data = { s -> noWhisperRunning(s).copy(chat = listOf(
        io.github.odnwks.mimir.data.ChatEntry.Mine("m1", "what is the weather forecast for Portland", null),
        io.github.odnwks.mimir.data.ChatEntry.Reply("r1", listOf(
            "Weather for Portland, Oregon, United States, from Open-Meteo at 07:45 local time.",
            "Now: 48 °F (feels like 45 °F), partly cloudy, wind 4 mph.",
            "Today (2026-11-19): light rain, 44 to 58 °F, 60% chance of rain, wind up to 9 mph.",
            "From open-meteo.com",
        ), "Play the answer"),
    )) }),
    // Search: the one-time warning, and a blocked search offering its results (owner, 2026-11-19).
    Case("62a_FirstRun_SearchWarning", data = notPaired, engine = {
        io.github.odnwks.mimir.online.Online.set(io.github.odnwks.mimir.online.Online.Settings())
        testEngine(it, ready = true).apply {
            send("search for cheap flights to Oslo")
            val until = System.currentTimeMillis() + 5_000
            Thread.sleep(20)
            while (state.value.writing && System.currentTimeMillis() < until) Thread.sleep(10)
        }
    }),
    Case("62b_FirstRun_SearchBlocked", data = notPaired, engine = {
        testEngine(it, ready = true, turns = listOf(
            io.github.odnwks.mimir.standalone.Turn(true, "search for cheap flights to Oslo"),
            io.github.odnwks.mimir.standalone.Turn(false, "DuckDuckGo blocked the search just now, so I can't see the results. You can open them below.",
                sources = listOf(io.github.odnwks.mimir.online.Source("DuckDuckGo", io.github.odnwks.mimir.online.Search.resultsPage("cheap flights to Oslo"), "cheap flights to Oslo", results = true))),
        ))
    }),
    // Reading a page the owner sent: one tap on "Read …?" under the message.
    Case("63_FirstRun_ReadLink", data = notPaired, engine = {
        testEngine(it, ready = true, turns = listOf(
            io.github.odnwks.mimir.standalone.Turn(true, "is this true? https://news.example.com/bridge"),
            io.github.odnwks.mimir.standalone.Turn(false, "I haven't read that page, so I can't say. Tap Read to have me read it."),
        ))
    }),
    // A Whisper with internet: the closed eye with the globe, "via Tor", and its one-time notice.
    Case("64a_FirstRun_WhisperWeb", data = notPaired, engine = { testEngine(it, ready = true).apply { startWhisper(web = true) } }),
    Case("64b_FirstRun_WhisperWebMenu", data = notPaired, engine = { testEngine(it, ready = true).apply { startWhisper(web = true) } }, dialog = true, eyeMenu = true),
    Case("68_Updates_AppUpdate", data = { it.copy(pcVersion = "0.19") }) { selectTab(Tab.Updates) },
)

/** The weekly check's card, as the PC sends it. */
val worthSwitching = io.github.odnwks.mimir.data.Update(
    "u-worth", "Recipe box", "Mon", "Monday",
    "Worth switching? A new small uncensored model, Tiny 3B [example], scores 9.6 on the uncensored-models leaderboard, above your phone's best. Ask me to try it and add it to On this phone; nothing changes until you do.",
    io.github.odnwks.mimir.data.Heard.New, io.github.odnwks.mimir.data.UpdateTarget.Models, 10,
)

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class ScreenshotTest(private val case: Case, private val fontScale: Float, private val width: Int) {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = org.junit.rules.TemporaryFolder()

    @Test fun screen() {
        // The owner's S25 shows 339 dp of width (and text at 0.9); the boards were drawn at 390.
        if (width != 390) org.robolectric.RuntimeEnvironment.setQualifiers("w${width}dp-h734dp-xhdpi")
        val station = FakeStation(case.data(if (case.asleep) ExampleData.asleep() else ExampleData.awake()))
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(io.github.odnwks.mimir.ui.theme.AppSize.PREFS, 0).edit()
            .putFloat(io.github.odnwks.mimir.ui.theme.AppSize.SCALE, case.scale)
            .putFloat(io.github.odnwks.mimir.ui.theme.AppSize.CHAT_TEXT, case.chatText).commit()
        val nav = Navigator().apply(case.go)
        val engine = case.engine?.invoke(tmp.newFolder())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalPlayerRuns provides false,
                LocalPhoneModels provides (case.phone?.invoke() ?: examplePhoneModels()),
                LocalPhoneEngine provides engine,
                LocalVoices provides (case.voices?.invoke() ?: exampleVoices()),
                LocalEyeMenuOpen provides case.eyeMenu,
            ) {
                MimirTheme {
                    AppRoot(station, nav, chatDraft = case.chatDraft)
                }
            }
        }
        compose.waitForIdle()
        val scale = (if (width != 390) "s25_" else "") + if (fontScale <= 1f) "normal" else "font200"
        val file = "${System.getProperty("roborazzi.outputDir")}/${case.name}_$scale.png"
        if (case.dialog) com.github.takahirom.roborazzi.captureScreenRoboImage(file)
        else compose.onRoot().captureRoboImage(file)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} at {1}x, {2} dp")
        // Normal and large text, at the boards' 390 dp and at the S25's 339 dp (where normal is 0.9).
        fun params(): List<Array<Any>> = cases.flatMap { c ->
            listOf(arrayOf<Any>(c, 1f, 390), arrayOf<Any>(c, 2f, 390), arrayOf<Any>(c, 0.9f, 339), arrayOf<Any>(c, 2f, 339))
        }
    }
}

/** The phone's own models for checks: the default one downloaded and in use. */
fun examplePhoneModels(chosen: (String) -> Unit = {}): PhoneModels {
    val default = io.github.odnwks.mimir.standalone.Catalog.default.id
    val state = io.github.odnwks.mimir.standalone.LocalState(supported = true, model = default, have = setOf(default))
    return PhoneModels(kotlinx.coroutines.flow.MutableStateFlow(state), chosen)
}

/** The voices for checks: what [state] says, buttons remembered in [asked]. */
fun exampleVoices(
    state: io.github.odnwks.mimir.audio.VoicesState = io.github.odnwks.mimir.audio.VoicesState(),
    asked: MutableList<String> = ArrayList(),
): VoicesUi {
    val flow = kotlinx.coroutines.flow.MutableStateFlow(state)
    return VoicesUi(flow, object : io.github.odnwks.mimir.audio.VoiceActions {
        override fun choose(id: String?) { asked += "choose $id" }
        override fun sample(id: String) { asked += "sample $id" }
    })
}

/** No-op store buttons, so the Model Store shows them in checks. */
object NoActions : PhoneModelActions {
    override fun download(id: String) {}
    override fun cancelDownload() {}
    override fun removePartial(id: String) {}
    override fun delete(id: String) {}
    override fun test(id: String) {}
    override fun cancelTest() {}
}

/**
 * A Model Store for checks, on a 12 GB phone: the default model in use and tested, the quick one
 * downloaded (or being tested), the smartest one downloading, the last not downloaded.
 */
fun storePhoneModels(testing: Boolean = false): PhoneModels {
    val c = io.github.odnwks.mimir.standalone.Catalog
    val default = c.default.id
    val quick = "gemma4-e2b-heretic-q4km"
    val state = io.github.odnwks.mimir.standalone.LocalState(
        supported = true, model = default, have = setOf(default, quick),
        status = mapOf(
            default to io.github.odnwks.mimir.standalone.ModelStatus.Ready,
            quick to io.github.odnwks.mimir.standalone.ModelStatus.Ready,
            "gemma4-e4b-ultra-heretic-q4km" to io.github.odnwks.mimir.standalone.ModelStatus.Downloading(2_100_000_000, 5_335_289_696),
        ),
        tests = mapOf(
            default to io.github.odnwks.mimir.standalone.TestResult(
                at = 1_795_000_000_000, loaded = true, loadMs = 5_700, wordsPerSecond = 8.4, memoryMb = 2_100,
                tempBefore = 31f, tempAfter = 34f, sensible = 3, asked = 3, refused = false,
            ),
        ),
        testing = if (testing) io.github.odnwks.mimir.standalone.TestProgress(quick, 2, 5, "Asking everyday question 2 of 3") else null,
    )
    return PhoneModels(kotlinx.coroutines.flow.MutableStateFlow(state), {}, NoActions, 11_500_000_000)
}

/** The Model Store with a folder chosen for your own models: a Qwen, a Gemma that sees photos, one it can't run and one too big. */
fun ownPhoneModels(): PhoneModels {
    val own = listOf(
        io.github.odnwks.mimir.standalone.OwnEntry("d1", "Qwen3-4B-abliterated-Q4_K_M.gguf", 2_500_000_000, io.github.odnwks.mimir.standalone.Format.ChatMl, true, true),
        io.github.odnwks.mimir.standalone.OwnEntry(
            "d2", "gemma-4-E2B-it-Q4_K_M.gguf", 3_400_000_000, io.github.odnwks.mimir.standalone.Format.Gemma4, false, false,
            io.github.odnwks.mimir.standalone.OwnFile("d3", "mmproj-gemma-4-E2B-it-F16.gguf", 900_000_000),
        ),
        io.github.odnwks.mimir.standalone.OwnEntry("d4", "llama-2-7b.gguf", 3_800_000_000, null, false, false),
        io.github.odnwks.mimir.standalone.OwnEntry("d5", "Qwen3-32B-Q4_K_M.gguf", 19_000_000_000, io.github.odnwks.mimir.standalone.Format.ChatMl, true, false),
    )
    val state = io.github.odnwks.mimir.standalone.LocalState(
        supported = true, model = "own:d1", have = setOf("own:d1"), ownFolder = "Mimir models", own = own,
    )
    return PhoneModels(kotlinx.coroutines.flow.MutableStateFlow(state), {}, NoActions, 11_500_000_000)
}
