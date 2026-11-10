package app.fluff.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.fluff.FluffApp
import app.fluff.ui.components.pressScale
import app.fluff.ui.fluffViewModel
import app.fluff.ui.history.HistoryScreen
import app.fluff.ui.history.HistoryViewModel
import app.fluff.ui.history.SessionDetailScreen
import app.fluff.ui.history.SessionDetailViewModel
import app.fluff.ui.onboarding.OnboardingScreen
import app.fluff.ui.profile.ProfileActions
import app.fluff.ui.profile.ProfileScreen
import app.fluff.ui.profile.ProfileViewModel
import app.fluff.ui.profile.openCustomTab
import app.fluff.ui.profile.rememberHealthConnectRequest
import app.fluff.ui.progress.ProgressActions
import app.fluff.ui.progress.ProgressScreen
import app.fluff.ui.progress.ProgressViewModel
import app.fluff.ui.theme.Fluff
import app.fluff.ui.today.TodayActions
import app.fluff.ui.today.TodayScreen
import app.fluff.ui.today.TodayViewModel
import app.fluff.ui.workout.SummaryScreen
import app.fluff.ui.workout.SummaryViewModel
import app.fluff.ui.workout.WorkoutActions
import app.fluff.ui.workout.WorkoutScreen
import app.fluff.ui.workout.WorkoutViewModel
import kotlinx.serialization.Serializable

@Serializable object TodayRoute
@Serializable object ProgressRoute
@Serializable object HistoryRoute
@Serializable object ProfileRoute
@Serializable data class WorkoutRoute(val freestyle: Boolean = false)
@Serializable object OnboardingRoute
@Serializable data class SummaryRoute(val sessionId: Long)
@Serializable data class SessionRoute(val sessionId: Long)

private data class Tab(val route: Any, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(TodayRoute, "Today", Icons.Rounded.WbSunny),
    Tab(ProgressRoute, "Progress", Icons.Rounded.BarChart),
    Tab(HistoryRoute, "History", Icons.Rounded.CalendarMonth),
    Tab(ProfileRoute, "Profile", Icons.Rounded.Person),
)

@Composable
fun FluffRoot(onboarded: Boolean) {
    val nav = rememberNavController()
    val container = (LocalContext.current.applicationContext as FluffApp).container
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { container.messages.collect { snackbar.showSnackbar(it) } }
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val onTab = tabs.any { t -> dest?.hasRoute(t.route::class) == true }

    Box(Modifier.fillMaxSize().background(Fluff.Night)) {
        NavHost(
            navController = nav,
            startDestination = if (onboarded) TodayRoute else OnboardingRoute,
            enterTransition = { fadeIn(tween(220)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220)) },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(150)) + scaleOut(targetScale = 0.98f) },
            modifier = Modifier.fillMaxSize(),
        ) {
            composable<OnboardingRoute> {
                val vm = fluffViewModel { ProfileViewModel(it) }
                OnboardingScreen(vm) {
                    nav.navigate(TodayRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
                }
            }
            composable<TodayRoute> {
                val vm = fluffViewModel { TodayViewModel(it.engine, it.training, it.prefs, it.sync) }
                val state by vm.state.collectAsStateWithLifecycle()
                LifecycleResumeEffect(Unit) { vm.onResume(); onPauseOrDispose { } }
                TodayScreen(
                    state,
                    TodayActions(
                        refresh = vm::refresh, saveCheckIn = { vm.saveCheckIn(it) },
                        start = { nav.navigate(WorkoutRoute()) }, resume = { nav.navigate(WorkoutRoute()) },
                        startFreestyle = { nav.navigate(WorkoutRoute(freestyle = true)) },
                        openSummary = { nav.navigate(SessionRoute(it)) },
                        shorter = vm::shorter, longer = vm::longer, easier = vm::easier, harder = vm::harder,
                        shuffle = vm::shuffle, focus = vm::focus, reset = vm::reset,
                        openProfile = { nav.navigateTab(ProfileRoute) },
                    ),
                )
            }
            composable<ProgressRoute> {
                val vm = fluffViewModel { ProgressViewModel(it.training, it.prefs, it.engine) }
                val state by vm.state.collectAsStateWithLifecycle()
                ProgressScreen(state, ProgressActions(vm::selectLift, { id, kg, w -> vm.addGoal(id, kg, w) }, { vm.deleteGoal(it) }))
            }
            composable<HistoryRoute> {
                val vm = fluffViewModel { HistoryViewModel(it.training, it.prefs) }
                val state by vm.state.collectAsStateWithLifecycle()
                HistoryScreen(state, onOpen = { nav.navigate(SessionRoute(it)) }, onLog = { t, m, i, d -> vm.logActivity(t, m, i, d) }, onDeleteActivity = { vm.deleteActivity(it) })
            }
            composable<ProfileRoute> {
                val vm = fluffViewModel { ProfileViewModel(it) }
                val state by vm.state.collectAsStateWithLifecycle()
                val ctx = LocalContext.current
                LifecycleResumeEffect(Unit) { vm.refreshConnections(); onPauseOrDispose { } }
                val hc = rememberHealthConnectRequest(state.connections) { vm.onHealthConnectResult(it) }
                ProfileScreen(
                    state,
                    ProfileActions(
                        update = { vm.updateProfile(it) }, updateAndReplan = { vm.updateProfileAndReplan(it) },
                        settings = { vm.updateSettings(it) }, saveClaudeKey = { vm.saveClaudeKey(it) },
                        saveWhoop = { id, s -> vm.saveWhoopCredentials(id, s) },
                        connectWhoop = { vm.whoopAuthUri { openCustomTab(ctx, it) } },
                        disconnectWhoop = { vm.disconnectWhoop() }, connectHealth = hc, syncNow = { vm.syncNow() },
                        connectCloud = { url, token, done -> vm.connectCloud(url, token, done) },
                        disconnectCloud = { vm.disconnectCloud() }, cloudSyncNow = { vm.cloudSyncNow(it) },
                    ),
                )
            }
            composable<WorkoutRoute>(
                enterTransition = { slideInVertically(Fluff.gentle()) { it } + fadeIn() },
                exitTransition = { fadeOut(tween(200)) },
                popExitTransition = { slideOutVertically(tween(280)) { it } + fadeOut() },
            ) { e ->
                val vm = fluffViewModel { WorkoutViewModel(it.training, it.engine, it.prefs, it.finisher, it.json) }
                val freestyle = e.toRoute<WorkoutRoute>().freestyle
                LaunchedEffect(Unit) { vm.begin(freestyle) }
                val state by vm.state.collectAsStateWithLifecycle()
                WorkoutScreen(
                    state,
                    WorkoutActions(
                        minimize = { nav.popBackStack() },
                        select = vm::select, weight = vm::weightStep, reps = vm::repsStep, duration = vm::durationStep, rpe = vm::rpe,
                        log = vm::log, undo = { vm.undo(it) }, skip = { vm.skip(it) }, skipExercise = { vm.skipExercise(it) },
                        addSet = { vm.addSet(it) }, alternatives = vm::alternatives, swap = { g, e -> vm.swap(g, e) },
                        restAdd = vm::restAdd, restSkip = vm::restSkip,
                        finish = { r, n -> vm.finish(r, n) { id -> nav.navigate(SummaryRoute(id)) { popUpTo<WorkoutRoute> { inclusive = true } } } },
                        discard = { vm.discard { nav.popBackStack() } },
                        consumePr = vm::consumePr, consumeToast = vm::consumeToast,
                        edit = vm::edit, cancelEdit = vm::cancelEdit, library = vm::library, addExercise = vm::addExercise,
                    ),
                )
            }
            composable<SummaryRoute>(enterTransition = { fadeIn(tween(400)) + scaleIn(initialScale = 0.92f) }) { e ->
                val id = e.toRoute<SummaryRoute>().sessionId
                val vm = fluffViewModel(key = "summary$id") { SummaryViewModel(id, it.training, it.prefs, it.finisher) }
                val state by vm.state.collectAsStateWithLifecycle()
                SummaryScreen(state, onDone = { nav.popBackStack() })
            }
            composable<SessionRoute>(
                enterTransition = { slideInHorizontally(Fluff.gentle()) { it / 2 } + fadeIn() },
                popExitTransition = { slideOutHorizontally(tween(250)) { it / 2 } + fadeOut() },
            ) { e ->
                val id = e.toRoute<SessionRoute>().sessionId
                val vm = fluffViewModel(key = "session$id") { SessionDetailViewModel(id, it.training, it.prefs) }
                val session by vm.session.collectAsStateWithLifecycle()
                val summary by vm.summary.collectAsStateWithLifecycle()
                val profile by vm.profile.collectAsStateWithLifecycle()
                SessionDetailScreen(session, summary, profile, onBack = { nav.popBackStack() }, onDelete = { vm.delete { nav.popBackStack() } })
            }
        }

        AnimatedVisibility(
            visible = onTab,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (dest?.hasRoute(TodayRoute::class) != true) {
                BottomBar(nav, dest)
            } else {
                // Today has its own start button at the bottom; the tab bar sits above it.
                BottomBar(nav, dest, floating = true)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
    }
}

private fun NavHostController.navigateTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun BottomBar(nav: NavHostController, dest: androidx.navigation.NavDestination?, floating: Boolean = false) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .padding(bottom = if (floating) 74.dp else 0.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Fluff.CardHigh.copy(alpha = 0.96f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { t ->
            val selected = dest?.hasRoute(t.route::class) == true
            val bg by animateColorAsState(if (selected) Fluff.Lime else Fluff.CardHigh.copy(alpha = 0f), Fluff.gentle(), label = "tabBg")
            val fg by animateColorAsState(if (selected) Fluff.Night else Fluff.TextDim, label = "tabFg")
            val source = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .pressScale(source, 0.92f)
                    .clip(CircleShape)
                    .background(bg)
                    .semantics { this.selected = selected }
                    .clickable(source, null, role = Role.Tab) {
                        if (!selected) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); nav.navigateTab(t.route) }
                    }
                    .padding(horizontal = if (selected) 16.dp else 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, t.label, tint = fg, modifier = Modifier.size(22.dp))
                AnimatedVisibility(selected) {
                    Row { Spacer(Modifier.width(6.dp)); Text(t.label, style = MaterialTheme.typography.labelLarge, color = fg) }
                }
            }
        }
    }
}
