import { beforeEach, describe, expect, it } from "vitest";
import { authorizePath, call, clearData, connectAssistant, OWNER_KEY, pkce, registerClient, submitKey } from "./helpers";

describe("owner sign-in", () => {
  beforeEach(clearData);

  it("serves connect instructions with the MCP address", async () => {
    const html = await (await call("/")).text();
    expect(html).toContain("https://fluff.example.test/mcp");
    expect(html).toContain("Developer mode");
  });

  it("shows the client and refuses a wrong key", async () => {
    const client = await registerClient("Claude");
    const { challenge } = await pkce();
    const path = authorizePath(client, challenge);
    const page = await call(path);
    expect(page.status).toBe(200);
    const html = await page.text();
    expect(html).toContain("<strong>Claude</strong>");
    expect(html).toContain("claude.ai/api/mcp/auth_callback");
    expect((await submitKey(path, "wrong")).status).toBe(401);
  });

  it("rejects a cross-site form post", async () => {
    const client = await registerClient();
    const { challenge } = await pkce();
    expect((await submitKey(authorizePath(client, challenge), OWNER_KEY, {}, "https://evil.example")).status).toBe(403);
  });

  it("throttles repeated wrong keys", async () => {
    const client = await registerClient();
    const { challenge } = await pkce();
    const path = authorizePath(client, challenge);
    for (let i = 0; i < 10; i++) expect((await submitKey(path, "wrong")).status).toBe(401);
    expect((await submitKey(path, OWNER_KEY)).status).toBe(429);
  });

  it("requires PKCE", async () => {
    const client = await registerClient();
    const q = new URLSearchParams({ response_type: "code", client_id: client.clientId, redirect_uri: client.redirectUri, state: "s" });
    expect((await call(`/authorize?${q}`)).status).toBe(400);
  });

  it("lists and disconnects assistants", async () => {
    const { access } = await connectAssistant();
    const list = await submitKey("/connections", OWNER_KEY);
    const html = await list.text();
    expect(html).toContain("Claude");
    const grantId = /name="revoke" value="([^"]+)"/.exec(html)![1];
    await submitKey("/connections", OWNER_KEY, { revoke: grantId });
    const res = await call("/mcp", { method: "POST", headers: { authorization: `Bearer ${access}`, "content-type": "application/json", accept: "application/json, text/event-stream" }, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }) });
    expect(res.status).toBe(401);
  });
});
