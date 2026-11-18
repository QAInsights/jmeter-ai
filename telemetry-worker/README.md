# Feather Wand telemetry worker

Anonymous, opt-out usage telemetry receiver for the Feather Wand JMeter plugin.
Cloudflare Worker + D1. Endpoint: `POST https://telemetry.jmeter.ai/v1/ping`.

## What is stored

Each install can send one ping per UTC day. Stored fields: a random install UUID,
plugin/JMeter/Java version, OS name, CPU arch, AI provider name, agent-mode
enabled flag, first-run flag, coarse feature usage counts, and the country code
Cloudflare reports for the request. Day and timestamp are assigned server side.
IP addresses are used for burst rate limiting and starter lifetime quotas, and
are never stored in plaintext. Starter quota records retain an HMAC-SHA-256 IP
hash keyed by `STARTER_IP_HASH_SECRET` for the lifetime of the quota, alongside
the independent starter UUID.

## Starter chat

`POST /v1/starter/chat/completions` accepts OpenAI Chat Completions text chats.
Use SDK base URL `https://telemetry.jmeter.ai/v1/starter` and the plugin's random
starter UUID as the bearer API key (separate from its telemetry ID). No user provider key is required. Agent tools and
functions, client model fallbacks and paid plugins are stripped; 1–100 messages and a 64 KiB body are allowed. Output is
clamped to 2048 tokens by default. Both JSON and SSE streaming are supported.

Configure the two required starter secrets before deployment (choose a long
random value for the IP-hashing secret):

```bash
npx wrangler secret put STARTER_IP_HASH_SECRET
npx wrangler secret put STARTER_API_KEY
```

The only upstream is DeepInfra's paid OpenAI-compatible endpoint,
`https://api.deepinfra.com/v1/openai/chat/completions`, using the exact model ID
`nvidia/NVIDIA-Nemotron-3.5-Lightning`. Set `STARTER_API_KEY` to a DeepInfra API key.
Default accounting prices are $0.06 input / $0.16 output per million tokens,
matching the live [model documentation](https://deepinfra.com/nvidia/NVIDIA-Nemotron-3.5-Lightning/api)
(checked 2026-11-18).
Client `service_tier` overrides are stripped to keep standard-tier pricing;
[priority requests](https://docs.deepinfra.com/chat/overview) otherwise carry a 50% surcharge.
Network failures and non-2xx responses (including 401, 402, 403, 429 and 5xx)
are terminal: return 502 `starter_upstream_error` and refund the message reservation.
The plugin shows a friendly unavailable notice and Connect a provider action.
The worker never retries against a different upstream.
If the IP-hashing secret or provider key is missing, starter requests
return 503 `starter_unavailable`. Keep the IP-hashing secret stable: rotating it
changes the per-IP quota identities.
URLs, models, token limits, prices and budgets are configurable via the
`STARTER_*` vars in `wrangler.jsonc`; secrets must never be added there.

Messages go through jmeter.ai directly to DeepInfra. Its
[inference privacy documentation](https://docs.deepinfra.com/account/data-privacy)
says inputs/outputs are processed in memory, not stored on disk, deleted after
inference, and not used for training. Logs contain metadata (request ID, cost,
sampling parameters), not request content. The model page labels Nemotron
**Zero retention**. Keep the upstream/model and accounting rates aligned if
overriding configuration, and recheck the provider's policy and prices.

Privacy qualifications: DeepInfra's [privacy policy](https://deepinfra.com/privacy)
allows storage/training with explicit consent and legal disclosures. Its separate
bulk API can temporarily retain encrypted data on disk, and Google/Anthropic and
image models have exceptions; none is used by this text-chat endpoint. DeepInfra
also documents best-effort in-memory prompt caching and optional
[explicit cache retention](https://docs.deepinfra.com/chat/prompt-cache-retention)
for 5 minutes or 1 hour. The worker strips client cache/storage options (including
message `cache_control`) so callers cannot opt in to explicit retention. This is
not a guarantee against transient processing/caching in memory or legal obligations.
**Do not include secrets.** Do not opt in to content storage or training.

DeepInfra supports `stream_options.include_usage` (default true in its
[API schema](https://docs.deepinfra.com/api-reference/chat-completions/openai-chat-completions)).
The worker requests it explicitly. Its [streaming protocol](https://docs.deepinfra.com/chat/streaming)
returns token usage in the final chunk with `finish_reason`, before `[DONE]`;
the existing SSE accounting parser consumes that usage. Missing usage still uses
the conservative reservation cost bound.

### Message costs

At the configured default rates, `cost = (input_tokens * input_rate + output_tokens * output_rate) / 1e6`:

| Attempt | Typical: 3,000 in + 500 out | Approximate worst case: 16,000 in + 2,048 out |
| --- | ---: | ---: |
| DeepInfra Nemotron (0.06 / 0.16) | $0.000260 | $0.00128768 |

The requested 64 KB ≈ 16k-token estimate assumes roughly four bytes per token;
it is not a hard token bound (64 KiB / 4 is 16,384 tokens, costing $0.00131072
with 2,048 output tokens). Actual tokenization
varies. Admission uses the more conservative UTF-8-byte bound described below.

D1 migration 0003 stores lifetime reservations per install UUID and HMAC-SHA-256 IP
hash (not raw IP), plus UTC-month usage, upstream name and estimated cost. It
never stores chat content. Default lifetime allowances are 10 per install and 30
per IP; failed requests with no answer text refund the message. A stream that
delivers answer text consumes a message even if it later disconnects. Empty
streams return an error with the refunded allowance before sending headers.
Bursts are limited to 60/IP/minute. Each upstream attempt has a 120-second deadline.
Responses include `X-Starter-Remaining` and `X-Starter-Limit`. Quota exhaustion
returns 402 `starter_quota_exhausted`; missing starter secrets return 503
`starter_unavailable`. The default $50 monthly budget cap returns 503
`starter_budget_exhausted` when exhausted. The $50 cap
covers all starter calls; at the cap they return
`starter_budget_exhausted`, which the plugin displays as a friendly temporary
unavailability notice with a Connect a provider action. Migration 0004 adds atomic in-flight budget
reservations before each request. Reservations use a
conservative text-token bound (UTF-8 bytes of the request) plus the requested
output limit. Admission includes recorded spend and outstanding reservations,
so a paid request can be refused just below the cap. Reported usage replaces
the reservation atomically; missing usage retains its conservative cost estimate.
In-flight reservations expire after ten minutes (longer than the attempt
deadline), so an accounting outage cannot block starter requests indefinitely.
If both accounting writes fail, the unrecorded attempt stops gating paid calls
at expiry; this recovery tradeoff can leave that attempt's request, token and
cost totals uncounted.
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

# 4. Enable starter chat with the two required secrets.
#    Choose a long random IP-hashing secret and set the DeepInfra API key.
npx wrangler secret put STARTER_IP_HASH_SECRET
npx wrangler secret put STARTER_API_KEY

# 5. Deploy.
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
