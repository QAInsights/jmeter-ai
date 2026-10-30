import { env, SELF } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const TOKEN = "test-stats-token";

async function insertPing(installId: string, day: string, ts: number): Promise<void> {
	await env.DB.prepare(
		`INSERT INTO pings
		 (install_id, day, ts, event, plugin_version, jmeter_version, java_version, os, arch, provider,
		  agent_enabled, first_run, judgment_provider, model)
		 VALUES (?, ?, ?, 'daily', '3.9.0', '5.6.3', '17.0.12', 'Mac OS X', 'aarch64', 'anthropic',
		  1, 0, 'openrouter:microsoft', 'gpt-4o')`
	).bind(installId, day, ts).run();
}

function backfill(path = "", token?: string): Promise<Response> {
	const headers = new Headers();
	if (token) headers.set("Authorization", `Bearer ${token}`);
	return SELF.fetch(`https://example.com/v1/admin/posthog-backfill${path}`, {
		method: "POST",
		headers,
	});
}

function sentBatch(fetchSpy: ReturnType<typeof vi.spyOn>, call: number) {
	return JSON.parse(String(fetchSpy.mock.calls[call][1]?.body)).batch as {
		event: string;
		distinct_id: string;
		properties: Record<string, unknown>;
	}[];
}

beforeEach(async () => {
	env.POSTHOG_KEY = "test-posthog-key";
	await env.DB.exec("DELETE FROM pings; DELETE FROM feature_counts;");
});

afterEach(() => {
	vi.restoreAllMocks();
});

describe("POST /v1/admin/posthog-backfill", () => {
	it("requires the stats token and an enabled PostHog key", async () => {
		expect((await backfill()).status).toBe(401);

		env.POSTHOG_KEY = "";
		const response = await backfill("", TOKEN);
		expect(response.status).toBe(409);
		expect(await response.json()).toEqual({ error: "posthog disabled" });
	});

	it("paginates pings, excludes test installs, and associates features by install and day", async () => {
		const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 200 }));
		const dayA = "2026-10-01";
		const dayB = "2026-10-02";
		const dayC = "2026-10-03";
		await insertPing("install-a", dayA, Date.UTC(2026, 9, 1));
		await insertPing("install-b", dayB, Date.UTC(2026, 9, 2));
		await insertPing("install-c", dayC, Date.UTC(2026, 9, 3));
		await insertPing("install-test", dayB, Date.UTC(2026, 9, 2));
		await env.DB.prepare(
			`INSERT INTO feature_counts (install_id, day, feature, count) VALUES
			 ('install-a', ?, 'chat_message', 2),
			 ('install-b', ?, 'agent_run', 3),
			 ('install-c', ?, 'chat_message', 9),
			 ('install-test', ?, 'tool_echo', 5)`
		).bind(dayA, dayB, dayC, dayB).run();

		const firstResponse = await backfill("?limit=2", TOKEN);
		expect(firstResponse.status).toBe(200);
		const first = await firstResponse.json() as { pings: number; events: number; next: string | null };
		expect(first).toEqual({ pings: 2, events: 4, next: `${dayB}|install-b` });
		expect(fetchSpy).toHaveBeenCalledTimes(1);
		const firstBatch = sentBatch(fetchSpy, 0);
		expect(firstBatch.filter(event => event.event === "daily_ping").map(event => event.distinct_id))
			.toEqual(["install-a", "install-b"]);
		const dailyPing = firstBatch.find(event => event.event === "daily_ping");
		expect(dailyPing?.properties).not.toHaveProperty("$set");
		expect(dailyPing?.properties).not.toHaveProperty("$set_once");
		expect(firstBatch.find(event => event.event === "feature_used" && event.distinct_id === "install-a")?.properties)
			.toMatchObject({ feature: "chat_message", count: 2 });
		expect(firstBatch.find(event => event.event === "feature_used" && event.distinct_id === "install-b")?.properties)
			.toMatchObject({ feature: "agent_run", count: 3 });
		expect(firstBatch.map(event => event.distinct_id)).not.toContain("install-test");

		const secondResponse = await backfill(`?limit=2&after=${encodeURIComponent(first.next!)}`, TOKEN);
		expect(secondResponse.status).toBe(200);
		expect(await secondResponse.json()).toEqual({ pings: 1, events: 2, next: null });
		expect(fetchSpy).toHaveBeenCalledTimes(2);
		const secondBatch = sentBatch(fetchSpy, 1);
		expect(secondBatch.map(event => event.distinct_id)).toEqual(["install-c", "install-c"]);
		expect(secondBatch.find(event => event.event === "feature_used")?.properties)
			.toMatchObject({ feature: "chat_message", count: 9 });
	});

	it("returns 502 and the upstream status when PostHog rejects a batch", async () => {
		vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 500 }));
		await insertPing("install-a", "2026-10-01", Date.UTC(2026, 9, 1));

		const response = await backfill("", TOKEN);
		expect(response.status).toBe(502);
		expect(await response.json()).toEqual({
			error: "PostHog batch request failed",
			status: 500,
		});
	});
});
