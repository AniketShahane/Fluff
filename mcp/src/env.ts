import type { OAuthHelpers } from "@cloudflare/workers-oauth-provider";

/** Bindings and secrets (see wrangler.jsonc and scripts/setup.sh). */
export interface FluffBindings {
  /** The mirror of the phone's data, the assistant inbox and the sign-in throttle. */
  DB: D1Database;
  /** OAuth clients, grants and encrypted tokens (managed by workers-oauth-provider). */
  OAUTH_KV: KVNamespace;
  /** Secret. Typed once on the sign-in page when an assistant connects. Rotating it disconnects every assistant. */
  OWNER_KEY: string;
  /** Secret. The phone's bearer token for /sync. Rotating it disconnects the phone. */
  SYNC_TOKEN: string;
  /** Injected on every request by workers-oauth-provider. */
  OAUTH_PROVIDER: OAuthHelpers;
}

export type Env = FluffBindings;

/** Encrypted into each access token by the OAuth provider. */
export interface AuthProps {
  /** First 16 hex chars of SHA-256(OWNER_KEY) at sign-in; a rotated key invalidates the token. */
  keyId: string;
}

declare global {
  namespace Cloudflare {
    interface Env extends FluffBindings {}
  }
}
