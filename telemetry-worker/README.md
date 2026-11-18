# Feather Wand telemetry worker

Anonymous, opt-out usage telemetry receiver for the Feather Wand JMeter plugin.
Cloudflare Worker + D1. Endpoint: `POST https://telemetry.jmeter.ai/v1/ping`.

## What is stored

Each install can send one ping per UTC day. Stored fields: a random install UUID,
plugin/JMeter/Java version, OS name, CPU arch, AI provider name, agent-mode
enabled flag, first-run flag, coarse feature usage counts, and the country code
Cloudflare reports for the request. Day and timestamp are assigned server side.
IP addresses are used for burst rate limiting and starter lifetime quotas, and
are never stored in plaintext. Starter quota records retain a SHA-256 IP hash
for the lifetime of the quota, alongside the independent starter UUID.

## Starter chat

`POST /v1/starter/chat/completions` accepts OpenAI Chat Completions text chats.
Use SDK base URL `https://telemetry.jmeter.ai/v1/starter` and the plugin's random
starter UUID as the bearer API key (separate from its telemetry ID). No user provider key is required. Agent tools and
functions, client model fallbacks and paid plugins are stripped; 1–100 messages and a 64 KiB body are allowed. Output is
clamped to 2048 tokens by default. Both JSON and SSE streaming are supported.

Configure either or both provider secrets before deployment:

```bash
npx wrangler secret put STARTER_API_KEY
npx wrangler secret put STARTER_FALLBACK_API_KEY
```

Primary defaults to OpenRouter `nvidia/nemotron-3.5-lightning:free` (zero token cost).
Fallback defaults to Gemini `gemini-2.5-flash-lite` ($0.10/$0.40 per million
input/output tokens). Network failures, 401, 402, 403, 429, and 5xx trigger one fallback
attempt before streaming begins. With only one secret, only that provider is used.
URLs, models, token limits, prices and budgets are configurable via the
`STARTER_*` vars in `wrangler.jsonc`; secrets must never be added there.

The free NVIDIA endpoint retains and may train on prompts per OpenRouter's
listing. To switch to paid no-training Nemotron, set
`STARTER_MODEL=nvidia/nemotron-3.5-lightning`,
`STARTER_INPUT_USD_PER_MTOK=0.06`, `STARTER_OUTPUT_USD_PER_MTOK=0.16`, and
`STARTER_PROVIDER` to the JSON string
`{"only":["deepinfra"],"data_collection":"deny"}`. This optional var defaults to
unset and is sent as the `provider` field only to the primary upstream when its
host is `openrouter.ai`; invalid JSON is ignored. In `wrangler.jsonc`, encode it
as `"STARTER_PROVIDER": "{\"only\":[\"deepinfra\"],\"data_collection\":\"deny\"}"`.

D1 migration 0003 stores lifetime reservations per install UUID and SHA-256 IP
hash (not raw IP), plus UTC-month usage, upstream name and estimated cost. It
never stores chat content. Default lifetime allowances are 10 per install and 30
per IP; failed requests with no answer text refund the message. A stream that
delivers answer text consumes a message even if it later disconnects. Empty
streams return an error with the refunded allowance before sending headers.
Bursts are limited to 60/IP/minute. Each upstream attempt has a 120-second deadline.
Responses include `X-Starter-Remaining` and `X-Starter-Limit`. Quota exhaustion
returns 402 `starter_quota_exhausted`; unavailable providers or the default $50
monthly budget cap return 503 when no eligible upstream remains. The budget
cap excludes any upstream with a non-zero input or output price, while free
upstreams keep serving. Migration 0004 adds atomic in-flight paid budget
reservations before every attempt, including fallbacks. Reservations use a
conservative text-token bound (UTF-8 bytes of the request) plus the requested
output limit. Admission includes recorded spend and outstanding reservations,
so a paid request can be refused just below the cap. Reported usage replaces
the reservation atomically; missing usage retains its conservative cost estimate.
Failed upstream
attempts count in monthly request totals; any reported usage is priced at that
upstream's configured rates. The JSON stats include a `starter` summary and
per-upstream request/cost totals.

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
Backfilled events do not update person properties, so profiles keep the values from live pings.

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
