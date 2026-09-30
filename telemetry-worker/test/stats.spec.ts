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
