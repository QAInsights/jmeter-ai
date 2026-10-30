import { env, SELF } from "cloudflare:test";
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";

const UUID = "3f6b9f2e-9c1a-4d2b-8e5f-1a2b3c4d5e6f";

function pingBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
	return {
		installId: UUID,
		event: "daily",
		pluginVersion: "3.8.5",
		jmeterVersion: "5.6.3",
		javaVersion: "17.0.12",
		os: "Mac OS X",
		arch: "aarch64",
		provider: "anthropic",
		agentEnabled: true,
		firstRun: false,
		features: { chat_message: 12, agent_run: 3 },
		...overrides,
	};
}

function postPing(body: unknown, headers: Record<string, string> = {}) {
	return SELF.fetch("https://example.com/v1/ping", {
		method: "POST",
		headers: {
		"Content-Type": "application/json",
		// Unique IP per request so the rate limiter does not leak between tests.
		"CF-Connecting-IP": `10.${Math.floor(Math.random() * 255)}.${Math.floor(Math.random() * 255)}.${Math.floor(Math.random() * 255)}`,
		...headers,
	},
		body: typeof body === "string" ? body : JSON.stringify(body),
	});
}

beforeEach(async () => {
	env.POSTHOG_KEY = "";
	await env.DB.exec("DELETE FROM pings; DELETE FROM feature_counts;");
});

afterEach(() => {
	vi.restoreAllMocks();
});

describe("POST /v1/ping", () => {
	it("returns 204 and writes rows for a valid ping", async () => {
		const res = await postPing(pingBody());
		expect(res.status).toBe(204);

		const ping = await env.DB.prepare("SELECT * FROM pings").first<Record<string, unknown>>();
		expect(ping).toMatchObject({
			install_id: UUID,
			event: "daily",
			plugin_version: "3.8.5",
			jmeter_version: "5.6.3",
			java_version: "17.0.12",
			os: "Mac OS X",
			arch: "aarch64",
			provider: "anthropic",
			agent_enabled: 1,
			first_run: 0,
		});
		expect(String(ping!.day)).toMatch(/^\d{4}-\d{2}-\d{2}$/);

		const features = await env.DB.prepare(
			"SELECT feature, count FROM feature_counts ORDER BY feature"
		).all<{ feature: string; count: number }>();
		expect(features.results).toEqual([
			{ feature: "agent_run", count: 3 },
			{ feature: "chat_message", count: 12 },
		]);
	});

	it("a second ping the same day writes no extra rows or features", async () => {
		expect((await postPing(pingBody())).status).toBe(204);
		const res = await postPing(pingBody({ pluginVersion: "9.9.9", features: { chat_message: 99 } }));
		expect(res.status).toBe(204);

		const pings = await env.DB.prepare("SELECT COUNT(*) AS n FROM pings").first<{ n: number }>();
		expect(pings!.n).toBe(1);
		const feats = await env.DB.prepare(
			"SELECT SUM(count) AS n FROM feature_counts"
		).first<{ n: number }>();
		expect(feats!.n).toBe(15);
	});

	it("forwards only the first daily ping to PostHog", async () => {
		env.POSTHOG_KEY = "test-posthog-key";
		const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 200 }));

		expect((await postPing(pingBody())).status).toBe(204);
		await vi.waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(1));
		const [url, init] = fetchSpy.mock.calls[0];
		expect(String(url)).toBe("https://posthog.test.invalid/batch/");
		const body = JSON.parse(String(init?.body));
		expect(body.api_key).toBe("test-posthog-key");
		expect(body.batch[0].distinct_id).toBe(UUID);

		expect((await postPing(pingBody({ pluginVersion: "9.9.9" }))).status).toBe(204);
		expect(fetchSpy).toHaveBeenCalledTimes(1);
	});

	it("does not forward when PostHog is disabled", async () => {
		env.POSTHOG_KEY = "";
		const fetchSpy = vi.spyOn(globalThis, "fetch");

		expect((await postPing(pingBody())).status).toBe(204);
		await new Promise(resolve => setTimeout(resolve, 10));
		expect(fetchSpy).not.toHaveBeenCalled();
	});

	it("keeps accepted ping writes when the PostHog request rejects", async () => {
		env.POSTHOG_KEY = "test-posthog-key";
		vi.spyOn(console, "warn").mockImplementation(() => {});
		const fetchSpy = vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("network failed"));

		expect((await postPing(pingBody())).status).toBe(204);
		await vi.waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(1));
		const ping = await env.DB.prepare("SELECT COUNT(*) AS n FROM pings").first<{ n: number }>();
		const features = await env.DB.prepare("SELECT COUNT(*) AS n FROM feature_counts").first<{ n: number }>();
		expect(ping?.n).toBe(1);
		expect(features?.n).toBe(2);
	});

	it("skips zero-count features on the first ping", async () => {
		const res = await postPing(pingBody({ features: { chat_message: 0, agent_run: 2 } }));
		expect(res.status).toBe(204);
		const feats = await env.DB.prepare(
			"SELECT feature FROM feature_counts"
		).all<{ feature: string }>();
		expect(feats.results.map(r => r.feature)).toEqual(["agent_run"]);
	});

	it("ignores unknown top-level fields", async () => {
		const res = await postPing(pingBody({ surprise: { nested: true }, ip: "1.2.3.4" }));
		expect(res.status).toBe(204);
	});

	it("never stores the client IP", async () => {
		await postPing(pingBody(), { "CF-Connecting-IP": "203.0.113.7" });
		const cols = await env.DB.prepare("PRAGMA table_info(pings)").all<{ name: string }>();
		expect(cols.results.map(c => c.name)).not.toContain("ip");
		const row = await env.DB.prepare("SELECT * FROM pings").first<Record<string, unknown>>();
		expect(Object.values(row!).map(String)).not.toContain("203.0.113.7");
	});

	it("rejects a bad installId", async () => {
		expect((await postPing(pingBody({ installId: "not-a-uuid" }))).status).toBe(400);
	});

	it("rejects a non-daily event", async () => {
		expect((await postPing(pingBody({ event: "startup" }))).status).toBe(400);
	});

	it("rejects an overlong string field", async () => {
		expect((await postPing(pingBody({ os: "x".repeat(65) }))).status).toBe(400);
	});

	it("rejects a non-boolean agentEnabled", async () => {
		expect((await postPing(pingBody({ agentEnabled: "yes" }))).status).toBe(400);
	});

	it("rejects a bad feature key", async () => {
		expect((await postPing(pingBody({ features: { "Bad-Key": 1 } }))).status).toBe(400);
	});

	it("accepts exactly 100 feature keys", async () => {
		const features: Record<string, number> = {};
		for (let i = 0; i < 100; i++) features[`f_${i}`] = 1;
		expect((await postPing(pingBody({ features }))).status).toBe(204);
	});

	it("rejects more than 100 feature keys", async () => {
		const features: Record<string, number> = {};
		for (let i = 0; i < 101; i++) features[`f_${i}`] = 1;
		expect((await postPing(pingBody({ features }))).status).toBe(400);
	});

	it("rejects a feature count out of range", async () => {
		expect((await postPing(pingBody({ features: { a: 1_000_001 } }))).status).toBe(400);
		expect((await postPing(pingBody({ features: { a: -1 } }))).status).toBe(400);
		expect((await postPing(pingBody({ features: { a: 1.5 } }))).status).toBe(400);
	});

	it("rejects non-JSON bodies", async () => {
		expect((await postPing("not json")).status).toBe(400);
	});

	it("rejects bodies over 8 KB", async () => {
		const big = pingBody({ padding: "x".repeat(8192) });
		expect((await postPing(big)).status).toBe(400);
	});

	it("rejects oversized bodies by Content-Length header", async () => {
		const res = await SELF.fetch("https://example.com/v1/ping", {
			method: "POST",
			headers: { "Content-Type": "application/json", "Content-Length": "8193" },
			body: JSON.stringify(pingBody()),
		});
		expect(res.status).toBe(400);
	});

	it("accepts 100 maximum-length feature keys with realistic metadata", async () => {
		const features: Record<string, number> = {};
		for (let i = 0; i < 100; i++) {
			features[`f${String(i).padStart(2, "0")}_${"x".repeat(36)}`] = 1_000_000;
		}
		const body = pingBody({ features });
		expect(new TextEncoder().encode(JSON.stringify(body)).length).toBeLessThanOrEqual(8192);
		expect((await postPing(body)).status).toBe(204);
	});

	it("rate limits repeated requests from the same IP", async () => {
		let last = 0;
		for (let i = 0; i < 11; i++) {
			const res = await postPing(pingBody(), { "CF-Connecting-IP": "198.51.100.9" });
			last = res.status;
		}
		expect(last).toBe(429);
	});

	it("returns 404 for unknown paths", async () => {
		const res = await SELF.fetch("https://example.com/nope", { method: "POST" });
		expect(res.status).toBe(404);
	});
});

describe("POST /v1/ping new fields", () => {
	it("stores judgmentProvider and model", async () => {
		const res = await postPing(pingBody({ judgmentProvider: "openrouter", model: "gpt-4o-mini" }));
		expect(res.status).toBe(204);
		const row = await env.DB.prepare("SELECT judgment_provider, model FROM pings").first<any>();
		expect(row).toEqual({ judgment_provider: "openrouter", model: "gpt-4o-mini" });
	});

	it("stores nulls when an older plugin omits them", async () => {
		expect((await postPing(pingBody())).status).toBe(204);
		const row = await env.DB.prepare("SELECT judgment_provider, model FROM pings").first<any>();
		expect(row).toEqual({ judgment_provider: null, model: null });
	});

	it("rejects an overlong model", async () => {
		expect((await postPing(pingBody({ model: "m".repeat(65) }))).status).toBe(400);
	});
});
