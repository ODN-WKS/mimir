package io.github.odnwks.mimir.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import io.github.odnwks.mimir.nav.Tab
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.data.Station
import io.github.odnwks.mimir.nav.Move
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.nav.Screen
import io.github.odnwks.mimir.nav.TapPoint
import io.github.odnwks.mimir.standalone.Engine
import androidx.compose.ui.platform.LocalContext
import io.github.odnwks.mimir.ui.theme.LocalReduceMotion
import io.github.odnwks.mimir.ui.theme.Motion
import io.github.odnwks.mimir.ui.theme.Palette
import io.github.odnwks.mimir.ui.theme.motionSpec
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import io.github.odnwks.mimir.ui.adaptive.Centered
import io.github.odnwks.mimir.ui.adaptive.Limits
import io.github.odnwks.mimir.ui.adaptive.LocalAskTick
import io.github.odnwks.mimir.ui.adaptive.LocalPaneBack
import io.github.odnwks.mimir.ui.adaptive.PaneContent
import io.github.odnwks.mimir.ui.adaptive.PanePlaceholder
import io.github.odnwks.mimir.ui.adaptive.PaneRules
import io.github.odnwks.mimir.ui.adaptive.Rail
import io.github.odnwks.mimir.ui.adaptive.RailItem
import io.github.odnwks.mimir.ui.adaptive.Shortcut
import io.github.odnwks.mimir.ui.adaptive.escBack
import io.github.odnwks.mimir.ui.adaptive.keyboardAttached
import io.github.odnwks.mimir.ui.adaptive.rememberWindowShape
import io.github.odnwks.mimir.ui.adaptive.shortcuts
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union

/** The whole app: the current screen, the bottom bar, the sheets, and short stand-in notes. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppRoot(station: Station, nav: Navigator, chatDraft: String = "", standaloneOk: Boolean = true) {
    val state by station.state.collectAsStateWithLifecycle()
    val tracker = remember { TapTracker() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val standIn: (String) -> Unit = { text ->
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }
    }
    val reduce = LocalReduceMotion.current
    val saveable = rememberSaveableStateHolder()
    // One read-aloud speed for the PC's voice and the phone's (Settings › Voice).
    val voice = rememberReadAloud()
    // The saved speed goes to the player when the app opens; until the player has it, the
    // player's own 1x must not overwrite it. After that, the player's speed is the one saved.
    val savedSpeed = remember { io.github.odnwks.mimir.audio.ReadSpeed.snap(voice.speed) }
    var speedRestored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { station.setSpeed(savedSpeed) }
    LaunchedEffect(state.player.speed) {
        if (!speedRestored && kotlin.math.abs(state.player.speed - savedSpeed) > 0.001f) return@LaunchedEffect
        speedRestored = true
        voice.speed = state.player.speed
    }

    // Forget the scroll positions and drafts of screens that left the back stack.
    // The right pane keeps its own keys: for a frame, an item moving between the panes is drawn in both.
    val liveKeys: Set<Any> = nav.stack.map { it.key }.toSet() + nav.detail.map { "right-${it.key}" }
    val seenKeys = remember { mutableSetOf<Any>() }
    LaunchedEffect(liveKeys) {
        (seenKeys - liveKeys).forEach { saveable.removeState(it) }
        seenKeys.retainAll(liveKeys)
        seenKeys.addAll(liveKeys)
    }

    BackHandler(enabled = nav.canGoBack) { nav.back() }
    // The eye's startup animation: once per app start, only when the app opens on the home screen.
    val startup = rememberEyeStartup(opensOnHome = nav.stack.size == 1 && nav.current == Screen.Chat)

    // The undo bar: one thing at a time; a new one ends the old one's wait (it's done for good).
    var undoable by remember { mutableStateOf<Undoable?>(null) }
    var undoLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(undoable) {
        val u = undoable ?: return@LaunchedEffect
        // PHU-25: 8-second undo bar (was 10).
        for (left in 8 downTo 1) { undoLeft = left; kotlinx.coroutines.delay(1000) }
        if (undoable === u) { undoable = null; u.later() }
    }
    val offerUndo: (Undoable) -> Unit = { u -> undoable?.later(); undoable = u }
    CompositionLocalProvider(LocalStandIn provides standIn, LocalTapTracker provides tracker, LocalStandaloneOk provides standaloneOk, LocalUndo provides offerUndo, LocalOpenPage provides { url: String -> nav.open(Screen.Browse(url)) }, LocalEyeStartup provides startup) {
        Box(
            Modifier.fillMaxSize().background(Palette.Ground)
                // Sideways, the system's buttons and the camera cut-out sit at the sides: nothing goes under them.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
                .pointerInput(Unit) {
                    // Remember where each touch starts (without taking it), so a tapped card can grow from there.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        tracker.last = TapPoint(down.position.x / size.width, down.position.y / size.height)
                        startup.onTouch() // a touch anywhere skips the startup animation at once
                    }
                },
        ) {
            val shape = rememberWindowShape()
            // Two panes only once paired: before that the app is one plain chat on every size.
            val twoPanes = state.paired && shape.twoPanes
            androidx.compose.runtime.SideEffect { nav.fitPanes(twoPanes) }
            val rail = state.paired && shape.rail
            // The side menu: always open on the left of a wide window, a drawer from the left elsewhere.
            val permanent = shape.permanentMenu
            val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
            LaunchedEffect(permanent) { if (permanent) drawer.snapTo(androidx.compose.material3.DrawerValue.Closed) }
            val openMenu: () -> Unit = { if (!permanent) scope.launch { drawer.open() } }
            val closeMenu: () -> Unit = { if (drawer.isOpen) scope.launch { drawer.close() } }
            DrawerBack(drawer, closeMenu)
            val ctx = LocalContext.current
            val standalone = LocalStandaloneOk.current
            val given = LocalPhoneEngine.current
            val engine = remember(given, standalone, state.paired) { given ?: if (keepsChatsHere(standalone, state.paired)) Engine.get(ctx) else null }
            val local = engine?.state?.collectAsStateWithLifecycle()?.value
            val fold = remember { MenuFold(ctx.getSharedPreferences(MenuFold.PREFS, 0)) }
            val menu: @Composable (Modifier) -> Unit = { m -> SideMenu(state, local, station, engine, nav, fold, m, close = closeMenu) }
            val body: @Composable () -> Unit = {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxSize()
                    .escBack(nav.canGoBack) { nav.back() }
                    .shortcuts(cardAsking = { false }) { cut -> navShortcut(cut, nav, state.paired) },
            ) {
            val panes = shape.panes
            val right = if (twoPanes) rightPane(nav) else null
            Column(
                Modifier.fillMaxHeight()
                    .then(if (right != null && panes != null) Modifier.width(panes.left.dp) else Modifier.weight(1f))
                    .semantics { isTraversalGroup = true; traversalIndex = 1f },
            ) {
                AnimatedContent(
                    targetState = nav.currentEntry,
                    modifier = Modifier.weight(1f),
                    transitionSpec = { transitionFor(nav.lastMove, reduce) },
                    contentKey = { it.key },
                    label = "screen",
                ) { entry ->
                    // The home screens keep their own saved state (a half-written message) while another one shows.
                    saveable.SaveableStateProvider(if (entry.screen.tabPage) "tab-${entry.screen}" else entry.key) {
                        // The keyboard pushes up only what sits above it: Chat and a task lift just their
                        // composer (and the dock), so the rest isn't measured again on every frame of
                        // the keyboard's slide; rarer screens with a box lift as a whole.
                        Box(Modifier.fillMaxSize().background(entry.screen.ground()).windowInsetsPadding(WindowInsets.statusBars).then(if (entry.screen.liftsOwnComposer) Modifier else Modifier.imePadding())) {
                            Limited(rail || permanent, entry.screen.bottomBar, if (permanent) Limits.Lane else Limits.Reading) { ScreenFor(entry.screen, state, station, nav, chatDraft) }
                        }
                    }
                }
            }
            if (right != null) {
                Spacer(Modifier.width(PaneRules.DIVIDER.dp).fillMaxHeight().background(Palette.Line))
                Box(Modifier.weight(1f).fillMaxHeight().semantics { isTraversalGroup = true; traversalIndex = 2f }) {
                    PaneContent(right, opensFromRight = nav.detailForward) { r ->
                        CompositionLocalProvider(LocalPaneBack provides nav.detail.isNotEmpty()) {
                            val screen = r.entry?.screen
                            if (screen == null) PanePlaceholder(r.placeholder)
                            else saveable.SaveableStateProvider("right-${r.entry.key}") {
                                Box(Modifier.fillMaxSize().background(screen.ground()).windowInsetsPadding(WindowInsets.statusBars).then(if (screen.liftsOwnComposer) Modifier else Modifier.imePadding())) {
                                    Limited(true, screen.bottomBar) { ScreenFor(screen, state, station, nav, chatDraft) }
                                }
                            }
                        }
                    }
                }
            }
            }
            }
            // Turning the screen moves the body (and the menu) between a wide row and a drawer: movable content keeps
            // what is composed (the chat, its lists, the open page) instead of building it again on every turn.
            var menuSeen by remember { mutableStateOf(false) }
            val bodyNow by androidx.compose.runtime.rememberUpdatedState(body)
            val menuNow by androidx.compose.runtime.rememberUpdatedState(menu)
            val bodyMoves = remember { androidx.compose.runtime.movableContentOf { bodyNow() } }
            val menuMoves = remember { androidx.compose.runtime.movableContentOf { m: Modifier -> menuNow(m) } }
            CompositionLocalProvider(LocalOpenMenu provides openMenu, LocalPermanentMenu provides permanent, LocalMenuCount provides menuCount(state)) {
                if (permanent) {
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                        menuMoves(Modifier.width(shape.menuWidth.dp).fillMaxHeight().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))).semantics { isTraversalGroup = true; traversalIndex = 0f })
                        Spacer(Modifier.width(PaneRules.DIVIDER.dp).fillMaxHeight().background(Palette.Line))
                        Box(Modifier.weight(1f).fillMaxHeight()) { bodyMoves() }
                    }
                } else {
                    androidx.compose.material3.ModalNavigationDrawer(
                        drawerContent = {
                            androidx.compose.material3.ModalDrawerSheet(drawerContainerColor = androidx.compose.ui.graphics.Color(0xFF15181D), modifier = Modifier.width(310.dp)) {
                                // Built when first opened and then kept (off screen while closed), so every later opening costs no
                                // new composition on its first frames; hidden from TalkBack while closed. The state is read inside
                                // the small wrapper below, so opening and closing never recompose the menu itself.
                                if (menuSeen || drawer.currentValue != androidx.compose.material3.DrawerValue.Closed || drawer.targetValue != androidx.compose.material3.DrawerValue.Closed) menuSeen = true
                                if (menuSeen) ClosedMenuQuiet(drawer) { menuMoves(Modifier.fillMaxHeight()) }
                            }
                        },
                        // Opening is the menu button or the swipe from the chat's left edge; once open, a swipe or tap outside closes it.
                        gesturesEnabled = drawer.isOpen,
                        drawerState = drawer,
                    ) { bodyMoves() }
                }
            }
            UndoBar(
                undoable, undoLeft, { undoable?.undo?.invoke(); undoable = null },
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).imePadding().padding(bottom = 72.dp),
            )
            Box(Modifier.imePadding()) { SheetHost(state, station, nav) }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = 132.dp)) { data ->
                Snackbar(containerColor = Palette.Card, contentColor = Palette.Text) { Text(data.visuals.message) }
            }
        }
    }
}

/** Chat and a task lift only their composer above the keyboard. */
private val Screen.liftsOwnComposer: Boolean get() = this == Screen.Chat || this is Screen.Task

private fun Screen.ground() = when (this) {
    Screen.IncognitoTask -> WhisperTint.Ground
    Screen.Notifications -> Palette.NotificationGround
    else -> Palette.Ground
}

/**
 * Whether this phone keeps the chats (its own store): it can run a model, or it is paired (the PC's chats
 * are kept here too). Chat, History and Storage all use this one rule, so they list the same chats (PHW-01).
 */
internal fun keepsChatsHere(standaloneOk: Boolean, paired: Boolean): Boolean = standaloneOk || paired

@Composable
private fun ScreenFor(screen: Screen, state: io.github.odnwks.mimir.data.StationState, station: Station, nav: Navigator, chatDraft: String) {
    when (screen) {
        // An older PC (no "talk") keeps today's paired chat; otherwise one chat, paired or not (Stage 1, 2.1).
        Screen.Chat -> if (state.paired && !state.pcTalks) CompositionLocalProvider(LocalAskTick provides nav.askTick) { ChatScreen(state, station, nav, chatDraft) } else {
            val ctx = LocalContext.current
            val ok = LocalStandaloneOk.current
            val stand = LocalPhoneEngine.current
            PhoneChat(
                remember(stand, ok, state.paired) { stand ?: if (keepsChatsHere(ok, state.paired)) Engine.get(ctx) else null }, nav, paired = state.paired, pcOnline = state.pc.online, pcName = state.pc.name,
                task = state::task, onMoveTask = { id, p -> station.moveTask(id, p) }, menuWaiting = menuCount(state),
                needsYou = state.needsYou.size, unheard = unheardUpdates(state), pcModel = state.models.selectedName, claudeAnswers = state.models.chatsEngine == "paid",
                claudeOffered = state.models.chatsEngine != null, owner = state.owner, onPickEngine = { station.setEngines(it, null) },
                claudeModel = state.models.chatsModel, claudeChoices = state.models.chatModels, onPickClaudeModel = station::setChatModel,
            )
        }
        Screen.Status -> StatusScreen(state, station, nav)
        Screen.Updates -> UpdatesScreen(state, station, nav)
        Screen.Settings -> SettingsScreen(state, nav)
        Screen.NeedsYou -> NeedsYouScreen(state, station, nav)
        is Screen.Task -> TaskScreen(screen.taskId, state, station, nav)
        is Screen.Change -> ChangeScreen(screen.taskId, state, station, nav)
        is Screen.Project -> ProjectScreen(screen.name, state, station, nav)
        Screen.Models -> {
            val ctx = LocalContext.current
            val ok = LocalStandaloneOk.current
            val stand = LocalPhoneModels.current
            // A phone that can't run a model still sees the list, without the engine.
            val eng = LocalPhoneEngine.current
            val phone = remember(stand, ok, eng) { stand ?: if (ok) (eng ?: Engine.get(ctx)).storeModels(ctx) else null }
            ModelsScreen(state, station, nav, phone)
        }
        is Screen.ModelDetails -> {
            val ctx = LocalContext.current
            val ok = LocalStandaloneOk.current
            val stand = LocalPhoneModels.current
            val eng = LocalPhoneEngine.current
            val phone = remember(stand, ok, eng) { stand ?: if (ok) (eng ?: Engine.get(ctx)).storeModels(ctx) else null }
            ModelDetailsScreen(screen.id, nav, phone)
        }
        Screen.PhonePc -> PhonePcScreen(state, station, nav)
        Screen.Notifications -> NotificationsScreen(state, station, nav)
        Screen.IncognitoTask -> IncognitoTaskScreen(state, station, nav)
        Screen.Pair -> PairScreen(station, nav, state.screenLock)
        Screen.PairDone -> PairDoneScreen(state, nav)
        Screen.PairError -> PairErrorScreen(state, nav)
        Screen.Chats -> ChatsScreen(state, station, LocalPhoneEngine.current ?: if (keepsChatsHere(LocalStandaloneOk.current, state.paired)) Engine.get(LocalContext.current) else null, nav)
        Screen.Standalone -> StandaloneScreen(LocalPhoneEngine.current ?: Engine.get(LocalContext.current), nav)
        Screen.StandaloneModels -> StandaloneModelsScreen(LocalPhoneEngine.current ?: Engine.get(LocalContext.current), nav)
        is Screen.AllTasks -> AllTasksScreen(state, station, nav, screen.archived)
        Screen.General -> GeneralScreen(state, nav)
        Screen.Storage -> StorageScreen(LocalPhoneEngine.current ?: if (keepsChatsHere(LocalStandaloneOk.current, state.paired)) Engine.get(LocalContext.current) else null, nav, paired = state.paired)
        Screen.Voice -> VoiceScreen(state, station, nav)
        Screen.Voices -> VoicesScreen(nav)
        Screen.About -> AboutScreen(state, nav)
        Screen.Help -> HelpScreen(state, nav)
        Screen.Labs -> LabsScreen(nav)
        is Screen.HelpAnswer -> HelpAnswerScreen(screen.id, state, nav)
        Screen.GroundRules -> GroundRulesScreen(nav)
        Screen.PrivacyCheck -> PrivacyCheckScreen(state, nav)
        is Screen.Browse -> BrowseScreen(screen.url, nav, LocalPhoneEngine.current ?: if (LocalStandaloneOk.current) Engine.get(LocalContext.current) else null)
    }
}

/**
 * The Motion board: a new screen slides in from the side you went while the old one eases back
 * and fades (300 ms); a tapped card grows into its screen from the finger (280 ms).
 * Only position, scale and fade change. With animations removed, screens swap at once.
 */
fun transitionFor(move: Move, reduce: Boolean): ContentTransform {
    if (reduce || move == Move.None) return EnterTransition.None togetherWith ExitTransition.None
    val slide = Motion.spec<androidx.compose.ui.unit.IntOffset>(Motion.SCREEN_MS, false)
    return when (move) {
        Move.Forward, is Move.ToTab -> {
            val fromRight = move == Move.Forward || (move as Move.ToTab).toRight
            val sign = if (fromRight) 1 else -1
            // Slide only, no fade: both screens are solid, and a fade makes the phone's graphics chip
            // draw each whole screen into a second layer on every frame of the move.
            slideInHorizontally(slide) { sign * it } togetherWith slideOutHorizontally(slide) { -sign * it / 4 }
        }
        Move.Back -> ContentTransform(
            slideInHorizontally(slide) { -it / 4 },
            slideOutHorizontally(slide) { it },
            targetContentZIndex = -1f,
        )
        is Move.CardOpen -> {
            val grow = Motion.spec<Float>(Motion.CARD_OPEN_MS, false)
            (scaleIn(grow, initialScale = 0.6f, transformOrigin = TransformOrigin(move.x, move.y)) + fadeIn(grow)) togetherWith
                fadeOut(Motion.spec(Motion.CARD_OPEN_MS, false))
        }
        Move.None -> EnterTransition.None togetherWith ExitTransition.None
    }
}

/** What the right pane shows: what was opened there, or the section's own companion. */
private data class RightPane(val entry: io.github.odnwks.mimir.nav.Entry?, val placeholder: String = "")

/** Null when the left screen has nothing to show beside it (the window keeps one wide pane). */
private fun rightPane(nav: Navigator): RightPane? {
    nav.detail.lastOrNull()?.let { return RightPane(it) }
    return when (nav.current) {
        // Landscape opens on Chat with Needs you beside it.
        Screen.Chat -> RightPane(io.github.odnwks.mimir.nav.Entry(Screen.NeedsYou, -2))
        Screen.Status, is Screen.AllTasks -> RightPane(null, "Pick a task to see it here")
        // Settings opens on its first section, General.
        Screen.Settings -> RightPane(io.github.odnwks.mimir.nav.Entry(Screen.General, -3))
        else -> null
    }
}

/**
 * From 600 dp of width nothing stretches: the screen sits in a 640 dp column in the middle of its pane.
 * Screens that had the bottom bar under them keep clear of the system's bar at the bottom instead.
 */
@Composable
private fun Limited(on: Boolean, clearBottom: Boolean, max: androidx.compose.ui.unit.Dp = Limits.Reading, content: @Composable () -> Unit) {
    if (!on) { content(); return }
    Centered(max, if (clearBottom) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier) { content() }
}

/**
 * Actions a keyboard shortcut can trigger; injectable for unit tests (PHU-15).
 */
interface ShortcutActions {
    fun goToChat()
    fun goToStatus()
    fun goToUpdates()
    fun goToSettings()
    fun askClaude()
    fun search()
    fun nextNeedsYou()
    fun allowCard()
    fun dontAllowCard()
    fun pauseClaude()
    fun openTalk()
    fun showKeyList()
    fun sendMessage()
}

/** Routes a [Shortcut] to the appropriate action (PHU-15). Returns true when handled. */
fun handleShortcut(cut: Shortcut, actions: ShortcutActions, paired: Boolean, cardAsking: Boolean): Boolean {
    // Before pairing, only tab navigation and Settings.
    if (!paired && cut !in setOf(Shortcut.Chat, Shortcut.Status, Shortcut.Updates, Shortcut.Settings, Shortcut.Keys)) return false
    when (cut) {
        Shortcut.Chat -> actions.goToChat()
        Shortcut.Status -> actions.goToStatus()
        Shortcut.Updates -> actions.goToUpdates()
        Shortcut.Settings -> actions.goToSettings()
        Shortcut.AskClaude, Shortcut.MessageBox -> actions.askClaude()
        Shortcut.Search -> actions.search()
        Shortcut.NextNeedsYou -> actions.nextNeedsYou()
        Shortcut.Allow -> if (cardAsking) actions.allowCard() else return false
        Shortcut.DontAllow -> if (cardAsking) actions.dontAllowCard() else return false
        Shortcut.Pause -> actions.pauseClaude()
        Shortcut.Talk -> actions.openTalk()
        Shortcut.Keys -> actions.showKeyList()
        Shortcut.Send -> actions.sendMessage()
        else -> return false
    }
    return true
}

/** The keyboard shortcuts that move around the app (Section 7 of the tablet redesign). */
private fun navShortcut(cut: Shortcut, nav: Navigator, paired: Boolean): Boolean {
    // Before pairing, the tabs and Settings; the rest needs the PC.
    if (!paired && cut !in setOf(Shortcut.Chat, Shortcut.Status, Shortcut.Updates, Shortcut.Settings)) return false
    when (cut) {
        Shortcut.Chat -> nav.selectTab(Tab.Chat)
        Shortcut.Status -> nav.selectTab(Tab.Status)
        Shortcut.Updates -> nav.selectTab(Tab.Updates)
        Shortcut.Settings -> nav.open(Screen.Settings)
        Shortcut.AskClaude, Shortcut.MessageBox -> nav.ask()
        Shortcut.Search -> nav.open(Screen.AllTasks())
        Shortcut.NextNeedsYou -> nav.open(Screen.NeedsYou)
        else -> return false
    }
    return true
}

/** Back closes an open menu. The drawer's state is read here, so a change recomposes only this. */
@Composable
private fun DrawerBack(drawer: androidx.compose.material3.DrawerState, close: () -> Unit) {
    BackHandler(enabled = drawer.isOpen) { close() }
}

/** The kept menu is invisible to TalkBack and tests while shut. The drawer's state is read here, not in the menu. */
@Composable
private fun ClosedMenuQuiet(drawer: androidx.compose.material3.DrawerState, content: @Composable () -> Unit) {
    val shut = drawer.currentValue == androidx.compose.material3.DrawerValue.Closed && drawer.targetValue == androidx.compose.material3.DrawerValue.Closed
    Box(Modifier.fillMaxSize().then(if (shut) Modifier.clearAndSetSemantics { } else Modifier)) { content() }
}
