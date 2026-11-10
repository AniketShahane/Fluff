import { createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
import { env } from "cloudflare:workers";
import { expect } from "vitest";
import worker from "../src/index";

export const ORIGIN = "https://fluff.example.test";
export const OWNER_KEY = "test-owner-key-0123456789abcdef0123456789";
export const SYNC_TOKEN = "test-sync-token-0123456789abcdef";

/** Calls the whole Worker exactly as production would. */
export async function call(path: string, init: RequestInit = {}, overrides: Partial<typeof env> = {}): Promise<Response> {
  const ctx = createExecutionContext();
  const res = await worker.fetch(new Request(`${ORIGIN}${path}`, init), { ...env, ...overrides }, ctx);
  await waitOnExecutionContext(ctx);
  return res;
}

export const syncHeaders = { authorization: `Bearer ${SYNC_TOKEN}`, "content-type": "application/json" };

export async function postSnapshot(body: unknown): Promise<Response> {
  return call("/sync/snapshot", { method: "POST", headers: syncHeaders, body: JSON.stringify(body) });
}

export async function clearData(): Promise<void> {
  for (const t of ["sets", "sessions", "activities", "days", "exercises", "docs", "inbox", "throttle"]) await env.DB.prepare(`DELETE FROM ${t}`).run();
}

function b64url(bytes: ArrayBuffer): string {
  return btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function pkce() {
  const verifier = b64url(crypto.getRandomValues(new Uint8Array(32)).buffer as ArrayBuffer);
  const challenge = b64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
  return { verifier, challenge };
}

export async function registerClient(name = "Claude") {
  const redirectUri = "https://claude.ai/api/mcp/auth_callback";
  const res = await call("/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ client_name: name, redirect_uris: [redirectUri], token_endpoint_auth_method: "none", grant_types: ["authorization_code", "refresh_token"], response_types: ["code"] }),
  });
  expect(res.status, await res.clone().text()).toBe(201);
  return { clientId: ((await res.json()) as { client_id: string }).client_id, redirectUri };
}

export function authorizePath(client: { clientId: string; redirectUri: string }, challenge: string) {
  const q = new URLSearchParams({ response_type: "code", client_id: client.clientId, redirect_uri: client.redirectUri, state: "s1", code_challenge: challenge, code_challenge_method: "S256" });
  return `/authorize?${q}`;
}

export function submitKey(path: string, key: string, extra: Record<string, string> = {}, origin = ORIGIN) {
  return call(path, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded", origin }, body: new URLSearchParams({ key, decision: "allow", ...extra }).toString(), redirect: "manual" });
}

/** Runs the full browser-less OAuth flow and returns tokens. */
export async function connectAssistant(): Promise<{ access: string; refresh: string; clientId: string }> {
  const client = await registerClient();
  const { verifier, challenge } = await pkce();
  const path = authorizePath(client, challenge);
  const res = await submitKey(path, OWNER_KEY);
  expect(res.status).toBe(302);
  const code = new URL(res.headers.get("location")!).searchParams.get("code")!;
  const tok = await call("/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "authorization_code", code, redirect_uri: client.redirectUri, client_id: client.clientId, code_verifier: verifier }).toString(),
  });
  expect(tok.status, await tok.clone().text()).toBe(200);
  const json = (await tok.json()) as { access_token: string; refresh_token: string };
  return { access: json.access_token, refresh: json.refresh_token, clientId: client.clientId };
}

let rpcId = 0;
/** One JSON-RPC call to /mcp. */
export async function mcp(token: string, method: string, params: Record<string, unknown> = {}, overrides: Partial<typeof env> = {}) {
  const res = await call(
    "/mcp",
    {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json", accept: "application/json, text/event-stream", "mcp-protocol-version": "2025-06-18" },
      body: JSON.stringify({ jsonrpc: "2.0", id: ++rpcId, method, params }),
    },
    overrides,
  );
  return res;
}

export async function tool<T = Record<string, unknown>>(token: string, name: string, args: Record<string, unknown> = {}): Promise<{ data: T; isError: boolean }> {
  const res = await mcp(token, "tools/call", { name, arguments: args });
  expect(res.status, await res.clone().text()).toBe(200);
  const body = (await res.json()) as { result: { structuredContent: T; isError?: boolean }; error?: unknown };
  expect(body.error).toBeUndefined();
  return { data: body.result.structuredContent, isError: !!body.result.isError };
}
