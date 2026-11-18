import { env, SELF, createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
import { beforeEach, afterEach, describe, it, expect, vi } from "vitest";
import { handleStarter } from "../src/starter";
import { UUID_RE } from "../src/index";

const UUID = "3f6b9f2e-9c1a-4d2b-8e5f-1a2b3c4d5e6f";
let ip: string;
function chat(body: unknown = { messages: [{ role: "user", content: "Hello" }] }, id = UUID, address = ip) {
	return SELF.fetch("https://example.com/v1/starter/chat/completions", {
		method: "POST", headers: { Authorization: `Bearer ${id}`, "CF-Connecting-IP": address }, body: JSON.stringify(body),
	});
}
function completion() {
	return new Response(JSON.stringify({ choices: [{ message: { role: "assistant", content: "Hi" } }], usage: { prompt_tokens: 100, completion_tokens: 50 } }));
}
beforeEach(async () => {
	await env.DB.exec("DELETE FROM starter_messages; DELETE FROM starter_usage;");
	env.STARTER_API_KEY = "test-primary";
	env.STARTER_FALLBACK_API_KEY = "";
	env.STARTER_MONTHLY_BUDGET_USD = "50";
	env.STARTER_INPUT_USD_PER_MTOK = "0";
	env.STARTER_OUTPUT_USD_PER_MTOK = "0";
	env.STARTER_PROVIDER = "";
	env.STARTER_UPSTREAM_URL = "https://openrouter.ai/api/v1/chat/completions";
	ip = crypto.randomUUID();
});
afterEach(() => vi.restoreAllMocks());

describe("starter chat", () => {
	it("rejects invalid authentication", async () => {
		const spy = vi.spyOn(globalThis, "fetch");
		const res = await chat(undefined, "not-a-uuid");
		expect(res.status).toBe(401);
		expect(await res.json()).toMatchObject({ error: { message: "A Feather Wand starter ID is required." } });
		expect(res.headers.get("X-Starter-Limit")).toBe("10");
		expect(spy).not.toHaveBeenCalled();
	});
	it("allows ten messages then rejects the eleventh", async () => {
		vi.spyOn(globalThis, "fetch").mockImplementation(async () => completion());
		for (let i = 0; i < 10; i++) {
			const res = await chat();
			expect(res.status).toBe(200);
			expect(res.headers.get("X-Starter-Remaining")).toBe(String(9 - i));
		}
		const res = await chat();
		expect(res.status).toBe(402);
		expect(await res.json()).toMatchObject({ error: { code: "starter_quota_exhausted" } });
	});
	it("enforces the lifetime shared IP quota", async () => {
		vi.spyOn(globalThis, "fetch").mockImplementation(async () => completion());
		for (let i = 0; i < 30; i++) expect((await chat(undefined, crypto.randomUUID())).status).toBe(200);
		expect((await chat(undefined, crypto.randomUUID())).status).toBe(402);
	});
	it.each([500, 429, 402, 401, 403])("refunds on upstream %s without a fallback", async status => {
		vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("failed", { status }));
		const res = await chat();
		expect(res.status).toBe(502);
		expect(res.headers.get("X-Starter-Remaining")).toBe("10");
		expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM starter_messages").first()).toEqual({ n: 0 });
	});
	it("refunds on network failure", async () => {
		vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("offline"));
		expect((await chat()).status).toBe(502);
		expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM starter_messages").first()).toEqual({ n: 0 });
	});
	it("strips tools, overrides model and clamps both token fields", async () => {
		const spy = vi.spyOn(globalThis, "fetch").mockImplementation(async () => completion());
		expect((await chat({ messages: [{ role: "user", content: "Hi" }], tools: [{}], tool_choice: "auto", functions: [{}], function_call: "auto", model: "expensive", max_tokens: 9000, max_completion_tokens: 4000 })).status).toBe(200);
		const init = spy.mock.calls[0][1]!;
		const body = JSON.parse(String(init.body));
		expect(body.model).toBe("nvidia/nemotron-3.5-lightning:free");
		expect(body.max_tokens).toBe(1024);
		expect(body.max_completion_tokens).toBe(1024);
		for (const key of ["tools", "tool_choice", "functions", "function_call"]) expect(body).not.toHaveProperty(key);
		expect(init.headers).toMatchObject({ "HTTP-Referer": "https://jmeter.ai", "X-Title": "Feather Wand" });
	});
	it.each([["0.06", "0.16"], ["0", "0.16"], ["0.06", "0"]])("blocks paid upstreams at exhausted monthly budgets (%s/%s)", async (input, output) => {
		env.STARTER_INPUT_USD_PER_MTOK = input;
		env.STARTER_OUTPUT_USD_PER_MTOK = output;
		env.STARTER_FALLBACK_API_KEY = "test-fallback";
		await env.DB.prepare("INSERT INTO starter_usage(month,install_id,upstream,input_tokens,output_tokens,cost_usd) VALUES (?,?,?,0,0,50)")
			.bind(new Date().toISOString().slice(0, 7), UUID, "fallback").run();
		const spy = vi.spyOn(globalThis, "fetch");
		const res = await chat();
		expect(res.status).toBe(503);
		expect(await res.json()).toMatchObject({ error: { code: "starter_budget_exhausted" } });
		expect(spy).not.toHaveBeenCalled();
	});
	it("keeps the free primary serving at the budget cap but excludes paid fallback", async () => {
		env.STARTER_MONTHLY_BUDGET_USD = "0";
		env.STARTER_FALLBACK_API_KEY = "test-fallback";
		const spy = vi.spyOn(globalThis, "fetch").mockImplementation(async () => completion());
		expect((await chat()).status).toBe(200);
		expect(spy).toHaveBeenCalledTimes(1);
		spy.mockResolvedValue(new Response("limited", { status: 429 }));
		expect((await chat()).status).toBe(502);
		expect(spy).toHaveBeenCalledTimes(2);
		expect(spy.mock.calls.every(([url]) => String(url).includes("openrouter.ai"))).toBe(true);
	});
	it("routes OpenRouter primary with configured provider JSON", async () => {
		const provider = { only: ["deepinfra"], data_collection: "deny" };
		env.STARTER_PROVIDER = JSON.stringify(provider);
		const spy = vi.spyOn(globalThis, "fetch").mockImplementation(async () => completion());
		expect((await chat()).status).toBe(200);
		expect(JSON.parse(String(spy.mock.calls[0][1]?.body)).provider).toEqual(provider);
	});
	it.each(["", "{invalid"]) ("ignores unset or invalid provider JSON (%s)", async provider => {
		env.STARTER_PROVIDER = provider;
		const spy = vi.spyOn(globalThis, "fetch").mockImplementation(async () => completion());
		expect((await chat()).status).toBe(200);
		expect(JSON.parse(String(spy.mock.calls[0][1]?.body))).not.toHaveProperty("provider");
	});
	it("does not send provider routing to other primary hosts", async () => {
		env.STARTER_PROVIDER = '{"only":["deepinfra"]}';
		env.STARTER_UPSTREAM_URL = "https://provider.example/chat/completions";
		const spy = vi.spyOn(globalThis, "fetch").mockImplementation(async () => completion());
		expect((await chat()).status).toBe(200);
		expect(JSON.parse(String(spy.mock.calls[0][1]?.body))).not.toHaveProperty("provider");
	});
	it("does not send primary provider routing to fallback", async () => {
		env.STARTER_API_KEY = "";
		env.STARTER_FALLBACK_API_KEY = "test-fallback";
		env.STARTER_PROVIDER = '{"only":["deepinfra"]}';
		const spy = vi.spyOn(globalThis, "fetch").mockImplementation(async () => completion());
		expect((await chat()).status).toBe(200);
		expect(JSON.parse(String(spy.mock.calls[0][1]?.body))).not.toHaveProperty("provider");
	});
	it("returns unavailable without either secret", async () => {
		env.STARTER_API_KEY = "";
		const res = await chat();
		expect(res.status).toBe(503);
		expect(await res.json()).toMatchObject({ error: { code: "starter_unavailable" } });
	});
	it("passes SSE through unchanged and records chunked usage", async () => {
		const sse = 'data: {"choices":[{"delta":{"content":"Hi"}}]}\n\ndata: {"usage":{"prompt_tokens":20,"completion_tokens":10}}\n\ndata: [DONE]\n\n';
		const bytes = new TextEncoder().encode(sse);
		const spy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(new ReadableStream({ start(c) {
			c.enqueue(bytes.slice(0, 42)); c.enqueue(bytes.slice(42)); c.close();
		} })));
		const res = await chat({ messages: [{ role: "user", content: "Hi" }], stream: true });
		expect(await res.text()).toBe(sse);
		expect(JSON.parse(String(spy.mock.calls[0][1]?.body)).stream_options).toEqual({ include_usage: true });
		await vi.waitFor(async () => expect(await env.DB.prepare("SELECT input_tokens,output_tokens FROM starter_usage").first()).toEqual({ input_tokens: 20, output_tokens: 10 }));
		expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM starter_messages").first()).toEqual({ n: 1 });
	});
	it.each(["", 'data: {"choices":[{"delta":{"role":"assistant","content":""}}]}\n\ndata: [DONE]\n\n'])
	("refunds a successful stream with no answer content", async sse => {
		vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(sse));
		const ctx = createExecutionContext();
		const res = await handleStarter(new Request("https://example.com/v1/starter/chat/completions", {
			method: "POST", headers: { Authorization: `Bearer ${UUID}`, "CF-Connecting-IP": ip },
			body: JSON.stringify({ messages: [{ role: "user", content: "Hi" }], stream: true }),
		}), env, ctx, UUID_RE);
		expect(res.status).toBe(200);
		expect(await res.text()).toBe(sse);
		await waitOnExecutionContext(ctx);
		expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM starter_messages").first()).toEqual({ n: 0 });
		expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM starter_usage").first()).toEqual({ n: 1 });
	});
	it.each([false, true])("refunds a failed stream only before an answer (answered=%s)", async answered => {
		let controller: ReadableStreamDefaultController<Uint8Array>;
		const stream = new ReadableStream<Uint8Array>({ start(c) { controller = c; } });
		vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(stream));
		const ctx = createExecutionContext();
		const res = await handleStarter(new Request("https://example.com/v1/starter/chat/completions", {
			method: "POST", headers: { Authorization: `Bearer ${UUID}`, "CF-Connecting-IP": ip },
			body: JSON.stringify({ messages: [{ role: "user", content: "Hi" }], stream: true }),
		}), env, ctx, UUID_RE);
		const reader = res.body!.getReader();
		controller!.enqueue(new TextEncoder().encode(`data: ${JSON.stringify({ choices: [{ delta: { content: answered ? "Hi" : "" } }], usage: { prompt_tokens: 20, completion_tokens: 10 } })}\n\n`));
		await reader.read();
		controller!.error(new Error("upstream disconnected"));
		await expect(reader.read()).rejects.toThrow();
		await waitOnExecutionContext(ctx);
		expect(await env.DB.prepare("SELECT input_tokens,output_tokens FROM starter_usage").first()).toEqual({ input_tokens: 20, output_tokens: 10 });
		expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM starter_messages").first()).toEqual({ n: answered ? 1 : 0 });
	});
	it.each([429, 401, 403])("falls back on %s and prices each attempt at its own rates", async status => {
		env.STARTER_FALLBACK_API_KEY = "test-fallback";
		env.STARTER_INPUT_USD_PER_MTOK = "1";
		env.STARTER_OUTPUT_USD_PER_MTOK = "2";
		const spy = vi.spyOn(globalThis, "fetch")
			.mockResolvedValueOnce(new Response(JSON.stringify({ usage: { prompt_tokens: 10, completion_tokens: 5 } }), { status }))
			.mockImplementation(async () => completion());
		const res = await chat();
		expect(res.status).toBe(200);
		expect(await res.json()).toMatchObject({ choices: [{ message: { content: "Hi" } }] });
		expect(spy).toHaveBeenCalledTimes(2);
		expect(String(spy.mock.calls[1][0])).toContain("generativelanguage.googleapis.com");
		expect(JSON.parse(String(spy.mock.calls[1][1]?.body)).model).toBe("gemini-2.5-flash-lite");
		const rows = (await env.DB.prepare("SELECT upstream,cost_usd FROM starter_usage ORDER BY id").all()).results;
		expect(rows[0].upstream).toBe("primary"); expect(rows[0].cost_usd).toBeCloseTo(0.00002);
		expect(rows[1].upstream).toBe("fallback"); expect(rows[1].cost_usd).toBeCloseTo(0.00003);
		expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM starter_messages").first()).toEqual({ n: 1 });
	});
	it("uses fallback alone when only its secret is set", async () => {
		env.STARTER_API_KEY = ""; env.STARTER_FALLBACK_API_KEY = "test-fallback";
		const spy = vi.spyOn(globalThis, "fetch").mockImplementation(async () => completion());
		expect((await chat()).status).toBe(200);
		expect(String(spy.mock.calls[0][0])).toContain("generativelanguage.googleapis.com");
	});
	it.each([{}, { messages: [] }, { messages: Array(101).fill({ role: "user", content: "hi" }) }, { messages: [{ role: "user", content: "x".repeat(65536) }] }])("rejects invalid or oversized chats", async body => {
		expect((await chat(body)).status).toBe(400);
	});
	it("rate limits bursts", async () => {
		env.STARTER_API_KEY = "";
		for (let i = 0; i < 60; i++) await chat();
		expect((await chat()).status).toBe(429);
	});
});
