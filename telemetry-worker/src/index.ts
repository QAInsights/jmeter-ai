import { posthogEvents, sendToPostHog, type PostHogEvent } from "./posthog";
import { FEATHER_WAND_ICON } from "./brand";
import { TEST_INSTALLS, TEST_ONLY_FEATURES } from "./test-installs";

export interface Env {
	DB: D1Database;
	PING_LIMITER: RateLimit;
	STATS_TOKEN?: string;
	POSTHOG_KEY?: string;
	POSTHOG_HOST?: string;
}

const MAX_BODY_BYTES = 8192;
const MAX_STRING_FIELD = 64;
const MAX_FEATURES = 100;
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

async function handlePing(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
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
	if (ins.meta.changes === 1) {
		try {
			const events = await posthogEvents({
				installId: p.installId,
				day,
				ts,
				pluginVersion: p.pluginVersion,
				jmeterVersion: p.jmeterVersion,
				javaVersion: p.javaVersion,
				os: p.os,
				arch: p.arch,
				provider: p.provider,
				judgmentProvider: p.judgmentProvider,
				model: p.model,
				agentEnabled: p.agentEnabled,
				firstRun: p.firstRun,
			}, p.features ?? {});
			ctx.waitUntil(sendToPostHog(env, events, { retries: 2 }));
		} catch {
			// Telemetry forwarding must never affect the accepted ping response.
		}
	}
	return new Response(null, { status: 204 });
}

interface StatsRow { [k: string]: unknown }

interface BackfillPingRow {
	install_id: string;
	day: string;
	ts: number;
	plugin_version: string | null;
	jmeter_version: string | null;
	java_version: string | null;
	os: string | null;
	arch: string | null;
	provider: string | null;
	judgment_provider: string | null;
	model: string | null;
	agent_enabled: number | null;
	first_run: number | null;
}

interface BackfillFeatureRow {
	install_id: string;
	day: string;
	feature: string;
	count: number;
}

async function handlePostHogBackfill(request: Request, env: Env): Promise<Response> {
	if (!(await tokenOk(request, env))) {
		return json({ error: "unauthorized" }, 401);
	}
	if (!env.POSTHOG_KEY) {
		return json({ error: "posthog disabled" }, 409);
	}

	const url = new URL(request.url);
	const requestedLimit = Number(url.searchParams.get("limit") ?? "50");
	if (!Number.isInteger(requestedLimit)) {
		return badRequest("limit must be an integer");
	}
	// D1 allows at most 100 bound parameters; the feature query binds 2 plus one per install.
	const limit = Math.max(1, Math.min(50, requestedLimit));
	const cursor = url.searchParams.get("after");
	let afterDay = "";
	let afterInstallId = "";
	if (cursor !== null && cursor !== "") {
		const match = /^(\d{4}-\d{2}-\d{2})\|(.+)$/.exec(cursor);
		if (!match) return badRequest("invalid after cursor");
		afterDay = match[1];
		afterInstallId = match[2];
	}

	const rows = (await env.DB.prepare(
		`SELECT install_id, day, ts, plugin_version, jmeter_version, java_version, os, arch, provider,
		        judgment_provider, model, agent_enabled, first_run
		 FROM pings
		 WHERE (day > ?1 OR (day = ?1 AND install_id > ?2))
		   AND install_id NOT IN (${TEST_INSTALLS})
		 ORDER BY day, install_id
		 LIMIT ?3`
	).bind(afterDay, afterInstallId, limit).all<BackfillPingRow>()).results;

	if (rows.length === 0) {
		return json({ pings: 0, events: 0, next: null });
	}

	const installIds = [...new Set(rows.map(row => row.install_id))];
	const placeholders = installIds.map((_, index) => `?${index + 3}`).join(", ");
	const featureRows = (await env.DB.prepare(
		`SELECT install_id, day, feature, count FROM feature_counts
		 WHERE day >= ?1 AND day <= ?2 AND install_id IN (${placeholders})`
	).bind(rows[0].day, rows[rows.length - 1].day, ...installIds).all<BackfillFeatureRow>()).results;

	const pagePairs = new Set(rows.map(row => `${row.install_id}|${row.day}`));
	const featuresByPair = new Map<string, Record<string, number>>();
	for (const featureRow of featureRows) {
		const key = `${featureRow.install_id}|${featureRow.day}`;
		if (!pagePairs.has(key)) continue;
		const features = featuresByPair.get(key) ?? {};
		features[featureRow.feature] = featureRow.count;
		featuresByPair.set(key, features);
	}

	const events: PostHogEvent[] = [];
	for (const row of rows) {
		const ping = {
			installId: row.install_id,
			day: row.day,
			ts: row.ts,
			pluginVersion: row.plugin_version ?? undefined,
			jmeterVersion: row.jmeter_version ?? undefined,
			javaVersion: row.java_version ?? undefined,
			os: row.os ?? undefined,
			arch: row.arch ?? undefined,
			provider: row.provider ?? undefined,
			judgmentProvider: row.judgment_provider ?? undefined,
			model: row.model ?? undefined,
			agentEnabled: row.agent_enabled === null ? undefined : row.agent_enabled === 1,
			firstRun: row.first_run === null ? undefined : row.first_run === 1,
		};
		events.push(...await posthogEvents(
			ping,
			featuresByPair.get(`${row.install_id}|${row.day}`) ?? {},
			{ historical: true }
		));
	}

	let upstreamStatus: number | undefined;
	const ok = await sendToPostHog(env, events, {
		onStatus: status => { upstreamStatus = status; },
	});
	if (!ok) {
		return json({ error: "PostHog batch request failed", status: upstreamStatus ?? null }, 502);
	}

	const last = rows[rows.length - 1];
	const next = rows.length < limit ? null : `${last.day}|${last.install_id}`;
	return json({ pings: rows.length, events: events.length, next });
}

/**
 * Tool names that exist only in the plugin's own unit tests. Every `mvn verify` JVM used to
 * send a ping with them, so installs that ever reported one are test runs, not users.
 */
export { TEST_ONLY_FEATURES };
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

interface Html { html: string }

function raw(html: string): Html {
	return { html };
}

function fmt(n: number): string {
	return Number.isInteger(n) ? n.toLocaleString("en-US") : String(n);
}

function cell(c: unknown): string {
	if (c !== null && typeof c === "object" && "html" in c) return (c as Html).html;
	return esc(typeof c === "number" ? fmt(c) : c);
}

/** Bare table; columns from `numFrom` (1-based) are right-aligned numbers. */
function tableHtml(headers: string[], rows: unknown[][], numFrom = 2): string {
	const head = headers.map(h => `<th>${esc(h)}</th>`).join("");
	const body = rows.length === 0
		? `<tr><td colspan="${headers.length}" class="empty">No data in this window</td></tr>`
		: rows.map(r => `<tr>${r.map(c => `<td>${cell(c)}</td>`).join("")}</tr>`).join("");
	return `<table class="n${numFrom}"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
}

function table(title: string, headers: string[], rows: unknown[][],
	opts: { numFrom?: number; note?: string; span2?: boolean } = {}): string {
	const note = opts.note ? `<p class="card-note">${esc(opts.note)}</p>` : "";
	return `<section class="card${opts.span2 ? " span2" : ""}"><h2>${esc(title)}</h2>${note}`
		+ `<div class="table-wrap">${tableHtml(headers, rows, opts.numFrom)}</div></section>`;
}

function pct(part: number, whole: number): string {
	return whole > 0 ? `${Math.round((part / whole) * 100)}%` : "-";
}

/** Inline share bar with its percentage. */
function share(part: number, whole: number): Html {
	const p = whole > 0 ? Math.max(0, Math.min(1, part / whole)) : 0;
	return raw(`<span class="share"><span class="track"><span class="fill" style="width:${(p * 100).toFixed(1)}%"></span></span>`
		+ `<span class="pct">${Math.round(p * 100)}%</span></span>`);
}

/** Week-over-week change, e.g. "+120%"; "-" when there is no previous week to compare. */
function change(now: number, prev: number): string {
	if (prev <= 0) return "-";
	const p = Math.round(((now - prev) / prev) * 100);
	return `${p > 0 ? "+" : ""}${p}%`;
}

function delta(now: number, prev: number): string {
	const c = change(now, prev);
	if (c === "-") return `<span class="muted">No previous week yet</span>`;
	const dir = now > prev ? "up" : now < prev ? "down" : "flat";
	return `<span class="delta ${dir}">${esc(c)}</span><span class="muted">vs previous 7 days</span>`;
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

function parseDecisionKey(feature: string): [string, string, string] | null {
	if (!feature.startsWith("decide_")) return null;
	const rest = feature.slice("decide_".length);
	if (rest.startsWith("fail_")) {
		const failure = rest.slice("fail_".length);
		for (const category of ERROR_CATEGORIES) {
			const suffix = `_${category}`;
			if (failure.endsWith(suffix) && failure.length > suffix.length) {
				return [failure.slice(0, -suffix.length), "fail", category];
			}
		}
		return [failure, "fail", "other"];
	}
	const separator = rest.indexOf("_");
	if (separator < 0) return [rest, "other", ""];
	const result = rest.slice(0, separator);
	return [rest.slice(separator + 1), result, ""];
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

function shortDay(day: string): string {
	const [, m, d] = day.split("-").map(Number);
	return `${MONTHS[m - 1]} ${d}`;
}

/** The `n` UTC days ending at `today`, oldest first. */
function lastDays(today: string, n: number): string[] {
	const end = Date.parse(`${today}T00:00:00Z`);
	return Array.from({ length: n }, (_, i) => new Date(end - (n - 1 - i) * 86400_000).toISOString().slice(0, 10));
}

/** Axis maximum and gridline step: a 1, 2 or 5 step giving at most 4 intervals. */
function axis(max: number): { top: number; step: number } {
	if (max <= 0) return { top: 4, step: 1 };
	const rough = max / 4;
	const mag = 10 ** Math.floor(Math.log10(rough));
	const step = Math.max(1, [1, 2, 5, 10].map(m => m * mag).find(s => s >= rough) ?? 10 * mag);
	return { top: step * Math.ceil(max / step), step };
}

/** `cls` picks the palette slot (s0 to s5, or sx for Other); defaults to the series index. */
interface Series { name: string; values: number[]; cls?: string }

/** Stacked daily bar chart. Bars stretch with the card; labels stay HTML so text never scales. */
function barChart(days: string[], series: Series[], label: string): string {
	const n = days.length;
	const totals = days.map((_, i) => series.reduce((sum, s) => sum + s.values[i], 0));
	const { top, step } = axis(Math.max(0, ...totals));
	const y = (v: number) => 100 - (v / top) * 100;
	const ticks: number[] = [];
	for (let v = 0; v <= top; v += step) ticks.push(v);
	const grid = ticks.map(v =>
		`<line x1="0" x2="${n * 10}" y1="${y(v)}" y2="${y(v)}" class="${v === 0 ? "base" : "rule"}" vector-effect="non-scaling-stroke"/>`
	).join("");
	const bars = days.map((d, i) => {
		let acc = 0;
		const segs = series.map((s, k) => {
			const v = s.values[i];
			if (v <= 0) return "";
			const rect = `<rect class="seg s${s.cls ?? k}" x="${i * 10 + 2}" y="${y(acc + v)}" width="6" height="${(v / top) * 100}"/>`;
			acc += v;
			return rect;
		}).join("");
		const detail = series.length > 1
			? series.filter(s => s.values[i] > 0).map(s => `\n${s.name}: ${fmt(s.values[i])}`).join("")
			: "";
		return `<g class="bar"><title>${esc(`${shortDay(d)}: ${fmt(totals[i])}${detail}`)}</title>`
			+ `<rect class="hit" x="${i * 10}" y="0" width="10" height="100"/>${segs}</g>`;
	}).join("");
	const yLabels = ticks.map(v => `<span style="bottom:${(v / top) * 100}%">${fmt(v)}</span>`).join("");
	const xLabels = [0, Math.floor((n - 1) / 2), n - 1].map(i => `<span>${esc(shortDay(days[i]))}</span>`).join("");
	return `<div class="chart" role="img" aria-label="${esc(label)}"><div class="y-axis">${yLabels}</div>`
		+ `<svg viewBox="0 0 ${n * 10} 100" preserveAspectRatio="none" aria-hidden="true">${grid}${bars}</svg>`
		+ `<div class="x-axis">${xLabels}</div></div>`;
}

function chartCard(title: string, meta: string, chart: string, details: string, legend = ""): string {
	return `<section class="card wide"><div class="card-head"><h2>${esc(title)}</h2><span class="card-meta">${meta}</span></div>`
		+ `${legend}${chart}<details><summary>Show the numbers</summary><div class="table-wrap">${details}</div></details></section>`;
}

function dailyCard(title: string, today: string, list: StatsRow[]): string {
	const byDay = new Map(list.map(r => [String(r.day), Number(r.installs)]));
	const days = lastDays(today, 30);
	const values = days.map(d => byDay.get(d) ?? 0);
	const total = values.reduce((a, b) => a + b, 0);
	const meta = `Peak <b>${fmt(Math.max(0, ...values))}</b><span class="dot"></span>Daily average <b>${(total / days.length).toFixed(1)}</b>`;
	return chartCard(title, meta, barChart(days, [{ name: "Installs", values }], title),
		tableHtml(["Day", "Installs"], list.map(r => [r.day, r.installs])));
}

/** Installs per day stacked by plugin version (the 6 most common, the rest as Other), with the pivot table. */
function adoptionCard(today: string, rows: StatsRow[]): string {
	const version = (r: StatsRow) => String(r.version ?? "Not reported");
	const totals = new Map<string, number>();
	for (const r of rows) totals.set(version(r), (totals.get(version(r)) ?? 0) + Number(r.installs));
	const versions = [...totals.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6).map(e => e[0]);
	const byDay = new Map<string, Map<string, number>>();
	for (const r of rows) {
		const d = String(r.day);
		if (!byDay.has(d)) byDay.set(d, new Map());
		byDay.get(d)!.set(version(r), Number(r.installs));
	}
	const days = lastDays(today, 30);
	const series: Series[] = versions.map(v => ({ name: v, values: days.map(d => byDay.get(d)?.get(v) ?? 0) }));
	const other = days.map(d => [...(byDay.get(d)?.entries() ?? [])]
		.filter(([v]) => !versions.includes(v)).reduce((sum, [, n]) => sum + n, 0));
	const hasOther = other.some(n => n > 0);
	if (hasOther) series.push({ name: "Other", values: other, cls: "x" });
	const legend = `<div class="legend">${series.map((s, k) =>
		`<span><i class="swatch s${s.cls ?? k}"></i>${esc(s.name)}</span>`).join("")}</div>`;
	const chart = barChart(days, series, "Installs per day by plugin version");
	const body = [...byDay.entries()].map(([d, m]) => {
		const row: unknown[] = [d, ...versions.map(v => m.get(v) ?? 0)];
		if (hasOther) row.push([...m.entries()].filter(([v]) => !versions.includes(v)).reduce((sum, [, n]) => sum + n, 0));
		return row;
	});
	return chartCard("Plugin version adoption (30d, installs per day)", `${versions.length} most common versions`,
		chart, tableHtml(["Day", ...versions, ...(hasOther ? ["Other"] : [])], body), legend);
}

function kpi(label: string, value: number, foot: string): string {
	return `<div class="kpi"><div class="kpi-label">${esc(label)}</div><div class="kpi-value">${fmt(value)}</div>`
		+ `<div class="kpi-foot">${foot}</div></div>`;
}

function group(id: string, title: string, blurb: string, body: string): string {
	return `<section class="group" id="${id}"><div class="group-head"><div class="group-title">${esc(title)}</div>`
		+ `<p>${esc(blurb)}</p></div><div class="grid">${body}</div></section>`;
}

const STATS_CSS = `
:root {
	color-scheme: light dark;
	--bg: #f6f7f9; --surface: #fff; --sunken: #f1f3f4; --line: #e3e5e8; --line-strong: #c9cdd2;
	--text: #1f2328; --muted: #5f6368; --faint: #80868b;
	--accent: #1a73e8; --track: #e8eaed; --up: #188038; --down: #c5221f;
	--s0: #1a73e8; --s1: #12b5cb; --s2: #f29900; --s3: #9334e6; --s4: #1e8e3e; --s5: #e52592; --sx: #bdc1c6;
	--shadow: 0 1px 2px rgba(60, 64, 67, .08), 0 1px 3px 1px rgba(60, 64, 67, .04);
}
@media (prefers-color-scheme: dark) {
	:root {
		--bg: #111316; --surface: #1a1d21; --sunken: #202328; --line: #2c3036; --line-strong: #3c4148;
		--text: #e8eaed; --muted: #a8adb3; --faint: #80868b;
		--accent: #8ab4f8; --track: #2c3036; --up: #81c995; --down: #f28b82;
		--s0: #8ab4f8; --s1: #78d9ec; --s2: #fdd663; --s3: #c58af9; --s4: #81c995; --s5: #ff8bcb; --sx: #5f6368;
		--shadow: none;
	}
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; scroll-padding-top: 76px; }
body {
	margin: 0; background: var(--bg); color: var(--text);
	font: 14px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
	-webkit-font-smoothing: antialiased; font-variant-numeric: tabular-nums;
}
.wrap { max-width: 1280px; margin: 0 auto; padding: 0 32px; }
.top { position: sticky; top: 0; z-index: 5; background: var(--surface); border-bottom: 1px solid var(--line); }
.top-inner { display: flex; align-items: center; justify-content: space-between; gap: 24px; min-height: 64px; flex-wrap: wrap; }
.brand { display: flex; align-items: center; gap: 12px; }
.brand img { width: 28px; height: 28px; display: block; }
h1 { margin: 0; font-size: 17px; font-weight: 600; letter-spacing: -.01em; }
.sub { margin: 0; font-size: 12.5px; color: var(--muted); }
nav { display: flex; gap: 4px; flex-wrap: wrap; }
nav a { color: var(--muted); text-decoration: none; font-size: 13px; font-weight: 500; padding: 6px 12px; border-radius: 999px; }
nav a:hover { color: var(--text); background: var(--sunken); }
nav a:focus-visible, summary:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
main.wrap { padding-top: 40px; padding-bottom: 48px; }
.group + .group { margin-top: 48px; }
.group-head { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; margin-bottom: 16px; }
.group-title { font-size: 20px; font-weight: 600; letter-spacing: -.015em; }
.group-head p { margin: 0; color: var(--muted); font-size: 13px; }
.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 150px), 1fr)); gap: 16px; margin-bottom: 12px; }
.kpi { background: var(--surface); border: 1px solid var(--line); border-radius: 12px; padding: 16px 18px; box-shadow: var(--shadow); }
.kpi-label { font-size: 13px; color: var(--muted); font-weight: 500; }
.kpi-value { font-size: 30px; font-weight: 600; letter-spacing: -.02em; line-height: 1.2; margin: 6px 0 8px; }
.kpi-foot { font-size: 12.5px; display: flex; gap: 6px; align-items: baseline; flex-wrap: wrap; min-height: 19px; }
.muted { color: var(--muted); }
.delta { font-weight: 600; }
.delta.up { color: var(--up); }
.delta.down { color: var(--down); }
.delta.flat { color: var(--muted); }
.note { margin: 0 0 16px; color: var(--muted); font-size: 12.5px; max-width: 90ch; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 380px), 1fr)); gap: 16px; align-items: start; grid-auto-flow: dense; }
.card { background: var(--surface); border: 1px solid var(--line); border-radius: 12px; box-shadow: var(--shadow); padding: 16px 0 6px; min-width: 0; }
.card.wide { grid-column: 1 / -1; padding-bottom: 12px; }
@media (min-width: 900px) { .card.span2 { grid-column: span 2; } }
.card h2 { margin: 0; padding: 0 18px; font-size: 14px; font-weight: 600; }
.card-head { display: flex; justify-content: space-between; align-items: baseline; gap: 4px 16px; flex-wrap: wrap; padding: 0 18px; }
.card-head h2 { padding: 0; }
.card-meta { font-size: 12.5px; color: var(--muted); display: flex; align-items: center; gap: 8px; }
.card-meta b { color: var(--text); font-weight: 600; }
.dot { width: 3px; height: 3px; border-radius: 50%; background: var(--faint); }
.card-note { margin: 4px 0 0; padding: 0 18px; color: var(--muted); font-size: 12.5px; }
.table-wrap { margin-top: 10px; max-height: 420px; overflow: auto; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th { position: sticky; top: 0; background: var(--surface); color: var(--muted); font-weight: 500; font-size: 12px; text-align: left; padding: 6px 12px 8px; border-bottom: 1px solid var(--line); white-space: nowrap; }
td { padding: 7px 12px; border-bottom: 1px solid var(--line); text-align: left; overflow-wrap: break-word; }
td:first-child { min-width: 7.5em; }
:is(th, td):first-child { padding-left: 18px; }
:is(th, td):last-child { padding-right: 18px; }
tbody tr:last-child td { border-bottom: 0; }
tbody tr:hover td { background: var(--sunken); }
.n2 :is(th, td):nth-child(n+2), .n3 :is(th, td):nth-child(n+3), .n4 :is(th, td):nth-child(n+4) { text-align: right; white-space: nowrap; }
td.empty { color: var(--faint); text-align: center !important; padding: 20px 18px; }
.share { display: inline-flex; align-items: center; gap: 10px; justify-content: flex-end; }
.track { width: 56px; height: 6px; border-radius: 3px; background: var(--track); overflow: hidden; }
.fill { display: block; height: 100%; background: var(--accent); border-radius: 3px; }
.pct { min-width: 34px; color: var(--muted); }
.chart { display: grid; grid-template-columns: 40px 1fr; grid-template-rows: 180px auto; column-gap: 8px; padding: 18px 18px 0 8px; }
.y-axis { position: relative; font-size: 11px; color: var(--faint); }
.y-axis span { position: absolute; right: 0; transform: translateY(50%); line-height: 1; }
.chart svg { width: 100%; height: 100%; display: block; overflow: visible; }
.chart .rule { stroke: var(--line); stroke-width: 1; }
.chart .base { stroke: var(--line-strong); stroke-width: 1; }
.chart .hit { fill: transparent; }
.chart .bar:hover .hit { fill: var(--sunken); }
.chart .bar:hover .seg { opacity: .85; }
.x-axis { grid-column: 2; display: flex; justify-content: space-between; font-size: 11px; color: var(--faint); padding-top: 8px; }
.seg.s0 { fill: var(--s0); } .seg.s1 { fill: var(--s1); } .seg.s2 { fill: var(--s2); }
.seg.s3 { fill: var(--s3); } .seg.s4 { fill: var(--s4); } .seg.s5 { fill: var(--s5); } .seg.sx { fill: var(--sx); }
.legend { display: flex; flex-wrap: wrap; gap: 6px 18px; padding: 10px 18px 0; font-size: 12.5px; color: var(--muted); }
.legend span { display: inline-flex; align-items: center; gap: 6px; }
.swatch { width: 10px; height: 10px; border-radius: 3px; display: inline-block; }
.swatch.s0 { background: var(--s0); } .swatch.s1 { background: var(--s1); } .swatch.s2 { background: var(--s2); }
.swatch.s3 { background: var(--s3); } .swatch.s4 { background: var(--s4); } .swatch.s5 { background: var(--s5); }
.swatch.sx { background: var(--sx); }
details { margin: 12px 18px 0; border-top: 1px solid var(--line); padding-top: 10px; }
summary { cursor: pointer; color: var(--accent); font-size: 13px; font-weight: 500; width: fit-content; border-radius: 4px; }
details .table-wrap { margin: 4px -18px 0; }
.foot { color: var(--faint); font-size: 12px; margin-top: 40px; padding-bottom: 40px; }
@media (max-width: 640px) {
	.wrap { padding: 0 16px; }
	.top { position: static; }
	nav { flex-wrap: nowrap; overflow-x: auto; margin: 0 -16px; padding: 0 12px 10px; }
	.kpi { padding: 14px; }
	.kpi-value { font-size: 26px; }
}
`;

function statsPage(s: Record<string, unknown>): string {
	const bd = s.breakdowns as Record<string, StatsRow[]>;
	const breakdown = (title: string, label: string, list: StatsRow[],
		show: (v: unknown) => unknown = v => v ?? "Not reported", note?: string) => {
		const sum = list.reduce((a, r) => a + Number(r.installs), 0);
		return table(title, [label, "Installs", "Share"],
			list.map(r => [show(r.value), r.installs, share(Number(r.installs), sum)]), { note });
	};
	const onOff = (v: unknown) => v === 1 ? "On" : v === 0 ? "Off" : "Not reported";
	const features = s.features as StatsRow[];
	const name = (r: StatsRow) => String(r.feature);
	const errors = features.filter(r => parseErrorKey(name(r)) !== null);
	const outcomes = features.filter(r => name(r).startsWith("outcome_"));
	const tools = features.filter(r => name(r).startsWith("tool_"));
	const toolFailures = features.filter(r => name(r).startsWith("toolfail_"));
	const judgment = features.filter(r => name(r).startsWith("route_") || name(r).startsWith("triage_"));
	const chatProviders = features.filter(r => name(r).startsWith("msg_"));
	const agentProviders = features.filter(r => name(r).startsWith("agentrun_"));
	const agentSizes = features.filter(r => name(r).startsWith("agent_steps_"));
	const setup = features.filter(r => name(r).startsWith("setup_") || name(r) === "welcome_no_provider");
	const decisions = features.filter(r => name(r).startsWith("decide_"));
	const picker = features.filter(r => name(r).startsWith("picker_"));
	const fixUsage = features.filter(r => name(r).startsWith("fix_"));
	const loadUsage = features.filter(r => name(r).startsWith("load_"));
	const setupOutcomes = ["auth_rejected", "rate_limited", "network_error", "http_error", "ok"];
	const setupRows = setup.map(r => {
		const feature = name(r);
		if (feature === "welcome_no_provider") {
			return ["welcome_no_provider", "", "", r.total, r.installs];
		}
		if (feature === "setup_open") {
			return ["open", "", "", r.total, r.installs];
		}
		if (feature.startsWith("setup_save_")) {
			return ["save", feature.slice("setup_save_".length), "", r.total, r.installs];
		}
		const rest = feature.slice("setup_test_".length);
		const outcome = setupOutcomes.find(value => rest.endsWith(`_${value}`));
		return outcome
			? ["test", rest.slice(0, -(outcome.length + 1)), outcome, r.total, r.installs]
			: [feature, "", "", r.total, r.installs];
	});
	const decisionRows = decisions.map(r => {
		const parsed = parseDecisionKey(name(r));
		return parsed ? [...parsed, r.total, r.installs] : [name(r), "other", "", r.total, r.installs];
	});
	const isSpecialized = (feature: string) =>
		feature.startsWith("msg_") || feature.startsWith("agentrun_")
		|| feature.startsWith("agent_steps_") || feature.startsWith("setup_")
		|| feature === "welcome_no_provider" || feature.startsWith("decide_")
		|| feature.startsWith("picker_") || feature.startsWith("fix_") || feature.startsWith("load_");
	const usage = features.filter(r => !errors.includes(r) && !outcomes.includes(r)
		&& !tools.includes(r) && !toolFailures.includes(r) && !judgment.includes(r)
		&& !isSpecialized(name(r)));
	// toolfail_ keys keep 31 name characters and tool_ keys keep 35, so join on the shorter prefix.
	const failNameLength = 40 - "toolfail_".length;
	const callsByTool = new Map<string, number>();
	for (const r of tools) {
		const tool = name(r).slice("tool_".length, "tool_".length + failNameLength);
		callsByTool.set(tool, (callsByTool.get(tool) ?? 0) + Number(r.total));
	}
	const failureRows = toolFailures.map(r => {
		const tool = name(r).slice("toolfail_".length);
		if (tool === "_unknown") return ["(unknown tool)", r.total, r.installs, "-"];
		return [tool, r.total, r.installs, pct(Number(r.total), callsByTool.get(tool) ?? 0)];
	});
	const usageRows = (list: StatsRow[], strip = "") =>
		list.map(r => [name(r).slice(strip.length), r.total, r.installs, perInstall(r)]);
	const usageHead = (first: string) => [first, "Total", "Installs", "Per install"];
	const retention = s.retention as { days: number; cohort: number; retained: number }[];
	const g = s.growth as { newThisWeek: number; newPrevWeek: number; wau: number; wauPrev: number };
	const e = s.engagement as { messages: number; messagingInstalls: number; activeInstalls: number };
	const today = String(s.today);
	const messagesPerActive = e.activeInstalls > 0 ? (e.messages / e.activeInstalls).toFixed(1) : "-";
	const nav = [["overview", "Overview"], ["audience", "Audience"], ["setup", "Setup"], ["usage", "Usage"],
		["agent", "Agent"], ["errors", "Errors"]].map(([id, label]) => `<a href="#${id}">${label}</a>`).join("");

	const overview = `<div class="kpis">
${kpi("Daily active", Number(s.dau), `<span class="muted">Installs active today</span>`)}
${kpi("Weekly active", g.wau, delta(g.wau, g.wauPrev))}
${kpi("Monthly active", Number(s.mau), `<span class="muted">${esc(pct(e.messagingInstalls, e.activeInstalls))} sent a message</span>`)}
${kpi("New this week", g.newThisWeek, delta(g.newThisWeek, g.newPrevWeek))}
${kpi("Messages, 30 days", e.messages, `<span class="muted">${esc(messagesPerActive)} per active install</span>`)}
${kpi("Total installs", Number(s.totalInstalls), `<span class="muted">All time</span>`)}
</div>
<p class="note">Excludes ${esc(s.testInstalls)} installs that were the plugin's own test runs (installs that reported a test-only tool). Older test runs that pinged before reaching those tools can still be counted, so treat this as a lower bound.
DAU, WAU and MAU count installs active today, in the last 7 days and in the last 30 days (UTC).</p>`;

	return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Feather Wand Telemetry</title>
<link rel="icon" href="${FEATHER_WAND_ICON}">
<style>${STATS_CSS}</style></head><body>
<header class="top"><div class="wrap top-inner">
<div class="brand"><img src="${FEATHER_WAND_ICON}" alt=""><div><h1>Feather Wand telemetry</h1>
<p class="sub">${esc(today)} UTC<span aria-hidden="true"> · </span>Last 30 days unless a section says otherwise</p></div></div>
<nav aria-label="Sections">${nav}</nav>
</div></header>
<main class="wrap">
<section class="group" id="overview"><div class="group-head"><div class="group-title">Overview</div>
<p>Active installs, growth and how much people use the plugin.</p></div>
${overview}
<div class="grid">
${dailyCard("Daily active installs (30d)", today, s.dailyActive as StatsRow[])}
${dailyCard("New installs (30d)", today, s.newInstalls as StatsRow[])}
${table("Weekly growth (last 7 days vs the 7 before)", ["Metric", "Last 7 days", "7 before", "Change"], [
		["New installs", g.newThisWeek, g.newPrevWeek, change(g.newThisWeek, g.newPrevWeek)],
		["Active installs (WAU)", g.wau, g.wauPrev, change(g.wau, g.wauPrev)],
	])}
${table("Engagement (30d)", ["Metric", "Value"], [
		["Messages sent (chat + agent)", e.messages],
		["Installs that sent a message", `${e.messagingInstalls} of ${e.activeInstalls} (${pct(e.messagingInstalls, e.activeInstalls)})`],
		["Messages per active install", messagesPerActive],
		["Messages per messaging install", e.messagingInstalls > 0 ? (e.messages / e.messagingInstalls).toFixed(1) : "-"],
	])}
${table("Retention (all time)", ["After", "Eligible", "Returned", "Rate"],
		retention.map(r => [`${r.days} day${r.days === 1 ? "" : "s"}`, r.cohort, r.retained, pct(r.retained, r.cohort)]))}
</div></section>
${group("audience", "Audience", "Who runs Feather Wand, based on each install's latest ping.", `
${adoptionCard(today, s.versionAdoption as StatsRow[])}
${breakdown("Plugin versions (30d)", "Version", bd.pluginVersion)}
${breakdown("JMeter versions (30d)", "Version", bd.jmeterVersion)}
${breakdown("Java versions (30d)", "Version", bd.javaVersion)}
${breakdown("OS (30d)", "OS", bd.os)}
${breakdown("CPU architecture (30d)", "Architecture", bd.arch)}
${breakdown("Countries (30d)", "Country", bd.country)}`)}
${group("setup", "Setup", "Providers, models and modes each install is configured with.", `
${breakdown("Agent Mode (30d)", "Agent Mode", bd.agentEnabled, onOff)}
${breakdown("Models (30d)", "Model", bd.model)}
${breakdown("Decisions backend (30d)", "Backend", bd.judgmentProvider)}
${breakdown("JSR223 Refactor provider setting (30d)", "Provider setting", bd.provider, undefined,
		"This is the provider setting for JSR223 Refactor, not the chat provider.")}
${table("Provider setup funnel (30d)", ["Event", "Option", "Outcome", "Total", "Installs"], setupRows, { numFrom: 4, span2: true })}
${table("Model picker (30d)", usageHead("Action"), usageRows(picker, "picker_"))}`)}
${group("usage", "Usage", "Feature counters summed over the window.", `
${table("Feature usage (30d)", usageHead("Feature"), usageRows(usage))}
${table("Chat messages by provider (30d)", usageHead("Provider"), usageRows(chatProviders, "msg_"))}
${table("Make it pass (30d)", usageHead("Event"), usageRows(fixUsage, "fix_"))}
${table("Workload Designer (30d)", usageHead("Event"), usageRows(loadUsage, "load_"))}`)}
${group("agent", "Agent", "Agent runs, tool calls and the routing decisions behind them.", `
${table("Agent runs by provider (30d)", usageHead("Provider"), usageRows(agentProviders, "agentrun_"))}
${table("Agent run size (30d)", usageHead("Size"), usageRows(agentSizes, "agent_steps_"))}
${table("Agent outcomes (30d)", usageHead("Outcome"), usageRows(outcomes, "outcome_"))}
${table("Agent tool calls (30d)", usageHead("Tool"), usageRows(tools, "tool_"))}
${table("Smart routing and triage (30d)", usageHead("Event"), usageRows(judgment))}
${table("Decisions calls (30d)", ["Backend", "Result", "Error category", "Total", "Installs"], decisionRows, { numFrom: 4, span2: true })}`)}
${group("errors", "Errors", "Failures reported by providers and agent tools.", `
${table("Provider errors (30d)", ["Provider", "Error", "Total", "Installs"],
		errors.map(r => { const [prov, cat] = parseErrorKey(name(r))!; return [prov, cat, r.total, r.installs]; }), { numFrom: 3 })}
${table("Agent tool failures (30d)", ["Tool", "Failures", "Installs", "Of calls"], failureRows)}`)}
</main>
<footer class="wrap foot">Installs are counted once per window. Totals add up every feature counter. All days are UTC.</footer>
</body></html>`;
}

export default {
	async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
		const url = new URL(request.url);
		if (url.pathname === "/v1/ping" && request.method === "POST") {
			return handlePing(request, env, ctx);
		}
		if (url.pathname === "/v1/admin/posthog-backfill" && request.method === "POST") {
			return handlePostHogBackfill(request, env);
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
