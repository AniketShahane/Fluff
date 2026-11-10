package app.fluff.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.fluff.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.fluffPrefs: DataStore<Preferences> by preferencesDataStore(name = "fluff_prefs")

@Serializable
data class AppSettings(
    val aiCoachEnabled: Boolean = true,
    val healthConnectEnabled: Boolean = false,
    val writeWorkoutsToHealthConnect: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val restTimerSound: Boolean = false,
    val lastSyncEpochMs: Long = 0,
    val lastSyncError: String? = null,
)

/** Profile and settings live in DataStore as JSON blobs: small, atomic and easy to migrate. */
class PreferencesStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profileKey = stringPreferencesKey("profile")
    private val settingsKey = stringPreferencesKey("settings")

    val profile: Flow<UserProfile> = context.fluffPrefs.data.map { p ->
        p[profileKey]?.let { runCatching { json.decodeFromString(UserProfile.serializer(), it) }.getOrNull() } ?: UserProfile()
    }

    val settings: Flow<AppSettings> = context.fluffPrefs.data.map { p ->
        p[settingsKey]?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
    }

    suspend fun currentProfile(): UserProfile = profile.first()
    suspend fun currentSettings(): AppSettings = settings.first()

    suspend fun updateProfile(transform: (UserProfile) -> UserProfile) {
        context.fluffPrefs.edit { p ->
            val current = p[profileKey]?.let { runCatching { json.decodeFromString(UserProfile.serializer(), it) }.getOrNull() } ?: UserProfile()
            p[profileKey] = json.encodeToString(UserProfile.serializer(), transform(current))
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.fluffPrefs.edit { p ->
            val current = p[settingsKey]?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
            p[settingsKey] = json.encodeToString(AppSettings.serializer(), transform(current))
        }
    }
}
