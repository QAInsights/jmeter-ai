import { env, SELF } from "cloudflare:test";
import { describe, it, expect, beforeEach } from "vitest";

const TOKEN = "test-stats-token";

function day(offset: number): string {
	return new Date(Date.now() - offset * 86400_000).toISOString().slice(0, 10);
}

async function insertPing(
	installId: string,
	dayStr: string,
	fields: { plugin_version?: string; jmeter_version?: string; os?: string; provider?: string; country?: string; ts?: number } = {}
) {
	await env.DB.prepare(
		`INSERT OR IGNORE INTO pings
		 (install_id, day, ts, event, plugin_version, jmeter_version, java_version, os, arch, provider, agent_enabled, first_run, country)
		 VALUES (?, ?, ?, 'daily', ?, '5.6.3', '17', ?, 'aarch64', ?, 1, 0, ?)`
	).bind(
		installId, dayStr,
		fields.ts ?? Date.now(),
		fields.plugin_version ?? "3.8.5",
		fields.os ?? "Mac OS X",
		fields.provider ?? "anthropic",
		fields.country ?? "US"
	).run();
}

function stats(path: string, token?: string) {
	const headers: Record<string, string> = {};
	if (token) headers.Authorization = `Bearer ${token}`;
	return SELF.fetch(`https://example.com${path}`, { headers });
}

beforeEach(async () => {
	await env.DB.exec("DELETE FROM pings; DELETE FROM feature_counts;");
});

describe("stats auth", () => {
	it("401 without a token", async () => {
		expect((await stats("/v1/stats")).status).toBe(401);
	});

	it("401 with a wrong token", async () => {
		expect((await stats("/v1/stats", "wrong")).status).toBe(401);
	});

	it("accepts the token via ?token=", async () => {
		expect((await stats(`/v1/stats?token=${TOKEN}`)).status).toBe(200);
	});

	it("accepts a Bearer header", async () => {
		expect((await stats("/v1/stats", TOKEN)).status).toBe(200);
	});

	it("/stats returns HTML with a token", async () => {
		const res = await stats(`/stats?token=${TOKEN}`);
		expect(res.status).toBe(200);
		expect(res.headers.get("Content-Type")).toContain("text/html");
		expect(await res.text()).toContain("Feather Wand telemetry");
	});
});

describe("stats numbers", () => {
	it("computes dau, wau, mau, totals, breakdowns, new installs and features", async () => {
		// a: active today. b: active 5 days ago (wau but not dau). c: 20 days ago (mau but not wau). d: 60 days ago (total only).
		await insertPing("a", day(0));
		await insertPing("b", day(5));
		await insertPing("c", day(20));
		await insertPing("d", day(60));
		// a also pinged yesterday, then upgraded and pinged today (latest ping wins).
		await insertPing("a", day(1), { plugin_version: "3.8.4", ts: Date.now() - 86400_000 });
		// a has an even older install day.
		await insertPing("d", day(50));

		await env.DB.prepare(
			"INSERT INTO feature_counts (install_id, day, feature, count) VALUES ('a', ?, 'chat_message', 10), ('a', ?, 'chat_message', 5), ('b', ?, 'agent_run', 2), ('b', ?, 'old_feature', 7)"
		).bind(day(0), day(1), day(5), day(60)).run();

		const res = await stats(`/v1/stats?token=${TOKEN}`);
		expect(res.status).toBe(200);
		const s = await res.json() as any;

		expect(s.dau).toBe(1);
		expect(s.wau).toBe(2);
		expect(s.mau).toBe(3);
		expect(s.totalInstalls).toBe(4);

		expect(s.dailyActive).toEqual([
			{ day: day(20), installs: 1 },
			{ day: day(5), installs: 1 },
			{ day: day(1), installs: 1 },
			{ day: day(0), installs: 1 },
		]);

		// d first installed 60 days ago; a, b, c's first days are their only/earliest pings.
		expect(s.newInstalls).toEqual([
			{ day: day(20), installs: 1 },
			{ day: day(5), installs: 1 },
			{ day: day(1), installs: 1 },
		]);

		// Latest-ping breakdown: a is counted under 3.8.5, not 3.8.4.
		expect(s.breakdowns.pluginVersion).toEqual([
			{ value: "3.8.5", installs: 3 },
		]);
		expect(s.breakdowns.os).toEqual([{ value: "Mac OS X", installs: 3 }]);
		expect(s.breakdowns.provider).toEqual([{ value: "anthropic", installs: 3 }]);
		expect(s.breakdowns.country).toEqual([{ value: "US", installs: 3 }]);

		// Features: last 30 days only (old_feature on day(60) excluded).
		expect(s.features).toEqual([
			{ feature: "chat_message", total: 15, installs: 1 },
			{ feature: "agent_run", total: 2, installs: 1 },
		]);
	});
});

describe("extended stats", () => {
	it("computes retention, version adoption and new breakdowns", async () => {
		// x: first seen 10 days ago, back 2 days ago. y: first seen 3 days ago only. z: new today.
		await insertPing("x", day(10), { ts: Date.now() - 10 * 86400_000 });
		await insertPing("x", day(2), { plugin_version: "3.8.8", ts: Date.now() - 2 * 86400_000 });
		await insertPing("y", day(3));
		await insertPing("z", day(0));
		await env.DB.prepare(
			"UPDATE pings SET judgment_provider = 'openrouter', model = 'gpt-4o-mini' WHERE install_id = 'x' AND day = ?"
		).bind(day(2)).run();

		const s = await (await stats(`/v1/stats?token=${TOKEN}`)).json() as any;

		expect(s.retention).toEqual([
			{ days: 1, cohort: 2, retained: 1 },
			{ days: 7, cohort: 1, retained: 1 },
			{ days: 30, cohort: 0, retained: 0 },
		]);
		expect(s.versionAdoption).toEqual([
			{ day: day(10), version: "3.8.5", installs: 1 },
			{ day: day(3), version: "3.8.5", installs: 1 },
			{ day: day(2), version: "3.8.8", installs: 1 },
			{ day: day(0), version: "3.8.5", installs: 1 },
		]);
		expect(s.breakdowns.javaVersion).toEqual([{ value: "17", installs: 3 }]);
		expect(s.breakdowns.arch).toEqual([{ value: "aarch64", installs: 3 }]);
		expect(s.breakdowns.agentEnabled).toEqual([{ value: 1, installs: 3 }]);
		expect(s.breakdowns.judgmentProvider).toContainEqual({ value: "openrouter", installs: 1 });
		expect(s.breakdowns.model).toContainEqual({ value: "gpt-4o-mini", installs: 1 });
	});

	it("groups errors, outcomes, tools and routing on the HTML page", async () => {
		await insertPing("a", day(0));
		await env.DB.prepare(
			`INSERT INTO feature_counts (install_id, day, feature, count) VALUES
			 ('a', ?1, 'err_openai_not_found', 3), ('a', ?1, 'outcome_completed', 4),
			 ('a', ?1, 'tool_add_element', 6), ('a', ?1, 'toolfail_add_element', 3),
			 ('a', ?1, 'tool_abcdefghijklmnopqrstuvwxyzabcdefghi', 4),
			 ('a', ?1, 'toolfail_abcdefghijklmnopqrstuvwxyzabcde', 1), ('a', ?1, 'toolfail__unknown', 2),
			 ('a', ?1, 'route_focused', 2), ('a', ?1, 'chat_message', 9)`
		).bind(day(0)).run();

		const html = await (await stats(`/stats?token=${TOKEN}`)).text();

		for (const heading of ["Retention (all time)", "Plugin version adoption", "Java versions",
			"CPU architecture", "Agent Mode", "Models", "Decisions backend", "Agent outcomes",
			"Agent tool calls", "Agent tool failures", "Smart routing and triage", "Provider errors"]) {
			expect(html).toContain(heading);
		}
		expect(html).toContain("<td>openai</td><td>not_found</td><td>3</td>");
		expect(html).toContain("<td>add_element</td><td>6</td><td>1</td><td>6.0</td>");
		expect(html).toContain("<td>add_element</td><td>3</td><td>1</td><td>50%</td>");
		expect(html).toContain("<td>abcdefghijklmnopqrstuvwxyzabcde</td><td>1</td><td>1</td><td>25%</td>");
		expect(html).toContain("<td>(unknown tool)</td><td>2</td><td>1</td><td>-</td>");
		expect(html).not.toContain("<td>toolfail_add_element</td>");
		expect(html).toContain("<td>completed</td><td>4</td>");
		expect(html).toContain("<td>route_focused</td><td>2</td>");
		expect(html).toContain("<td>chat_message</td><td>9</td>");
	});

	it("groups provider, setup, Decisions and picker counters outside generic feature usage", async () => {
		await insertPing("a", day(0));
		await insertPing("b", day(0));
		await env.DB.prepare(
			`INSERT INTO feature_counts (install_id, day, feature, count) VALUES
			 ('a', ?1, 'msg_claudecode', 3), ('a', ?1, 'agentrun_openrouter', 2),
			 ('a', ?1, 'agent_steps_1_5', 2), ('a', ?1, 'setup_open', 1),
			 ('a', ?1, 'setup_test_openai_auth_rejected', 1), ('b', ?1, 'setup_save_openai', 1),
			 ('b', ?1, 'welcome_no_provider', 2), ('a', ?1, 'decide_ok_openai', 3),
			 ('b', ?1, 'decide_fail_openrouter_timeout', 1), ('a', ?1, 'decide_slow_typesafe', 1),
			 ('a', ?1, 'picker_open', 4), ('b', ?1, 'picker_search', 2),
			 ('a', ?1, 'fix_fixed', 2), ('b', ?1, 'fix_guard_rollback', 1),
			 ('a', ?1, 'fix_soft_failure', 1), ('a', ?1, 'fix_assertions_added', 1),
			 ('a', ?1, 'fix_concurrency_fixed', 2), ('b', ?1, 'fix_concurrency_warn', 1),
			 ('a', ?1, 'load_applied', 2), ('b', ?1, 'load_target_users', 1),
			 ('a', ?1, 'load_distributed', 1), ('a', ?1, 'load_target_messages', 2),
			 ('b', ?1, 'load_hosts_set', 1), ('a', ?1, 'load_jdbc_warned', 1),
			 ('b', ?1, 'load_generators_short', 1),
			 ('a', ?1, 'chat_message', 1)`
		).bind(day(0)).run();

		const html = await (await stats(`/stats?token=${TOKEN}`)).text();

		for (const heading of [
			"Chat messages by provider (30d)", "Agent runs by provider (30d)", "Agent run size (30d)",
			"Provider setup funnel (30d)", "Decisions calls (30d)", "Model picker (30d)",
			"Make it pass (30d)",
			"Workload Designer (30d)",
			"JSR223 Refactor provider setting (30d)",
		]) {
			expect(html).toContain(heading);
		}
		expect(html).toContain("not the chat provider");
		expect(html).toContain("<td>claudecode</td><td>3</td><td>1</td><td>3.0</td>");
		expect(html).toContain("<td>openrouter</td><td>2</td><td>1</td><td>2.0</td>");
		expect(html).toContain("<td>test</td><td>openai</td><td>auth_rejected</td><td>1</td><td>1</td>");
		expect(html).toContain("<td>openrouter</td><td>fail</td><td>timeout</td><td>1</td><td>1</td>");
		expect(html).toContain("<td>openai</td><td>ok</td><td></td><td>3</td><td>1</td>");
		expect(html).toContain("<td>open</td><td></td><td></td><td>1</td><td>1</td>");
		expect(html).toContain("<td>open</td><td>4</td><td>1</td><td>4.0</td>");
		expect(html).toContain("<td>fixed</td><td>2</td><td>1</td><td>2.0</td>");
		expect(html).toContain("<td>soft_failure</td><td>1</td><td>1</td><td>1.0</td>");
		expect(html).toContain("<td>assertions_added</td><td>1</td><td>1</td><td>1.0</td>");
		expect(html).toContain("<td>concurrency_fixed</td><td>2</td><td>1</td><td>2.0</td>");
		expect(html).toContain("<td>concurrency_warn</td><td>1</td><td>1</td><td>1.0</td>");
		expect(html).toContain("<td>applied</td><td>2</td><td>1</td><td>2.0</td>");
		expect(html).toContain("<td>target_users</td><td>1</td><td>1</td><td>1.0</td>");
		expect(html).toContain("<td>distributed</td><td>1</td><td>1</td><td>1.0</td>");
		expect(html).toContain("<td>target_messages</td><td>2</td><td>1</td><td>2.0</td>");
		expect(html).toContain("<td>hosts_set</td><td>1</td><td>1</td><td>1.0</td>");
		expect(html).toContain("<td>jdbc_warned</td><td>1</td><td>1</td><td>1.0</td>");
		expect(html).toContain("<td>generators_short</td><td>1</td><td>1</td><td>1.0</td>");

		const usageStart = html.indexOf("<h2>Feature usage (30d)</h2>");
		const usageEnd = html.indexOf("<h2>Chat messages by provider (30d)</h2>");
		const genericUsage = html.slice(usageStart, usageEnd);
		expect(genericUsage).toContain("<td>chat_message</td>");
		for (const key of [
			"msg_claudecode", "agentrun_openrouter", "agent_steps_1_5", "setup_open",
			"setup_test_openai_auth_rejected", "setup_save_openai", "welcome_no_provider",
			"decide_ok_openai", "decide_fail_openrouter_timeout", "decide_slow_typesafe",
			"picker_open", "picker_search",
			"fix_fixed", "fix_guard_rollback",
			"load_applied", "load_target_users", "load_distributed", "load_target_messages",
			"load_hosts_set", "load_jdbc_warned", "load_generators_short",
		]) {
			expect(genericUsage).not.toContain(`<td>${key}</td>`);
		}
	});
});

describe("test-run filtering, growth and engagement", () => {
	it("hides installs that reported test-only tools from every number", async () => {
		await insertPing("real", day(0), { os: "Windows 11", country: "IN" });
		await insertPing("ci", day(0), { os: "Linux", country: "US" });
		await insertPing("ci", day(1), { os: "Linux", country: "US" });
		await env.DB.prepare(
			`INSERT INTO feature_counts (install_id, day, feature, count) VALUES
			 ('real', ?1, 'chat_message', 4), ('ci', ?2, 'tool_boom', 1), ('ci', ?2, 'chat_message', 50),
			 ('ci', ?1, 'tool_add_element', 2)`
		).bind(day(0), day(1)).run();

		const s = await (await stats(`/v1/stats?token=${TOKEN}`)).json() as any;

		expect([s.dau, s.wau, s.mau, s.totalInstalls, s.testInstalls]).toEqual([1, 1, 1, 1, 1]);
		expect(s.dailyActive).toEqual([{ day: day(0), installs: 1 }]);
		expect(s.newInstalls).toEqual([{ day: day(0), installs: 1 }]);
		expect(s.breakdowns.os).toEqual([{ value: "Windows 11", installs: 1 }]);
		expect(s.breakdowns.country).toEqual([{ value: "IN", installs: 1 }]);
		expect(s.features).toEqual([{ feature: "chat_message", total: 4, installs: 1 }]);
		expect(s.retention[0]).toEqual({ days: 1, cohort: 0, retained: 0 });
		expect(s.versionAdoption).toEqual([{ day: day(0), version: "3.8.5", installs: 1 }]);
	});

	it("compares the last 7 days with the 7 before and reports messages per active install", async () => {
		// old: first seen 10 days ago, active again today. a, b: new this week. gone: only 12 days ago.
		await insertPing("old", day(10));
		await insertPing("old", day(0));
		await insertPing("gone", day(12));
		await insertPing("a", day(3));
		await insertPing("b", day(0));
		await env.DB.prepare(
			`INSERT INTO feature_counts (install_id, day, feature, count) VALUES
			 ('a', ?1, 'chat_message', 6), ('a', ?1, 'agent_run', 2), ('b', ?2, 'chat_message', 4),
			 ('b', ?2, 'panel_open', 9)`
		).bind(day(3), day(0)).run();

		const s = await (await stats(`/v1/stats?token=${TOKEN}`)).json() as any;

		expect(s.growth).toEqual({ newThisWeek: 2, newPrevWeek: 2, wau: 3, wauPrev: 2 });
		expect(s.engagement).toEqual({ messages: 12, messagingInstalls: 2, activeInstalls: 4 });

		const html = await (await stats(`/stats?token=${TOKEN}`)).text();
		expect(html).toContain("Excludes 0 installs that were the plugin's own test runs");
		expect(html).toContain("<td>New installs</td><td>2</td><td>2</td><td>0%</td>");
		expect(html).toContain("<td>Active installs (WAU)</td><td>3</td><td>2</td><td>+50%</td>");
		expect(html).toContain("<td>Installs that sent a message</td><td>2 of 4 (50%)</td>");
		expect(html).toContain("<td>Messages per active install</td><td>3.0</td>");
		expect(html).toContain("<td>Messages per messaging install</td><td>6.0</td>");
	});
});
