/**
 * Build-time stand-in for the optional `hono-rate-limiter` peer of @hono/mcp.
 * @hono/mcp's auth helpers (which this Worker does not use) import it lazily; esbuild still
 * needs the specifier to resolve. wrangler.jsonc aliases the package here.
 */
export function rateLimiter(): never {
  throw new Error("hono-rate-limiter is not bundled; the Fluff server uses @cloudflare/workers-oauth-provider instead");
}
