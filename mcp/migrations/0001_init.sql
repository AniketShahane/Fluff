-- Fluff's server-side copy of the phone's data. The phone is the source of truth: each sync
-- replaces a date window, so these tables are a mirror, never edited by hand.

CREATE TABLE sessions (
  id INTEGER PRIMARY KEY,           -- the phone's Room id
  date TEXT NOT NULL,               -- local date, YYYY-MM-DD
  started_at INTEGER NOT NULL,      -- epoch ms
  ended_at INTEGER,
  title TEXT NOT NULL,
  focus TEXT,
  source TEXT,
  status TEXT NOT NULL,             -- COMPLETED or ACTIVE
  readiness_score INTEGER,
  session_rpe REAL,
  notes TEXT,
  duration_min REAL,
  work_sets INTEGER,
  volume_kg REAL,
  plan_json TEXT
);
CREATE INDEX sessions_date ON sessions(date);

CREATE TABLE sets (
  session_id INTEGER NOT NULL,
  exercise_id TEXT NOT NULL,
  block TEXT NOT NULL,
  exercise_order INTEGER NOT NULL,
  set_index INTEGER NOT NULL,
  kind TEXT NOT NULL,
  target_reps INTEGER,
  target_duration_sec INTEGER,
  target_weight_kg REAL,
  target_rpe REAL,
  rest_sec INTEGER,
  weight_kg REAL,
  reps INTEGER,
  rpe REAL,
  duration_sec INTEGER,
  completed_at INTEGER,
  skipped INTEGER NOT NULL DEFAULT 0,
  e1rm_kg REAL
);
CREATE INDEX sets_session ON sets(session_id);
CREATE INDEX sets_exercise ON sets(exercise_id);

CREATE TABLE activities (
  id TEXT PRIMARY KEY,
  date TEXT NOT NULL,
  type TEXT NOT NULL,
  source TEXT NOT NULL,
  start_ms INTEGER NOT NULL,
  duration_min REAL NOT NULL,
  intensity REAL,
  name TEXT,
  energy_kcal REAL,
  distance_m REAL
);
CREATE INDEX activities_date ON activities(date);

-- One row per day: wearable signals, the computed readiness, the morning check-in, the plan.
CREATE TABLE days (
  date TEXT PRIMARY KEY,
  whoop_recovery REAL,
  hrv_ms REAL,
  resting_hr REAL,
  sleep_hours REAL,
  sleep_performance REAL,
  hrv_source TEXT,
  readiness_score INTEGER,
  readiness_band TEXT,
  readiness_json TEXT,
  checkin_json TEXT,
  plan_json TEXT
);

CREATE TABLE exercises (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  json TEXT NOT NULL
);

-- Small documents replaced whole on every sync: profile, goals, insights, sync metadata.
CREATE TABLE docs (
  key TEXT PRIMARY KEY,
  json TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

-- Changes requested through MCP, applied by the phone on its next sync.
CREATE TABLE inbox (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  status TEXT NOT NULL,             -- pending, applied, rejected, cancelled
  result TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX inbox_status ON inbox(status);

-- Sign-in throttle for the owner-key form.
CREATE TABLE throttle (
  key TEXT PRIMARY KEY,
  count INTEGER NOT NULL,
  reset_at INTEGER NOT NULL
);
