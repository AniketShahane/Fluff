/** Pages that need no OAuth token: landing/connect instructions, owner sign-in, connections. */
import { AuthorizationError } from "@cloudflare/workers-oauth-provider";
import { Hono, type Context } from "hono";
import type { AuthProps, Env } from "./env";
import { attempt } from "./store";
import { syncApp } from "./sync";
import { constantTimeEqual, escapeHtml, keyId, sha256Hex } from "./util";

export const OWNER = "owner";
const MAX_ATTEMPTS = 10;
const WINDOW_MS = 15 * 60 * 1000;

const HEADERS = {
  "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
  // "same-origin" keeps the Origin header on form posts (checked below).
  "referrer-policy": "same-origin",
  "x-content-type-options": "nosniff",
  "cache-control": "no-store",
};

function page(title: string, body: string): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(title)}</title>
<style>
:root{color-scheme:light dark;--bg:#f5f3fb;--card:#fff;--ink:#1b1830;--muted:#5d5873;--line:#e4e0f0;--accent:#6b4eff}
@media (prefers-color-scheme:dark){:root{--bg:#0f0d18;--card:#1a1726;--ink:#f1eefb;--muted:#a8a2c0;--line:#2c2840;--accent:#9d8bff}}
body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,sans-serif}
main{width:min(92vw,560px);background:var(--card);border-radius:22px;padding:28px;margin:24px auto;box-shadow:0 12px 40px rgba(40,20,90,.12)}
h1{font-size:1.3rem;margin:0 0 8px}h2{font-size:1rem;margin:0 0 4px}p,li{color:var(--muted)}ol{padding-left:20px}
code{background:var(--bg);padding:2px 6px;border-radius:6px;word-break:break-all}
label{display:block;font-weight:600;margin-top:16px}
input[type=password]{width:100%;box-sizing:border-box;margin-top:6px;padding:12px 14px;font-size:1rem;border:1px solid var(--line);border-radius:12px;background:var(--bg);color:var(--ink)}
button{font:inherit;font-weight:600;margin-top:16px;padding:12px 16px;border:0;border-radius:12px;background:var(--accent);color:#fff;cursor:pointer}
button.secondary{background:transparent;color:var(--muted);border:1px solid var(--line)}
.card{border:1px solid var(--line);border-radius:16px;padding:16px;margin:14px 0}.row{display:flex;gap:8px;align-items:center}
.row code{flex:1;padding:10px 12px}.err{background:#fde8e6;color:#8a1c12;border-radius:10px;padding:10px 12px}
</style></head><body><main>${body}</main></body></html>`;
}

type C = Context<{ Bindings: Env }>;

/** Checks the form's origin, throttles by IP, and verifies the owner key. */
async function checkOwner(c: C, form: FormData): Promise<Response | null> {
  if (c.req.header("origin") !== new URL(c.req.url).origin) return c.text("Invalid form origin", 403, HEADERS);
  const ip = c.req.header("cf-connecting-ip") ?? "local";
  if (!(await attempt(c.env.DB, "signin:" + (await sha256Hex(ip)), MAX_ATTEMPTS, WINDOW_MS, Date.now()))) {
    return c.text("Too many attempts. Try again in 15 minutes.", 429, { ...HEADERS, "retry-after": "900" });
  }
  const key = String(form.get("key") ?? "");
  if (!c.env.OWNER_KEY || !(await constantTimeEqual(key, c.env.OWNER_KEY))) return null;
  return new Response(null, { status: 204 });
}

async function readForm(c: C): Promise<FormData | Response> {
  const raw = new TextDecoder().decode(await c.req.arrayBuffer());
  if (raw.length > 8192) return c.text("Form too large", 413, HEADERS);
  return new Request(c.req.url, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: raw }).formData();
}

function signInForm(action: string, intro: string, error?: string, extra = ""): string {
  return page(
    "Fluff sign-in",
    `<h1>Fluff</h1><p>${intro}</p>${error ? `<p class="err">${escapeHtml(error)}</p>` : ""}
<form method="post" action="${escapeHtml(action)}">${extra}
<label>Owner key<input name="key" type="password" autocomplete="current-password" required maxlength="200" autofocus></label>
<div class="row"><button name="decision" value="allow">Allow</button><button class="secondary" name="decision" value="deny">Cancel</button></div></form>
<p><small>The owner key was printed when the server was set up. Never enter it on any other site.</small></p>`,
  );
}

export const pagesApp = new Hono<{ Bindings: Env }>();

pagesApp.get("/", (c) => {
  const mcpUrl = `${new URL(c.req.url).origin}/mcp`;
  const safe = escapeHtml(mcpUrl);
  return c.html(
    page(
      "Fluff MCP",
      `<h1>Talk to Fluff from Claude or ChatGPT</h1>
<p>This server lets an AI assistant read your Fluff training data and, with your confirmation, add goals or schedule workouts. Use this address:</p>
<div class="row"><code id="u">${safe}</code><button id="c" type="button">Copy</button></div>
<section class="card"><h2>Claude</h2><ol>
<li>Open <strong>Settings › Connectors</strong> on claude.ai or in Claude Desktop.</li>
<li>Choose <strong>Add custom connector</strong>, paste the address and add it.</li>
<li>Click <strong>Connect</strong> and enter your owner key on the Fluff page that opens.</li>
<li>Once added on the web or desktop, it also shows up in the Claude phone app.</li></ol></section>
<section class="card"><h2>ChatGPT</h2><ol>
<li>On chatgpt.com in a browser, open <strong>Settings › Apps › Advanced settings</strong> and turn on <strong>Developer mode</strong>.</li>
<li>Click <strong>Create app</strong>, paste the address, choose <strong>OAuth</strong> and create it.</li>
<li>Sign in with your owner key. Turn the app on in a chat from the <strong>+</strong> menu.</li></ol></section>
<p><small>Claude Code: <code>claude mcp add --transport http fluff ${safe}</code>. <a href="/connections">Manage connections</a>.</small></p>
<script>document.getElementById("c").onclick=async function(){try{await navigator.clipboard.writeText(document.getElementById("u").textContent);this.textContent="Copied"}catch(e){}};</script>`,
    ),
    200,
    HEADERS,
  );
});

pagesApp.get("/health", (c) => c.json({ ok: true }));

pagesApp.get("/authorize", async (c) => {
  try {
    const req = await c.env.OAUTH_PROVIDER.parseAuthRequest(c.req.raw);
    if (!req.codeChallenge || req.codeChallengeMethod !== "S256") return c.text("S256 PKCE required", 400, HEADERS);
    const client = await c.env.OAUTH_PROVIDER.lookupClient(req.clientId);
    const who = escapeHtml(client?.clientName ?? req.clientId);
    return c.html(signInForm(c.req.url, `<strong>${who}</strong> wants to read your training data and request changes (goals, scheduled workouts) that your phone applies on its next sync. It will return to <code>${escapeHtml(req.redirectUri)}</code>.`), 200, HEADERS);
  } catch {
    return c.text("Invalid authorization request", 400, HEADERS);
  }
});

pagesApp.post("/authorize", async (c) => {
  let req;
  try {
    req = await c.env.OAUTH_PROVIDER.parseAuthRequest(c.req.raw);
  } catch {
    return c.text("Invalid authorization request", 400, HEADERS);
  }
  if (!req.codeChallenge || req.codeChallengeMethod !== "S256") return c.text("S256 PKCE required", 400, HEADERS);
  const form = await readForm(c);
  if (form instanceof Response) return form;
  if (form.get("decision") === "deny") return c.text("Cancelled. You can close this page.", 200, HEADERS);
  const check = await checkOwner(c, form);
  if (check === null) return c.html(signInForm(c.req.url, "Enter your owner key to continue.", "That key is not right."), 401, HEADERS);
  if (check.status !== 204) return check;
  try {
    const client = await c.env.OAUTH_PROVIDER.lookupClient(req.clientId);
    const props: AuthProps = { keyId: await keyId(c.env.OWNER_KEY) };
    const { redirectTo } = await c.env.OAUTH_PROVIDER.completeAuthorization({
      request: req,
      userId: OWNER,
      metadata: { client: client?.clientName ?? req.clientId, at: new Date().toISOString() },
      scope: req.scope,
      props,
    });
    return c.redirect(redirectTo, 302);
  } catch (err) {
    if (err instanceof AuthorizationError) return c.text("Invalid authorization request", 400, HEADERS);
    throw err;
  }
});

/** Lists connected assistants and disconnects one. Rotating OWNER_KEY disconnects all of them. */
pagesApp.get("/connections", (c) => c.html(signInForm("/connections", "Enter your owner key to see connected assistants."), 200, HEADERS));

pagesApp.post("/connections", async (c) => {
  const form = await readForm(c);
  if (form instanceof Response) return form;
  if (form.get("decision") === "deny") return c.redirect("/", 302);
  const check = await checkOwner(c, form);
  if (check === null) return c.html(signInForm("/connections", "Enter your owner key.", "That key is not right."), 401, HEADERS);
  if (check.status !== 204) return check;
  const revoke = form.get("revoke");
  if (typeof revoke === "string" && revoke) await c.env.OAUTH_PROVIDER.revokeGrant(revoke, OWNER);
  const grants = (await c.env.OAUTH_PROVIDER.listUserGrants(OWNER)).items;
  const rows = grants.length
    ? grants
        .map((g) => {
          const m = (g.metadata ?? {}) as { client?: string; at?: string };
          return `<label><input type="radio" name="revoke" value="${escapeHtml(g.id)}"> ${escapeHtml(m.client ?? g.clientId)} <small>since ${escapeHtml(m.at ?? "")}</small></label>`;
        })
        .join("")
    : "<p>No assistants are connected.</p>";
  return c.html(signInForm("/connections", `${revoke ? "Disconnected. " : ""}To disconnect an assistant, pick it and enter your key again.`, undefined, rows), 200, HEADERS);
});

pagesApp.route("/sync", syncApp);

pagesApp.notFound((c) => c.json({ error: "not found" }, 404));
pagesApp.onError((_err, c) => {
  console.error("Unhandled request error");
  return c.json({ error: "internal error" }, 500);
});
