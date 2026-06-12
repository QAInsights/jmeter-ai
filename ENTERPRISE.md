# Feather Wand for the Enterprise

This guide ties together a set of changes that make Feather Wand's AI terminal
safe, governable, and automatable for teams — turning a developer convenience
into something a platform/performance org can roll out at scale. It covers the
**AWS Kiro** integration plus governance, CI/CD, and performance-engineering
capabilities built on top of the existing adapter architecture.

> Audience: platform engineers, performance leads, and security reviewers
> evaluating Feather Wand for organisation-wide use.

---

## 1. The starting point

Feather Wand's embedded terminal auto-detects AI CLIs on `PATH` (Claude Code,
Codex, OpenCode, Copilot, Antigravity). Engineers standardised on **AWS Kiro**
hit:

> NO AI CLIs were detected on your PATH

because Kiro ships as `kiro-cli` (installed to `%USERPROFILE%\.kiro\bin` on
Windows) — a name and location the detector never checked. Fixing that opened
the door to a broader question: *what would it take to run AI-assisted
performance testing across a whole organisation, safely?* This document is the
answer.

---

## 2. What was added, at a glance

| # | Capability | Why it matters to an enterprise | PR |
|---|------------|---------------------------------|----|
| 1 | **AWS Kiro adapter** | Teams on Kiro can use the terminal at all; robust detection survives the Windows "stale PATH" trap | #61 |
| 2 | **Secret redaction** | Test plans carry passwords/tokens/PII; these are masked before any context leaves JMeter | #62 |
| 3 | **Governed tool-trust** | The agent is read-only by default; write/exec requires an explicit, admin-controllable opt-in | #62 |
| 4 | **Audit logging** | "Who sent what to which AI, and when" — answerable for security & compliance | #63 |
| 5 | **Headless / CI mode** | Run AI analysis in pipelines and `jmeter -n`, not just the GUI | #64 |
| 6 | **MCP wiring** | The agent runs tests and parses JTLs through tools instead of free text | #65 |
| 7 | **Spec/HAR → JMX generator** | Generate test plans from OpenAPI or a HAR capture, then let the AI refine them | #66 |
| 8 | **Managed/remote config** | A platform team controls policy org-wide instead of per-machine `user.properties` | #67 |
| 9 | **Multi-CLI consensus** | High-stakes analysis cross-checked across two models | #68 |
| 10 | **Correlation autopilot** | Auto-detect dynamic tokens (CSRF/session) from a recording — the most tedious scripting task | #69 |

(Ten capabilities across nine PRs; #62 delivers both redaction and tool-trust.)

The PRs are **stacked** and intended to merge in order
**#61 → #62 → #63 → #64 → #65 → #66 → #67 → #68 → #69**.

---

## 3. Security & governance model

Three independent controls, all **on by default** and centrally lockable:

### Context redaction (`SecretRedactor`)
Before any test-plan context is written to `CLAUDE.md` / `AGENTS.md` /
`KIRO.md`, secrets are masked: credential-like keys (`*.password`, `api_key`,
`authorization`, …), `Bearer` tokens, and JWTs. The content never leaves JMeter
in the clear.

```properties
jmeter.ai.security.redaction.enabled=true          # default on
jmeter.ai.security.redaction.extra_keys=pin,otp,ssn # extend the key list
```

### Tool-trust policy (Kiro)
The agent launches read-only by default — it cannot mutate the test plan or
filesystem unless an operator opts in.

```properties
jmeter.ai.terminal.kiro.trust_tools=read,grep,fs_read  # default
jmeter.ai.terminal.kiro.trust_all_tools=false          # set true only for trusted operators
```

### Audit trail (`AuditLogger`)
Every AI CLI launch appends one JSON line capturing the actor, host, CLI,
command flags (no secret values), working dir, redaction state, and a
**SHA-256 of the shared context** — proving what was sent without storing it.

```properties
jmeter.ai.security.audit.enabled=true
jmeter.ai.security.audit.file=    # default <JMETER_HOME>/logs/jmeter-ai-audit.log
```

Example entry:
```json
{"timestamp":"2026-06-12T15:04:05Z","event":"ai_cli_launch","user":"jdoe",
 "host":"WIN-PERF01","cli":"AWS Kiro","command":"... chat --trust-tools=read,grep,fs_read",
 "redactionEnabled":"true","contextSha256":"<64-hex>","contextBytes":"2048"}
```

### Central enforcement (`ManagedConfigLoader`)
A platform team publishes a managed `.properties` file; every install pulls it
at startup and (by default) lets it **override** local settings, so policy can't
be undone on individual machines.

```properties
jmeter.ai.config.remote.url=https://platform.example.com/jmeter-ai.properties
jmeter.ai.config.remote.override=true   # managed values win
```

> Recommended baseline for a regulated environment: ship a managed config that
> sets redaction on, `trust_all_tools=false`, audit on with a centralised file,
> and an allow-list of enabled CLIs.

---

## 4. Automation & CI/CD

All of the following run **without the GUI** via `HeadlessAiRunner`
(`run-ai-headless.sh` / `.bat`). Governance from §3 applies to every mode.

```bash
# Analyze results and gate the build
./run-ai-headless.sh --jmx plan.jmx \
  --prompt "Analyze the attached JTL; fail if p95 regressed >10%" \
  --output report.md --fail-on-error

# Generate a plan from an API spec or a recording, then refine it
./run-ai-headless.sh --generate-from openapi.json --generate-out plan.jmx \
  --prompt "Add response assertions and realistic think-times"

# Find dynamic values to correlate, from a recording
./run-ai-headless.sh --correlate-from recording.har --output correlation.md

# Cross-check a high-stakes conclusion across two models
./run-ai-headless.sh --jmx plan.jmx --consensus --clis kiro,claude \
  --prompt "Explain the p99 spike" --output consensus.md
```

Exit codes are CI-friendly: `0` success, `2` usage error, `3` CLI not found,
`4` no headless mode, `124` timeout; `--fail-on-error` propagates the CLI's exit
code so a pipeline step fails when the analysis fails.

Reports are **structured artifacts** (Markdown or JSON via `--format`) meant to
be archived by the build and reviewed in a pull request.

---

## 5. Performance-engineering capabilities

- **Spec/HAR → JMX generation** — `OpenAPI 3` / `Swagger 2` / HAR → a valid
  Test Plan → Thread Group → HTTP Samplers (+ Header Managers). Output opens
  directly in JMeter; pair with `--prompt` to have the AI add assertions,
  think-times, and config.
- **MCP-driven execution** — wires the
  [JMeter MCP server](https://github.com/QAInsights/jmeter-mcp-server) into the
  working directory (`.kiro/settings/mcp.json` for Kiro) so the agent runs tests
  and parses JTLs through tools, not guesses.
- **Correlation autopilot** — detects tokens returned in a JSON response and
  replayed in a later request, and suggests `$..key` extractors with variable
  names. Complements the live-run `CorrelationEngine` with an offline,
  recording-based path.
- **Multi-CLI consensus** — runs the same prompt across CLIs and reports a
  deterministic agreement score (pairwise Jaccard) so divergence is visible
  before you act on a single model's conclusion.

---

## 6. Configuration reference

| Property | Default | Purpose |
|----------|---------|---------|
| `jmeter.ai.terminal.kiro.enabled` | `true` | Enable the Kiro adapter |
| `jmeter.ai.terminal.kiro.path` | _(auto)_ | Pin the `kiro-cli` binary |
| `jmeter.ai.terminal.kiro.trust_tools` | `read,grep,fs_read` | Tools trusted without prompting |
| `jmeter.ai.terminal.kiro.trust_all_tools` | `false` | Trust everything (opt-in) |
| `jmeter.ai.security.redaction.enabled` | `true` | Mask secrets in shared context |
| `jmeter.ai.security.redaction.extra_keys` | _(empty)_ | Extra secret key fragments |
| `jmeter.ai.security.audit.enabled` | `true` | Append-only audit log |
| `jmeter.ai.security.audit.file` | _(JMETER_HOME/logs)_ | Audit log destination |
| `jmeter.ai.config.remote.url` / `.file` | _(empty)_ | Managed config source |
| `jmeter.ai.config.remote.override` | `true` | Managed values win over local |
| `jmeter.ai.mcp.enabled` | `true` | Write MCP config when configured |
| `jmeter.ai.mcp.jmeter.dir` | _(empty)_ | jmeter-mcp-server checkout (enables it) |
| `jmeter.ai.mcp.jmeter.command` | `uv` | Launcher for the MCP server |
| `jmeter.ai.mcp.jmeter.autoApprove` | _(empty)_ | MCP tools to auto-approve |

See `jmeter-ai-sample.properties` for the annotated, copy-pasteable version.

---

## 7. Architecture notes

These changes follow the existing **adapter pattern** to stay low-risk and
extensible:

- `AiCliAdapter → BaseCliAdapter → {ClaudeCodeCliAdapter, KiroCliAdapter, …}`.
  New capabilities are added as overridable methods on `BaseCliAdapter`
  (`supportsHeadless`, `buildHeadlessCommand`, `supportsMcp`,
  `mcpConfigRelativePath`) with safe defaults, so existing adapters are
  unaffected.
- Orchestration is built around a `ProcessRunner` seam, so the headless runner,
  consensus, and exit-code logic are unit-tested **without launching real
  binaries**.
- Generation and correlation are pure, deterministic, dependency-light
  (`jackson-databind` only) and validated by tests (e.g. generated JMX is parsed
  back through a DOM builder to prove well-formedness).

To add another headless-capable CLI: implement `buildHeadlessCommand` /
`supportsHeadless` on its adapter and register it in
`HeadlessAiRunner.resolveAdapter` — consensus and CI modes pick it up
automatically.

---

## 8. Suggested rollout

1. **Pilot** — install the plugin + Kiro on a few machines; verify detection and
   a headless run end-to-end.
2. **Lock policy** — publish a managed config (`jmeter.ai.config.remote.url`)
   enforcing redaction on, `trust_all_tools=false`, and centralised audit.
3. **Wire CI** — add `run-ai-headless.sh` steps for plan generation, correlation,
   and results analysis with `--fail-on-error`.
4. **Enable MCP** (optional) — point `jmeter.ai.mcp.jmeter.dir` at a shared
   jmeter-mcp-server so the agent can execute tests.
5. **Review the audit log** — confirm entries are captured where your compliance
   tooling can collect them.
