<!--
================================================================================
EXAMPLE: How a typical famous repo README looks like
================================================================================

<div align="center">

  <img src="logo.png" alt="Logo" width="120">

  # Project Name

  [![Build](https://img.shields.io/badge/build-passing-brightgreen)]()
  [![Version](https://img.shields.io/badge/version-1.0.0-blue)]()
  [![Downloads](https://img.shields.io/badge/downloads-1M+-orange)]()
  [![License](https://img.shields.io/badge/license-MIT-green)]()
  [![Stars](https://img.shields.io/badge/stars-10k+-yellow)]()

  ### One-line tagline that hooks the reader instantly

  [Getting Started](#getting-started) · [Features](#features) · [Docs]() · [Changelog]()

  <img src="demo.gif" width="700" alt="Demo">

</div>

---

## Contents
- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Contributing](#contributing)
- [License](#license)

================================================================================
-->

<div align="center">

# 🪶 Feather Wand

**AI-powered assistant for Apache JMeter**

[![Release](https://img.shields.io/github/v/release/QAInsights/jmeter-ai?logo=github&style=flat-square)](https://github.com/QAInsights/jmeter-ai/releases)
[![PerfAtlas](https://img.shields.io/badge/PerfAtlas-View_Plugin-b0d600?logo=apachejmeter&logoColor=white&style=flat-square)](https://plugins.jmeter.ai/plugin/feather-wand-jmeter-ai-agent/)
[![License](https://img.shields.io/github/license/QAInsights/jmeter-ai?style=flat-square)](LICENSE)
[![Stars](https://img.shields.io/github/stars/QAInsights/jmeter-ai?style=flat-square&logo=github)](https://github.com/QAInsights/jmeter-ai)

[Features](#-features) · [Install](#-installation) · [Configure](#-configuration) · [Commands](#-special-commands) · [Changelog](https://github.com/QAInsights/jmeter-ai/releases)

</div>

> 🪄 **Why "Feather Wand"?** My kids named it after a *Bluey* episode, where a simple feather becomes a magical wand that turns the ordinary into something special. That's exactly what this plugin does for your JMeter workflow.

<div align="center">

<img src="./images/Feather-Wand-AI-Agent-JMeter.png" alt="Feather Wand Chat UI" width="700">

</div>

---

## 📑 Contents

- [Features](#-features)
- [Installation](#-installation)
- [Configuration](#-configuration)
- [Special Commands](#-special-commands)
- [Streaming](#-streaming-ai-responses)
- [Response Chime](#-response-chime)
- [AI CLI Terminal](#-multi-ai-cli-terminal)
- [API Setup](#-api-configuration)
- [Roadmap & Issues](#-report-issues)
- [Disclaimer](#-disclaimer-and-best-practices)

---

## ✨ Features

| | |
|:---|:---|
| 🤖 **Multi-Model Chat** | Talk to Claude, OpenAI, Google Gemini, DeepSeek, or Ollama — all inside JMeter. |
| ⚡ **Real-Time Streaming** | Watch AI responses appear token-by-token with a **Stop** button to cancel anytime. |
| 🖥️ **AI CLI Terminal** | Run **Claude Code**, **OpenAI Codex**, **OpenCode**, or **Antigravity** directly in JMeter. |
| 🧹 **Smart Refactoring** | Right-click in the JSR223 editor to refactor, format, or inject functions with AI. |
| 🔍 **Context-Aware Commands** | `@this`, `@optimize`, `@lint`, `@wrap`, `@code`, `@usage` — each tailored to your test plan. |
| 🔔 **Audio Chime** | Optional sound notification when AI finishes responding. |
| 🔧 **Model Filtering** | Only chat-compatible models appear in the dropdown — no audio/TTS clutter. |
| ⚙️ **Fully Configurable** | Customize prompts, temperature, tokens, history, timeouts, and more via JMeter properties. |

---

## 📥 Installation

### Plugins Manager *(Recommended)*

```text
1. Install JMeter Plugins Manager → https://jmeter-plugins.org/
2. Restart JMeter
3. Open Plugins Manager → Available Plugins
4. Search for "feather wand"
5. Select it → Apply Changes and Restart JMeter
```

### Manual Installation

```text
1. Download the latest JAR from Releases
2. Drop it into JMeter's lib/ext directory
3. Copy jmeter-ai-sample.properties into jmeter.properties (or user.properties)
4. Add your API key(s) and restart JMeter
```

See [Releases](https://github.com/QAInsights/jmeter-ai/releases) for the latest JAR.

## ⚙️ Configuration

Copy `jmeter-ai-sample.properties` into your `jmeter.properties` or `user.properties` and adjust the values below.

### General Settings

| Property | Description | Default |
|----------|-------------|---------|
| `jmeter.ai.streaming.enabled` | Stream AI responses token-by-token | `true` |
| `jmeter.ai.response.chime` | Play a chime when AI finishes | `false` |
| `jmeter.ai.refactoring.enabled` | Enable JSR223 editor AI refactoring | `true` |
| `jmeter.ai.service.type` | Default AI service for refactoring | `anthropic` |

### AI Service Settings

<details>
<summary><b>Anthropic (Claude)</b></summary>

| Property | Description | Default |
|----------|-------------|---------|
| `anthropic.api.key` | Claude API key | **Required** |
| `claude.default.model` | Default model | `claude-sonnet-4-6` |
| `claude.temperature` | Temperature (0.0-1.0) | `0.5` |
| `claude.max.tokens` | Max response tokens | `1024` |
| `claude.max.history.size` | Conversation history size | `10` |
| `claude.system.prompt` | System prompt | See sample file |
| `anthropic.log.level` | Logging (`info`/`debug`) | *(empty)* |

</details>

<details>
<summary><b>OpenAI</b></summary>

| Property | Description | Default |
|----------|-------------|---------|
| `openai.api.key` | OpenAI API key | **Required** |
| `openai.default.model` | Default model | `gpt-4o` |
| `openai.temperature` | Temperature (0.0-1.0) | `0.5` |
| `openai.max.tokens` | Max response tokens | `1024` |
| `openai.max.history.size` | Conversation history size | `10` |
| `openai.system.prompt` | System prompt | See sample file |
| `openai.log.level` | Logging (`INFO`/`DEBUG`) | *(empty)* |

</details>

<details>
<summary><b>Google Gemini</b></summary>

| Property | Description | Default |
|----------|-------------|---------|
| `google.api.key` | Google AI API key | **Required** |
| `google.default.model` | Default model | `gemini-2.5-flash` |
| `google.temperature` | Temperature (0.0-1.0) | `0.7` |
| `google.max.tokens` | Max response tokens | `4096` |
| `google.max.history.size` | Conversation history size | `10` |
| `google.system.prompt` | System prompt | See sample file |

</details>

<details>
<summary><b>Ollama (Local)</b></summary>

| Property | Description | Default |
|----------|-------------|---------|
| `ollama.host` | Server host | `http://localhost` |
| `ollama.port` | Server port | `11434` |
| `ollama.default.model` | Default model | `deepseek-r1:1.5b` |
| `ollama.temperature` | Temperature (0.0-1.0) | `0.5` |
| `ollama.max.history.size` | Conversation history size | `10` |
| `ollama.thinking.mode` | Extended thinking (`ENABLED`/`DISABLED`) | `DISABLED` |
| `ollama.thinking.level` | Thinking depth (`LOW`/`MEDIUM`/`HIGH`) | `MEDIUM` |
| `ollama.request.timeout.seconds` | Request timeout | `120` |
| `ollama.system.prompt` | System prompt | See sample file |

> ⚠️ If `ollama.thinking.mode=ENABLED`, raise `ollama.request.timeout.seconds` to at least `300`.

</details>

### AI CLI Terminal

| Property | Description | Default |
|----------|-------------|---------|
| `jmeter.ai.terminal.claudecode.enabled` | Enable the embedded terminal | `true` |
| `jmeter.ai.terminal.claudecode.path` | Full path to `claude` binary | *(auto-detect)* |
| `jmeter.ai.terminal.copilot.enabled` | Enable GitHub Copilot CLI | `false` |
| `jmeter.ai.terminal.copilot.path` | Full path to `copilot` binary | *(auto-detect)* |
| `jmeter.ai.terminal.antigravity.enabled` | Enable Antigravity CLI | `false` |

**Prerequisite CLIs**

| CLI | Binary | Install Guide |
|-----|--------|---------------|
| Claude Code | `claude` | [Docs](https://docs.anthropic.com/en/docs/claude-code) |
| OpenAI Codex | `codex` | [Repo](https://github.com/openai/codex) |
| GitHub Copilot | `copilot` | [Docs](https://docs.github.com/en/copilot/how-tos/copilot-cli/cli-getting-started) |
| OpenCode | `opencode` | [Repo](https://github.com/sst/opencode) |
| Antigravity | `agy` | [Site](https://www.antigravity.google/product/antigravity-cli) |

### Custom System Prompts

Each service supports its own `*.system.prompt` property — tweak them in your properties file to focus the AI on specific JMeter topics or team conventions.

## 🔍 Special Commands

Type any of these directly in the chat box. All commands are context-aware and work with the currently selected test-plan element.

| Command | What it does | Example |
|---------|--------------|---------|
| `@this` | Describe the selected element and suggest best practices. | `How do I configure @this?` |
| `@optimize` | Analyze the selected element and suggest performance tweaks. | `@optimize` or `optimize this sampler` |
| `@lint` | Auto-rename elements for consistency. Undo/redo supported. | `@lint rename elements in PascalCase` |
| `@wrap` | Group HTTP samplers under Transaction Controllers. | `@wrap` *(select a Thread Group first)* |
| `@code` | Extract the last AI code block into the JSR223 editor. | `@code` |
| `@usage` | Show token-usage stats and recent conversation history. | `@usage` |

### `@lint` Tips
- Run it after importing a recorded test plan to clean up generic names.
- Use it before sharing plans with your team.
- Apply custom rules: `@lint rename based on the URL`.

### `@wrap` Details
`@wrap` uses pattern matching (not AI) to group related HTTP samplers under Transaction Controllers, preserving child elements and hierarchy. Great for imported or recorded plans.

## 💨 Streaming AI Responses

All five AI services support real-time streaming out of the box. Responses appear token-by-token as they are generated.

| Control | What it does |
|---------|--------------|
| **Stop** | Appears next to the Send button during streaming — click to cancel mid-response. |

**Disable streaming:**

```properties
jmeter.ai.streaming.enabled=false
```

## 🔔 Response Chime

Get an audible cue when the AI finishes responding so you can multitask across windows.

```properties
jmeter.ai.response.chime=true
```

The bundled WAV plays from `src/main/resources/org/qainsights/jmeter/ai/sound/jmeter-chime.wav` with an MP3 fallback.

## 💻 Multi-AI CLI Terminal

An embedded interactive terminal (JediTerm) that brings agentic AI CLIs directly into JMeter.

**Supported CLIs:** Claude Code · OpenAI Codex · OpenCode · Antigravity

**How it works**
1. Install one or more CLIs on your `PATH`.
2. Feather Wand auto-detects them on startup.
3. Pick a CLI from the dropdown in the terminal header.
4. The terminal receives your open `.jmx` context via an auto-generated `CLAUDE.md`.
5. Use natural language to run tests, parse JTL files, refactor scripts, and more.

**Buttons**
- **Reload** — refresh the test plan from disk.
- **Ctx** — resend the current test-plan context.

**Architecture**
Built on an Adapter Pattern: `AiCliAdapter` → `BaseCliAdapter` → concrete adapters (`ClaudeCodeCliAdapter`, `OpenAiCodexCliAdapter`, ...). To add a new CLI, implement `AiCliAdapter` and register it in `detectAvailableClis()`.

> ⚠️ **Caution**: AI CLIs can execute commands and modify files. Review each CLI's documentation before enabling.

## 🗝️ API Configuration

### Quick Setup

| Provider | Steps | Property |
|----------|-------|----------|
| **Claude** | Sign up at [anthropic.com](https://www.anthropic.com/) → create API key | `anthropic.api.key` |
| **OpenAI** | Sign up at [platform.openai.com](https://platform.openai.com/) → create API key | `openai.api.key` |
| **Gemini** | Sign in at [Google AI Studio](https://aistudio.google.com/) → Get API Key | `google.api.key` |
| **Ollama** | Install from [ollama.com](https://ollama.com/) → `ollama pull llama3.1` | No key needed |

Set `jmeter.ai.service.type=ollama` to switch to a local model. All other providers work side-by-side; just pick the model from the UI dropdown.

### Model Filtering

Feather Wand automatically hides non-chat models so you only see useful options:

- **OpenAI** — hides audio, TTS, whisper, davinci, search, realtime, and instruct models.
- **Claude** — shows only the latest available models.
- **Gemini** — shows only `gemini-*` and `gemma-*` chat models.

Default models: `claude-sonnet-4-6` · `gpt-4o` · `gemini-2.5-flash` · `deepseek-chat` · `deepseek-r1:1.5b`

---

## 🪲 Report Issues

Found a bug or have an idea? [Open an issue](https://github.com/qainsights/jmeter-ai/issues).

## ⛳️ Roadmap

See what's next on the [project board](https://github.com/users/QAInsights/projects/12).

## ⚠️ Disclaimer

- **Verify everything** — AI can hallucinate. Double-check critical suggestions before production runs.
- **Backup first** — Save your `.jmx` before letting AI refactor it.
- **Test in staging** — Validate changes in a safe environment.
- **Watch costs** — Token usage adds up. Use `@usage` to keep an eye on it.
- **No secrets in chat** — Never paste credentials or proprietary code into the chat box.

Feather Wand is an assistant, not a replacement for engineering judgment.

