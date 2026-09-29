export interface Env {
	DB: D1Database;
	PING_LIMITER: RateLimit;
	STATS_TOKEN?: string;
}

const MAX_BODY_BYTES = 4096;
const MAX_STRING_FIELD = 64;
const MAX_FEATURES = 50;
const FEATURE_KEY_RE = /^[a-z0-9_]{1,40}$/;
const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const STRING_FIELDS = [
	"pluginVersion",
	"jmeterVersion",
	"javaVersion",
	"os",
	"arch",
	"provider",
] as const;

function json(body: unknown, status = 200): Response {
	return new Response(JSON.stringify(body), {
		status,
		headers: { "Content-Type": "application/json" },
	});
}

function badRequest(msg: string): Response {
	return json({ error: msg }, 400);
}

function todayUTC(d = new Date()): string {
	return d.toISOString().slice(0, 10);
}

function dateOffset(daysBack: number): string {
	return new Date(Date.now() - daysBack * 86400_000).toISOString().slice(0, 10);
}

async function tokenOk(request: Request, env: Env): Promise<boolean> {
	const url = new URL(request.url);
	const header = request.headers.get("Authorization");
	const provided = header && header.startsWith("Bearer ")
		? header.slice("Bearer ".length)
		: url.searchParams.get("token");
	const expected = env.STATS_TOKEN ?? "";
	// Compare SHA-256 digests so the check does not depend on token length or content.
	const enc = new TextEncoder();
	const [a, b] = await Promise.all([
		crypto.subtle.digest("SHA-256", enc.encode(provided ?? "")),
		crypto.subtle.digest("SHA-256", enc.encode(expected)),
	]);
	const xa = new Uint8Array(a);
	const xb = new Uint8Array(b);
	let diff = 0;
	for (let i = 0; i < xa.length; i++) diff |= xa[i] ^ xb[i];
	return expected.length > 0 && diff === 0;
}

interface Ping {
	installId: string;
	event: string;
	pluginVersion?: string;
	jmeterVersion?: string;
	javaVersion?: string;
	os?: string;
	arch?: string;
	provider?: string;
	agentEnabled?: boolean;
	firstRun?: boolean;
	features?: Record<string, number>;
}

function validate(body: unknown): Ping | Response {
	if (typeof body !== "object" || body === null || Array.isArray(body)) {
		return badRequest("expected a JSON object");
	}
	const b = body as Record<string, unknown>;

	if (typeof b.installId !== "string" || !UUID_RE.test(b.installId)) {
		return badRequest("installId must be a UUID");
	}
	if (b.event !== "daily") {
		return badRequest("event must be \"daily\"");
	}
	for (const f of STRING_FIELDS) {
		const v = b[f];
		if (v !== undefined && (typeof v !== "string" || v.length > MAX_STRING_FIELD)) {
			return badRequest(`${f} must be a string of at most ${MAX_STRING_FIELD} characters`);
		}
	}
	for (const f of ["agentEnabled", "firstRun"] as const) {
		if (b[f] !== undefined && typeof b[f] !== "boolean") {
			return badRequest(`${f} must be a boolean`);
		}
	}
	if (b.features !== undefined) {
		if (typeof b.features !== "object" || b.features === null || Array.isArray(b.features)) {
			return badRequest("features must be an object");
		}
		const entries = Object.entries(b.features as Record<string, unknown>);
		if (entries.length > MAX_FEATURES) {
			return badRequest(`features may have at most ${MAX_FEATURES} keys`);
		}
		for (const [k, v] of entries) {
			if (!FEATURE_KEY_RE.test(k)) {
				return badRequest(`invalid feature key: ${k}`);
			}
			if (typeof v !== "number" || !Number.isInteger(v) || v < 0 || v > 1_000_000) {
				return badRequest(`invalid feature count for ${k}`);
			}
		}
	}
	return b as unknown as Ping;
}

async function handlePing(request: Request, env: Env): Promise<Response> {
	const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
	const { success } = await env.PING_LIMITER.limit({ key: ip });
	if (!success) {
		return json({ error: "rate limited" }, 429);
	}

	const contentLength = Number(request.headers.get("Content-Length") ?? "0");
	if (contentLength > MAX_BODY_BYTES) {
		return badRequest("body too large");
	}
	let raw: string;
	try {
		raw = await request.text();
	} catch {
		return badRequest("unreadable body");
	}
	if (new TextEncoder().encode(raw).length > MAX_BODY_BYTES) {
		return badRequest("body too large");
	}
	let parsed: unknown;
	try {
		parsed = JSON.parse(raw);
	} catch {
		return badRequest("body is not valid JSON");
	}
	const result = validate(parsed);
	if (result instanceof Response) return result;
	const p = result;

	const day = todayUTC();
	const ts = Date.now();
	const country = (request.cf?.country as string | undefined) ?? null;

	const ins = await env.DB.prepare(
		`INSERT OR IGNORE INTO pings
		 (install_id, day, ts, event, plugin_version, jmeter_version, java_version,
		  os, arch, provider, agent_enabled, first_run, country)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
	).bind(
		p.installId, day, ts, p.event,
		p.pluginVersion ?? null, p.jmeterVersion ?? null, p.javaVersion ?? null,
		p.os ?? null, p.arch ?? null, p.provider ?? null,
		p.agentEnabled === undefined ? null : p.agentEnabled ? 1 : 0,
		p.firstRun === undefined ? null : p.firstRun ? 1 : 0,
		country
	).run();

	if (ins.meta.changes === 1 && p.features) {
		const stmts = Object.entries(p.features)
			.filter(([, count]) => count > 0)
			.map(([feature, count]) =>
				env.DB.prepare(
					`INSERT INTO feature_counts (install_id, day, feature, count) VALUES (?, ?, ?, ?)`
				).bind(p.installId, day, feature, count)
			);
		if (stmts.length > 0) {
			await env.DB.batch(stmts);
		}
	}
	return new Response(null, { status: 204 });
}

interface StatsRow { [k: string]: unknown }

async function gatherStats(env: Env): Promise<Record<string, unknown>> {
	const today = todayUTC();
	const wauSince = dateOffset(6);
	const mauSince = dateOffset(29);

	const scalar = async (sql: string, ...binds: string[]) => {
		const r = await env.DB.prepare(sql).bind(...binds).first<{ n: number }>();
		return r?.n ?? 0;
	};

	const [dau, wau, mau, totalInstalls] = await Promise.all([
		scalar(`SELECT COUNT(DISTINCT install_id) AS n FROM pings WHERE day = ?`, today),
		scalar(`SELECT COUNT(DISTINCT install_id) AS n FROM pings WHERE day >= ?`, wauSince),
		scalar(`SELECT COUNT(DISTINCT install_id) AS n FROM pings WHERE day >= ?`, mauSince),
		scalar(`SELECT COUNT(DISTINCT install_id) AS n FROM pings`),
	]);

	const dailyActive = (await env.DB.prepare(
		`SELECT day, COUNT(DISTINCT install_id) AS installs
		 FROM pings WHERE day >= ? GROUP BY day ORDER BY day`
	).bind(mauSince).all<StatsRow>()).results;

	const newInstalls = (await env.DB.prepare(
		`SELECT first_day AS day, COUNT(*) AS installs FROM (
			 SELECT install_id, MIN(day) AS first_day FROM pings GROUP BY install_id
		 ) WHERE first_day >= ? GROUP BY first_day ORDER BY first_day`
	).bind(mauSince).all<StatsRow>()).results;

	const breakdown = async (col: string) =>
		(await env.DB.prepare(
			`SELECT ${col} AS value, COUNT(*) AS installs FROM (
				 SELECT install_id, ${col},
					ROW_NUMBER() OVER (PARTITION BY install_id ORDER BY ts DESC) AS rn
				 FROM pings WHERE day >= ?
			 ) WHERE rn = 1 GROUP BY ${col} ORDER BY installs DESC`
		).bind(mauSince).all<StatsRow>()).results;

	const [pluginVersions, jmeterVersions, oses, providers, countries, features] =
		await Promise.all([
			breakdown("plugin_version"),
			breakdown("jmeter_version"),
			breakdown("os"),
			breakdown("provider"),
			breakdown("country"),
			env.DB.prepare(
				`SELECT feature, SUM(count) AS total, COUNT(DISTINCT install_id) AS installs
				 FROM feature_counts WHERE day >= ? GROUP BY feature ORDER BY total DESC`
			).bind(mauSince).all<StatsRow>().then(r => r.results),
		]);

	return {
		today, dau, wau, mau, totalInstalls,
		dailyActive, newInstalls,
		breakdowns: {
			pluginVersion: pluginVersions,
			jmeterVersion: jmeterVersions,
			os: oses,
			provider: providers,
			country: countries,
		},
		features,
	};
}

function esc(s: unknown): string {
	return String(s ?? "")
		.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
		.replaceAll('"', "&quot;");
}

function table(title: string, headers: string[], rows: unknown[][]): string {
	const head = headers.map(h => `<th>${esc(h)}</th>`).join("");
	const body = rows.length === 0
		? `<tr><td colspan="${headers.length}" class="empty">no data</td></tr>`
		: rows.map(r => `<tr>${r.map(c => `<td>${esc(c)}</td>`).join("")}</tr>`).join("");
	return `<h2>${esc(title)}</h2><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
}

function statsPage(s: Record<string, unknown>): string {
	const bd = s.breakdowns as Record<string, StatsRow[]>;
	const rows = (r: StatsRow[]) => r.map(x => [x.value ?? "(none)", x.installs]);
	return `<!doctype html><html><head><meta charset="utf-8"><title>Feather Wand Telemetry</title>
<style>
body { font-family: system-ui, sans-serif; margin: 2rem; color: #222; }
h1 { font-size: 1.4rem; }
h2 { font-size: 1.05rem; margin-top: 1.6rem; }
.cards { display: flex; gap: 1rem; flex-wrap: wrap; }
.card { border: 1px solid #ddd; border-radius: 8px; padding: 0.8rem 1.2rem; }
.card .n { font-size: 1.6rem; font-weight: 600; }
table { border-collapse: collapse; min-width: 20rem; }
th, td { border: 1px solid #ddd; padding: 0.3rem 0.7rem; text-align: left; font-size: 0.9rem; }
th { background: #f5f5f5; }
.empty { color: #999; }
</style></head><body>
<h1>Feather Wand telemetry (${esc(s.today)} UTC)</h1>
<div class="cards">
<div class="card"><div class="n">${esc(s.dau)}</div>DAU</div>
<div class="card"><div class="n">${esc(s.wau)}</div>WAU</div>
<div class="card"><div class="n">${esc(s.mau)}</div>MAU</div>
<div class="card"><div class="n">${esc(s.totalInstalls)}</div>Total installs</div>
</div>
${table("Daily active installs (30d)", ["day", "installs"], (s.dailyActive as StatsRow[]).map(r => [r.day, r.installs]))}
${table("New installs (30d)", ["day", "installs"], (s.newInstalls as StatsRow[]).map(r => [r.day, r.installs]))}
${table("Plugin versions (30d)", ["version", "installs"], rows(bd.pluginVersion))}
${table("JMeter versions (30d)", ["version", "installs"], rows(bd.jmeterVersion))}
${table("OS (30d)", ["os", "installs"], rows(bd.os))}
${table("Providers (30d)", ["provider", "installs"], rows(bd.provider))}
${table("Countries (30d)", ["country", "installs"], rows(bd.country))}
${table("Feature usage (30d)", ["feature", "total", "installs"], (s.features as StatsRow[]).map(r => [r.feature, r.total, r.installs]))}
</body></html>`;
}

export default {
	async fetch(request: Request, env: Env): Promise<Response> {
		const url = new URL(request.url);
		if (url.pathname === "/v1/ping" && request.method === "POST") {
			return handlePing(request, env);
		}
		if (url.pathname === "/v1/stats" && request.method === "GET") {
			if (!(await tokenOk(request, env))) {
				return json({ error: "unauthorized" }, 401);
			}
			return json(await gatherStats(env));
		}
		if (url.pathname === "/stats" && request.method === "GET") {
			if (!(await tokenOk(request, env))) {
				return json({ error: "unauthorized" }, 401);
			}
			return new Response(statsPage(await gatherStats(env)), {
				headers: { "Content-Type": "text/html; charset=utf-8" },
			});
		}
		return json({ error: "not found" }, 404);
	},
} satisfies ExportedHandler<Env>;
