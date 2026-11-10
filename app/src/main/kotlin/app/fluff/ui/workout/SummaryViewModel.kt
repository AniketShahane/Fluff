package app.fluff.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluff.core.model.UserProfile
import app.fluff.core.progress.TrainingHistory
import app.fluff.data.prefs.PreferencesStore
import app.fluff.data.repo.TrainingRepository
import app.fluff.data.repo.WorkoutSummary
import app.fluff.data.repo.toRecord
import app.fluff.domain.WorkoutFinisher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SummaryState(
    val loading: Boolean = true,
    val summary: WorkoutSummary? = null,
    val profile: UserProfile = UserProfile(),
    val readinessScore: Int? = null,
    val sessionRpe: Double? = null,
    val debrief: String? = null,
    val debriefLoading: Boolean = false,
)

class SummaryViewModel(
    private val sessionId: Long,
    private val training: TrainingRepository,
    private val prefs: PreferencesStore,
    private val finisher: WorkoutFinisher,
) : ViewModel() {
    private val _state = MutableStateFlow(SummaryState())
    val state: StateFlow<SummaryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = prefs.currentProfile()
            val s = training.session(sessionId) ?: run { _state.value = SummaryState(loading = false, profile = profile); return@launch }
            val record = s.toRecord()
            // PRs relative to everything before this session.
            val prior = TrainingHistory(training.history(profile.bodyWeightKg).sessions.filter { it.id != sessionId && it.startedAt < record.startedAt }, profile.bodyWeightKg)
            val prs = prior.detectRecords(record.sets, record.startedAt)
            val summary = training.summarize(record, s.session.title, prs, profile.bodyWeightKg)
            _state.value = SummaryState(false, summary, profile, s.session.readinessScore, s.session.sessionRpe, debriefLoading = true)
            val text = runCatching { finisher.debrief(summary) }.getOrNull()
            _state.update { it.copy(debrief = text, debriefLoading = false) }
        }
    }
}
