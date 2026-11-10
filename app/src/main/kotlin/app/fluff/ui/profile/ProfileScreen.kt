package app.fluff.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fluff.BuildConfig
import app.fluff.core.model.Equipment
import app.fluff.core.model.UserProfile
import app.fluff.integrations.healthconnect.HcAvailability
import app.fluff.integrations.whoop.WhoopEndpoints
import app.fluff.ui.components.Eyebrow
import app.fluff.ui.components.FluffCard
import app.fluff.ui.components.GhostButton
import app.fluff.ui.components.PrimaryButton
import app.fluff.ui.theme.Fluff
import java.text.DateFormat
import java.util.Date

data class ProfileActions(
    val update: ((UserProfile) -> UserProfile) -> Unit = {},
    val updateAndReplan: ((UserProfile) -> UserProfile) -> Unit = {},
    val settings: ((app.fluff.data.prefs.AppSettings) -> app.fluff.data.prefs.AppSettings) -> Unit = {},
    val saveClaudeKey: (String?) -> Unit = {},
    val saveWhoop: (String, String) -> Unit = { _, _ -> },
    val connectWhoop: () -> Unit = {},
    val disconnectWhoop: () -> Unit = {},
    val connectHealth: () -> Unit = {},
    val syncNow: () -> Unit = {},
    val importFitbod: () -> Unit = {},
)

private enum class Editor { NAME, GOAL, EMPHASIS, EXPERIENCE, SCHEDULE, EQUIPMENT, LIMITS, ACTIVITIES, BODY, CLAUDE, WHOOP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(state: ProfileState, actions: ProfileActions, modifier: Modifier = Modifier) {
    val p = state.profile
    val c = state.connections
    var editor by rememberSaveable { mutableStateOf<Editor?>(null) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp, bottom = 6.dp).clip(MaterialTheme.shapes.large).clickable { editor = Editor.NAME }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Fluff.Lime, Fluff.Mint))), contentAlignment = Alignment.Center) {
                    Text(p.name.trim().firstOrNull()?.uppercase() ?: "F", style = MaterialTheme.typography.headlineMedium, color = Fluff.Night)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(p.name.ifBlank { "Add your name" }, style = MaterialTheme.typography.headlineSmall)
                    Text("${p.goal.label} · ${p.experience.label} · ${p.daysPerWeek}×/week", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
                }
            }
        }
        item { Eyebrow("Connections", Modifier.padding(start = 4.dp, top = 8.dp)) }
        item {
            ConnectionCard(
                icon = Icons.Rounded.Watch, color = Fluff.Mint, title = "WHOOP",
                status = when { c.whoopConnected -> "Connected · recovery, sleep and workouts"; c.whoopConfigured -> "Ready to connect"; else -> "Needs your WHOOP developer app" },
                connected = c.whoopConnected,
            ) {
                when {
                    c.whoopConnected -> GhostButton("Disconnect", actions.disconnectWhoop)
                    c.whoopConfigured -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Connect WHOOP", actions.connectWhoop, height = 48.dp, color = Fluff.Mint)
                        GhostButton("Edit keys", { editor = Editor.WHOOP })
                    }
                    else -> GhostButton("Add client ID & secret", { editor = Editor.WHOOP })
                }
            }
        }
        item {
            ConnectionCard(
                icon = Icons.Rounded.Favorite, color = Fluff.Rose, title = "Health Connect",
                status = when {
                    c.hcAvailability == HcAvailability.UNAVAILABLE -> "Not available on this device"
                    c.hcAvailability == HcAvailability.NEEDS_UPDATE -> "Update Health Connect to continue"
                    c.hcConnected -> "Connected · ${c.hcGranted.size} of ${c.hcAllPermissions.size} permissions"
                    else -> "Fitbit, Pixel Watch, Garmin, Oura, Samsung…"
                },
                connected = c.hcConnected,
            ) {
                if (c.hcAvailability != HcAvailability.UNAVAILABLE) {
                    if (!c.hcConnected || c.hcGranted.size < c.hcAllPermissions.size) {
                        PrimaryButton(if (c.hcAvailability == HcAvailability.NEEDS_UPDATE) "Update" else if (c.hcConnected) "Grant the rest" else "Connect", actions.connectHealth, height = 48.dp, color = Fluff.Rose)
                    }
                    if (c.hcConnected) {
                        ToggleRow("Save workouts to Health Connect", state.settings.writeWorkoutsToHealthConnect) { v -> actions.settings { it.copy(writeWorkoutsToHealthConnect = v) } }
                    }
                }
            }
        }
        item {
            ConnectionCard(
                icon = Icons.Rounded.AutoAwesome, color = Fluff.Violet, title = "Claude coach",
                status = when {
                    !c.claudeKeySet -> "Add your Claude API key to unlock AI-designed sessions"
                    state.settings.aiCoachEnabled -> "On · plans are refined by Claude and checked on-device"
                    else -> "Off · plans come from the on-device engine"
                },
                connected = c.claudeKeySet && state.settings.aiCoachEnabled,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GhostButton(if (c.claudeKeySet) "Change key" else "Add API key", { editor = Editor.CLAUDE })
                }
                if (c.claudeKeySet) ToggleRow("Use the AI coach", state.settings.aiCoachEnabled) { v -> actions.settings { it.copy(aiCoachEnabled = v) } }
            }
        }
        item {
            val last = state.settings.lastSyncEpochMs
            FluffCard(padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (last == 0L) "Never synced" else "Last: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last)),
                            style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint,
                        )
                        state.settings.lastSyncError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Fluff.Amber) }
                    }
                    GhostButton(if (state.syncing) "Syncing…" else "Sync now", actions.syncNow, icon = Icons.Rounded.Sync)
                }
            }
        }
        item { Eyebrow("Training", Modifier.padding(start = 4.dp, top = 8.dp)) }
        item {
            FluffCard(padding = PaddingValues(vertical = 6.dp)) {
                SettingRow("Goal", p.goal.label) { editor = Editor.GOAL }
                SettingRow("Movement emphasis", p.movementEmphasis.label) { editor = Editor.EMPHASIS }
                SettingRow("Experience", p.experience.label) { editor = Editor.EXPERIENCE }
                SettingRow("Schedule", "${p.daysPerWeek} days · ${p.sessionMinutes} min") { editor = Editor.SCHEDULE }
                SettingRow("Equipment", equipmentLabel(p.equipment)) { editor = Editor.EQUIPMENT }
                SettingRow("Limitations", p.limitations.takeIf { it.isNotEmpty() }?.joinToString { it.region.label } ?: "None") { editor = Editor.LIMITS }
                SettingRow("Other sports", p.activities.takeIf { it.isNotEmpty() }?.joinToString { it.type.emoji } ?: "None") { editor = Editor.ACTIVITIES }
            }
        }
        item { Eyebrow("You", Modifier.padding(start = 4.dp, top = 8.dp)) }
        item {
            FluffCard(padding = PaddingValues(vertical = 6.dp)) {
                SettingRow("Body", "${app.fluff.ui.Fmt.weightWithUnit(p.bodyWeightKg, p.unit)} · ${p.sex.label}") { editor = Editor.BODY }
                ToggleRow("Haptics", state.settings.hapticsEnabled, Modifier.padding(horizontal = 18.dp)) { v -> actions.settings { it.copy(hapticsEnabled = v) } }
            }
        }
        item { Eyebrow("Your data", Modifier.padding(start = 4.dp, top = 8.dp)) }
        item {
            FluffCard(padding = PaddingValues(vertical = 6.dp)) {
                SettingRow("Import from Fitbod", "Workout history", actions.importFitbod)
            }
        }
        item {
            Text(
                "Your data stays on this phone. With the AI coach on, a compact training summary (never your name) is sent to Anthropic to plan sessions. Fluff ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint, modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp),
            )
        }
    }

    editor?.let { e ->
        ModalBottomSheet(onDismissRequest = { editor = null }, containerColor = Fluff.Ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
                when (e) {
                    Editor.NAME -> TextEditor("Your name", p.name, KeyboardCapitalization.Words) { v -> actions.update { it.copy(name = v.trim()) }; editor = null }
                    Editor.GOAL -> { SheetTitle("Goal"); GoalPicker(p.goal) { g -> actions.updateAndReplan { it.copy(goal = g) } } }
                    Editor.EMPHASIS -> {
                        SheetTitle("Movement emphasis")
                        Text("Compound lifts train several muscles at once (squat, bench, row). Isolation moves train one (curls, raises). Fluff tags every exercise so you can see which is which.", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim, modifier = Modifier.padding(bottom = 14.dp))
                        EmphasisPicker(p.movementEmphasis) { m -> actions.updateAndReplan { it.copy(movementEmphasis = m) } }
                    }
                    Editor.EXPERIENCE -> { SheetTitle("Experience"); ExperiencePicker(p.experience) { x -> actions.updateAndReplan { it.copy(experience = x) } } }
                    Editor.SCHEDULE -> { SheetTitle("Schedule"); ScheduleEditor(p.daysPerWeek, p.sessionMinutes, { d -> actions.updateAndReplan { it.copy(daysPerWeek = d) } }, { m -> actions.updateAndReplan { it.copy(sessionMinutes = m) } }) }
                    Editor.EQUIPMENT -> { SheetTitle("Equipment"); EquipmentEditor(p.equipment) { eq -> actions.updateAndReplan { it.copy(equipment = eq) } } }
                    Editor.LIMITS -> { SheetTitle("Limitations"); LimitationsEditor(p.limitations) { l -> actions.updateAndReplan { it.copy(limitations = l) } } }
                    Editor.ACTIVITIES -> { SheetTitle("Other sports"); ActivitiesEditor(p.activities) { a -> actions.updateAndReplan { it.copy(activities = a) } } }
                    Editor.BODY -> {
                        SheetTitle("Body")
                        BodyEditor(
                            p.sex, p.bodyWeightKg, p.unit, p.birthYear,
                            { s -> actions.update { it.copy(sex = s) } }, { w -> actions.updateAndReplan { it.copy(bodyWeightKg = w) } },
                            { u -> actions.update { it.copy(unit = u) } }, { y -> actions.update { it.copy(birthYear = y) } },
                        )
                    }
                    Editor.CLAUDE -> ClaudeKeyEditor(c.claudeKeySet, { k -> actions.saveClaudeKey(k); editor = null })
                    Editor.WHOOP -> WhoopEditor { id, secret -> actions.saveWhoop(id, secret); editor = null }
                }
            }
        }
    }
}

private fun equipmentLabel(e: Set<Equipment>) = when (e) {
    Equipment.FULL_GYM -> "Full gym"
    Equipment.HOME_DUMBBELLS -> "Home dumbbells"
    Equipment.BODYWEIGHT_ONLY -> "Bodyweight"
    else -> "${e.size} items"
}

@Composable
private fun SheetTitle(t: String) { Text(t, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp)) }

@Composable
private fun ConnectionCard(icon: ImageVector, color: Color, title: String, status: String, connected: Boolean, content: @Composable () -> Unit) {
    FluffCard(border = if (connected) color.copy(alpha = 0.35f) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (connected) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(8.dp).clip(CircleShape).background(color)) }
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = Fluff.TextDim)
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Fluff.TextFaint)
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked, null, colors = SwitchDefaults.colors(checkedTrackColor = Fluff.Lime, checkedThumbColor = Fluff.Night))
    }
}

@Composable
private fun TextEditor(title: String, initial: String, cap: KeyboardCapitalization, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    SheetTitle(title)
    OutlinedTextField(v, { v = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = cap, imeAction = ImeAction.Done))
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Save", { onSave(v) }, Modifier.fillMaxWidth())
}

@Composable
fun ClaudeKeyEditor(hasKey: Boolean, onSave: (String?) -> Unit) {
    var key by remember { mutableStateOf("") }
    SheetTitle("Claude API key")
    Text(
        "Create a key at console.anthropic.com → API keys. It's stored encrypted with Android Keystore and only ever sent to Anthropic.",
        style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim,
    )
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        key, { key = it }, label = { Text("sk-ant-…") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Save key", { onSave(key) }, Modifier.fillMaxWidth(), enabled = key.trim().startsWith("sk-"), color = Fluff.Violet)
    if (hasKey) { Spacer(Modifier.height(8.dp)); GhostButton("Remove key", { onSave(null) }, Modifier.fillMaxWidth(), color = Fluff.Rose) }
}

@Composable
fun WhoopEditor(onSave: (String, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    SheetTitle("WHOOP developer app")
    Text(
        "WHOOP requires each app to be registered. At developer.whoop.com create an app with the redirect URL below and the read scopes, then paste its credentials here. They're stored encrypted on this phone.",
        style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim,
    )
    Spacer(Modifier.height(10.dp))
    Text(WhoopEndpoints.REDIRECT_URI, style = MaterialTheme.typography.titleSmall, color = Fluff.Mint)
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(id, { id = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(secret, { secret = it }, label = { Text("Client secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Save", { onSave(id, secret) }, Modifier.fillMaxWidth(), enabled = id.isNotBlank() && secret.isNotBlank(), color = Fluff.Mint)
}
