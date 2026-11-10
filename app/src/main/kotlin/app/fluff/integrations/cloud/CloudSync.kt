package app.fluff.integrations.cloud

import app.fluff.core.ai.AiCoachContract
import app.fluff.core.ai.AiPlan
import app.fluff.core.ai.PlanValidator
import app.fluff.core.ai.ValidationResult
import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.ActivityRecord
import app.fluff.core.model.ActivityType
import app.fluff.core.model.DataSource
import app.fluff.core.model.PlanAdjustments
import app.fluff.core.model.PlanSource
import app.fluff.core.model.WorkoutPlan
import app.fluff.data.db.FluffDatabase
import app.fluff.data.db.PlanEntity
import app.fluff.data.prefs.PreferencesStore
import app.fluff.data.repo.TrainingRepository
import app.fluff.data.repo.toGoal
import app.fluff.data.repo.toRecord
import app.fluff.data.secure.Secret
import app.fluff.data.secure.SecretStore
import app.fluff.domain.CoachEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Keeps the Fluff MCP server (mcp/) up to date so Claude or ChatGPT can talk about training,
 * and applies the changes an assistant queued there (goals, scheduled workouts, activities).
 *
 * Each run: pull queued changes → apply them here (the phone stays the source of truth and
 * re-validates everything) → report results → upload the last [WINDOW_DAYS] days. The first run
 * after connecting uploads the whole history in [CHUNK_DAYS]-day chunks.
 */
class CloudSync(
    private val db: FluffDatabase,
    private val prefs: PreferencesStore,
    private val secrets: SecretStore,
    private val training: TrainingRepository,
    private val engine: CoachEngine,
    private val api: CloudApi,
    private val clock: Clock,
    private val json: Json,
) {
    private val lock = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val zone: ZoneId get() = clock.zone

    suspend fun isConnected(): Boolean = !prefs.currentSettings().cloudUrl.isNullOrBlank() && !secrets.get(Secret.CLOUD_SYNC_TOKEN).isNullOrBlank()

    /** Checks the server and token, saves them, and uploads the full history. Returns an error or null. */
    suspend fun connect(rawUrl: String, token: String): String? {
        val url = normalizeUrl(rawUrl) ?: return "Enter the server address, e.g. https://fluff-mcp.you.workers.dev"
        val t = token.trim().ifEmpty { return "Enter the sync token" }
        runCatching { api.status(url, t) }.onFailure { return it.message ?: "Couldn't reach the server" }
        secrets.put(Secret.CLOUD_SYNC_TOKEN, t)
        prefs.updateSettings { it.copy(cloudUrl = url, cloudFullSyncDone = false, cloudLastError = null) }
        return sync()
    }

    suspend fun disconnect() {
        secrets.put(Secret.CLOUD_SYNC_TOKEN, null)
        prefs.updateSettings { it.copy(cloudUrl = null, cloudLastSyncMs = 0, cloudLastError = null, cloudFullSyncDone = false, cloudOlderFingerprint = null) }
    }

    /** Safe to call often and concurrently. Returns a human-readable error, or null. */
    suspend fun sync(full: Boolean = false): String? = lock.withLock {
        val settings = prefs.currentSettings()
        val url = settings.cloudUrl ?: return@withLock null
        val token = secrets.get(Secret.CLOUD_SYNC_TOKEN) ?: return@withLock null
        _running.value = true
        try {
            val results = api.inbox(url, token).mapNotNull { apply(it) }
            // Report before uploading, so a failed upload never makes a change apply twice.
            api.ack(url, token, results)
            val today = LocalDate.now(clock)
            val windowStart = today.minusDays(WINDOW_DAYS - 1)
            // Sessions added or removed before the last window (e.g. an import of old workouts) need a full upload.
            val olderChanged = settings.cloudOlderFingerprint?.split("|")?.let { (cutoff, fp) ->
                db.sessions().fingerprintBefore(cutoff.toLong()) != fp
            } ?: false
            if (full || !settings.cloudFullSyncDone || olderChanged) {
                val first = listOfNotNull(
                    db.sessions().firstDay(),
                    db.signals().firstDay(),
                    db.activities().firstStartMs()?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() },
                ).minOrNull()?.let(LocalDate::ofEpochDay)?.coerceAtMost(today) ?: today
                var from = first
                var reset = true
                while (!from.isAfter(today)) {
                    val to = minOf(from.plusDays(CHUNK_DAYS - 1), today)
                    api.upload(url, token, snapshot(from, to, reset))
                    reset = false
                    from = to.plusDays(1)
                }
            } else {
                api.upload(url, token, snapshot(windowStart, today, reset = false))
            }
            val older = "${windowStart.toEpochDay()}|${db.sessions().fingerprintBefore(windowStart.toEpochDay())}"
            prefs.updateSettings { it.copy(cloudLastSyncMs = clock.millis(), cloudLastError = null, cloudFullSyncDone = true, cloudOlderFingerprint = older) }
            null
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            prefs.updateSettings { it.copy(cloudLastError = msg) }
            msg
        } finally {
            _running.value = false
        }
    }

    private suspend fun snapshot(from: LocalDate, to: LocalDate, reset: Boolean): JsonObject {
        val profile = prefs.currentProfile()
        val now = clock.instant()
        val f = from.toEpochDay()
        val t = to.toEpochDay()
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return CloudSnapshot.build(
            SnapshotInput(
                from = from, to = to, reset = reset, today = LocalDate.now(clock), now = now, zone = zone, profile = profile,
                sessions = db.sessions().inRange(f, t),
                history = training.history(profile.bodyWeightKg),
                activities = db.activities().since(fromMs).filter { it.startEpochMs < toMs },
                recentActivities = training.activitiesSince(now.minus(Duration.ofDays(35))),
                signals = db.signals().range(f, t),
                readiness = db.readiness().range(f, t),
                checkIns = db.checkIns().range(f, t),
                plans = db.plans().range(f, t),
                goals = db.goals().all().map { it.toGoal() },
            ),
        )
    }

    // ───────── Queued changes ─────────

    /** Applies one change. Null leaves it queued (a workout scheduled for a later day). */
    private suspend fun apply(item: InboxItem): InboxResult? {
        val p = item.payload
        fun str(k: String) = (p[k] as? JsonPrimitive)?.contentOrNull
        fun ok(msg: String? = null) = InboxResult(item.id, "applied", msg)
        fun no(msg: String) = InboxResult(item.id, "rejected", msg)
        return runCatching {
            when (item.kind) {
                "schedule_workout" -> {
                    val date = str("date")?.let(LocalDate::parse) ?: return no("Missing date")
                    val today = LocalDate.now(clock)
                    when {
                        date.isAfter(today) -> null
                        date.isBefore(today) -> no("$date has passed")
                        else -> scheduleToday(AiCoachContract.json.decodeFromJsonElement(AiPlan.serializer(), p["plan"] ?: return no("Missing plan")))
                            .let { (applied, msg) -> if (applied) ok(msg) else no(msg) }
                    }
                }
                "add_goal" -> {
                    val id = str("exerciseId") ?: return no("Missing exercise")
                    val ex = ExerciseLibrary[id] ?: return no("Unknown exercise $id")
                    val target = p["targetKg"]?.jsonPrimitive?.doubleOrNull ?: return no("Missing target")
                    val date = str("targetDate")?.let(LocalDate::parse)
                    if (db.goals().all().any { it.exerciseId == id && it.targetKg == target && it.achievedEpochDay == null }) return ok("Already set")
                    val profile = prefs.currentProfile()
                    training.addGoal(id, target, date, training.history(profile.bodyWeightKg).currentE1rm(id, LocalDate.now(clock)))
                    ok("Goal added: ${ex.name} ${target}kg")
                }
                "remove_goal" -> {
                    val id = p["goalId"]?.jsonPrimitive?.longOrNull ?: return no("Missing goal id")
                    if (db.goals().all().none { it.id == id }) return no("That goal no longer exists")
                    training.deleteGoal(id)
                    ok()
                }
                "log_activity" -> {
                    val type = str("type")?.let { t -> ActivityType.entries.firstOrNull { it.name == t } } ?: ActivityType.OTHER
                    val date = str("date")?.let(LocalDate::parse) ?: return no("Missing date")
                    val time = str("startTime")?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.NOON
                    val minutes = p["durationMin"]?.jsonPrimitive?.doubleOrNull ?: return no("Missing duration")
                    training.logActivity(
                        ActivityRecord(
                            id = "manual:assistant:${item.id}", type = type, source = DataSource.MANUAL,
                            start = date.atTime(time).atZone(zone).toInstant(), durationMinutes = minutes,
                            intensity = p["intensity"]?.jsonPrimitive?.doubleOrNull, name = str("name"),
                        ),
                    )
                    runCatching { engine.onNewSignals() }
                    ok()
                }
                else -> no("This version of Fluff can't apply \"${item.kind}\"; update the app")
            }
        }.getOrElse { no("Couldn't apply: ${it.message ?: it.javaClass.simpleName}") }
    }

    /** Validates an assistant's plan exactly like the in-app coach's and makes it today's plan. */
    private suspend fun scheduleToday(ai: AiPlan): Pair<Boolean, String> {
        val today = LocalDate.now(clock)
        if (db.sessions().active() != null || training.completedOn(today.toEpochDay())) return false to "You've already trained today"
        engine.ensureTodayPlan()
        val readiness = engine.refreshReadiness()
        val baseline = db.plans().get(today.toEpochDay())?.let(CloudSnapshot::planOf) ?: return false to "No baseline plan for today"
        val profile = prefs.currentProfile()
        val validator = PlanValidator(profile, readiness, training.history(profile.bodyWeightKg), today, baseline)
        return when (val v = validator.validate(ai)) {
            is ValidationResult.Invalid -> false to v.reason
            is ValidationResult.Valid -> {
                val plan = v.plan.copy(source = PlanSource.ASSISTANT)
                db.plans().upsert(PlanEntity(today.toEpochDay(), json.encodeToString(WorkoutPlan.serializer(), plan), clock.millis(), PlanAdjustments().hashCode()))
                true to (listOf("Scheduled for today") + v.warnings).joinToString("; ")
            }
        }
    }

    companion object {
        const val WINDOW_DAYS = 30L
        const val CHUNK_DAYS = 120L

        /** Accepts "fluff-mcp.x.workers.dev", "https://…/", or the "/mcp" address. */
        fun normalizeUrl(raw: String): String? {
            var u = raw.trim().trimEnd('/')
            if (u.isEmpty()) return null
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            if (u.endsWith("/mcp")) u = u.removeSuffix("/mcp")
            val host = runCatching { java.net.URI(u).host }.getOrNull() ?: return null
            return u.takeIf { host.contains('.') || host == "localhost" }
        }
    }
}
