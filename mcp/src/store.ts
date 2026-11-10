/** D1 access. Writes come only from the phone's snapshot and from the MCP inbox tools. */
import type { Snapshot } from "./schema";
import { parseJson } from "./util";

/** D1 caps a single bound value at 2 MB. */
export const MAX_PARAM_BYTES = 1_900_000;

export class SnapshotTooLarge extends Error {}

function param(value: unknown): string {
  const s = JSON.stringify(value);
  if (new TextEncoder().encode(s).byteLength > MAX_PARAM_BYTES) throw new SnapshotTooLarge("snapshot part over 1.9 MB; send a smaller date range");
  return s;
}

const j = (path: string) => `json_extract(value, '$.${path}')`;

/**
 * Applies one snapshot atomically (a D1 batch is a single transaction and counts as one query).
 * Rows are expanded with json_each, so the statement count is fixed however much data arrives.
 */
export async function applySnapshot(db: D1Database, s: Snapshot, now: number): Promise<void> {
  const { from, to } = s.range;
  const sessions = param(s.sessions.map(({ plan, ...rest }) => ({ ...rest, plan: plan ? JSON.stringify(plan) : null })));
  const sets = param(s.sessions.flatMap((x) => x.sets.map((set) => ({ ...set, sessionId: x.id, skipped: set.skipped ? 1 : 0 }))));
  const activities = param(s.activities);
  const days = param(
    s.days.map((d) => ({
      ...d,
      readinessScore: typeof d.readiness?.score === "number" ? d.readiness.score : null,
      readinessBand: typeof d.readiness?.band === "string" ? d.readiness.band : null,
      readiness: d.readiness ? JSON.stringify(d.readiness) : null,
      checkIn: d.checkIn ? JSON.stringify(d.checkIn) : null,
      plan: d.plan ? JSON.stringify(d.plan) : null,
    })),
  );

  const stmts: D1PreparedStatement[] = [];
  if (s.reset) {
    for (const t of ["sets", "sessions", "activities", "days"]) stmts.push(db.prepare(`DELETE FROM ${t}`));
  } else {
    stmts.push(
      db.prepare(`DELETE FROM sets WHERE session_id IN (SELECT id FROM sessions WHERE date BETWEEN ?1 AND ?2) OR session_id IN (SELECT ${j("id")} FROM json_each(?3))`).bind(from, to, sessions),
      db.prepare(`DELETE FROM sessions WHERE date BETWEEN ?1 AND ?2`).bind(from, to),
      db.prepare(`DELETE FROM activities WHERE date BETWEEN ?1 AND ?2`).bind(from, to),
      db.prepare(`DELETE FROM days WHERE date BETWEEN ?1 AND ?2`).bind(from, to),
    );
  }
  stmts.push(
    db.prepare(
      `INSERT OR REPLACE INTO sessions (id, date, started_at, ended_at, title, focus, source, status, readiness_score, session_rpe, notes, duration_min, work_sets, volume_kg, plan_json)
       SELECT ${j("id")}, ${j("date")}, ${j("startedAt")}, ${j("endedAt")}, ${j("title")}, ${j("focus")}, ${j("source")}, ${j("status")}, ${j("readinessScore")},
              ${j("sessionRpe")}, ${j("notes")}, ${j("durationMin")}, ${j("workSets")}, ${j("volumeKg")}, ${j("plan")} FROM json_each(?1)`,
    ).bind(sessions),
    db.prepare(
      `INSERT INTO sets (session_id, exercise_id, block, exercise_order, set_index, kind, target_reps, target_duration_sec, target_weight_kg, target_rpe, rest_sec, weight_kg, reps, rpe, duration_sec, completed_at, skipped, e1rm_kg)
       SELECT ${j("sessionId")}, ${j("exerciseId")}, ${j("block")}, ${j("exerciseOrder")}, ${j("setIndex")}, ${j("kind")}, ${j("targetReps")}, ${j("targetDurationSec")},
              ${j("targetWeightKg")}, ${j("targetRpe")}, ${j("restSec")}, ${j("weightKg")}, ${j("reps")}, ${j("rpe")}, ${j("durationSec")}, ${j("completedAt")}, ${j("skipped")}, ${j("e1rmKg")} FROM json_each(?1)`,
    ).bind(sets),
    db.prepare(
      `INSERT OR REPLACE INTO activities (id, date, type, source, start_ms, duration_min, intensity, name, energy_kcal, distance_m)
       SELECT ${j("id")}, ${j("date")}, ${j("type")}, ${j("source")}, ${j("startMs")}, ${j("durationMin")}, ${j("intensity")}, ${j("name")}, ${j("energyKcal")}, ${j("distanceMeters")} FROM json_each(?1)`,
    ).bind(activities),
    db.prepare(
      `INSERT OR REPLACE INTO days (date, whoop_recovery, hrv_ms, resting_hr, sleep_hours, sleep_performance, hrv_source, readiness_score, readiness_band, readiness_json, checkin_json, plan_json)
       SELECT ${j("date")}, ${j("whoopRecovery")}, ${j("hrvMs")}, ${j("restingHr")}, ${j("sleepHours")}, ${j("sleepPerformance")}, ${j("hrvSource")}, ${j("readinessScore")}, ${j("readinessBand")},
              ${j("readiness")}, ${j("checkIn")}, ${j("plan")} FROM json_each(?1)`,
    ).bind(days),
  );
  if (s.exercises) {
    stmts.push(
      db.prepare(`DELETE FROM exercises`),
      db.prepare(`INSERT OR REPLACE INTO exercises (id, name, json) SELECT ${j("id")}, ${j("name")}, value FROM json_each(?1)`).bind(param(s.exercises)),
    );
  }
  const putDoc = (key: string, value: unknown) =>
    db.prepare(`INSERT OR REPLACE INTO docs (key, json, updated_at) VALUES (?1, ?2, ?3)`).bind(key, param(value), now);
  if (s.profile) stmts.push(putDoc("profile", s.profile));
  if (s.goals) stmts.push(putDoc("goals", s.goals));
  if (s.insights) stmts.push(putDoc("insights", s.insights));
  stmts.push(putDoc("sync", { lastSyncAt: new Date(now).toISOString(), phoneGeneratedAt: s.generatedAt, timeZone: s.timeZone, lastRange: s.range }));
  await db.batch(stmts);
}

export async function getDoc<T = Record<string, unknown>>(db: D1Database, key: string): Promise<T | null> {
  const row = await db.prepare(`SELECT json FROM docs WHERE key = ?1`).bind(key).first<{ json: string }>();
  return parseJson<T>(row?.json);
}

export interface SyncMeta {
  lastSyncAt: string;
  phoneGeneratedAt: string;
  timeZone: string;
}

// ───────── Inbox ─────────

export type InboxStatus = "pending" | "applied" | "rejected" | "cancelled";
export interface InboxItem {
  id: string;
  kind: string;
  payload: Record<string, unknown>;
  status: InboxStatus;
  result: string | null;
  createdAt: string;
  updatedAt: string;
}

interface InboxRow {
  id: string;
  kind: string;
  payload_json: string;
  status: InboxStatus;
  result: string | null;
  created_at: number;
  updated_at: number;
}

function toItem(r: InboxRow): InboxItem {
  return {
    id: r.id,
    kind: r.kind,
    payload: parseJson<Record<string, unknown>>(r.payload_json) ?? {},
    status: r.status,
    result: r.result,
    createdAt: new Date(r.created_at).toISOString(),
    updatedAt: new Date(r.updated_at).toISOString(),
  };
}

export async function addInbox(db: D1Database, kind: string, payload: Record<string, unknown>, now: number): Promise<InboxItem> {
  const id = crypto.randomUUID();
  await db
    .prepare(`INSERT INTO inbox (id, kind, payload_json, status, created_at, updated_at) VALUES (?1, ?2, ?3, 'pending', ?4, ?4)`)
    .bind(id, kind, JSON.stringify(payload), now)
    .run();
  return { id, kind, payload, status: "pending", result: null, createdAt: new Date(now).toISOString(), updatedAt: new Date(now).toISOString() };
}

export async function listInbox(db: D1Database, opts: { status?: InboxStatus; limit?: number } = {}): Promise<InboxItem[]> {
  const limit = opts.limit ?? 50;
  // Pending items go to the phone oldest first; every other listing is newest first.
  const rows =
    opts.status === "pending"
      ? await db.prepare(`SELECT * FROM inbox WHERE status = 'pending' ORDER BY created_at ASC, rowid ASC LIMIT ?1`).bind(limit).all<InboxRow>()
      : await db.prepare(`SELECT * FROM inbox WHERE ?1 IS NULL OR status = ?1 ORDER BY created_at DESC, rowid DESC LIMIT ?2`).bind(opts.status ?? null, limit).all<InboxRow>();
  return rows.results.map(toItem);
}

/** Moves a pending item to a final state. Returns false if it was not pending. */
export async function settleInbox(db: D1Database, id: string, status: Exclude<InboxStatus, "pending">, result: string | null, now: number): Promise<boolean> {
  const r = await db
    .prepare(`UPDATE inbox SET status = ?2, result = ?3, updated_at = ?4 WHERE id = ?1 AND status = 'pending'`)
    .bind(id, status, result, now)
    .run();
  return (r.meta.changes ?? 0) > 0;
}

// ───────── Throttle ─────────

/** Counts an attempt; false once `limit` attempts happened inside the window. */
export async function attempt(db: D1Database, key: string, limit: number, windowMs: number, now: number): Promise<boolean> {
  const row = await db
    .prepare(
      `INSERT INTO throttle (key, count, reset_at) VALUES (?1, 1, ?2)
       ON CONFLICT(key) DO UPDATE SET count = CASE WHEN reset_at < ?3 THEN 1 ELSE count + 1 END,
                                      reset_at = CASE WHEN reset_at < ?3 THEN ?2 ELSE reset_at END
       RETURNING count`,
    )
    .bind(key, now + windowMs, now)
    .first<{ count: number }>();
  return (row?.count ?? 0) <= limit;
}
