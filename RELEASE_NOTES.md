## Feather Wand - JMeter AI Agent v4.0.1

### What's new

- **Feather Wand Free.** Start chatting in JMeter without an account or API key. Free is selected by default when no other chat provider is configured, shows your remaining allowance, and is chat-only; connect a supported provider for Agent Mode. (#260, #262)
- **First-run provider setup.** When no provider is ready on first use, Feather Wand opens **Connect a provider** from the welcome screen after checking for existing providers. Choose Free or connect your own provider without restarting JMeter. (#257, #258)
- **OpenCode V2.** OpenCode chat supports the V2 CLI while keeping compatibility with V1. (#252, #254)

### Improvements and fixes

- **Agent Mode.** Edits preserve literal HTTP request bodies and use JMeter's native property types. (#267, #269)
- **Agent Mode usage.** Per-turn request sizes and token usage are reported in the JMeter log and Make it pass reports. (#271)
- **Agent Mode efficiency.** Stable Claude prompts use prompt caching on supported Anthropic endpoints, and an unchanged large test tree is replaced by a short notice instead of being resent on later turns of the same run. (#272, #273)
- **Telemetry dashboard.** The redesigned adoption table includes an **Other** column for less common plugin versions. (#237)

[Full comparison with v4.0.0](https://github.com/QAInsights/jmeter-ai/compare/v4.0.0...v4.0.1)

<!--
Publishing checklist (not part of the GitHub release body):
- Without #259, publish the self-contained jmeter-agent-4.0.1.jar; the registry needs no libs field.
- If #259 merges first, rebuild and test on the merged tree. Publish jmeter-agent-4.0.1.jar (thin, for lib/ext), zzz-jmeter-agent-deps-4.0.1.jar (for lib), and jmeter-agent-4.0.1-all.jar (self-contained, for manual installs). Keep the registry 4.0.1 downloadUrl and add this libs field to that version only:
  "libs": {"zzz-jmeter-agent-deps>=4.0.1": "https://github.com/QAInsights/jmeter-ai/releases/download/v4.0.1/zzz-jmeter-agent-deps-4.0.1.jar"}
-->
