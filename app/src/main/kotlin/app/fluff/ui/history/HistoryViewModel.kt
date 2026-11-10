package app.fluff.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluff.core.model.ActivityRecord
import app.fluff.core.model.ActivityType
import app.fluff.core.model.DataSource
import app.fluff.core.model.UserProfile
import app.fluff.data.db.SessionWithSets
import app.fluff.data.prefs.PreferencesStore
import app.fluff.data.repo.TrainingRepository
import app.fluff.data.repo.WorkoutSummary
import app.fluff.data.repo.toRecord
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

sealed interface TimelineItem {
    val date: LocalDate
    val sortKey: Long
    val key: String

    data class Lift(val summary: WorkoutSummary, override val date: LocalDate, val readiness: Int?, val source: String, override val sortKey: Long) : TimelineItem {
        override val key get() = "s${summary.sessionId}"
    }

    data class Activity(val record: ActivityRecord, override val date: LocalDate, override val sortKey: Long) : TimelineItem {
        override val key get() = "a${record.id}"
    }
}

data class HistoryState(val loading: Boolean = true, val profile: UserProfile = UserProfile(), val items: List<TimelineItem> = emptyList(), val today: LocalDate = LocalDate.now())

class HistoryViewModel(private val training: TrainingRepository, prefs: PreferencesStore) : ViewModel() {
    val state: StateFlow<HistoryState> = combine(
        training.completedRaw,
        training.observeActivities(Instant.now().minus(Duration.ofDays(365))),
        prefs.profile,
    ) { sessions, activities, profile ->
        val zone = ZoneId.systemDefault()
        val lifts = sessions.map { s -> lift(s, profile) }
        val acts = activities.map { TimelineItem.Activity(it, it.start.atZone(zone).toLocalDate(), it.start.toEpochMilli()) }
        HistoryState(false, profile, (lifts + acts).sortedByDescending { it.sortKey })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())

    private fun lift(s: SessionWithSets, profile: UserProfile): TimelineItem.Lift {
        val rec = s.toRecord()
        return TimelineItem.Lift(
            training.summarize(rec, s.session.title, emptyList(), profile.bodyWeightKg),
            rec.date, s.session.readinessScore, s.session.source, s.session.startedAt,
        )
    }

    fun logActivity(type: ActivityType, minutes: Int, intensity: Int, daysAgo: Int) = viewModelScope.launch {
        val start = LocalDate.now().minusDays(daysAgo.toLong()).atTime(if (daysAgo == 0) java.time.LocalTime.now().minusMinutes(minutes.toLong()) else java.time.LocalTime.of(18, 0))
            .atZone(ZoneId.systemDefault()).toInstant()
        training.logActivity(ActivityRecord("manual:${UUID.randomUUID()}", type, DataSource.MANUAL, start, minutes.toDouble(), intensity.toDouble()))
    }

    fun deleteActivity(id: String) = viewModelScope.launch { training.deleteActivity(id) }
}

class SessionDetailViewModel(sessionId: Long, private val training: TrainingRepository, prefs: PreferencesStore) : ViewModel() {
    val session: StateFlow<SessionWithSets?> = training.observeSession(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profile: StateFlow<UserProfile> = prefs.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserProfile())
    val summary: StateFlow<WorkoutSummary?> = combine(training.observeSession(sessionId), prefs.profile) { s, p ->
        s?.let { training.summarize(it.toRecord(), it.session.title, emptyList(), p.bodyWeightKg) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(onDone: () -> Unit) {
        val id = session.value?.session?.id ?: return
        viewModelScope.launch { training.deleteSession(id); onDone() }
    }
}
