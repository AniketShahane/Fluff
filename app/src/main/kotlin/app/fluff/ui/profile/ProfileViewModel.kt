package app.fluff.ui.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluff.AppContainer
import app.fluff.core.model.UserProfile
import app.fluff.data.prefs.AppSettings
import app.fluff.data.secure.Secret
import app.fluff.integrations.healthconnect.HcAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Connections(
    val claudeKeySet: Boolean = false,
    val whoopConfigured: Boolean = false,
    val whoopConnected: Boolean = false,
    val hcAvailability: HcAvailability = HcAvailability.UNAVAILABLE,
    val hcGranted: Set<String> = emptySet(),
    val hcAllPermissions: Set<String> = emptySet(),
    val cloudConnected: Boolean = false,
) {
    val hcConnected: Boolean get() = hcGranted.isNotEmpty()
}

data class ProfileState(
    val profile: UserProfile = UserProfile(),
    val settings: AppSettings = AppSettings(),
    val connections: Connections = Connections(),
    val syncing: Boolean = false,
    val cloudSyncing: Boolean = false,
)

/** Shared by Profile and onboarding: profile edits plus every connection. */
class ProfileViewModel(private val c: AppContainer) : ViewModel() {
    private val connections = MutableStateFlow(Connections())

    val state: StateFlow<ProfileState> = combine(c.prefs.profile, c.prefs.settings, connections, c.sync.state, c.cloud.running) { p, s, conn, sync, cloud ->
        ProfileState(p, s, conn, sync.running, cloud)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileState())

    init { refreshConnections() }

    fun refreshConnections() = viewModelScope.launch {
        val hc = c.healthConnect
        connections.value = Connections(
            claudeKeySet = !c.secrets.get(Secret.CLAUDE_API_KEY).isNullOrBlank(),
            whoopConfigured = c.whoop.isConfigured(),
            whoopConnected = c.whoop.isConnected(),
            hcAvailability = hc.availability(),
            hcGranted = hc.grantedPermissions(),
            hcAllPermissions = hc.allPermissions,
            cloudConnected = c.cloud.isConnected(),
        )
    }

    fun updateProfile(transform: (UserProfile) -> UserProfile) = viewModelScope.launch {
        c.prefs.updateProfile { runCatching { transform(it) }.getOrDefault(it) }
    }

    /** Profile changes that affect planning rebuild today's plan unless a workout is under way. */
    fun updateProfileAndReplan(transform: (UserProfile) -> UserProfile) = viewModelScope.launch {
        c.prefs.updateProfile { runCatching { transform(it) }.getOrDefault(it) }
        if (c.db.sessions().active() == null && !c.training.completedOn(c.engine.today().toEpochDay())) {
            runCatching { c.engine.planToday(force = true) }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { c.prefs.updateSettings(transform) }

    fun saveClaudeKey(key: String?) = viewModelScope.launch {
        c.secrets.put(Secret.CLAUDE_API_KEY, key?.trim()?.takeIf { it.isNotEmpty() })
        refreshConnections()
    }

    fun saveWhoopCredentials(id: String, secret: String) = viewModelScope.launch {
        c.secrets.put(Secret.WHOOP_CLIENT_ID, id.trim().takeIf { it.isNotEmpty() })
        c.secrets.put(Secret.WHOOP_CLIENT_SECRET, secret.trim().takeIf { it.isNotEmpty() })
        refreshConnections()
    }

    fun whoopAuthUri(onUri: (Uri) -> Unit) = viewModelScope.launch {
        runCatching { c.whoop.authorizationUri() }
            .onSuccess(onUri)
            .onFailure { c.messages.tryEmit(it.message ?: "Couldn't start WHOOP sign-in") }
    }

    /** Connects the Fluff MCP server; [onDone] gets an error message or null. */
    fun connectCloud(url: String, token: String, onDone: (String?) -> Unit) = viewModelScope.launch {
        val err = c.cloud.connect(url, token)
        refreshConnections()
        onDone(err)
        c.messages.tryEmit(err ?: "Connected. Claude and ChatGPT can now see your training.")
    }

    fun disconnectCloud() = viewModelScope.launch { c.cloud.disconnect(); refreshConnections() }

    fun cloudSyncNow(full: Boolean = false) = viewModelScope.launch {
        c.messages.tryEmit(c.cloud.sync(full) ?: if (full) "Re-uploaded everything" else "Uploaded")
    }

    fun disconnectWhoop() = viewModelScope.launch { c.whoop.disconnect(); refreshConnections() }

    fun onHealthConnectResult(granted: Set<String>) = viewModelScope.launch {
        c.prefs.updateSettings { it.copy(healthConnectEnabled = granted.isNotEmpty()) }
        refreshConnections()
        if (granted.isNotEmpty()) syncNow()
    }

    fun syncNow() = viewModelScope.launch {
        val err = c.sync.sync()
        runCatching { c.engine.onNewSignals() }
        c.messages.tryEmit(err ?: "Synced")
    }

    fun finishOnboarding(onDone: () -> Unit) = viewModelScope.launch {
        c.prefs.updateProfile { it.copy(onboarded = true) }
        runCatching { c.sync.sync() }
        runCatching { c.engine.planToday(force = true) }
        onDone()
    }
}
