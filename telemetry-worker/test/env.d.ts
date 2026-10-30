declare namespace Cloudflare {
	interface Env {
		DB: D1Database;
		PING_LIMITER: RateLimit;
		STATS_TOKEN: string;
		POSTHOG_KEY?: string;
		POSTHOG_HOST?: string;
		TEST_MIGRATIONS: import("cloudflare:test").D1Migration[];
	}
}
