/**
 * The MCP server: a fresh stateless McpServer + Streamable HTTP transport per request at /mcp.
 * OAuth is enforced before this module by workers-oauth-provider (index.ts).
 */
import { StreamableHTTPTransport } from "@hono/mcp";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { Hono } from "hono";
import { z } from "zod";
import type { AuthProps, Env } from "./env";
import { AiPlanSchema } from "./schema";
import { addInbox, getDoc, listInbox, settleInbox, type SyncMeta } from "./store";
import { addDays, isIsoDate, keyId, parseJson, round, todayIn } from "./util";

const SERVER_INFO = { name: "fluff", version: "0.1.0" };

export const INSTRUCTIONS = `Fluff is one person's adaptive weightlifting app. Every morning it scores readiness (WHOOP recovery or HRV, resting heart rate and sleep from Health Connect, plus an optional check-in and training load) and designs that day's session with a warm-up, main work and cool-down. It logs every set and tracks estimated 1RM (e1RM), strength scores and goals.
Units: weights in kg (profile.unit says whether the person thinks in kg or lb, so convert when you answer), dates as YYYY-MM-DD in the person's own time zone, durations in minutes unless a field says _sec. RPE is 1-10.
Start with get_overview. Then use list_sessions / get_session for workouts, get_exercise_history for one lift over time, get_recovery for readiness and wearable signals, list_activities for runs, rides and sports, get_progress for strength scores, weekly volume and goals, get_plan for a day's planned workout, and list_exercises for exercise ids.
The data is a copy the phone uploads when it syncs (on open and every few hours); get_overview shows how fresh it is.
Changes (schedule_workout, set_goal, remove_goal, log_activity) are queued and applied by the phone on its next sync. The phone re-checks scheduled workouts against the person's injuries, equipment and that day's readiness, sets the weights itself, and may reject or trim them; list_changes shows the outcome. Never present a queued change as already done.`;

type Result = { content: { type: "text"; text: string }[]; structuredContent?: Record<string, unknown>; isError?: boolean };
const ok = (data: Record<string, unknown>): Result => ({ content: [{ type: "text", text: JSON.stringify(data) }], structuredContent: data });
const fail = (error: string, extra: Record<string, unknown> = {}): Result => {
  const data = { error, ...extra };
  return { content: [{ type: "text", text: JSON.stringify(data) }], structuredContent: data, isError: true };
};

const dateArg = z.string().refine(isIsoDate, "use YYYY-MM-DD");
const READ = { readOnlyHint: true, openWorldHint: false } as const;
const WRITE = { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false } as const;

interface SessionRow {
  id: number; date: string; started_at: number; ended_at: number | null; title: string; focus: string | null; source: string | null; status: string;
  readiness_score: number | null; session_rpe: number | null; notes: string | null; duration_min: number | null; work_sets: number | null; volume_kg: number | null; plan_json: string | null;
}
interface SetRow {
  session_id: number; exercise_id: string; block: string; exercise_order: number; set_index: number; kind: string;
  target_reps: number | null; target_duration_sec: number | null; target_weight_kg: number | null; target_rpe: number | null; rest_sec: number | null;
  weight_kg: number | null; reps: number | null; rpe: number | null; duration_sec: number | null; completed_at: number | null; skipped: number; e1rm_kg: number | null;
}
interface DayRow {
  date: string; whoop_recovery: number | null; hrv_ms: number | null; resting_hr: number | null; sleep_hours: number | null; sleep_performance: number | null;
  hrv_source: string | null; readiness_score: number | null; readiness_band: string | null; readiness_json: string | null; checkin_json: string | null; plan_json: string | null;
}

const isWork = (s: SetRow) => s.kind !== "WARMUP" && s.block === "MAIN" && s.completed_at !== null && !s.skipped;

function setText(s: { weight_kg: number | null; reps: number | null; rpe: number | null; duration_sec: number | null }): string {
  const load = s.weight_kg ? `${round(s.weight_kg, 2)}kg` : "bodyweight";
  const amount = s.reps ? `×${s.reps}` : s.duration_sec ? ` for ${s.duration_sec}s` : "";
  return `${load}${amount}${s.rpe ? ` @${s.rpe}` : ""}`;
}

class Data {
  private names?: Map<string, string>;
  constructor(readonly db: D1Database) {}

  async exerciseNames(): Promise<Map<string, string>> {
    if (!this.names) {
      const rows = await this.db.prepare(`SELECT id, name FROM exercises`).all<{ id: string; name: string }>();
      this.names = new Map(rows.results.map((r) => [r.id, r.name]));
    }
    return this.names;
  }

  async name(id: string): Promise<string> {
    return (await this.exerciseNames()).get(id) ?? id.replace(/_/g, " ");
  }

  async meta(): Promise<SyncMeta | null> {
    return getDoc<SyncMeta>(this.db, "sync");
  }

  async today(): Promise<string> {
    return todayIn((await this.meta())?.timeZone);
  }

  async setsFor(ids: number[]): Promise<SetRow[]> {
    if (!ids.length) return [];
    const rows = await this.db
      .prepare(`SELECT sets.* FROM sets JOIN json_each(?1) j ON sets.session_id = j.value ORDER BY session_id, exercise_order, set_index`)
      .bind(JSON.stringify(ids))
      .all<SetRow>();
    return rows.results;
  }

  /** "back_squat" or "Back squat" or "squat" → matching exercise ids. */
  async resolveExercise(query: string): Promise<{ id: string; name: string }[]> {
    const names = await this.exerciseNames();
    const q = query.trim().toLowerCase();
    if (names.has(query)) return [{ id: query, name: names.get(query)! }];
    const all = [...names].map(([id, name]) => ({ id, name }));
    const exact = all.filter((e) => e.name.toLowerCase() === q || e.id.toLowerCase() === q);
    if (exact.length) return exact;
    const words = q.split(/\s+/).filter(Boolean);
    const partial = all.filter((e) => words.every((w) => e.name.toLowerCase().includes(w) || e.id.toLowerCase().includes(w)));
    if (partial.length) return partial;
    // Exercise logged before the library was uploaded, or a custom id.
    const logged = await this.db.prepare(`SELECT DISTINCT exercise_id FROM sets WHERE exercise_id LIKE ?1 LIMIT 20`).bind(`%${q.replace(/\s+/g, "%")}%`).all<{ exercise_id: string }>();
    return logged.results.map((r) => ({ id: r.exercise_id, name: names.get(r.exercise_id) ?? r.exercise_id }));
  }

  /** Per-exercise summary of a session's completed work sets. */
  async exerciseSummary(sets: SetRow[]) {
    const byEx = new Map<string, SetRow[]>();
    for (const s of sets.filter(isWork)) byEx.set(s.exercise_id, [...(byEx.get(s.exercise_id) ?? []), s]);
    return Promise.all(
      [...byEx].map(async ([id, list]) => {
        const best = list.reduce((a, b) => ((b.e1rm_kg ?? 0) > (a.e1rm_kg ?? 0) || ((b.weight_kg ?? 0) > (a.weight_kg ?? 0) && !a.e1rm_kg) ? b : a));
        return { exerciseId: id, name: await this.name(id), workSets: list.length, bestSet: setText(best), bestE1rmKg: round(best.e1rm_kg) };
      }),
    );
  }

  async describePlan(plan: Record<string, unknown> | null) {
    if (!plan) return null;
    const blocks = (plan.blocks as { type: string; exercises: { exerciseId: string; note?: string; sets: Record<string, unknown>[] }[] }[] | undefined) ?? [];
    return {
      title: plan.title,
      focus: plan.focus,
      source: plan.source,
      estimatedMinutes: plan.estimatedMinutes,
      readinessScore: plan.readinessScore,
      rationale: plan.rationale,
      coachNote: plan.coachNote,
      fallbackReason: plan.fallbackReason,
      blocks: await Promise.all(
        blocks.map(async (b) => ({
          block: b.type,
          exercises: await Promise.all(
            b.exercises.map(async (e) => ({
              exerciseId: e.exerciseId,
              name: await this.name(e.exerciseId),
              note: e.note ?? null,
              sets: e.sets.map((s) => {
                const out: Record<string, unknown> = {};
                for (const [k, v] of Object.entries(s)) if (v !== null && v !== undefined) out[k] = v;
                return out;
              }),
            })),
          ),
        })),
      ),
    };
  }
}

function readinessSummary(json: string | null) {
  const r = parseJson<{ score?: number; band?: string; confidence?: string; factors?: { title?: string; detail?: string; impact?: number }[]; prescription?: Record<string, unknown> }>(json);
  if (!r) return null;
  return {
    score: r.score,
    band: r.band,
    confidence: r.confidence,
    factors: (r.factors ?? []).map((f) => `${f.title}: ${f.detail}${typeof f.impact === "number" ? ` (${f.impact >= 0 ? "+" : ""}${f.impact})` : ""}`),
    prescription: r.prescription,
  };
}

export function createServer(env: Env): McpServer {
  const server = new McpServer(SERVER_INFO, { instructions: INSTRUCTIONS });
  const data = new Data(env.DB);
  const db = env.DB;

  server.registerTool(
    "get_overview",
    {
      title: "Overview",
      description:
        "Start here. The person's profile (goal, experience, bodyweight, schedule, equipment, injuries/limitations, other sports), how fresh the synced data is, today's readiness and plan, the last 7 and 28 days of lifting and other activities, the latest session, goals, headline strength scores, and queued changes.",
      inputSchema: {},
      annotations: READ,
    },
    async () => {
      const meta = await data.meta();
      if (!meta) return fail("Nothing has synced yet. In the Fluff app, open You › Claude & ChatGPT and connect this server, then try again.");
      const today = todayIn(meta.timeZone);
      const [profile, goals, insights, day, last, pending] = await Promise.all([
        getDoc(db, "profile"),
        getDoc<unknown[]>(db, "goals"),
        getDoc<Record<string, unknown>>(db, "insights"),
        db.prepare(`SELECT * FROM days WHERE date = ?1`).bind(today).first<DayRow>(),
        db.prepare(`SELECT * FROM sessions WHERE status = 'COMPLETED' ORDER BY started_at DESC LIMIT 1`).first<SessionRow>(),
        listInbox(db, { status: "pending" }),
      ]);
      const window = async (days: number) => {
        const from = addDays(today, -(days - 1));
        const s = await db
          .prepare(`SELECT COUNT(*) AS sessions, COALESCE(SUM(work_sets),0) AS work_sets, COALESCE(SUM(volume_kg),0) AS volume_kg, COALESCE(SUM(duration_min),0) AS minutes FROM sessions WHERE status='COMPLETED' AND date BETWEEN ?1 AND ?2`)
          .bind(from, today)
          .first<{ sessions: number; work_sets: number; volume_kg: number; minutes: number }>();
        const a = await db
          .prepare(`SELECT type, COUNT(*) AS n, SUM(duration_min) AS minutes FROM activities WHERE date BETWEEN ?1 AND ?2 GROUP BY type ORDER BY minutes DESC`)
          .bind(from, today)
          .all<{ type: string; n: number; minutes: number }>();
        return {
          from,
          liftingSessions: s?.sessions ?? 0,
          workSets: s?.work_sets ?? 0,
          volumeKg: round(s?.volume_kg, 0),
          liftingMinutes: round(s?.minutes, 0),
          otherActivities: a.results.map((r) => ({ type: r.type, sessions: r.n, minutes: round(r.minutes, 0) })),
        };
      };
      const ageHours = (Date.now() - Date.parse(meta.lastSyncAt)) / 3_600_000;
      return ok({
        today,
        sync: { lastSyncAt: meta.lastSyncAt, hoursAgo: round(ageHours, 1), timeZone: meta.timeZone, note: ageHours > 24 ? "Data may be stale: the phone has not synced for over a day." : undefined },
        profile,
        readinessToday: day ? { ...readinessSummary(day.readiness_json), whoopRecovery: day.whoop_recovery, hrvMs: day.hrv_ms, restingHr: day.resting_hr, sleepHours: day.sleep_hours } : null,
        planToday: day?.plan_json ? await data.describePlan(parseJson(day.plan_json)) : null,
        last7Days: await window(7),
        last28Days: await window(28),
        lastSession: last
          ? { id: last.id, date: last.date, title: last.title, durationMin: round(last.duration_min, 0), workSets: last.work_sets, volumeKg: round(last.volume_kg, 0), sessionRpe: last.session_rpe, exercises: await data.exerciseSummary(await data.setsFor([last.id])) }
          : null,
        goals: goals ?? [],
        strength: insights?.strength ?? null,
        streakWeeks: insights?.streakWeeks ?? null,
        queuedChanges: pending.length,
      });
    },
  );

  server.registerTool(
    "list_sessions",
    {
      title: "List workouts",
      description:
        "Completed lifting sessions, newest first: id, date, title, focus, duration, work sets, volume (kg moved in main work), readiness that day, session RPE, notes, and the best set and e1RM per exercise. Filter by date range or by an exercise (id or name). Use get_session for every set.",
      inputSchema: {
        from: dateArg.optional().describe("Earliest date, YYYY-MM-DD"),
        to: dateArg.optional().describe("Latest date, YYYY-MM-DD"),
        exercise: z.string().max(100).optional().describe("Only sessions that included this exercise (id or name)"),
        limit: z.number().int().min(1).max(100).optional().describe("Default 20"),
      },
      annotations: READ,
    },
    async ({ from, to, exercise, limit }) => {
      let ids: string[] | null = null;
      if (exercise) {
        const matches = await data.resolveExercise(exercise);
        if (!matches.length) return fail(`No exercise matches "${exercise}". Try list_exercises.`);
        ids = matches.map((m) => m.id);
      }
      const rows = await db
        .prepare(
          `SELECT * FROM sessions WHERE status = 'COMPLETED' AND date >= ?1 AND date <= ?2
           AND (?3 IS NULL OR id IN (SELECT session_id FROM sets JOIN json_each(?3) j ON sets.exercise_id = j.value))
           ORDER BY started_at DESC LIMIT ?4`,
        )
        .bind(from ?? "0000-01-01", to ?? "9999-12-31", ids ? JSON.stringify(ids) : null, limit ?? 20)
        .all<SessionRow>();
      const sets = await data.setsFor(rows.results.map((r) => r.id));
      const sessions = await Promise.all(
        rows.results.map(async (r) => ({
          id: r.id,
          date: r.date,
          title: r.title,
          focus: r.focus,
          source: r.source,
          durationMin: round(r.duration_min, 0),
          workSets: r.work_sets,
          volumeKg: round(r.volume_kg, 0),
          readinessScore: r.readiness_score,
          sessionRpe: r.session_rpe,
          notes: r.notes,
          exercises: await data.exerciseSummary(sets.filter((s) => s.session_id === r.id)),
        })),
      );
      return ok({ count: sessions.length, sessions });
    },
  );

  server.registerTool(
    "get_session",
    {
      title: "Workout detail",
      description:
        "One session with every set in order, grouped into warm-up, main work and cool-down: planned target (reps, seconds, kg, RPE, rest) next to what was actually done, skipped sets, e1RM per set, plus the plan's rationale and the session notes.",
      inputSchema: { id: z.number().int().positive().describe("Session id from list_sessions") },
      annotations: READ,
    },
    async ({ id }) => {
      const s = await db.prepare(`SELECT * FROM sessions WHERE id = ?1`).bind(id).first<SessionRow>();
      if (!s) return fail(`No session with id ${id}.`);
      const sets = await data.setsFor([id]);
      const blocks: Record<string, { exerciseId: string; name: string; sets: Record<string, unknown>[] }[]> = {};
      for (const set of sets) {
        const list = (blocks[set.block] ??= []);
        let ex = list.find((e, i) => e.exerciseId === set.exercise_id && i === list.length - 1);
        if (!ex) list.push((ex = { exerciseId: set.exercise_id, name: await data.name(set.exercise_id), sets: [] }));
        ex.sets.push({
          kind: set.kind,
          target: [set.target_reps ? `${set.target_reps} reps` : null, set.target_duration_sec ? `${set.target_duration_sec}s` : null, set.target_weight_kg ? `${round(set.target_weight_kg, 2)}kg` : null, set.target_rpe ? `@${set.target_rpe}` : null].filter(Boolean).join(" ") || null,
          done: set.completed_at ? setText(set) : null,
          skipped: !!set.skipped || undefined,
          e1rmKg: round(set.e1rm_kg) ?? undefined,
          restSec: set.rest_sec,
        });
      }
      const plan = parseJson<Record<string, unknown>>(s.plan_json);
      return ok({
        id: s.id,
        date: s.date,
        title: s.title,
        focus: s.focus,
        status: s.status,
        startedAt: new Date(s.started_at).toISOString(),
        durationMin: round(s.duration_min, 0),
        workSets: s.work_sets,
        volumeKg: round(s.volume_kg, 0),
        readinessScore: s.readiness_score,
        sessionRpe: s.session_rpe,
        notes: s.notes,
        planRationale: plan?.rationale ?? null,
        planSource: plan?.source ?? s.source,
        blocks,
      });
    },
  );

  server.registerTool(
    "get_exercise_history",
    {
      title: "Lift history",
      description:
        "One exercise over time: every session it was done (date, work sets, best set, best e1RM, volume), plus all-time best e1RM, heaviest set, the latest e1RM and the change over the period. Accepts an id or a name like \"bench\" or \"back squat\"; if several exercises match it returns the candidates.",
      inputSchema: {
        exercise: z.string().min(1).max(100),
        from: dateArg.optional().describe("Earliest date (default: all history)"),
        limit: z.number().int().min(1).max(200).optional().describe("Most recent sessions to include, default 50"),
      },
      annotations: READ,
    },
    async ({ exercise, from, limit }) => {
      const matches = await data.resolveExercise(exercise);
      if (!matches.length) return fail(`No exercise matches "${exercise}". Try list_exercises.`);
      if (matches.length > 1 && !matches.some((m) => m.id === exercise)) {
        const logged = await db
          .prepare(`SELECT exercise_id, COUNT(DISTINCT session_id) AS n FROM sets JOIN json_each(?1) j ON sets.exercise_id = j.value GROUP BY exercise_id`)
          .bind(JSON.stringify(matches.map((m) => m.id)))
          .all<{ exercise_id: string; n: number }>();
        const done = logged.results.filter((r) => r.n > 0);
        if (done.length !== 1) {
          return fail("Several exercises match; call again with one id.", {
            candidates: matches.slice(0, 25).map((m) => ({ ...m, sessionsLogged: done.find((d) => d.exercise_id === m.id)?.n ?? 0 })),
          });
        }
        matches.splice(0, matches.length, matches.find((m) => m.id === done[0].exercise_id)!);
      }
      const ex = matches[0];
      const rows = await db
        .prepare(
          `SELECT s.id, s.date, sets.weight_kg, sets.reps, sets.rpe, sets.duration_sec, sets.e1rm_kg, sets.kind, sets.block, sets.completed_at, sets.skipped
           FROM sets JOIN sessions s ON s.id = sets.session_id
           WHERE sets.exercise_id = ?1 AND s.status = 'COMPLETED' AND s.date >= ?2 AND sets.completed_at IS NOT NULL AND sets.skipped = 0 AND sets.kind != 'WARMUP'
           ORDER BY s.started_at, sets.set_index`,
        )
        .bind(ex.id, from ?? "0000-01-01")
        .all<{ id: number; date: string; weight_kg: number | null; reps: number | null; rpe: number | null; duration_sec: number | null; e1rm_kg: number | null }>();
      const bySession = new Map<number, typeof rows.results>();
      for (const r of rows.results) bySession.set(r.id, [...(bySession.get(r.id) ?? []), r]);
      const sessions = [...bySession].map(([id, list]) => {
        const best = list.reduce((a, b) => ((b.e1rm_kg ?? 0) > (a.e1rm_kg ?? 0) ? b : a));
        return {
          sessionId: id,
          date: list[0].date,
          workSets: list.length,
          sets: list.map(setText),
          bestSet: setText(best),
          bestE1rmKg: round(best.e1rm_kg),
          volumeKg: round(list.reduce((t, s) => t + (s.weight_kg ?? 0) * (s.reps ?? 0), 0), 0),
        };
      });
      const withE1rm = sessions.filter((s) => s.bestE1rmKg !== null);
      const top = withE1rm.reduce<(typeof sessions)[number] | null>((a, b) => (!a || (b.bestE1rmKg ?? 0) > (a.bestE1rmKg ?? 0) ? b : a), null);
      const heaviest = rows.results.reduce<(typeof rows.results)[number] | null>((a, b) => (!a || (b.weight_kg ?? 0) > (a.weight_kg ?? 0) ? b : a), null);
      const first = withE1rm[0];
      const latest = withE1rm[withE1rm.length - 1];
      return ok({
        exerciseId: ex.id,
        name: ex.name,
        sessionsLogged: sessions.length,
        allTimeBestE1rm: top ? { kg: top.bestE1rmKg, date: top.date, set: top.bestSet } : null,
        heaviestSet: heaviest?.weight_kg ? { set: setText(heaviest), date: heaviest.date } : null,
        latestE1rm: latest ? { kg: latest.bestE1rmKg, date: latest.date } : null,
        changeOverPeriodKg: first && latest && first !== latest ? round((latest.bestE1rmKg ?? 0) - (first.bestE1rmKg ?? 0)) : null,
        sessions: sessions.slice(-(limit ?? 50)).reverse(),
      });
    },
  );

  server.registerTool(
    "list_exercises",
    {
      title: "Exercise library",
      description:
        "Fluff's exercise library with ids, names and attributes (movement pattern, muscles, equipment, roles). Search by words in the name or id. Use the ids in schedule_workout and set_goal. The phone's equipment and injury filters still apply when it receives a workout.",
      inputSchema: {
        query: z.string().max(100).optional().describe("Words to match, e.g. \"row\" or \"hamstring\""),
        limit: z.number().int().min(1).max(300).optional().describe("Default 50"),
      },
      annotations: READ,
    },
    async ({ query, limit }) => {
      const words = (query ?? "").toLowerCase().split(/\s+/).filter(Boolean);
      const rows = await db.prepare(`SELECT id, name, json FROM exercises ORDER BY name`).all<{ id: string; name: string; json: string }>();
      const matches = rows.results.filter((r) => {
        const hay = (r.id + " " + r.json).toLowerCase();
        return words.every((w) => hay.includes(w));
      });
      return ok({ total: matches.length, exercises: matches.slice(0, limit ?? 50).map((r) => parseJson(r.json)) });
    },
  );

  server.registerTool(
    "get_recovery",
    {
      title: "Recovery and readiness",
      description:
        "Day by day: Fluff's readiness score and band with the factors behind it, WHOOP recovery, HRV (ms), resting heart rate, sleep hours and the morning check-in (1-5 scales), plus averages for the period. Default last 14 days, up to 180.",
      inputSchema: { days: z.number().int().min(1).max(180).optional(), to: dateArg.optional().describe("Last day (default today)") },
      annotations: READ,
    },
    async ({ days, to }) => {
      const end = to ?? (await data.today());
      const start = addDays(end, -((days ?? 14) - 1));
      const rows = await db.prepare(`SELECT * FROM days WHERE date BETWEEN ?1 AND ?2 ORDER BY date DESC`).bind(start, end).all<DayRow>();
      const avg = (pick: (d: DayRow) => number | null, digits = 1) => {
        const v = rows.results.map(pick).filter((x): x is number => typeof x === "number");
        return v.length ? round(v.reduce((a, b) => a + b, 0) / v.length, digits) : null;
      };
      return ok({
        from: start,
        to: end,
        averages: {
          readiness: avg((d) => d.readiness_score, 0),
          whoopRecovery: avg((d) => d.whoop_recovery, 0),
          hrvMs: avg((d) => d.hrv_ms),
          restingHr: avg((d) => d.resting_hr),
          sleepHours: avg((d) => d.sleep_hours),
        },
        days: rows.results.map((d) => ({
          date: d.date,
          readiness: readinessSummary(d.readiness_json),
          whoopRecovery: d.whoop_recovery,
          hrvMs: round(d.hrv_ms),
          hrvSource: d.hrv_source,
          restingHr: round(d.resting_hr),
          sleepHours: round(d.sleep_hours, 2),
          sleepPerformance: d.sleep_performance,
          checkIn: parseJson(d.checkin_json),
        })),
      });
    },
  );

  server.registerTool(
    "list_activities",
    {
      title: "Other activities",
      description:
        "Non-lifting activities from WHOOP, Health Connect or logged by hand (runs, rides, sports, climbs, yoga...): date, type, source, duration, intensity (1-10), distance, energy. Default last 28 days, up to 365.",
      inputSchema: {
        days: z.number().int().min(1).max(365).optional(),
        type: z.string().max(32).optional().describe("e.g. RUNNING, CYCLING, TENNIS"),
      },
      annotations: READ,
    },
    async ({ days, type }) => {
      const end = await data.today();
      const start = addDays(end, -((days ?? 28) - 1));
      const rows = await db
        .prepare(`SELECT * FROM activities WHERE date BETWEEN ?1 AND ?2 AND (?3 IS NULL OR type = ?3) ORDER BY start_ms DESC`)
        .bind(start, end, type?.toUpperCase() ?? null)
        .all<{ id: string; date: string; type: string; source: string; start_ms: number; duration_min: number; intensity: number | null; name: string | null; energy_kcal: number | null; distance_m: number | null }>();
      return ok({
        from: start,
        to: end,
        count: rows.results.length,
        totalMinutes: round(rows.results.reduce((t, r) => t + r.duration_min, 0), 0),
        activities: rows.results.map((r) => ({
          date: r.date,
          start: new Date(r.start_ms).toISOString(),
          type: r.type,
          name: r.name,
          source: r.source,
          durationMin: round(r.duration_min, 0),
          intensity: round(r.intensity),
          distanceKm: r.distance_m ? round(r.distance_m / 1000, 2) : null,
          energyKcal: round(r.energy_kcal, 0),
        })),
      });
    },
  );

  server.registerTool(
    "get_plan",
    {
      title: "Planned workout",
      description:
        "The workout Fluff planned for a day (default today): title, focus, who designed it (Fluff engine, Claude coach or your assistant), the rationale, and every exercise and set in the warm-up, main work and cool-down. Also lists any workout queued for that day with schedule_workout that the phone has not picked up yet.",
      inputSchema: { date: dateArg.optional() },
      annotations: READ,
    },
    async ({ date }) => {
      const day = date ?? (await data.today());
      const row = await db.prepare(`SELECT plan_json, readiness_json FROM days WHERE date = ?1`).bind(day).first<{ plan_json: string | null; readiness_json: string | null }>();
      const queued = (await listInbox(db, { status: "pending" })).filter((i) => i.kind === "schedule_workout" && i.payload.date === day);
      return ok({
        date: day,
        plan: row?.plan_json ? await data.describePlan(parseJson(row.plan_json)) : null,
        readiness: readinessSummary(row?.readiness_json ?? null),
        queuedWorkouts: queued.map((q) => ({ changeId: q.id, title: (q.payload.plan as { title?: string })?.title, createdAt: q.createdAt })),
        note: row?.plan_json ? undefined : "No plan synced for this day. Fluff plans a day when the app is opened that morning.",
      });
    },
  );

  server.registerTool(
    "get_progress",
    {
      title: "Progress",
      description:
        "Strength scores (overall and per movement pattern, compared with bodyweight standards), how well that strength supports each of the person's sports, goal progress and projections, current e1RM for main lifts, weekly sets per muscle, the week streak, and a week-by-week table of sessions, work sets, volume and average readiness.",
      inputSchema: { weeks: z.number().int().min(1).max(52).optional().describe("Weeks in the weekly table, default 8") },
      annotations: READ,
    },
    async ({ weeks }) => {
      const today = await data.today();
      const n = weeks ?? 8;
      // Weeks start on Monday. strftime('%w'): 0 = Sunday.
      const rows = await db
        .prepare(
          `SELECT date(date, '-' || ((CAST(strftime('%w', date) AS INTEGER) + 6) % 7) || ' days') AS week,
                  COUNT(*) AS sessions, COALESCE(SUM(work_sets),0) AS work_sets, COALESCE(SUM(volume_kg),0) AS volume_kg, AVG(readiness_score) AS readiness
           FROM sessions WHERE status = 'COMPLETED' AND date >= ?1 GROUP BY week ORDER BY week DESC`,
        )
        .bind(addDays(today, -7 * n))
        .all<{ week: string; sessions: number; work_sets: number; volume_kg: number; readiness: number | null }>();
      const [insights, goals] = await Promise.all([getDoc<Record<string, unknown>>(db, "insights"), getDoc<unknown[]>(db, "goals")]);
      return ok({
        ...(insights ?? {}),
        goals: goals ?? [],
        weekly: rows.results.slice(0, n).map((r) => ({ weekOf: r.week, sessions: r.sessions, workSets: r.work_sets, volumeKg: round(r.volume_kg, 0), avgReadiness: round(r.readiness, 0) })),
      });
    },
  );

  server.registerTool(
    "list_changes",
    {
      title: "Queued changes",
      description: "Changes requested through this connector and what the phone did with each: pending (not synced yet), applied, rejected (with the reason) or cancelled. Newest first.",
      inputSchema: { status: z.enum(["pending", "applied", "rejected", "cancelled"]).optional(), limit: z.number().int().min(1).max(100).optional() },
      annotations: READ,
    },
    async ({ status, limit }) => ok({ changes: await listInbox(db, { status, limit: limit ?? 20 }) }),
  );

  // ───────── Changes (queued for the phone) ─────────

  async function knownIds(ids: string[]): Promise<string[]> {
    const names = await data.exerciseNames();
    return names.size ? ids.filter((id) => !names.has(id)) : [];
  }

  server.registerTool(
    "schedule_workout",
    {
      title: "Schedule a workout",
      description:
        "Queue a workout you designed for a date (today up to 14 days ahead). It replaces Fluff's own plan for that day unless the person has already trained. Use exercise ids from list_exercises. Give reps and RPE, not weights: the phone calculates loads from the person's history, adds ramp-up sets, clamps sets and RPE to that day's readiness, and rejects exercises that conflict with injuries or equipment. Include a real warm-up and cool-down. Kinds: \"warmup\" for every warm-up and cool-down item, \"top\" for one heavy top set, \"backoff\" after it, otherwise \"working\". For timed items set reps 0 and duration_sec; for rep sets duration_sec 0; rpe 0 for warm-up, mobility and stretches.",
      inputSchema: {
        date: dateArg.describe("YYYY-MM-DD"),
        plan: AiPlanSchema,
      },
      annotations: WRITE,
    },
    async ({ date, plan }) => {
      const today = await data.today();
      if (date < today || date > addDays(today, 14)) return fail(`date must be between ${today} and ${addDays(today, 14)}`);
      const unknown = await knownIds([...plan.warmup, ...plan.main, ...plan.cooldown].map((e) => e.exercise_id));
      if (unknown.length) return fail("Unknown exercise ids; use list_exercises.", { unknown: [...new Set(unknown)] });
      const item = await addInbox(db, "schedule_workout", { date, plan }, Date.now());
      return ok({ queued: true, changeId: item.id, date, note: "The phone applies this on its next sync (when Fluff is opened, or within a few hours) and may adjust it; check list_changes." });
    },
  );

  server.registerTool(
    "set_goal",
    {
      title: "Add a strength goal",
      description: "Queue a new goal: reach an estimated 1RM (target_kg, in kg) on a lift, optionally by a date. Fluff tracks progress and projects when it will be reached.",
      inputSchema: {
        exercise_id: z.string().min(1).max(100).describe("Id from list_exercises"),
        target_kg: z.number().positive().max(1000).describe("Target estimated 1RM in kg"),
        target_date: dateArg.optional(),
      },
      annotations: WRITE,
    },
    async ({ exercise_id, target_kg, target_date }) => {
      if ((await knownIds([exercise_id])).length) return fail(`Unknown exercise id ${exercise_id}; use list_exercises.`);
      if (target_date && target_date <= (await data.today())) return fail("target_date must be in the future");
      const item = await addInbox(db, "add_goal", { exerciseId: exercise_id, targetKg: target_kg, targetDate: target_date ?? null }, Date.now());
      return ok({ queued: true, changeId: item.id, exercise: await data.name(exercise_id), targetKg: target_kg, targetDate: target_date ?? null });
    },
  );

  server.registerTool(
    "remove_goal",
    {
      title: "Remove a goal",
      description: "Queue removal of a goal by its id (see get_overview or get_progress).",
      inputSchema: { goal_id: z.number().int().positive() },
      annotations: { ...WRITE, destructiveHint: true },
    },
    async ({ goal_id }) => {
      const goals = (await getDoc<{ id?: number }[]>(db, "goals")) ?? [];
      if (!goals.some((g) => g.id === goal_id)) return fail(`No goal with id ${goal_id}.`);
      const item = await addInbox(db, "remove_goal", { goalId: goal_id }, Date.now());
      return ok({ queued: true, changeId: item.id });
    },
  );

  server.registerTool(
    "log_activity",
    {
      title: "Log an activity",
      description:
        "Queue a non-lifting activity the wearables missed (a match, a hike, a class) so Fluff counts it in training load and muscle fatigue. Intensity is session RPE 1-10 (omit to use the activity's typical value).",
      inputSchema: {
        type: z.enum(["RUNNING", "CYCLING", "SWIMMING", "CLIMBING", "TENNIS", "FOOTBALL", "BASKETBALL", "MARTIAL_ARTS", "ROWING", "SKIING", "HIKING", "YOGA", "WALKING", "HIIT", "OTHER"]),
        date: dateArg,
        start_time: z.string().regex(/^\d{2}:\d{2}$/).optional().describe("Local HH:MM, default 12:00"),
        duration_min: z.number().positive().max(1440),
        intensity: z.number().min(1).max(10).optional(),
        name: z.string().max(100).optional(),
      },
      annotations: WRITE,
    },
    async ({ type, date, start_time, duration_min, intensity, name }) => {
      const today = await data.today();
      if (date > today || date < addDays(today, -60)) return fail(`date must be between ${addDays(today, -60)} and ${today}`);
      const item = await addInbox(db, "log_activity", { type, date, startTime: start_time ?? "12:00", durationMin: duration_min, intensity: intensity ?? null, name: name ?? null }, Date.now());
      return ok({ queued: true, changeId: item.id });
    },
  );

  server.registerTool(
    "cancel_change",
    {
      title: "Cancel a queued change",
      description: "Cancel a change that the phone has not applied yet.",
      inputSchema: { change_id: z.string().min(1).max(64) },
      annotations: { ...WRITE, idempotentHint: true },
    },
    async ({ change_id }) =>
      (await settleInbox(db, change_id, "cancelled", "Cancelled from the assistant", Date.now()))
        ? ok({ cancelled: true })
        : fail("That change is not pending (already applied, rejected, cancelled, or unknown)."),
  );

  return server;
}

export const mcpApp = new Hono<{ Bindings: Env }>();

mcpApp.all("/mcp", async (c) => {
  const props = (c.executionCtx as ExecutionContext & { props?: Partial<AuthProps> }).props;
  // A rotated OWNER_KEY disconnects every assistant at once.
  if (!props?.keyId || props.keyId !== (await keyId(c.env.OWNER_KEY))) {
    const origin = new URL(c.req.url).origin;
    return c.json({ error: "invalid_token" }, 401, { "WWW-Authenticate": `Bearer error="invalid_token", resource_metadata="${origin}/.well-known/oauth-protected-resource"` });
  }
  c.header("Cache-Control", "no-store");
  const server = createServer(c.env);
  const transport = new StreamableHTTPTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
  await server.connect(transport);
  return transport.handleRequest(c);
});

mcpApp.all("/mcp/*", (c) => c.json({ error: "the MCP endpoint is /mcp" }, 404));
