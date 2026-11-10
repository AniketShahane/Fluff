/**
 * The phone → server snapshot. The phone owns all training logic (e1RM, volume, strength
 * scores, readiness); the server stores what it is sent and answers questions about it.
 * Keep in sync with app/src/main/kotlin/app/fluff/integrations/cloud/CloudSnapshot.kt.
 */
import { z } from "zod";
import { isIsoDate } from "./util";

export const SNAPSHOT_VERSION = 1;

const isoDate = z.string().refine(isIsoDate, "expected YYYY-MM-DD");
const num = z.number().finite();
const optNum = num.nullish();
const optInt = z.number().int().nullish();
const id = z.string().min(1).max(200);
const text = (max: number) => z.string().max(max);
/** Free-form JSON documents the phone computes (plan, readiness, insights...). */
const doc = z.record(z.string(), z.unknown());

export const SetSchema = z.object({
  exerciseId: id,
  block: text(16),
  exerciseOrder: z.number().int(),
  setIndex: z.number().int(),
  kind: text(16),
  targetReps: optInt,
  targetDurationSec: optInt,
  targetWeightKg: optNum,
  targetRpe: optNum,
  restSec: optInt,
  weightKg: optNum,
  reps: optInt,
  rpe: optNum,
  durationSec: optInt,
  completedAt: optInt,
  skipped: z.boolean().default(false),
  e1rmKg: optNum,
});

export const SessionSchema = z.object({
  id: z.number().int().positive(),
  date: isoDate,
  startedAt: z.number().int(),
  endedAt: optInt,
  title: text(200),
  focus: text(32).nullish(),
  source: text(32).nullish(),
  status: z.enum(["COMPLETED", "ACTIVE"]),
  readinessScore: optInt,
  sessionRpe: optNum,
  notes: text(4000).nullish(),
  durationMin: optNum,
  workSets: optInt,
  volumeKg: optNum,
  plan: doc.nullish(),
  sets: z.array(SetSchema).max(400),
});

export const ActivitySchema = z.object({
  id,
  date: isoDate,
  type: text(32),
  source: text(32),
  startMs: z.number().int(),
  durationMin: num,
  intensity: optNum,
  name: text(200).nullish(),
  energyKcal: optNum,
  distanceMeters: optNum,
});

export const DaySchema = z.object({
  date: isoDate,
  whoopRecovery: optNum,
  hrvMs: optNum,
  restingHr: optNum,
  sleepHours: optNum,
  sleepPerformance: optNum,
  hrvSource: text(32).nullish(),
  readiness: doc.nullish(),
  checkIn: doc.nullish(),
  plan: doc.nullish(),
});

export const ExerciseSchema = z.object({
  id,
  name: text(200),
}).passthrough();

export const SnapshotSchema = z.object({
  version: z.literal(SNAPSHOT_VERSION),
  generatedAt: z.string().max(40),
  timeZone: text(64),
  /** Every session, activity and day inside [from, to] is replaced by what this snapshot holds. */
  range: z.object({ from: isoDate, to: isoDate }),
  /** True on the first chunk of a full re-sync: wipes all mirrored data first. */
  reset: z.boolean().default(false),
  profile: doc.nullish(),
  goals: z.array(doc).max(200).nullish(),
  insights: doc.nullish(),
  exercises: z.array(ExerciseSchema).max(2000).nullish(),
  sessions: z.array(SessionSchema).max(2000),
  activities: z.array(ActivitySchema).max(5000),
  days: z.array(DaySchema).max(5000),
}).refine((s) => s.range.from <= s.range.to, "range.from must not be after range.to");

export type Snapshot = z.infer<typeof SnapshotSchema>;

/** The plan shape the in-app Claude coach returns (core/ai/AiCoachContract.kt AiPlan). */
export const AiSetSchema = z.object({
  kind: z.enum(["warmup", "working", "top", "backoff"]),
  reps: z.number().int().min(0).max(100).describe("Reps; 0 for timed items"),
  duration_sec: z.number().int().min(0).max(3600).describe("Seconds for holds, carries, cardio, stretches; 0 for rep sets"),
  rpe: z.number().min(0).max(10).describe("Target RPE; 0 for warm-up, mobility and cool-down items"),
  rest_sec: z.number().int().min(0).max(600),
});

export const AiExerciseSchema = z.object({
  exercise_id: z.string().min(1).max(100).describe("An id from list_exercises"),
  sets: z.array(AiSetSchema).min(1).max(12),
  note: z.string().max(300).describe("A short cue, or empty"),
});

export const FOCUSES = ["FULL_BODY", "UPPER", "LOWER", "PUSH", "PULL", "LEGS", "RECOVERY"] as const;

export const AiPlanSchema = z.object({
  title: z.string().min(1).max(80),
  focus: z.enum(FOCUSES),
  rationale: z.array(z.string().max(300)).min(1).max(6),
  coach_note: z.string().max(300),
  warmup: z.array(AiExerciseSchema).max(12),
  main: z.array(AiExerciseSchema).min(1).max(10),
  cooldown: z.array(AiExerciseSchema).max(12),
});
export type AiPlan = z.infer<typeof AiPlanSchema>;
