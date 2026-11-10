/** A small, realistic snapshot as the phone builds it. Dates are relative to "today" in UTC. */
export function isoDay(offset: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + offset);
  return d.toISOString().slice(0, 10);
}

const set = (exerciseId: string, i: number, weightKg: number, reps: number, rpe: number, e1rmKg: number, kind = "WORKING") => ({
  exerciseId, block: "MAIN", exerciseOrder: exerciseId === "back_squat" || exerciseId === "bench_press" ? 0 : 1, setIndex: i, kind,
  targetReps: reps, targetWeightKg: weightKg, targetRpe: rpe, restSec: 150, weightKg, reps, rpe, completedAt: Date.now(), skipped: false, e1rmKg,
});

export function session(id: number, dayOffset: number, squatKg: number) {
  return {
    id,
    date: isoDay(dayOffset),
    startedAt: Date.parse(isoDay(dayOffset) + "T07:00:00Z"),
    endedAt: Date.parse(isoDay(dayOffset) + "T08:05:00Z"),
    title: "Lower strength",
    focus: "LOWER",
    source: "ENGINE",
    status: "COMPLETED",
    readinessScore: 72,
    sessionRpe: 7.5,
    notes: id === 1 ? "Knee felt fine" : null,
    durationMin: 65,
    workSets: 4,
    volumeKg: squatKg * 5 * 3 + 80 * 10,
    plan: { title: "Lower strength", rationale: ["HRV is at baseline, so top sets are on."], source: "ENGINE" },
    sets: [
      { exerciseId: "back_squat", block: "WARMUP", exerciseOrder: 0, setIndex: 0, kind: "WARMUP", targetReps: 5, restSec: 60, weightKg: 60, reps: 5, completedAt: Date.now(), skipped: false },
      set("back_squat", 1, squatKg, 5, 8, squatKg * 1.2),
      set("back_squat", 2, squatKg, 5, 8.5, squatKg * 1.21),
      set("back_squat", 3, squatKg, 5, 9, squatKg * 1.23),
      set("romanian_deadlift", 0, 80, 10, 7, 120),
    ],
  };
}

export function snapshot(overrides: Record<string, unknown> = {}) {
  return {
    version: 1,
    generatedAt: new Date().toISOString(),
    timeZone: "UTC",
    range: { from: isoDay(-29), to: isoDay(0) },
    reset: false,
    profile: { name: "Test", goal: "STRENGTH", experience: "INTERMEDIATE", bodyWeightKg: 80, daysPerWeek: 4, sessionMinutes: 60, unit: "KG", limitations: [{ region: "KNEE", severity: "MILD" }] },
    goals: [{ id: 3, exerciseId: "back_squat", exerciseName: "Back squat", targetKg: 160, currentE1rmKg: 147.6, projectedDate: isoDay(70) }],
    insights: { strength: { overall: 61, level: "Intermediate" }, streakWeeks: 5 },
    exercises: [
      { id: "back_squat", name: "Back squat", pattern: "SQUAT" },
      { id: "romanian_deadlift", name: "Romanian deadlift", pattern: "HINGE" },
      { id: "bench_press", name: "Bench press", pattern: "HORIZONTAL_PUSH" },
      { id: "dumbbell_bench_press", name: "Dumbbell bench press", pattern: "HORIZONTAL_PUSH" },
      { id: "bike_easy", name: "Easy bike", pattern: "CARDIO" },
      { id: "hip_flexor_stretch", name: "Hip flexor stretch", pattern: "MOBILITY" },
    ],
    sessions: [session(1, -1, 120), session(2, -8, 115)],
    activities: [{ id: "whoop:1", date: isoDay(-2), type: "RUNNING", source: "WHOOP", startMs: Date.parse(isoDay(-2) + "T18:00:00Z"), durationMin: 42, intensity: 6.5, distanceMeters: 8100 }],
    days: [
      { date: isoDay(0), whoopRecovery: 71, hrvMs: 62.4, restingHr: 51, sleepHours: 7.4, hrvSource: "WHOOP", readiness: { score: 74, band: "READY", factors: [{ title: "Recovery", detail: "WHOOP 71%", impact: 4 }] }, checkIn: { sleepQuality: 4, energy: 4, soreness: 2, stress: 2 }, plan: { title: "Upper volume", focus: "UPPER", source: "AI", rationale: ["Legs trained yesterday."], blocks: [{ type: "MAIN", exercises: [{ exerciseId: "bench_press", sets: [{ kind: "WORKING", reps: 8, weightKg: 80, rpe: 8, restSec: 120 }] }] }] } },
      { date: isoDay(-1), whoopRecovery: 55, hrvMs: 55.1, restingHr: 54, sleepHours: 6.2, readiness: { score: 58, band: "STEADY" } },
    ],
    ...overrides,
  };
}
