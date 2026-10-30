# Feather Wand telemetry worker

Anonymous, opt-out usage telemetry receiver for the Feather Wand JMeter plugin.
Cloudflare Worker + D1. Endpoint: `POST https://telemetry.jmeter.ai/v1/ping`.

## What is stored

Each install can send one ping per UTC day. Stored fields: a random install UUID,
plugin/JMeter/Java version, OS name, CPU arch, AI provider name, agent-mode
enabled flag, first-run flag, coarse feature usage counts, and the country code
Cloudflare reports for the request. Day and timestamp are assigned server side.
IP addresses are used only for rate limiting and are never stored.

## Deploy

```bash
cd telemetry-worker
npm ci

# 1. First-time setup only (the production id is already committed in
#    wrangler.jsonc): create the database and paste the printed id into
#    d1_databases[0].database_id.
npx wrangler d1 create feather-wand-telemetry

# 2. Apply migrations.
npx wrangler d1 migrations apply feather-wand-telemetry --remote

# 3. Set the stats bearer token (choose a long random value).
npx wrangler secret put STATS_TOKEN

# 4. Deploy.
npx wrangler deploy
```

The route `telemetry.jmeter.ai` is configured as a custom domain in
`wrangler.jsonc`; add the DNS/zone in Cloudflare before deploying.

## Updating an existing deployment

When a PR adds a file under `migrations/`, apply it before deploying the new code:

```bash
cd telemetry-worker
npm ci
npx wrangler d1 migrations apply feather-wand-telemetry --remote
npx wrangler deploy
```

Older plugin versions keep working: new ping fields are optional and unknown fields are ignored.

## PostHog

Each newly accepted daily ping is forwarded to PostHog US as a `daily_ping` event, with one
`feature_used` event for each positive feature count. The forwarded fields are the install ID,
day, versions, OS, architecture, provider, decisions backend, model, agent-mode and first-run
flags, and feature counts. No IP address or country/location is sent. An empty or missing
`POSTHOG_KEY` disables forwarding. No D1 migration is needed; deploy the Worker with
`npx wrangler deploy`.

Historical pings can be replayed in pages using the stats bearer token. `jq` is required:

```bash
NEXT=""
while :; do
  RESPONSE=$(curl -s -X POST -H "Authorization: Bearer $STATS_TOKEN" \
    "https://telemetry.jmeter.ai/v1/admin/posthog-backfill?limit=50&after=$NEXT")
  if ! jq -e 'has("pings") and has("next")' >/dev/null <<<"$RESPONSE"; then
    printf '%s\n' "$RESPONSE" >&2
    exit 1
  fi
  NEXT=$(jq -r '.next' <<<"$RESPONSE")
  [ "$NEXT" = "null" ] && break
done
```

Event UUIDs are deterministic, so rerunning a backfill is safe and PostHog deduplicates the events.

## Viewing stats

- JSON: `curl -H "Authorization: Bearer $STATS_TOKEN" https://telemetry.jmeter.ai/v1/stats`
- HTML: open `https://telemetry.jmeter.ai/stats?token=$STATS_TOKEN` in a browser.

Every number leaves out installs that ever reported a tool from `TEST_ONLY_FEATURES` in `src/index.ts`
(`tool_boom`, `tool_nullish`, `tool_echo`). Those names exist only in the plugin's unit tests, and before
the plugin's Surefire config set `DO_NOT_TRACK=1`, each `mvn verify` run (CI included) pinged as a fresh install.
The plugin now also skips telemetry in any JUnit Platform test JVM (Maven, Gradle or an IDE). The filter is a lower
bound: an old test run that pinged before reaching a fake tool is still counted.
The page shows how many were hidden, plus week-over-week growth and messages per active install.

## Testing

```bash
npm ci
npm test              # vitest in workerd, migrations applied to in-memory D1
npm run typecheck     # tsc --noEmit
npm run deploy:dry    # wrangler deploy --dry-run
```
