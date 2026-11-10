import { env } from "cloudflare:workers";
import { beforeAll, describe, expect, it } from "vitest";
import { call, clearData, connectAssistant, mcp, postSnapshot, syncHeaders, tool } from "./helpers";
import { isoDay, snapshot } from "./fixtures";

let token: string;

const plan = {
  title: "Push day",
  focus: "PUSH",
  rationale: ["Legs are tired from yesterday's squats."],
  coach_note: "Smooth reps today.",
  warmup: [{ exercise_id: "bike_easy", sets: [{ kind: "warmup", reps: 0, duration_sec: 300, rpe: 0, rest_sec: 0 }], note: "" }],
  main: [{ exercise_id: "bench_press", sets: [{ kind: "working", reps: 8, duration_sec: 0, rpe: 8, rest_sec: 120 }], note: "Pause the first rep" }],
  cooldown: [{ exercise_id: "hip_flexor_stretch", sets: [{ kind: "warmup", reps: 0, duration_sec: 60, rpe: 0, rest_sec: 0 }], note: "" }],
};

describe("MCP tools", () => {
  beforeAll(async () => {
    await clearData();
    token = (await connectAssistant()).access;
  });

  it("explains how to connect when nothing has synced", async () => {
    const r = await tool(token, "get_overview");
    expect(r.isError).toBe(true);
    expect(String(r.data.error)).toContain("Nothing has synced");
    expect((await postSnapshot(snapshot())).status).toBe(200);
  });

  it("lists tools with read-only hints on reads", async () => {
    const res = await mcp(token, "tools/list");
    const tools = ((await res.json()) as { result: { tools: { name: string; annotations?: { readOnlyHint?: boolean } }[] } }).result.tools;
    const byName = Object.fromEntries(tools.map((t) => [t.name, t]));
    expect(Object.keys(byName).sort()).toEqual(
      ["cancel_change", "get_exercise_history", "get_overview", "get_plan", "get_progress", "get_recovery", "get_session", "list_activities", "list_changes", "list_exercises", "list_sessions", "log_activity", "remove_goal", "schedule_workout", "set_goal"].sort(),
    );
    for (const n of ["get_overview", "list_sessions", "get_session", "get_exercise_history", "get_recovery", "list_activities", "get_plan", "get_progress", "list_exercises", "list_changes"]) expect(byName[n].annotations?.readOnlyHint).toBe(true);
    for (const n of ["schedule_workout", "set_goal", "remove_goal", "log_activity", "cancel_change"]) expect(byName[n].annotations?.readOnlyHint).toBe(false);
  });

  it("gives an overview", async () => {
    const { data } = await tool<any>(token, "get_overview");
    expect(data.today).toBe(isoDay(0));
    expect(data.readinessToday).toMatchObject({ score: 74, band: "READY", whoopRecovery: 71 });
    expect(data.planToday.title).toBe("Upper volume");
    expect(data.planToday.blocks[0].exercises[0].name).toBe("Bench press");
    expect(data.last7Days.liftingSessions).toBe(1);
    expect(data.last28Days).toMatchObject({ liftingSessions: 2, workSets: 8, otherActivities: [{ type: "RUNNING", sessions: 1, minutes: 42 }] });
    expect(data.lastSession).toMatchObject({ id: 1, title: "Lower strength" });
    expect(data.lastSession.exercises[0]).toMatchObject({ name: "Back squat", workSets: 3, bestSet: "120kg×5 @9" });
    expect(data.goals[0].targetKg).toBe(160);
    expect(data.profile.limitations[0].region).toBe("KNEE");
  });

  it("lists, filters and opens sessions", async () => {
    let { data } = await tool<any>(token, "list_sessions");
    expect(data.sessions.map((s: any) => s.id)).toEqual([1, 2]);
    ({ data } = await tool<any>(token, "list_sessions", { exercise: "bench" }));
    expect(data.count).toBe(0);
    ({ data } = await tool<any>(token, "list_sessions", { exercise: "squat", from: isoDay(-3) }));
    expect(data.sessions.map((s: any) => s.id)).toEqual([1]);
    const detail = (await tool<any>(token, "get_session", { id: 1 })).data;
    expect(detail.notes).toBe("Knee felt fine");
    expect(detail.planRationale).toEqual(["HRV is at baseline, so top sets are on."]);
    expect(detail.blocks.WARMUP[0].sets[0].done).toBe("60kg×5");
    expect(detail.blocks.MAIN[0]).toMatchObject({ name: "Back squat" });
    expect(detail.blocks.MAIN[0].sets[0]).toMatchObject({ target: "5 reps 120kg @8", done: "120kg×5 @8" });
    expect((await tool(token, "get_session", { id: 999 })).isError).toBe(true);
  });

  it("tracks a lift over time", async () => {
    const { data } = await tool<any>(token, "get_exercise_history", { exercise: "Back Squat" });
    expect(data).toMatchObject({ exerciseId: "back_squat", sessionsLogged: 2, allTimeBestE1rm: { kg: 147.6 }, latestE1rm: { kg: 147.6 }, changeOverPeriodKg: 6.1 });
    expect(data.sessions[0].sets).toHaveLength(3);
    // "bench" matches two exercises and neither was logged: ask the model to choose.
    const amb = await tool<any>(token, "get_exercise_history", { exercise: "bench" });
    expect(amb.isError).toBe(true);
    expect(amb.data.candidates.map((c: any) => c.id).sort()).toEqual(["bench_press", "dumbbell_bench_press"]);
  });

  it("reports recovery, activities, plan, progress and exercises", async () => {
    const rec = (await tool<any>(token, "get_recovery", { days: 7 })).data;
    expect(rec.days.map((d: any) => d.date)).toEqual([isoDay(0), isoDay(-1)]);
    expect(rec.averages).toMatchObject({ readiness: 66, hrvMs: 58.8 });
    expect(rec.days[0].readiness.factors).toEqual(["Recovery: WHOOP 71% (+4)"]);
    const acts = (await tool<any>(token, "list_activities")).data;
    expect(acts.activities[0]).toMatchObject({ type: "RUNNING", distanceKm: 8.1 });
    const p = (await tool<any>(token, "get_plan")).data;
    expect(p.plan.source).toBe("AI");
    const prog = (await tool<any>(token, "get_progress", { weeks: 4 })).data;
    expect(prog.strength).toEqual({ overall: 61, level: "Intermediate" });
    expect(prog.weekly.reduce((t: number, w: any) => t + w.sessions, 0)).toBe(2);
    const ex = (await tool<any>(token, "list_exercises", { query: "hinge" })).data;
    expect(ex.exercises.map((e: any) => e.id)).toEqual(["romanian_deadlift"]);
  });

  it("queues changes, hands them to the phone, and records the outcome", async () => {
    let r = await tool<any>(token, "schedule_workout", { date: isoDay(1), plan: { ...plan, main: [{ ...plan.main[0], exercise_id: "made_up" }] } });
    expect(r.isError).toBe(true);
    expect(r.data.unknown).toEqual(["made_up"]);
    expect((await tool(token, "schedule_workout", { date: isoDay(20), plan })).isError).toBe(true);
    const scheduled = await tool<any>(token, "schedule_workout", { date: isoDay(1), plan });
    expect(scheduled.data.queued).toBe(true);
    const goal = await tool<any>(token, "set_goal", { exercise_id: "bench_press", target_kg: 110 });
    expect(goal.data.exercise).toBe("Bench press");
    expect((await tool(token, "remove_goal", { goal_id: 77 })).isError).toBe(true);
    const removal = await tool<any>(token, "remove_goal", { goal_id: 3 });
    const activity = await tool<any>(token, "log_activity", { type: "TENNIS", date: isoDay(0), duration_min: 60 });
    expect((await tool(token, "cancel_change", { change_id: activity.data.changeId })).data).toEqual({ cancelled: true });

    expect((await tool<any>(token, "get_plan", { date: isoDay(1) })).data.queuedWorkouts).toHaveLength(1);

    // The phone's next sync receives the pending changes in order...
    const res = await postSnapshot(snapshot());
    const inbox = ((await res.json()) as { inbox: { id: string; kind: string; payload: any }[] }).inbox;
    expect(inbox.map((i) => i.kind)).toEqual(["schedule_workout", "add_goal", "remove_goal"]);
    expect(inbox[0].payload).toEqual({ date: isoDay(1), plan });
    // ...and reports what it did.
    const ack = await call("/sync/inbox/ack", {
      method: "POST",
      headers: syncHeaders,
      body: JSON.stringify({ results: [{ id: scheduled.data.changeId, status: "applied", result: "Scheduled; 2 warnings" }, { id: goal.data.changeId, status: "applied" }, { id: removal.data.changeId, status: "rejected", result: "Goal no longer exists" }] }),
    });
    expect(await ack.json()).toEqual({ ok: true, settled: 3 });
    const changes = (await tool<any>(token, "list_changes")).data.changes;
    expect(changes.map((c: any) => c.status)).toEqual(["cancelled", "rejected", "applied", "applied"]);
    expect((await (await call("/sync/inbox", { headers: syncHeaders })).json()) as unknown).toEqual({ inbox: [] });
  });
});

describe("assistant auth", () => {
  it("requires a token for /mcp", async () => {
    const res = await call("/mcp", { method: "POST", headers: { "content-type": "application/json" }, body: "{}" });
    expect(res.status).toBe(401);
    expect(res.headers.get("www-authenticate")).toContain("resource_metadata");
  });

  it("publishes discovery metadata", async () => {
    const as = (await (await call("/.well-known/oauth-authorization-server")).json()) as Record<string, unknown>;
    expect(as).toMatchObject({ authorization_endpoint: "https://fluff.example.test/authorize", token_endpoint: "https://fluff.example.test/token", registration_endpoint: "https://fluff.example.test/register" });
    const pr = (await (await call("/.well-known/oauth-protected-resource")).json()) as Record<string, unknown>;
    expect(pr.resource).toBe("https://fluff.example.test");
  });

  it("disconnects every assistant when the owner key is rotated", async () => {
    const { access } = await connectAssistant();
    expect((await mcp(access, "tools/list")).status).toBe(200);
    expect((await mcp(access, "tools/list", {}, { OWNER_KEY: "a-brand-new-owner-key-abcdefghijklmnop" })).status).toBe(401);
  });

  it("refreshes tokens", async () => {
    const { refresh, clientId } = await connectAssistant();
    const res = await call("/token", { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: refresh, client_id: clientId }).toString() });
    expect(res.status, await res.clone().text()).toBe(200);
    const { access_token } = (await res.json()) as { access_token: string };
    expect((await mcp(access_token, "tools/list")).status).toBe(200);
  });

  it("keeps D1 untouched by unauthenticated writes", async () => {
    const before = (await env.DB.prepare(`SELECT COUNT(*) AS n FROM inbox`).first<{ n: number }>())!.n;
    await call("/mcp", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "set_goal", arguments: { exercise_id: "bench_press", target_kg: 100 } } }) });
    expect((await env.DB.prepare(`SELECT COUNT(*) AS n FROM inbox`).first<{ n: number }>())!.n).toBe(before);
  });
});
