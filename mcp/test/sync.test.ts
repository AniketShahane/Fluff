import { env } from "cloudflare:workers";
import { beforeEach, describe, expect, it } from "vitest";
import { call, clearData, postSnapshot, syncHeaders } from "./helpers";
import { isoDay, session, snapshot } from "./fixtures";

const count = async (table: string) => (await env.DB.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first<{ n: number }>())!.n;

describe("phone sync", () => {
  beforeEach(clearData);

  it("rejects a missing or wrong token", async () => {
    expect((await call("/sync/status")).status).toBe(401);
    expect((await call("/sync/status", { headers: { authorization: "Bearer nope" } })).status).toBe(401);
    expect((await call("/sync/status", { headers: syncHeaders })).status).toBe(200);
  });

  it("stores a snapshot and reports the sync time", async () => {
    const res = await postSnapshot(snapshot());
    expect(res.status, await res.clone().text()).toBe(200);
    expect(await res.json()).toMatchObject({ ok: true, inbox: [] });
    expect(await count("sessions")).toBe(2);
    expect(await count("sets")).toBe(10);
    expect(await count("activities")).toBe(1);
    expect(await count("days")).toBe(2);
    expect(await count("exercises")).toBe(6);
    const day = await env.DB.prepare(`SELECT readiness_score, readiness_band FROM days WHERE date = ?1`).bind(isoDay(0)).first();
    expect(day).toEqual({ readiness_score: 74, readiness_band: "READY" });
    const status = (await (await call("/sync/status", { headers: syncHeaders })).json()) as { lastSyncAt: string };
    expect(Date.parse(status.lastSyncAt)).toBeGreaterThan(Date.now() - 60_000);
  });

  it("replaces only the sent window, so phone-side deletes and edits propagate", async () => {
    await postSnapshot(snapshot({ sessions: [session(1, -1, 120), session(2, -8, 115), session(3, -40, 100)], range: { from: isoDay(-60), to: isoDay(0) } }));
    expect(await count("sessions")).toBe(3);
    // Next sync covers the last 7 days: session 1 was edited, session 2 (outside) and 3 stay.
    const edited = { ...session(1, -1, 125), sets: session(1, -1, 125).sets.slice(0, 2) };
    await postSnapshot(snapshot({ range: { from: isoDay(-6), to: isoDay(0) }, sessions: [edited], activities: [], days: [] }));
    expect(await count("sessions")).toBe(3);
    expect(await count("sets")).toBe(5 + 5 + 2);
    expect(await count("activities")).toBe(0);
    // Deleting a session on the phone removes it at the next sync of that window.
    await postSnapshot(snapshot({ range: { from: isoDay(-6), to: isoDay(0) }, sessions: [], activities: [], days: [] }));
    expect(await count("sessions")).toBe(2);
    expect(await count("sets")).toBe(10);
  });

  it("wipes everything on a reset chunk", async () => {
    await postSnapshot(snapshot({ sessions: [session(1, -1, 120), session(3, -40, 100)], range: { from: isoDay(-60), to: isoDay(0) } }));
    await postSnapshot(snapshot({ reset: true, range: { from: isoDay(-6), to: isoDay(0) }, sessions: [session(9, -2, 130)] }));
    const ids = (await env.DB.prepare(`SELECT id FROM sessions`).all<{ id: number }>()).results.map((r) => r.id);
    expect(ids).toEqual([9]);
  });

  it("rejects invalid snapshots without writing", async () => {
    let res = await postSnapshot(snapshot({ sessions: [session(1, -45, 120)] }));
    expect(res.status).toBe(400);
    expect(((await res.json()) as { error: string }).error).toBe("rows outside range");
    res = await postSnapshot({ ...snapshot(), version: 2 });
    expect(res.status).toBe(400);
    res = await call("/sync/snapshot", { method: "POST", headers: syncHeaders, body: "not json" });
    expect(res.status).toBe(400);
    expect(await count("sessions")).toBe(0);
  });

  it("refuses bodies over the limit", async () => {
    const res = await call("/sync/snapshot", { method: "POST", headers: syncHeaders, body: "x".repeat(4 * 1024 * 1024 + 1) });
    expect(res.status).toBe(413);
  });
});
