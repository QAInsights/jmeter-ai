import { describe, it, expect, vi } from "vitest";
import { posthogEvents, sendToPostHog, type PingRecord } from "../src/posthog";

const ping: PingRecord = {
	installId: "3f6b9f2e-9c1a-4d2b-8e5f-1a2b3c4d5e6f",
	day: "2026-10-30",
	ts: Date.UTC(2026, 9, 30, 12),
	pluginVersion: "3.9.0",
	jmeterVersion: "5.6.3",
	javaVersion: "17.0.12",
	os: "Mac OS X",
	arch: "aarch64",
	provider: "anthropic",
	judgmentProvider: "openrouter:microsoft",
	model: "gpt-4o",
	agentEnabled: true,
	firstRun: false,
};

describe("posthogEvents", () => {
	it("maps the daily ping and positive feature counts", async () => {
		const events = await posthogEvents(ping, { chat_message: 12, agent_run: 3, skipped: 0 });

		expect(events.map(event => event.event)).toEqual(["daily_ping", "feature_used", "feature_used"]);
		expect(events[0]).toMatchObject({
			distinct_id: ping.installId,
			timestamp: new Date(ping.ts).toISOString(),
			properties: {
				plugin_version: "3.9.0",
				jmeter_version: "5.6.3",
				java_version: "17.0.12",
				os: "Mac OS X",
				arch: "aarch64",
				provider: "anthropic",
				judgment_provider: "openrouter:microsoft",
				model: "gpt-4o",
				agent_enabled: true,
				first_run: false,
				day: "2026-10-30",
				feature_total: 15,
				feature_count: 2,
				$geoip_disable: true,
				$lib: "feather-wand-telemetry-worker",
				$set: {
					plugin_version: "3.9.0",
					jmeter_version: "5.6.3",
					java_version: "17.0.12",
					os: "Mac OS X",
					arch: "aarch64",
					provider: "anthropic",
					judgment_provider: "openrouter:microsoft",
					model: "gpt-4o",
					agent_enabled: true,
				},
				$set_once: { first_seen_day: "2026-10-30" },
			},
		});
		expect(events[1].properties).toMatchObject({
			feature: "chat_message",
			count: 12,
			day: "2026-10-30",
			plugin_version: "3.9.0",
			provider: "anthropic",
		});
		expect(events[2].properties).toMatchObject({ feature: "agent_run", count: 3 });
		expect(events[1].properties).not.toHaveProperty("country");
		expect(JSON.stringify(events)).not.toContain("country");
	});

	it("omits nullish values and skips test-only installs", async () => {
		const minimal = await posthogEvents({
			installId: ping.installId,
			day: ping.day,
			ts: ping.ts,
		}, { empty: 0 });
		expect(minimal).toHaveLength(1);
		expect(minimal[0].properties).not.toHaveProperty("plugin_version");
		expect(minimal[0].properties).not.toHaveProperty("provider");
		expect(await posthogEvents(ping, { tool_boom: 0, chat_message: 1 })).toEqual([]);
	});

	it("uses deterministic UUID v8 values unique to each feature", async () => {
		const first = await posthogEvents(ping, { chat_message: 1, agent_run: 1 });
		const second = await posthogEvents(ping, { chat_message: 1, agent_run: 1 });

		expect(first.map(event => event.uuid)).toEqual(second.map(event => event.uuid));
		expect(new Set(first.map(event => event.uuid)).size).toBe(3);
		for (const event of first) {
			expect(event.uuid).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
		}
	});
});

describe("sendToPostHog", () => {
	it("posts a batch to the configured host without a trailing slash", async () => {
		const events = await posthogEvents(ping, {});
		const fetcher = vi.fn(async () => new Response(null, { status: 200 }));

		expect(await sendToPostHog({
			POSTHOG_KEY: "test-key",
			POSTHOG_HOST: "https://posthog.example///",
		}, events, { fetcher })).toBe(true);
		expect(fetcher).toHaveBeenCalledWith("https://posthog.example/batch/", expect.objectContaining({
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({ api_key: "test-key", batch: events }),
		}));
	});

	it("does not fetch when disabled or when the batch is empty", async () => {
		const fetcher = vi.fn(async () => new Response(null, { status: 200 }));
		expect(await sendToPostHog({}, [], { fetcher })).toBe(true);
		expect(await sendToPostHog({ POSTHOG_KEY: "test-key" }, [], { fetcher })).toBe(true);
		expect(fetcher).not.toHaveBeenCalled();
	});

	it("returns false and does not throw on upstream failure", async () => {
		const events = await posthogEvents(ping, {});
		const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
		const fetcher = vi.fn(async () => new Response(null, { status: 500 }));

		expect(await sendToPostHog({ POSTHOG_KEY: "test-key" }, events, { fetcher })).toBe(false);
		fetcher.mockRejectedValueOnce(new Error("network failed"));
		expect(await sendToPostHog({ POSTHOG_KEY: "test-key" }, events, { fetcher })).toBe(false);
		expect(warn).toHaveBeenCalledTimes(2);
		warn.mockRestore();
	});
});
