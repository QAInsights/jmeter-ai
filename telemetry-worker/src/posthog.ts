import { TEST_ONLY_FEATURES } from "./test-installs";

export interface PingRecord {
	installId: string;
	day: string;
	ts: number;
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
}

export interface PostHogEvent {
	event: string;
	distinct_id: string;
	timestamp: string;
	uuid: string;
	properties: Record<string, unknown>;
}

export interface PostHogEnv {
	POSTHOG_KEY?: string;
	POSTHOG_HOST?: string;
}

export interface PostHogSendOptions {
	fetcher?: typeof fetch;
	onStatus?: (status: number) => void;
	retries?: number;
	sleep?: (milliseconds: number) => Promise<void>;
}

const POSTHOG_LIB = "feather-wand-telemetry-worker";

function omitNullish(properties: Record<string, unknown>): Record<string, unknown> {
	return Object.fromEntries(
		Object.entries(properties).filter(([, value]) => value !== undefined && value !== null)
	);
}

async function eventUuid(installId: string, day: string, event: string, feature = ""): Promise<string> {
	const input = new TextEncoder().encode(`${installId}|${day}|${event}|${feature}`);
	const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", input));
	const bytes = digest.slice(0, 16);
	bytes[6] = (bytes[6] & 0x0f) | 0x80;
	bytes[8] = (bytes[8] & 0x3f) | 0x80;
	const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
	return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function baseProperties(): Record<string, unknown> {
	return {
		$geoip_disable: true,
		$lib: POSTHOG_LIB,
	};
}

export async function posthogEvents(
	ping: PingRecord,
	features: Record<string, number>,
	options?: { historical?: boolean }
): Promise<PostHogEvent[]> {
	if (Object.keys(features).some(feature => (TEST_ONLY_FEATURES as readonly string[]).includes(feature))) {
		return [];
	}

	const featureTotal = Object.values(features).reduce((sum, count) => sum + count, 0);
	const featureCount = Object.values(features).filter(count => count > 0).length;
	const dailyFields = omitNullish({
		plugin_version: ping.pluginVersion,
		jmeter_version: ping.jmeterVersion,
		java_version: ping.javaVersion,
		os: ping.os,
		arch: ping.arch,
		provider: ping.provider,
		judgment_provider: ping.judgmentProvider,
		model: ping.model,
		agent_enabled: ping.agentEnabled,
		first_run: ping.firstRun,
		day: ping.day,
		feature_total: featureTotal,
		feature_count: featureCount,
	});
	const personFields = omitNullish({
		plugin_version: ping.pluginVersion,
		jmeter_version: ping.jmeterVersion,
		java_version: ping.javaVersion,
		os: ping.os,
		arch: ping.arch,
		provider: ping.provider,
		judgment_provider: ping.judgmentProvider,
		model: ping.model,
		agent_enabled: ping.agentEnabled,
	});
	const timestamp = new Date(ping.ts).toISOString();
	const events: PostHogEvent[] = [{
		event: "daily_ping",
		distinct_id: ping.installId,
		timestamp,
		uuid: await eventUuid(ping.installId, ping.day, "daily_ping"),
		properties: {
			...baseProperties(),
			...dailyFields,
			...(options?.historical ? {} : {
				$set: personFields,
				$set_once: { first_seen_day: ping.day },
			}),
		},
	}];

	for (const [feature, count] of Object.entries(features)) {
		if (count <= 0) continue;
		events.push({
			event: "feature_used",
			distinct_id: ping.installId,
			timestamp,
			uuid: await eventUuid(ping.installId, ping.day, "feature_used", feature),
			properties: {
				...baseProperties(),
				...omitNullish({
					feature,
					count,
					day: ping.day,
					plugin_version: ping.pluginVersion,
					provider: ping.provider,
				}),
			},
		});
	}

	return events;
}

export async function sendToPostHog(
	env: PostHogEnv,
	events: PostHogEvent[],
	options: PostHogSendOptions = {}
): Promise<boolean> {
	const key = env.POSTHOG_KEY;
	if (!key || events.length === 0) return true;

	const host = (env.POSTHOG_HOST || "https://us.i.posthog.com").replace(/\/+$/, "");
	const retries = Number.isFinite(options.retries) ? Math.max(0, Math.floor(options.retries ?? 0)) : 0;
	const sleep = options.sleep ?? (milliseconds =>
		new Promise<void>(resolve => setTimeout(resolve, milliseconds)));

	for (let attempt = 0; attempt <= retries; attempt++) {
		try {
			const response = await (options.fetcher ?? fetch)(`${host}/batch/`, {
				method: "POST",
				headers: { "Content-Type": "application/json" },
				body: JSON.stringify({ api_key: key, batch: events }),
			});
			options.onStatus?.(response.status);
			if (response.ok) return true;
			const retryable = response.status === 429 || (response.status >= 500 && response.status < 600);
			if (!retryable || attempt === retries) {
				console.warn(`PostHog batch request failed with status ${response.status}`);
				return false;
			}
		} catch {
			if (attempt === retries) {
				console.warn("PostHog batch request failed");
				return false;
			}
		}

		try {
			await sleep(attempt === 0 ? 1_000 : 3_000);
		} catch {
			console.warn("PostHog batch request failed");
			return false;
		}
	}
	return false;
}
