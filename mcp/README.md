# Fluff MCP server

Lets Claude, ChatGPT or any MCP client talk about your Fluff training: sessions and sets, lift
history, recovery and readiness, other activities, plans, strength scores and goals. It can also
queue changes (schedule a workout, add or remove a goal, log an activity) that the phone applies
on its next sync, after running them through the same safety checks as the in-app coach.

```
 Fluff app ──POST /sync/snapshot──▶  Cloudflare Worker + D1  ◀──/mcp (OAuth)── Claude / ChatGPT
           ◀──GET /sync/inbox──────   (copy of your data,
                                       queued changes)
```

The phone stays the source of truth. Every sync uploads the last 30 days (the first one uploads
everything) and replaces that window on the server, so edits and deletions propagate. All the
training maths (volume, e1RM, strength scores, goal projections, readiness) runs on the phone; the
server only stores and queries it.

## Set up (once, about 5 minutes)

Needs Node 22+ and a free Cloudflare account.

```sh
cd mcp
npm ci
npx wrangler login     # opens the browser
npm run setup          # creates the database, generates keys, deploys, prints what to paste
```

`setup` prints three things:

1. **Server URL.** In Fluff open **You › Claude & ChatGPT › Connect server** and paste it together
   with the **sync token**. The phone uploads your history straight away.
2. **MCP address** (`…/mcp`). Add it as a custom connector:
   - **Claude:** Settings › Connectors › Add custom connector. Click Connect and enter the owner key.
   - **ChatGPT:** on chatgpt.com, Settings › Apps › Advanced settings › Developer mode, then
     Create app with the address and OAuth, and sign in with the owner key.
   - **Claude Code:** `claude mcp add --transport http fluff https://…/mcp`
3. **Owner key.** You type it once per assistant on the sign-in page. Both secrets are also saved in
   `mcp/.fluff-secrets` (git-ignored); keep them in a password manager.

Commit the database ids that `setup` wrote into `wrangler.jsonc`. After that, deploying an update
is `npm run deploy`.

## Tools

| Tool | What it returns or does |
|---|---|
| `get_overview` | Profile, data freshness, today's readiness and plan, last 7/28 days, last session, goals, strength |
| `list_sessions` / `get_session` | Workouts with best sets; every set with target vs done |
| `get_exercise_history` | One lift over time: e1RM trend, best and heaviest sets |
| `get_recovery` | Readiness score and factors, WHOOP recovery, HRV, resting HR, sleep, check-ins |
| `list_activities` | Runs, rides, sports from WHOOP / Health Connect / manual |
| `get_plan` | A day's planned workout and why |
| `get_progress` | Strength scores, sport-specific strength, goals, weekly volume and sets per muscle |
| `list_exercises` | Exercise ids for planning |
| `schedule_workout` | Queue a workout for a date; the phone validates it, picks loads and clamps it to readiness |
| `set_goal` / `remove_goal` | Queue goal changes |
| `log_activity` | Queue an activity the wearables missed |
| `list_changes` / `cancel_change` | What happened to queued changes |

## Security

- One owner. Assistants connect with OAuth 2.1 + PKCE (dynamic client registration), and the
  sign-in page needs the owner key, which is a random 256-bit secret. Sign-in attempts are throttled.
- The phone uses a separate random sync token over HTTPS.
- Disconnect one assistant at `/connections`. Rotate everything with `npm run setup -- --rotate`.
  That disconnects every assistant and the phone; reconnect them with the new values.
- Your training data is stored in your own Cloudflare account. The assistant you connect sees what
  its tools return.

## Develop

```sh
npm test            # workerd + real D1/KV, full OAuth and MCP round trips
npm run typecheck
npm run dev         # local server; put OWNER_KEY and SYNC_TOKEN in .dev.vars
```

`test/fixtures/phone-snapshot.json` is real output of the app's `CloudSnapshot` builder (refresh it
from `app/build/cloud-snapshot-sample.json` after running `CloudSyncTest`), so a format change on
either side fails a test.
