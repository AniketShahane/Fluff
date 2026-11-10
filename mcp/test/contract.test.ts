import { beforeEach, describe, expect, it } from "vitest";
import phone from "./fixtures/phone-snapshot.json";
import { clearData, connectAssistant, postSnapshot, tool } from "./helpers";

/**
 * phone-snapshot.json is real output of the Android app's CloudSnapshot builder
 * (written by app/src/test/.../CloudSyncTest). If the app's format drifts, this fails.
 */
describe("contract with the Android app", () => {
  beforeEach(clearData);

  it("accepts the phone's snapshot and answers questions about it", async () => {
    const res = await postSnapshot(phone);
    expect(res.status, await res.clone().text()).toBe(200);
    const { access } = await connectAssistant();
    const sessions = (await tool<any>(access, "list_sessions", { limit: 3 })).data;
    expect(sessions.count).toBe(3);
    expect(sessions.sessions[0].exercises.length).toBeGreaterThan(0);
    expect(sessions.sessions[0].volumeKg).toBeGreaterThan(0);
    const squat = (await tool<any>(access, "get_exercise_history", { exercise: "back squat" })).data;
    expect(squat.exerciseId).toBe("back_squat");
    expect(squat.latestE1rm.kg).toBeGreaterThan(100);
    const progress = (await tool<any>(access, "get_progress")).data;
    expect(progress.strength.overall).toBeGreaterThan(0);
    expect(progress.goals[0].exerciseName).toBe("Back Squat");
    const ex = (await tool<any>(access, "list_exercises", { query: "squat" })).data;
    expect(ex.total).toBeGreaterThan(2);
  });
});
