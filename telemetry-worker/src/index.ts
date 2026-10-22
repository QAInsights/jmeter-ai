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
	"judgmentProvider",
	"model",
] as const;
const ERROR_CATEGORIES = [
	"auth", "forbidden", "not_found", "bad_request", "rate_limit",
	"server", "timeout", "unreachable", "other",
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
	judgmentProvider?: string;
	model?: string;
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
		  os, arch, provider, agent_enabled, first_run, country, judgment_provider, model)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
	).bind(
		p.installId, day, ts, p.event,
		p.pluginVersion ?? null, p.jmeterVersion ?? null, p.javaVersion ?? null,
		p.os ?? null, p.arch ?? null, p.provider ?? null,
		p.agentEnabled === undefined ? null : p.agentEnabled ? 1 : 0,
		p.firstRun === undefined ? null : p.firstRun ? 1 : 0,
		country,
		p.judgmentProvider ?? null, p.model ?? null
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

/**
 * Tool names that exist only in the plugin's own unit tests. Every `mvn verify` JVM used to
 * send a ping with them, so installs that ever reported one are test runs, not users.
 */
export const TEST_ONLY_FEATURES = ["tool_boom", "tool_nullish", "tool_echo"] as const;
const TEST_INSTALLS = `SELECT install_id FROM feature_counts WHERE feature IN (${
	TEST_ONLY_FEATURES.map(f => `'${f}'`).join(", ")})`;
const REAL = `install_id NOT IN (${TEST_INSTALLS})`;

async function gatherStats(env: Env): Promise<Record<string, unknown>> {
	const today = todayUTC();
	const wauSince = dateOffset(6);
	const mauSince = dateOffset(29);

	const scalar = async (sql: string, ...binds: string[]) => {
		const r = await env.DB.prepare(sql).bind(...binds).first<{ n: number }>();
		return r?.n ?? 0;
	};

	const prevWeekSince = dateOffset(13);
	const [dau, wau, mau, totalInstalls, testInstalls, wauPrev, newThisWeek, newPrevWeek] = await Promise.all([
		scalar(`SELECT COUNT(DISTINCT install_id) AS n FROM pings WHERE day = ? AND ${REAL}`, today),
		scalar(`SELECT COUNT(DISTINCT install_id) AS n FROM pings WHERE day >= ? AND ${REAL}`, wauSince),
		scalar(`SELECT COUNT(DISTINCT install_id) AS n FROM pings WHERE day >= ? AND ${REAL}`, mauSince),
		scalar(`SELECT COUNT(DISTINCT install_id) AS n FROM pings WHERE ${REAL}`),
		scalar(`SELECT COUNT(DISTINCT install_id) AS n FROM pings WHERE install_id IN (${TEST_INSTALLS})`),
		scalar(`SELECT COUNT(DISTINCT install_id) AS n FROM pings WHERE day >= ? AND day < ? AND ${REAL}`,
			prevWeekSince, wauSince),
		scalar(`SELECT COUNT(*) AS n FROM (SELECT MIN(day) AS first_day FROM pings WHERE ${REAL}
			 GROUP BY install_id) WHERE first_day >= ?`, wauSince),
		scalar(`SELECT COUNT(*) AS n FROM (SELECT MIN(day) AS first_day FROM pings WHERE ${REAL}
			 GROUP BY install_id) WHERE first_day >= ? AND first_day < ?`, prevWeekSince, wauSince),
	]);

	const dailyActive = (await env.DB.prepare(
		`SELECT day, COUNT(DISTINCT install_id) AS installs
		 FROM pings WHERE day >= ? AND ${REAL} GROUP BY day ORDER BY day`
	).bind(mauSince).all<StatsRow>()).results;

	const newInstalls = (await env.DB.prepare(
		`SELECT first_day AS day, COUNT(*) AS installs FROM (
			 SELECT install_id, MIN(day) AS first_day FROM pings WHERE ${REAL} GROUP BY install_id
		 ) WHERE first_day >= ? GROUP BY first_day ORDER BY first_day`
	).bind(mauSince).all<StatsRow>()).results;

	const breakdown = async (col: string) =>
		(await env.DB.prepare(
			`SELECT ${col} AS value, COUNT(*) AS installs FROM (
				 SELECT install_id, ${col},
					ROW_NUMBER() OVER (PARTITION BY install_id ORDER BY ts DESC) AS rn
				 FROM pings WHERE day >= ? AND ${REAL}
			 ) WHERE rn = 1 GROUP BY ${col} ORDER BY installs DESC`
		).bind(mauSince).all<StatsRow>()).results;

	const [pluginVersions, jmeterVersions, oses, providers, countries,
		javaVersions, arches, agentEnabled, judgmentProviders, models, features] =
		await Promise.all([
			breakdown("plugin_version"),
			breakdown("jmeter_version"),
			breakdown("os"),
			breakdown("provider"),
			breakdown("country"),
			breakdown("java_version"),
			breakdown("arch"),
			breakdown("agent_enabled"),
			breakdown("judgment_provider"),
			breakdown("model"),
			env.DB.prepare(
				`SELECT feature, SUM(count) AS total, COUNT(DISTINCT install_id) AS installs
				 FROM feature_counts WHERE day >= ? AND ${REAL} GROUP BY feature ORDER BY total DESC`
			).bind(mauSince).all<StatsRow>().then(r => r.results),
		]);

	// Of the installs at least N days old, how many pinged again N or more days after their first ping.
	const retention = await Promise.all([1, 7, 30].map(async (days) => {
		const r = await env.DB.prepare(
			`WITH firsts AS (SELECT install_id, MIN(day) AS first_day FROM pings WHERE ${REAL} GROUP BY install_id)
			 SELECT COUNT(*) AS cohort,
				SUM(CASE WHEN EXISTS (
					SELECT 1 FROM pings p WHERE p.install_id = f.install_id
					AND julianday(p.day) - julianday(f.first_day) >= ?2
				) THEN 1 ELSE 0 END) AS retained
			 FROM firsts f WHERE julianday(?1) - julianday(f.first_day) >= ?2`
		).bind(today, days).first<{ cohort: number; retained: number | null }>();
		return { days, cohort: r?.cohort ?? 0, retained: r?.retained ?? 0 };
	}));

	const messagers = await env.DB.prepare(
		`SELECT COALESCE(SUM(count), 0) AS total, COUNT(DISTINCT install_id) AS installs
		 FROM feature_counts WHERE day >= ? AND feature IN ('chat_message', 'agent_run') AND ${REAL}`
	).bind(mauSince).first<{ total: number; installs: number }>();

	const versionAdoption = (await env.DB.prepare(
		`SELECT day, plugin_version AS version, COUNT(DISTINCT install_id) AS installs
		 FROM pings WHERE day >= ? AND ${REAL} GROUP BY day, plugin_version ORDER BY day, installs DESC`
	).bind(mauSince).all<StatsRow>()).results;

	return {
		today, dau, wau, mau, totalInstalls, testInstalls,
		growth: { newThisWeek, newPrevWeek, wau, wauPrev },
		engagement: { messages: messagers?.total ?? 0, messagingInstalls: messagers?.installs ?? 0, activeInstalls: mau },
		dailyActive, newInstalls, retention, versionAdoption,
		breakdowns: {
			pluginVersion: pluginVersions,
			jmeterVersion: jmeterVersions,
			os: oses,
			provider: providers,
			country: countries,
			javaVersion: javaVersions,
			arch: arches,
			agentEnabled,
			judgmentProvider: judgmentProviders,
			model: models,
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

function pct(part: number, whole: number): string {
	return whole > 0 ? `${Math.round((part / whole) * 100)}%` : "-";
}

/** Week-over-week change, e.g. "+120%"; "-" when there is no previous week to compare. */
function change(now: number, prev: number): string {
	if (prev <= 0) return "-";
	const p = Math.round(((now - prev) / prev) * 100);
	return `${p > 0 ? "+" : ""}${p}%`;
}

function perInstall(r: StatsRow): string {
	const installs = Number(r.installs);
	return installs > 0 ? (Number(r.total) / installs).toFixed(1) : "-";
}

/** Splits err_<provider>_<category> into its parts; null for other keys. */
function parseErrorKey(feature: string): [string, string] | null {
	if (!feature.startsWith("err_")) return null;
	const rest = feature.slice(4);
	for (const c of ERROR_CATEGORIES) {
		if (rest.endsWith(`_${c}`) && rest.length > c.length + 1) {
			return [rest.slice(0, rest.length - c.length - 1), c];
		}
	}
	return null;
}

/** Pivot of installs per day (rows) and plugin version (columns, the 6 most common). */
function adoptionTable(rows: StatsRow[]): string {
	const totals = new Map<string, number>();
	for (const r of rows) {
		const v = String(r.version ?? "(none)");
		totals.set(v, (totals.get(v) ?? 0) + Number(r.installs));
	}
	const versions = [...totals.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6).map(e => e[0]);
	const byDay = new Map<string, Map<string, number>>();
	for (const r of rows) {
		const d = String(r.day);
		if (!byDay.has(d)) byDay.set(d, new Map());
		byDay.get(d)!.set(String(r.version ?? "(none)"), Number(r.installs));
	}
	const body = [...byDay.entries()].map(([d, m]) => [d, ...versions.map(v => m.get(v) ?? 0)]);
	return table("Plugin version adoption (30d, installs per day)", ["day", ...versions], body);
}

function statsPage(s: Record<string, unknown>): string {
	const bd = s.breakdowns as Record<string, StatsRow[]>;
	const rows = (r: StatsRow[]) => r.map(x => [x.value ?? "(none)", x.installs]);
	const onOff = (r: StatsRow[]) => r.map(x => [x.value === 1 ? "on" : x.value === 0 ? "off" : "(none)", x.installs]);
	const features = s.features as StatsRow[];
	const name = (r: StatsRow) => String(r.feature);
	const errors = features.filter(r => parseErrorKey(name(r)) !== null);
	const outcomes = features.filter(r => name(r).startsWith("outcome_"));
	const tools = features.filter(r => name(r).startsWith("tool_"));
	const toolFailures = features.filter(r => name(r).startsWith("toolfail_"));
	const judgment = features.filter(r => name(r).startsWith("route_") || name(r).startsWith("triage_"));
	const usage = features.filter(r => !errors.includes(r) && !outcomes.includes(r)
		&& !tools.includes(r) && !toolFailures.includes(r) && !judgment.includes(r));
	const callsByTool = new Map(tools.map(r => [name(r).slice("tool_".length), Number(r.total)]));
	const failureRows = toolFailures.map(r => {
		const tool = name(r).slice("toolfail_".length);
		return [tool, r.total, r.installs, pct(Number(r.total), callsByTool.get(tool) ?? 0)];
	});
	const usageRows = (list: StatsRow[], strip = "") =>
		list.map(r => [name(r).slice(strip.length), r.total, r.installs, perInstall(r)]);
	const retention = s.retention as { days: number; cohort: number; retained: number }[];
	const g = s.growth as { newThisWeek: number; newPrevWeek: number; wau: number; wauPrev: number };
	const e = s.engagement as { messages: number; messagingInstalls: number; activeInstalls: number };
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
.note { color: #666; font-size: 0.85rem; }
</style></head><body>
<h1>Feather Wand telemetry (${esc(s.today)} UTC)</h1>
<div class="cards">
<div class="card"><div class="n">${esc(s.dau)}</div>DAU</div>
<div class="card"><div class="n">${esc(s.wau)}</div>WAU</div>
<div class="card"><div class="n">${esc(s.mau)}</div>MAU</div>
<div class="card"><div class="n">${esc(s.totalInstalls)}</div>Total installs</div>
</div>
<p class="note">Excludes ${esc(s.testInstalls)} installs that were the plugin's own test runs (installs that reported a test-only tool). Older test runs that pinged before reaching those tools can still be counted, so treat this as a lower bound.
DAU, WAU and MAU count installs active today, in the last 7 days and in the last 30 days (UTC).</p>
${table("Weekly growth (last 7 days vs the 7 before)", ["metric", "last 7 days", "previous 7 days", "change"], [
		["New installs", g.newThisWeek, g.newPrevWeek, change(g.newThisWeek, g.newPrevWeek)],
		["Active installs (WAU)", g.wau, g.wauPrev, change(g.wau, g.wauPrev)],
	])}
${table("Engagement (30d)", ["metric", "value"], [
		["Messages sent (chat + agent)", e.messages],
		["Installs that sent a message", `${e.messagingInstalls} of ${e.activeInstalls} (${pct(e.messagingInstalls, e.activeInstalls)})`],
		["Messages per active install", e.activeInstalls > 0 ? (e.messages / e.activeInstalls).toFixed(1) : "-"],
		["Messages per messaging install", e.messagingInstalls > 0 ? (e.messages / e.messagingInstalls).toFixed(1) : "-"],
	])}
${table("Daily active installs (30d)", ["day", "installs"], (s.dailyActive as StatsRow[]).map(r => [r.day, r.installs]))}
${table("New installs (30d)", ["day", "installs"], (s.newInstalls as StatsRow[]).map(r => [r.day, r.installs]))}
${table("Plugin versions (30d)", ["version", "installs"], rows(bd.pluginVersion))}
${table("JMeter versions (30d)", ["version", "installs"], rows(bd.jmeterVersion))}
${table("OS (30d)", ["os", "installs"], rows(bd.os))}
${table("Providers (30d)", ["provider", "installs"], rows(bd.provider))}
${table("Countries (30d)", ["country", "installs"], rows(bd.country))}
${table("Retention (all time)", ["came back after", "eligible installs", "returned", "rate"],
		retention.map(r => [`${r.days} day${r.days === 1 ? "" : "s"}`, r.cohort, r.retained, pct(r.retained, r.cohort)]))}
${adoptionTable(s.versionAdoption as StatsRow[])}
${table("Java versions (30d)", ["version", "installs"], rows(bd.javaVersion))}
${table("CPU architecture (30d)", ["arch", "installs"], rows(bd.arch))}
${table("Agent Mode (30d)", ["agent mode", "installs"], onOff(bd.agentEnabled))}
${table("Models (30d)", ["model", "installs"], rows(bd.model))}
${table("Decisions backend (30d)", ["backend", "installs"], rows(bd.judgmentProvider))}
${table("Feature usage (30d)", ["feature", "total", "installs", "per install"], usageRows(usage))}
${table("Agent outcomes (30d)", ["outcome", "total", "installs", "per install"], usageRows(outcomes, "outcome_"))}
${table("Agent tool calls (30d)", ["tool", "total", "installs", "per install"], usageRows(tools, "tool_"))}
${table("Agent tool failures (30d)", ["tool", "failures", "installs", "of calls"], failureRows)}
${table("Smart routing and triage (30d)", ["event", "total", "installs", "per install"], usageRows(judgment))}
${table("Provider errors (30d)", ["provider", "error", "total", "installs"],
		errors.map(r => { const [prov, cat] = parseErrorKey(name(r))!; return [prov, cat, r.total, r.installs]; }))}
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
