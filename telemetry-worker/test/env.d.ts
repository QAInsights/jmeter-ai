declare namespace Cloudflare {
	interface Env {
		DB: D1Database;
		PING_LIMITER: RateLimit;
		STATS_TOKEN: string;
		TEST_MIGRATIONS: import("cloudflare:test").D1Migration[];
	}
}
