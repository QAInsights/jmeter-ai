export interface StarterEnv {
	DB: D1Database;
	STARTER_LIMITER: RateLimit;
	STARTER_API_KEY?: string;
	STARTER_FALLBACK_API_KEY?: string;
	STARTER_IP_HASH_SECRET?: string;
	STARTER_UPSTREAM_URL?: string;
	STARTER_MODEL?: string;
	STARTER_PROVIDER?: string;
	STARTER_FALLBACK_UPSTREAM_URL?: string;
	STARTER_FALLBACK_MODEL?: string;
	STARTER_MAX_OUTPUT_TOKENS?: string;
	STARTER_FREE_MESSAGES?: string;
	STARTER_IP_MESSAGES?: string;
	STARTER_INPUT_USD_PER_MTOK?: string;
	STARTER_OUTPUT_USD_PER_MTOK?: string;
	STARTER_FALLBACK_INPUT_USD_PER_MTOK?: string;
	STARTER_FALLBACK_OUTPUT_USD_PER_MTOK?: string;
	STARTER_MONTHLY_BUDGET_USD?: string;
}

function number(value: string | undefined, fallback: number): number {
	const n = value === undefined ? fallback : Number(value);
	return Number.isFinite(n) && n >= 0 ? n : fallback;
}

export async function starterStats(env: StarterEnv) {
	const month = new Date().toISOString().slice(0, 7);
	const totals = await env.DB.prepare(`SELECT COUNT(*) AS requests,
	 COALESCE(SUM(input_tokens),0) AS inputTokens, COALESCE(SUM(output_tokens),0) AS outputTokens,
	 COALESCE(SUM(cost_usd),0) AS estimatedCostUsd, COUNT(DISTINCT install_id) AS installs
	 FROM starter_usage WHERE month = ?`).bind(month).first<{
		requests: number; inputTokens: number; outputTokens: number; estimatedCostUsd: number; installs: number;
	}>();
	const upstreams = (await env.DB.prepare(`SELECT upstream, COUNT(*) AS requests,
	 COALESCE(SUM(cost_usd),0) AS estimatedCostUsd FROM starter_usage WHERE month = ? GROUP BY upstream`)
		.bind(month).all()).results;
	return { month, ...totals, upstreams };
}

export async function handleStarter(request: Request, env: StarterEnv, ctx: ExecutionContext, uuid: RegExp): Promise<Response> {
	const limit = Math.floor(number(env.STARTER_FREE_MESSAGES, 10));
	const install = (request.headers.get("Authorization") ?? "").replace(/^Bearer /, "");
	let remaining = limit;
	const headers = () => ({ "X-Starter-Remaining": String(remaining), "X-Starter-Limit": String(limit) });
	const error = (status: number, code: string, message: string) => new Response(JSON.stringify({ error: { code, message } }), {
		status, headers: { ...headers(), "Content-Type": "application/json" },
	});
	if (!request.headers.get("Authorization")?.startsWith("Bearer ") || !uuid.test(install)) {
		return error(401, "invalid_install", "A Feather Wand starter ID is required.");
	}
	const installId = install.toLowerCase();
	const used = await env.DB.prepare("SELECT COUNT(*) AS n FROM starter_messages WHERE install_id = ?")
		.bind(installId).first<{ n: number }>();
	remaining = Math.max(0, limit - (used?.n ?? 0));
	const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
	if (!(await env.STARTER_LIMITER.limit({ key: ip })).success) return error(429, "starter_rate_limited", "Please try again in a minute.");
	const upstreams = [
		{ name: "primary", key: env.STARTER_API_KEY, url: env.STARTER_UPSTREAM_URL ?? "https://openrouter.ai/api/v1/chat/completions",
			model: env.STARTER_MODEL ?? "nvidia/nemotron-3.5-lightning", input: number(env.STARTER_INPUT_USD_PER_MTOK, 0.07), output: number(env.STARTER_OUTPUT_USD_PER_MTOK, 0.20) },
		{ name: "fallback", key: env.STARTER_FALLBACK_API_KEY, url: env.STARTER_FALLBACK_UPSTREAM_URL ?? "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
			model: env.STARTER_FALLBACK_MODEL ?? "gemini-2.5-flash-lite", input: number(env.STARTER_FALLBACK_INPUT_USD_PER_MTOK, 0.10), output: number(env.STARTER_FALLBACK_OUTPUT_USD_PER_MTOK, 0.40) },
	].filter(u => u.key);
	if (!upstreams.length || !env.STARTER_IP_HASH_SECRET) return error(503, "starter_unavailable", "Starter chat is unavailable. Connect your own provider.");
	const budget = number(env.STARTER_MONTHLY_BUDGET_USD, 50);
	let body: Record<string, unknown>;
	try {
		if (Number(request.headers.get("Content-Length")) > 65536) throw new Error();
		const reader = request.body?.getReader();
		if (!reader) throw new Error();
		const chunks: Uint8Array[] = [];
		let size = 0;
		while (true) {
			const { done, value } = await reader.read();
			if (done) break;
			size += value.byteLength;
			if (size > 65536) { await reader.cancel(); throw new Error(); }
			chunks.push(value);
		}
		const bytes = new Uint8Array(size);
		let offset = 0;
		for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
		body = JSON.parse(new TextDecoder().decode(bytes));
		if (!body || typeof body !== "object" || Array.isArray(body) || !Array.isArray(body.messages)
			|| body.messages.length === 0 || body.messages.length > 100) throw new Error();
		if (body.stream !== undefined && typeof body.stream !== "boolean") throw new Error();
		for (const message of body.messages) {
			if (!message || !["system", "user", "assistant", "developer"].includes(message.role)
				|| typeof message.content !== "string") throw new Error();
			delete message.tool_calls;
			delete message.function_call;
		}
	} catch { return error(400, "invalid_request", "Expected a JSON chat with 1–100 text messages, at most 64 KB."); }
	for (const field of ["tools", "tool_choice", "functions", "function_call", "n", "provider", "plugins", "models", "route"]) delete body[field];
	const max = Math.max(1, Math.floor(number(env.STARTER_MAX_OUTPUT_TOKENS, 2048)));
	for (const field of ["max_tokens", "max_completion_tokens"]) {
		if (body[field] !== undefined) body[field] = Math.max(1, Math.min(max, Math.floor(Number(body[field])) || max));
	}
	if (body.max_tokens === undefined && body.max_completion_tokens === undefined) body.max_tokens = max;
	if (body.stream) body.stream_options = { include_usage: true };
	const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.STARTER_IP_HASH_SECRET),
		{ name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
	const hash = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(ip));
	const ipHash = Array.from(new Uint8Array(hash), b => b.toString(16).padStart(2, "0")).join("");
	const id = crypto.randomUUID();
	const reservation = await env.DB.prepare(`INSERT INTO starter_messages(id, install_id, ip_hash)
	 SELECT ?, ?, ? WHERE (SELECT COUNT(*) FROM starter_messages WHERE install_id = ?) < ?
	 AND (SELECT COUNT(*) FROM starter_messages WHERE ip_hash = ?) < ?`)
		.bind(id, installId, ipHash, installId, limit, ipHash, Math.floor(number(env.STARTER_IP_MESSAGES, 30))).run();
	const refreshRemaining = async () => {
		const row = await env.DB.prepare("SELECT COUNT(*) AS n FROM starter_messages WHERE install_id = ?")
			.bind(installId).first<{ n: number }>();
		remaining = Math.max(0, limit - (row?.n ?? 0));
	};
	await refreshRemaining();
	if (!reservation.meta.changes) return error(402, "starter_quota_exhausted", "Free starter messages exhausted. Connect your own provider to continue.");
	const refund = async () => {
		await env.DB.prepare("DELETE FROM starter_messages WHERE id = ?").bind(id).run();
		await refreshRemaining();
	};
	for (let i = 0; i < upstreams.length; i++) {
		if (request.signal.aborted) {
			await refund();
			return error(502, "starter_upstream_error", "Starter request was cancelled.");
		}
		const upstream = upstreams[i];
		const month = new Date().toISOString().slice(0, 7);
		const attemptId = crypto.randomUUID();
		const inputBound = new TextEncoder().encode(JSON.stringify({ ...body, model: upstream.model })).byteLength;
		const outputBound = Math.max(Number(body.max_tokens) || 0, Number(body.max_completion_tokens) || 0);
		const reservedCost = (inputBound * upstream.input + outputBound * upstream.output) / 1e6;
		{
			const now = Date.now();
			let admitted: D1Result;
			try {
				admitted = await env.DB.prepare(`WITH spend AS (
			 SELECT (SELECT COALESCE(SUM(cost_usd),0) FROM starter_usage WHERE month = ?)
			 + (SELECT COALESCE(SUM(cost_usd),0) FROM starter_budget_reservations WHERE month = ? AND expires_at > ?) AS total)
			 INSERT INTO starter_budget_reservations(id,month,cost_usd,expires_at)
			 SELECT ?,?,?,? FROM spend WHERE total < ? AND total + ? <= ?`)
				.bind(month, month, now, attemptId, month, reservedCost, now + 600_000, budget, reservedCost, budget).run();
			} catch {
				const release = () => env.DB.batch([
					env.DB.prepare("DELETE FROM starter_budget_reservations WHERE id = ?").bind(attemptId),
					env.DB.prepare("DELETE FROM starter_messages WHERE id = ?").bind(id),
				]);
				try { await release(); await refreshRemaining(); }
				catch { ctx.waitUntil(release().catch(() => {})); }
				return error(502, "starter_upstream_error", "Starter budget admission is unavailable. Please try again.");
			}
			if (!admitted.meta.changes) continue;
		}
		const record = async (usage: any) => {
			const tokens = (v: unknown) => typeof v === "number" && Number.isFinite(v) && v >= 0 ? Math.floor(v) : 0;
			const input = tokens(usage?.prompt_tokens), output = tokens(usage?.completion_tokens);
			const validUsage = [usage?.prompt_tokens, usage?.completion_tokens]
				.every(v => typeof v === "number" && Number.isFinite(v) && v >= 0);
			const reportedCost = (input * upstream.input + output * upstream.output) / 1e6;
			const cost = validUsage ? reportedCost : Math.max(reportedCost, reservedCost);
			const write = () => env.DB.batch([
				env.DB.prepare(`INSERT INTO starter_usage(month,install_id,upstream,input_tokens,output_tokens,cost_usd,attempt_id) VALUES (?,?,?,?,?,?,?)
				 ON CONFLICT(attempt_id) DO NOTHING`)
					.bind(month, installId, upstream.name, input, output, cost, attemptId),
				env.DB.prepare("DELETE FROM starter_budget_reservations WHERE id = ?").bind(attemptId),
			]);
			try { await write(); }
			catch { ctx.waitUntil(write().catch(() => {})); }
		};
		const abort = new AbortController();
		const timer = setTimeout(() => abort.abort(), 120_000);
		const disconnect = () => abort.abort();
		request.signal.addEventListener("abort", disconnect, { once: true });
		if (request.signal.aborted) disconnect();
		const finishAttempt = () => {
			clearTimeout(timer);
			request.signal.removeEventListener("abort", disconnect);
		};
		if (abort.signal.aborted) {
			finishAttempt();
			await env.DB.prepare("DELETE FROM starter_budget_reservations WHERE id = ?").bind(attemptId).run();
			await refund();
			return error(502, "starter_upstream_error", "Starter request was cancelled.");
		}
		let response: Response;
		try {
			const openRouter = new URL(upstream.url).hostname === "openrouter.ai";
			let routing: Record<string, unknown> = {};
			if (upstream.name === "primary" && openRouter) {
				routing = { provider: { only: ["fireworks", "deepinfra", "modelrun", "coreweave"], data_collection: "deny", zdr: true,
					max_price: { prompt: upstream.input, completion: upstream.output } } };
				if (env.STARTER_PROVIDER) {
					try {
						const provider = JSON.parse(env.STARTER_PROVIDER);
						if (provider && typeof provider === "object" && !Array.isArray(provider)) routing = { provider };
					} catch { /* Keep the default privacy routing for invalid JSON. */ }
				}
			}
			response = await fetch(upstream.url, { method: "POST", headers: {
				"Authorization": `Bearer ${upstream.key}`, "Content-Type": "application/json",
				...(openRouter ? { "HTTP-Referer": "https://jmeter.ai", "X-Title": "Feather Wand" } : {}),
			}, body: JSON.stringify({ ...body, ...routing, model: upstream.model }), signal: abort.signal });
		} catch {
			finishAttempt();
			await record(null);
			if (!request.signal.aborted && i + 1 < upstreams.length) continue;
			await refund();
			return error(502, "starter_upstream_error", "Starter provider is unavailable. Please try again.");
		}
		if (!response.ok) {
			let usage;
			try { usage = (await response.json() as any)?.usage; } catch { /* No usage in provider error. */ }
			finishAttempt();
			await record(usage);
			if (([401, 402, 403, 429].includes(response.status) || response.status >= 500) && i + 1 < upstreams.length) continue;
			await refund();
			return error(502, "starter_upstream_error", "Starter provider rejected the request. Please try again or connect your own provider.");
		}
		if (!body.stream) {
			let result: any;
			try { result = await response.json(); } catch {
				finishAttempt();
				await record(null); await refund();
				return error(502, "starter_upstream_error", "Invalid provider response.");
			}
			finishAttempt();
			await record(result?.usage);
			if (result?.error || typeof result?.choices?.[0]?.message?.content !== "string"
				|| !result.choices[0].message.content.trim()) {
				await refund();
				return error(502, "starter_upstream_error", "Empty or invalid provider response.");
			}
			return new Response(JSON.stringify(result), { headers: { ...headers(), "Content-Type": "application/json" } });
		}
		if (!response.body) { finishAttempt(); await record(null); await refund(); return error(502, "starter_upstream_error", "Empty provider stream."); }
		const [client, accounting] = response.body.tee();
		let answered = false;
		let ready!: () => void;
		const answerOrEnd = new Promise<void>(resolve => { ready = resolve; });
		const accounted = (async () => {
			let usage: unknown;
			const reader = accounting.pipeThrough(new TextDecoderStream()).getReader();
			let pending = "";
			const consume = (line: string) => {
				if (!line.startsWith("data:")) return;
				try {
					const data = JSON.parse(line.slice(5));
					if (data.usage) usage = data.usage;
					if (Array.isArray(data.choices) && data.choices.some((choice: any) =>
						typeof choice?.delta?.content === "string" && choice.delta.content.trim().length > 0)) {
						answered = true;
						ready();
					}
				} catch { /* SSE sentinel or non-JSON event. */ }
			};
			try {
				while (true) {
					const { value, done } = await reader.read();
					if (done) break;
					pending += value;
					let newline;
					while ((newline = pending.indexOf("\n")) >= 0) {
						const line = pending.slice(0, newline).trim(); pending = pending.slice(newline + 1);
						consume(line);
					}
					if (pending.length > 262144) pending = "";
				}
				consume(pending.trim());
			} catch { /* The client receives the upstream stream error. */ }
			finally {
				finishAttempt();
				try { await record(usage); }
				finally {
					try { if (!answered) await refund(); }
					finally { ready(); }
				}
			}
		})();
		ctx.waitUntil(accounted);
		await answerOrEnd;
		if (!answered) {
			void client.cancel().catch(() => {});
			return error(502, "starter_upstream_error", "Empty provider stream.");
		}
		return new Response(client, { headers: { ...headers(), "Content-Type": "text/event-stream", "Cache-Control": "no-cache" } });
	}
	await refund();
	return error(503, "starter_budget_exhausted", "Starter monthly budget exhausted. Connect your own provider.");
}
