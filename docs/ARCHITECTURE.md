# Fluff — Architecture & Product Plan

Fluff is an adaptive strength-training coach for Android. Every morning it reads how recovered you are, looks at what you trained and what else you did (runs, rides, climbs, matches), and designs today's session — a real warm-up, the work itself, and a cool-down — then tracks every set and turns the history into progress you can see.

## Product pillars

1. **Readiness-first.** The plan is a function of today's body, not a fixed program. A low-recovery day gets fewer hard sets, lower RPE caps, and more mobility; a prime day unlocks top sets.
2. **The coach explains itself.** Every plan carries a short rationale ("HRV is 14% under baseline and you ran 10 km yesterday, so legs are capped at RPE 7").
3. **Safety is non-negotiable.** Injuries/limitations filter exercises before any AI is involved, and AI output is validated against the same rules. The deterministic engine is always the fallback.
4. **Progress you can feel.** Estimated 1RM curves with goal projections, volume by muscle, strength radar, activity-specific strength, consistency heatmap.
5. **Offline and private by default.** Everything lives on device. Network is only used for WHOOP sync and the optional Claude coach.

## Modules

| Module | Kind | Responsibility |
|---|---|---|
| `:core` | Pure Kotlin/JVM | Domain model, exercise library, readiness engine, training-load model, workout planner, warm-up/cool-down builder, progression (e1RM, RPE→%1RM), strength & activity scores, AI plan contract + validator. No Android dependencies → fast, exhaustive unit tests. |
| `:app` | Android (Compose) | Persistence (Room, DataStore), integrations (Health Connect, WHOOP OAuth/API, Claude), background sync (WorkManager), UI, charts, animations. |

Dependency injection is a small hand-written `AppContainer` (no annotation processing beyond Room/KSP), which keeps the build fast and the graph obvious.

## Core engine

### Readiness (`ReadinessEngine`)
Inputs, all optional, fused by availability:
- **WHOOP recovery** (0–100) — used directly as the physiological signal when present.
- **Health Connect signals** — HRV (RMSSD), resting HR, sleep duration. Each is scored against the user's own rolling 30-day baseline (z-score → 0–100). This is how Fitbit Air (via the Google Health app → Health Connect), Pixel Watch, Garmin, Oura etc. are supported: their proprietary scores are not exported, so Fluff computes its own from the raw signals.
- **Manual check-in** — sleep quality, energy, soreness, stress (1–5).
- **Training load** — acute:chronic workload ratio from logged lifting volume and external activities (strain/duration × intensity). ACWR > 1.5 penalises readiness.

Output: score 0–100, band (`PRIME ≥ 80`, `READY ≥ 65`, `STEADY ≥ 50`, `LOW ≥ 35`, `RECOVER < 35`), the contributing factors with signed impact, and a `TrainingPrescription` (volume multiplier, RPE cap, whether to allow top sets / intervals).

### Muscle fatigue model
Each completed set adds fatigue to its primary (1.0) and secondary (0.5) muscles, scaled by RPE. External activities add fatigue to the muscles they load (e.g. running → quads, calves, hamstrings). Fatigue decays exponentially (half-life ≈ 24 h for small muscles, 36 h for large). The planner picks the focus whose muscles are most recovered and most "due".

### Planner (`WorkoutPlanner`)
1. Choose the split from training days/week (Full body ≤3, Upper/Lower 4, Push/Pull/Legs 5–6) and today's focus from fatigue + recency.
2. Filter the exercise library by available equipment and contraindications for the user's limitations.
3. Pick a main compound, secondary compound(s), and accessories by movement pattern coverage.
4. Prescribe sets × reps @ RPE from goal (strength / hypertrophy / general / sport) and readiness prescription; compute loads from the e1RM history via an RPE→%1RM table, rounded to available plate increments.
5. Fit to the session-length budget.
6. Build the **warm-up**: 5–8 min general cardio scaled to readiness, dynamic mobility for the regions trained (and any limited regions), activation drills, then ramp-up sets for the first main lift (e.g. bar×10, 40%×5, 60%×3, 75%×2, 85%×1).
7. Build the **cool-down**: easy cardio flush, static stretches for every trained muscle, breathing down-regulation.

### Progression
- e1RM via Epley for reps ≤ 10 with RPE adjustment (reps in reserve added to reps), stored per set.
- Next-session load suggestion: if last top set beat its RPE target by ≥1, +2.5–5%; if missed, −5%.
- PR detection (weight PR, rep PR, e1RM PR).

### Strength scores
- Per movement pattern: best recent e1RM ÷ bodyweight compared with standard ratios (novice → elite) → 0–100.
- **Overall strength** = weighted mean across patterns.
- **Activity-specific strength**: each activity (running, cycling, climbing, swimming, tennis, football, basketball, martial arts, rowing, skiing, hiking, yoga) has pattern weights; the score shows how well the user's strength profile supports that activity and which pattern is the limiter.

### AI coach contract
The Claude coach receives a compact JSON context (profile, limitations, readiness + factors, muscle fatigue, last 14 days of sessions and activities, goals, the allowed exercise IDs) and must answer with structured JSON matching `AiPlan` (enforced with `output_config.format` JSON schema). `PlanValidator` then rejects unknown or contraindicated exercises, clamps sets/reps/RPE to the readiness prescription, and fills loads from the progression engine. Any failure → deterministic plan, with the reason surfaced in the UI.

## App

### Data
- **Room**: `sessions`, `sets`, `readiness`, `activities`, `goals`, `body_weight`.
- **DataStore**: user profile + settings (JSON via kotlinx.serialization).
- **Secrets**: WHOOP tokens, WHOOP client credentials, Claude API key encrypted with an Android Keystore AES-GCM key.

### Integrations
- **Health Connect** (`androidx.health.connect:connect-client`): reads HRV, resting HR, sleep, exercise sessions; writes completed workouts as strength-training exercise sessions.
- **WHOOP** (API v2, OAuth 2.0 authorization code with `offline` scope): Custom Tab login → `fluff://oauth/whoop` redirect → token exchange → reads recovery, sleep, workouts. Refresh tokens rotate on every refresh, so refresh is serialised behind a mutex.
- **Claude** (official `anthropic-java` SDK, model `claude-opus-5-5`, adaptive thinking): plan generation + post-workout debrief.
- **WorkManager**: periodic sync (6 h) + on-open sync.

### UX map
- **Onboarding** (5 animated steps): goal → experience & schedule → equipment → limitations → other activities, then connections.
- **Today**: readiness orb (morphing, breathing shape coloured by band) with factors; today's plan card (warm-up / main / cool-down timeline) with rationale; quick adjust chips ("Short on time", "Feeling great", "Go easier", "Regenerate"); recent activity strip.
- **Active workout**: full-screen stepper per exercise, large weight/rep steppers, RPE slider, swipe-to-complete set, animated rest timer ring with haptics, PR celebration burst, live session summary.
- **Progress**: strength score hero + radar; activity strength rings; e1RM chart per lift with goal line & projection; weekly volume by muscle; readiness vs load; consistency heatmap.
- **History**: sessions list → session detail.
- **You**: profile, goals, limitations, activities, connections (WHOOP / Health Connect / Claude), units.

### Visual language
Dark-first, soft "fluff" geometry: large corner radii, glowing gradients, morphing shapes (`androidx.graphics:graphics-shapes`), spring physics everywhere, staggered list entrances, shared-element transitions from the plan card into the workout, and custom Canvas charts that draw themselves in.

## MCP server (`mcp/`)
Optional. A Cloudflare Worker (D1 for data, KV for OAuth state) in the owner's own account.
- **Phone → server:** `CloudSync` uploads a JSON snapshot after every wearable sync and every finished workout: the last 30 days of sessions/sets, days (signals, readiness, check-in, plan) and activities, plus profile, goals, computed insights and the exercise library. The server replaces that date window, so edits and deletions propagate. The first upload sends the whole history in 120-day chunks. All maths stays on the phone.
- **Assistant → phone:** MCP write tools only queue changes in an inbox. On its next sync the phone applies them (scheduled workouts go through `PlanValidator` exactly like the Claude coach's plans and are stored with source `ASSISTANT`, which later signal changes don't overwrite), then reports applied/rejected per change.
- **Auth:** assistants use OAuth 2.1 + PKCE with dynamic client registration; the sign-in page asks for a random owner key. The phone uses a separate random bearer token.

## Importing history

Fitbod (Profile → Import from Fitbod): `core/importer/FitbodImport.kt` reads both Fitbod CSV formats (the iOS in-app export and the date-only extract Fitbod support mails to Android users), groups sets into workouts by Fitbod's per-workout timestamp, and maps names to library ids through a hand-checked alias table. Only true equivalents are mapped (same movement, same weight semantics); anything else is stored as `fitbod:<name>` so it shows in history but never feeds e1RM or planning. Sessions are saved with `source = "FITBOD"` and deduplicated on their start time, so re-importing a newer export only adds new workouts. Fitbod has no end times, so duration is estimated at 2.5 min per set (20 to 120 min).

## Quality
- `:core` unit tests cover every engine.
- `:app` Robolectric tests for repositories/view-models and Roborazzi screenshot tests for key screens.
- GitHub Actions CI: build, unit tests, lint on every push/PR.
