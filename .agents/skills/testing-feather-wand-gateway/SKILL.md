---
name: testing-feather-wand-gateway
description: Run Feather Wand GUI end-to-end against a local OpenAI-compatible gateway stub and capture HTTP request counts and token usage.
---

# Feather Wand gateway GUI testing

## Devin Secrets Needed
None for local stub testing. Use a non-secret dummy OpenAI API key, never a real key, when pointing at a local stub.

## Setup
- Build the requested revision with `mvn -q -DskipTests package`.
- Locate a JMeter installation; this environment has `/home/ubuntu/apache-jmeter-5.6.3`.
- Copy only the freshly built `target/jmeter-agent-*-jar-with-dependencies.jar` into `lib/ext`, removing duplicate older plugin versions if present.
- Configure `openai.api.key`, `openai.base.url=http://127.0.0.1:<port>/v1`, `openai.models=gpt-4o`, `openai.default.model=gpt-4o`, and `jmeter.ai.agent.enabled=true`.
- Load the dedicated properties file explicitly: `bin/jmeter -q /absolute/path/to/jmeter-ai.properties -j /absolute/path/to/evidence/jmeter.log`. A file named `jmeter-ai.properties` is not automatically loaded just because it resides in `bin`.
- Retry clients are constructed from properties, so restart JMeter after changing `openai.max.retries`.
- Keep streaming enabled when validating the default plain-chat path; agent completions use normal JSON while plain chat uses SSE.
- Unconfigured other providers may log model-discovery authentication errors. Distinguish those startup errors from the selected local OpenAI completion requests.

## GUI
- Activate JMeter and maximize with `wmctrl -r 'Apache JMeter' -b add,maximized_vert,maximized_horz`.
- Use Ctrl+Shift+A to show Feather Wand; wait for model discovery and ensure gpt-4o is selected.
- Drag the split divider left to make response text readable.
- Chat is selected by default even when agent functionality is enabled. Explicitly click the Agent radio before agent tests.
- Type in the bottom composer and click the upward-arrow Send button once.

## Evidence
- Stub should record every completion POST with timestamp, path, stream flag and tool count, but never authorization headers.
- Count physical HTTP requests per click rather than inferring attempts from SDK property names.
- Explicitly send `Connection: close` from a Python HTTP stub (or implement correct persistent HTTP/1.1 framing). Otherwise connection reuse can consume SDK attempts without reaching the handler. Log `X-Stainless-Retry-Count`; default OpenAI retries should arrive as `0`, `1`, `2` on a healthy transport.
- Agent requests have tool definitions; ordinary chat requests do not. This distinguishes a retry from an unwanted plain-chat fallback.
- Return a deterministic JSON usage object and verify exact INFO-level `Agent token usage` prompt/completion/run-total values in the chosen JMeter log.
- Assert errors from screenshots, not only logs: callbacks may receive an informative exception yet render a generic UI message.
- Use annotated GUI recording and keep request JSONL/JMeter logs as supporting evidence. Do not record idle desktop while running shell-only setup.
