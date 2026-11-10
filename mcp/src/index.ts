/**
 * Fluff's remote MCP server. One owner, one phone:
 *   /mcp     OAuth-protected MCP endpoint for Claude, ChatGPT and other assistants
 *   /sync/*  the phone's upload/inbox API, bearer SYNC_TOKEN
 *   /        connect instructions; /authorize owner sign-in; /connections to disconnect assistants
 */
import { OAuthProvider } from "@cloudflare/workers-oauth-provider";
import type { Env } from "./env";
import { mcpApp } from "./mcp";
import { pagesApp } from "./pages";

const MAX_BODY = { sync: 4 * 1024 * 1024, other: 256 * 1024 };

function provider(origin: string) {
  return new OAuthProvider<Env>({
    apiHandlers: { "/mcp": { fetch: (request, env, ctx) => mcpApp.fetch(request, env, ctx) } },
    defaultHandler: { fetch: (request, env, ctx) => pagesApp.fetch(request, env, ctx) },
    authorizeEndpoint: "/authorize",
    tokenEndpoint: "/token",
    clientRegistrationEndpoint: "/register",
    accessTokenTTL: 60 * 60,
    refreshTokenTTL: 180 * 24 * 60 * 60,
    // Lets clients that publish a client identity document skip registration.
    clientIdMetadataDocumentEnabled: true,
    resourceMetadata: { resource: origin, resource_name: "Fluff training data" },
  });
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    // Bound the body before any library buffers it.
    const max = url.pathname.startsWith("/sync/") ? MAX_BODY.sync : MAX_BODY.other;
    const declared = Number(request.headers.get("content-length") ?? 0);
    if (declared > max) return new Response("Request too large", { status: 413 });
    if (request.body) {
      const reader = request.body.getReader();
      const chunks: Uint8Array[] = [];
      let size = 0;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > max) {
          await reader.cancel();
          return new Response("Request too large", { status: 413 });
        }
        chunks.push(value);
      }
      const bytes = new Uint8Array(size);
      let offset = 0;
      for (const chunk of chunks) {
        bytes.set(chunk, offset);
        offset += chunk.byteLength;
      }
      request = new Request(request, { body: bytes });
    }
    return provider(url.origin).fetch(request, env, ctx);
  },
};
