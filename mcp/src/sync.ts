/**
 * The phone's API. Authenticated with the SYNC_TOKEN secret as a bearer token (one owner,
 * one phone): simpler than OAuth on the phone, and rotating the secret disconnects it.
 *
 *   GET  /sync/status         → { ok, lastSyncAt }            (the app's "Test connection")
 *   POST /sync/snapshot       → { ok, inbox: pending items }  (upload, then receive changes)
 *   GET  /sync/inbox          → { inbox: pending items }
 *   POST /sync/inbox/ack      ← { results: [{ id, status: "applied" | "rejected", result? }] }
 */
import { Hono } from "hono";
import { z } from "zod";
import type { Env } from "./env";
import { SnapshotSchema } from "./schema";
import { SnapshotTooLarge, applySnapshot, getDoc, listInbox, settleInbox, type SyncMeta } from "./store";
import { bearerToken, constantTimeEqual } from "./util";

export const syncApp = new Hono<{ Bindings: Env }>();

syncApp.use("*", async (c, next) => {
  const token = bearerToken(c.req.header("authorization"));
  if (!c.env.SYNC_TOKEN || !token || !(await constantTimeEqual(token, c.env.SYNC_TOKEN))) {
    return c.json({ error: "unauthorized" }, 401);
  }
  c.header("cache-control", "no-store");
  await next();
});

syncApp.get("/status", async (c) => {
  const meta = await getDoc<SyncMeta>(c.env.DB, "sync");
  return c.json({ ok: true, lastSyncAt: meta?.lastSyncAt ?? null });
});

syncApp.post("/snapshot", async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "body must be JSON" }, 400);
  }
  const parsed = SnapshotSchema.safeParse(body);
  if (!parsed.success) {
    return c.json({ error: "invalid snapshot", issues: parsed.error.issues.slice(0, 10).map((i) => `${i.path.join(".")}: ${i.message}`) }, 400);
  }
  const s = parsed.data;
  const { from, to } = s.range;
  const outside = [
    ...s.sessions.filter((x) => x.date < from || x.date > to).map((x) => `session ${x.id}`),
    ...s.activities.filter((x) => x.date < from || x.date > to).map((x) => `activity ${x.id}`),
    ...s.days.filter((x) => x.date < from || x.date > to).map((x) => `day ${x.date}`),
  ];
  if (outside.length) return c.json({ error: "rows outside range", issues: outside.slice(0, 10) }, 400);
  try {
    await applySnapshot(c.env.DB, s, Date.now());
  } catch (e) {
    if (e instanceof SnapshotTooLarge) return c.json({ error: e.message }, 413);
    throw e;
  }
  return c.json({ ok: true, inbox: await listInbox(c.env.DB, { status: "pending" }) });
});

syncApp.get("/inbox", async (c) => c.json({ inbox: await listInbox(c.env.DB, { status: "pending" }) }));

const AckSchema = z.object({
  results: z
    .array(z.object({ id: z.string().min(1).max(64), status: z.enum(["applied", "rejected"]), result: z.string().max(1000).nullish() }))
    .max(100),
});

syncApp.post("/inbox/ack", async (c) => {
  const parsed = AckSchema.safeParse(await c.req.json().catch(() => null));
  if (!parsed.success) return c.json({ error: "invalid ack" }, 400);
  const now = Date.now();
  let settled = 0;
  for (const r of parsed.data.results) if (await settleInbox(c.env.DB, r.id, r.status, r.result ?? null, now)) settled++;
  return c.json({ ok: true, settled });
});
