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

# 1. Create the database and paste the printed id into wrangler.jsonc
#    (d1_databases[0].database_id).
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

## Viewing stats

- JSON: `curl -H "Authorization: Bearer $STATS_TOKEN" https://telemetry.jmeter.ai/v1/stats`
- HTML: open `https://telemetry.jmeter.ai/stats?token=$STATS_TOKEN` in a browser.

## Testing

```bash
npm ci
npm test              # vitest in workerd, migrations applied to in-memory D1
npm run typecheck     # tsc --noEmit
npm run deploy:dry    # wrangler deploy --dry-run
```
