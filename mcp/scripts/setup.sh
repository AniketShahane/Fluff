#!/usr/bin/env bash
# One-time setup of the Fluff MCP server on your own Cloudflare account. Safe to re-run.
#
#   cd mcp && npm ci && npx wrangler login && npm run setup
#
# It creates the D1 database and OAuth KV namespace (once), writes their ids into
# wrangler.jsonc, generates the owner key and phone sync token (once), applies migrations,
# deploys, and prints what to paste into the Fluff app and into Claude/ChatGPT.
# Pass --rotate to generate new secrets (disconnects the phone and every assistant).
set -euo pipefail
cd "$(dirname "$0")/.."
export WRANGLER_SEND_METRICS=false
W="npx --no-install wrangler"
SECRETS_FILE=".fluff-secrets"

$W whoami >/dev/null 2>&1 || { echo "Not logged in. Run: npx wrangler login"; exit 1; }

replace() { # replace PLACEHOLDER VALUE in wrangler.jsonc
  node -e 'const fs=require("fs");const f="wrangler.jsonc";fs.writeFileSync(f,fs.readFileSync(f,"utf8").replace(process.argv[1],process.argv[2]))' "$1" "$2"
}

if grep -q REPLACE_WITH_D1_ID wrangler.jsonc; then
  id=$($W d1 list --json | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const db=JSON.parse(s).find(d=>d.name==="fluff");process.stdout.write(db?db.uuid:"")})')
  if [ -z "$id" ]; then
    id=$($W d1 create fluff | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -1)
  fi
  [ -n "$id" ] || { echo "Could not create the D1 database"; exit 1; }
  replace REPLACE_WITH_D1_ID "$id"
  echo "D1 database: $id"
fi

if grep -q REPLACE_WITH_OAUTH_KV_ID wrangler.jsonc; then
  id=$($W kv namespace list | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const i=s.search(/\[\s*(\{|\])/);const ns=JSON.parse(s.slice(i)).find(n=>n.title==="fluff-oauth"||n.title==="fluff-mcp-fluff-oauth");process.stdout.write(ns?ns.id:"")})')
  if [ -z "$id" ]; then
    id=$($W kv namespace create fluff-oauth | grep -oE '[0-9a-f]{32}' | head -1)
  fi
  [ -n "$id" ] || { echo "Could not create the KV namespace"; exit 1; }
  replace REPLACE_WITH_OAUTH_KV_ID "$id"
  echo "OAuth KV namespace: $id"
fi

$W d1 migrations apply fluff --remote

new_secret() { node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("base64url"))'; }
if [ ! -f "$SECRETS_FILE" ] || [ "${1:-}" = "--rotate" ]; then
  OWNER_KEY=$(new_secret); SYNC_TOKEN=$(new_secret)
  # Deploy first so the secrets attach to an existing Worker.
  $W deploy >/dev/null
  printf '%s' "$OWNER_KEY" | $W secret put OWNER_KEY >/dev/null
  printf '%s' "$SYNC_TOKEN" | $W secret put SYNC_TOKEN >/dev/null
  umask 077
  printf 'OWNER_KEY=%s\nSYNC_TOKEN=%s\n' "$OWNER_KEY" "$SYNC_TOKEN" > "$SECRETS_FILE"
else
  # shellcheck disable=SC1090
  . "./$SECRETS_FILE"
fi

URL=$($W deploy 2>&1 | grep -oE 'https://[a-zA-Z0-9.-]+\.workers\.dev' | head -1)

cat <<DONE

Fluff MCP server is live.

  Server URL (Fluff app and assistants):  ${URL}
  MCP address for Claude / ChatGPT:       ${URL}/mcp
  Phone sync token (Fluff app):           ${SYNC_TOKEN}
  Owner key (typed once per assistant):   ${OWNER_KEY}

Both secrets are also in mcp/${SECRETS_FILE} (git-ignored). Keep them in a password manager.
In Fluff: You › Claude & ChatGPT › paste the server URL and sync token.
Then open ${URL} for the Claude and ChatGPT steps.
Commit wrangler.jsonc so future deploys (npm run deploy) use the same database.
DONE
