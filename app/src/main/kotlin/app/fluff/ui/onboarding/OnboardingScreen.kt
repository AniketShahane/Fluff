package app.fluff.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fluff.core.readiness.ReadinessBand
import app.fluff.integrations.healthconnect.HcAvailability
import app.fluff.ui.components.Eyebrow
import app.fluff.ui.components.FluffCard
import app.fluff.ui.components.GhostButton
import app.fluff.ui.components.PrimaryButton
import app.fluff.ui.components.ReadinessOrb
import app.fluff.ui.profile.ActivitiesEditor
import app.fluff.ui.profile.BodyEditor
import app.fluff.ui.profile.ClaudeKeyEditor
import app.fluff.ui.profile.EquipmentEditor
import app.fluff.ui.profile.ExperiencePicker
import app.fluff.ui.profile.GoalPicker
import app.fluff.ui.profile.LimitationsEditor
import app.fluff.ui.profile.ProfileViewModel
import app.fluff.ui.profile.ScheduleEditor
import app.fluff.ui.profile.WhoopEditor
import app.fluff.ui.profile.openCustomTab
import app.fluff.ui.profile.rememberHealthConnectRequest
import app.fluff.ui.theme.Fluff
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Step(val eyebrow: String, val title: String, val subtitle: String? = null, val optional: Boolean = false)

private val steps = listOf(
    Step("", ""),
    Step("1 · You", "What should we call you?"),
    Step("2 · Goal", "What are you training for?"),
    Step("3 · Experience", "How long have you been lifting?"),
    Step("4 · Schedule", "How often can you train?", "Fluff adapts each day, this is just the rhythm."),
    Step("5 · Equipment", "Where do you train?"),
    Step("6 · Body", "A few numbers", "Used for strength standards and bodyweight exercises."),
    Step("7 · Other sports", "What else do you do?", "Fluff balances lifting around these and scores your strength for them.", optional = true),
    Step("8 · Limitations", "Anything to work around?", optional = true),
    Step("9 · Connect", "Plug in your recovery data", "Optional. Without it, a 10-second morning check-in drives readiness.", optional = true),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(vm: ProfileViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }
    val p = state.profile
    val ctx = LocalContext.current
    val hcRequest = rememberHealthConnectRequest(state.connections) { vm.onHealthConnectResult(it) }
    var sheet by rememberSaveable { mutableIntStateOf(0) } // 1 = WHOOP keys, 2 = Claude key

    fun next() = scope.launch { if (pager.currentPage < steps.lastIndex) pager.animateScrollToPage(pager.currentPage + 1) else { finishing = true; vm.finishOnboarding(onDone) } }
    fun back() = scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    BackHandler(enabled = pager.currentPage > 0 && !finishing) { back() }

    Box(Modifier.fillMaxSize().background(Fluff.Night)) {
        if (finishing) { Building(); return@Box }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (pager.currentPage > 0) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Dots(pager.currentPage, steps.size, Modifier.weight(1f))
                    Spacer(Modifier.width(48.dp))
                }
            }
            HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = false) { page ->
                if (page == 0) { Welcome(); return@HorizontalPager }
                val step = steps[page]
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Eyebrow(step.eyebrow, color = Fluff.Lime)
                    Spacer(Modifier.height(6.dp))
                    Text(step.title, style = MaterialTheme.typography.headlineLarge)
                    step.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Fluff.TextDim, modifier = Modifier.padding(top = 6.dp)) }
                    Spacer(Modifier.height(24.dp))
                    when (page) {
                        1 -> OutlinedTextField(
                            name, { v -> name = v.take(40); vm.updateProfile { it.copy(name = name.trim()) } }, label = { Text("First name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        )
                        2 -> GoalPicker(p.goal) { g -> vm.updateProfile { it.copy(goal = g) } }
                        3 -> ExperiencePicker(p.experience) { e -> vm.updateProfile { it.copy(experience = e) } }
                        4 -> ScheduleEditor(p.daysPerWeek, p.sessionMinutes, { d -> vm.updateProfile { it.copy(daysPerWeek = d) } }, { m -> vm.updateProfile { it.copy(sessionMinutes = m) } })
                        5 -> EquipmentEditor(p.equipment) { e -> vm.updateProfile { it.copy(equipment = e) } }
                        6 -> BodyEditor(p.sex, p.bodyWeightKg, p.unit, p.birthYear,
                            { s -> vm.updateProfile { it.copy(sex = s) } }, { w -> vm.updateProfile { it.copy(bodyWeightKg = w) } },
                            { u -> vm.updateProfile { it.copy(unit = u) } }, { y -> vm.updateProfile { it.copy(birthYear = y) } })
                        7 -> ActivitiesEditor(p.activities) { a -> vm.updateProfile { it.copy(activities = a) } }
                        8 -> LimitationsEditor(p.limitations) { l -> vm.updateProfile { it.copy(limitations = l) } }
                        9 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val c = state.connections
                            ConnectRow(Icons.Rounded.Favorite, Fluff.Rose, "Health Connect", "Fitbit, Pixel Watch, Garmin, Oura, Samsung", c.hcConnected, c.hcAvailability != HcAvailability.UNAVAILABLE) { hcRequest() }
                            ConnectRow(Icons.Rounded.Watch, Fluff.Mint, "WHOOP", "Recovery, sleep and strain", c.whoopConnected, true) {
                                if (c.whoopConfigured) vm.whoopAuthUri { openCustomTab(ctx, it) } else sheet = 1
                            }
                            ConnectRow(Icons.Rounded.AutoAwesome, Fluff.Violet, "Claude coach", "AI-designed sessions with your own API key", c.claudeKeySet, true) { sheet = 2 }
                            Text("You can do all of this later in Profile.", style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint)
                        }
                    }
                }
            }
            val page = pager.currentPage
            Column(Modifier.padding(24.dp)) {
                PrimaryButton(
                    when (page) { 0 -> "Let's build your plan"; steps.lastIndex -> "Build today's session"; else -> "Continue" },
                    { next() }, Modifier.fillMaxWidth(),
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    enabled = page != 1 || name.isNotBlank(),
                )
                if (steps[page].optional && page != steps.lastIndex) {
                    TextButton(onClick = { next() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip", color = Fluff.TextDim) }
                }
            }
        }
    }
    if (sheet != 0) {
        ModalBottomSheet(onDismissRequest = { sheet = 0 }, containerColor = Fluff.Ink) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
                if (sheet == 1) WhoopEditor { id, s -> vm.saveWhoopCredentials(id, s); sheet = 0 }
                else ClaudeKeyEditor(state.connections.claudeKeySet) { k -> vm.saveClaudeKey(k); sheet = 0 }
            }
        }
    }
}

@Composable
private fun Dots(current: Int, total: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        (1 until total).forEach { i ->
            val w by animateDpAsState(if (i == current) 22.dp else 6.dp, Fluff.bouncy(), label = "dot")
            val c by animateColorAsState(if (i <= current) Fluff.Lime else Fluff.Line, label = "dotColor")
            Box(Modifier.padding(horizontal = 3.dp).height(6.dp).width(w).clip(CircleShape).background(c))
        }
    }
}

@Composable
private fun Welcome() {
    // The orb cycles through readiness states to show what Fluff does.
    var i by remember { mutableIntStateOf(0) }
    val demo = listOf(86 to ReadinessBand.PRIME, 58 to ReadinessBand.STEADY, 31 to ReadinessBand.RECOVER, 71 to ReadinessBand.READY)
    LaunchedEffect(Unit) { while (true) { delay(2400); i = (i + 1) % demo.size } }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ReadinessOrb(demo[i].first, demo[i].second, size = 240.dp)
        Spacer(Modifier.height(36.dp))
        Text("fluff", style = MaterialTheme.typography.displayLarge, color = Fluff.Lime)
        Spacer(Modifier.height(10.dp))
        Text("Strength training that listens to your body.", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Every morning Fluff reads your recovery, accounts for your other sports and designs today's session, warm-up to cool-down.",
            style = MaterialTheme.typography.bodyLarge, color = Fluff.TextDim, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConnectRow(icon: ImageVector, color: Color, title: String, sub: String, connected: Boolean, available: Boolean, onClick: () -> Unit) {
    FluffCard(onClick = if (available && !connected) onClick else null, border = if (connected) color.copy(alpha = 0.4f) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = color) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(if (available) sub else "Not available on this device", style = MaterialTheme.typography.bodySmall, color = Fluff.TextDim)
            }
            if (connected) Icon(Icons.Rounded.CheckCircle, "Connected", tint = color)
            else if (available) Text("Connect", style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
private fun Building() {
    var line by remember { mutableIntStateOf(0) }
    val lines = listOf("Reading your recovery…", "Balancing your week…", "Choosing lifts and loads…", "Designing warm-up and cool-down…")
    LaunchedEffect(Unit) { while (true) { delay(900); line = (line + 1) % lines.size } }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ReadinessOrb(null, null, size = 200.dp)
        Spacer(Modifier.height(28.dp))
        androidx.compose.animation.AnimatedContent(lines[line], label = "building") { Text(it, style = MaterialTheme.typography.titleMedium, color = Fluff.TextDim) }
    }
}
