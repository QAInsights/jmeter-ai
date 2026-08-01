# UI/UX Polish - Task Tracker

Source of truth: `.devin` plan (see `plan-15735a764d391cbd.md`). Scope confirmed by user: chat panel + all dialogs, pure Swing (no new deps), styled-turn transcript (no bubble rewrite), phased delivery. All changes must work in both JMeter light and dark themes.

Rules: every new class/method ships with a unit test; keep files under 400-450 lines; never change implementation to fix a failing test.

---

## Phase 1 - Quick Wins

| # | Task | Files | Status |
|---|------|-------|--------|
| QW1a | Create `gui/theme/ThemeColors.java` (semantic, luminance-aware palette) | `src/main/java/org/qainsights/jmeter/ai/gui/theme/ThemeColors.java` | DONE |
| QW1b | ThemeColors unit tests | `src/test/java/org/qainsights/jmeter/ai/gui/theme/ThemeColorsTest.java` | DONE (9/9 pass) |
| QW1c | Replace hardcoded colors in AiChatPanel (`Color.RED` x5, `Color.BLUE`, `LIGHT_GRAY` fallbacks) | `gui/AiChatPanel.java` | DONE |
| QW1d | Replace hardcoded colors in CorrelationReviewDialog (`BLACK`, `RED`, `new Color(0,100,0)` x2) | `correlation/CorrelationReviewDialog.java` | DONE |
| QW1e | Replace hardcoded colors in RecordingControlPanel (`BLUE`, `DARK_GRAY`, `new Color(0,150,0)`) | `record/RecordingControlPanel.java` | DONE |
| QW1f | IntellisensePopup border fallback -> ThemeColors.border(); MessageProcessor.getCodeBlockBackground -> delegate to ThemeColors.codeBackground() | `intellisense/IntellisensePopup.java`, `gui/MessageProcessor.java` | DONE |
| QW2a | GeminiBorderPanel: re-read `TextArea.background` on updateUI(), don't cache at construction (+test update) | `gui/GeminiBorderPanel.java` | DONE |
| QW2b | AiChatPanel.refreshChatColors: also refresh messageField/composer colors on theme switch | `gui/AiChatPanel.java` | DONE |
| QW4 | ThinkingIndicator class: offset-tracked animated dots, replace `"AI is thinking..."` string-search hack (+test) | NEW `gui/ThinkingIndicator.java` | DONE |
| QW8 | ChatScroller: smart auto-scroll only when user is at bottom; apply in appendStreamToken/processAiResponse/onStreamComplete (+test) | NEW `gui/ChatScroller.java` | DONE |
| QW3 | PlaceholderTextArea: ghost hint text in message field + `Enter to send` hint label (+test) | NEW `gui/PlaceholderTextArea.java` | DONE |
| QW5 | ModelDisplayRenderer: friendly `model - Provider` display names; value semantics unchanged (+test) | NEW `gui/ModelDisplayRenderer.java` | DONE |
| QW6 | Replace 70px "Navigation" titled-border block with slim toolbar row | `gui/AiChatPanel.java` (createToolbarRow) | DONE |
| QW7 | Button polish: hand cursor, hover background, consistent padding (createStyledButton, TreeNavigationButtons) | `gui/AiChatPanel.java`, `gui/TreeNavigationButtons.java` | DONE |
| QW9 | Welcome message onboarding copy refresh | `utils/Constants.java` | DONE |
| **V1** | **Phase 1 VERIFY: `mvn test` (1312 pass, 2 skipped) + `mvn install` -> jar copied to JMeter lib/ext** | - | DONE (2026-08-01) |

## Phase 2 - Medium Effort

| # | Task | Files | Status |
|---|------|-------|--------|
| M1 | Styled chat turns: sender headers (You / Feather Wand), accent color, spacing (+test) | `gui/MessageProcessor.java` / `gui/AiChatPanel.java` | DONE |
| M2 | Agent tool-activity rows: `appendToolActivity(String)` on CommandCallback (default -> appendMessageToChat), styled secondary/mono status lines (+test) | `gui/CommandCallback.java`, `gui/AiChatPanel.java`, `gui/CommandDispatcher.java` | DONE |
| M4 | Code block panel restyle: theme border, header bar w/ language + Copy, mono font | NEW `gui/CodeBlockRenderer.java` (extracted) | DONE |
| M5 | SwingToolConfirmationGate: structured Allow/Deny dialog (+test) | `agent/jmeter/SwingToolConfirmationGate.java` | DONE |
| M7 | IntellisensePopup two-line renderer: command bold + description secondary (+test) | `intellisense/IntellisensePopup.java`, `intellisense/CommandIntellisenseProvider.java` | DONE |
| M3a | Characterization tests for existing MessageProcessor markdown behavior (BEFORE changes) | `src/test/.../gui/MessageProcessorTest.java` | DONE (existing suite served as characterization) |
| M3b | Markdown upgrades: list bullets, horizontal rules, styled links (+tests) | NEW `gui/MarkdownRenderer.java` (extracted from MessageProcessor) | DONE |
| M6 | Correlation Studio + Record dialogs: gridless table, spacing, default buttons | `correlation/CorrelationReviewDialog.java`, `record/RecordingConfigDialog.java` | DONE |
| **V2** | **Phase 2 VERIFY: `mvn test` (1325 pass, 2 skipped) + `mvn install` -> jar copied to JMeter lib/ext** | - | DONE (2026-08-01) |

## Final

| # | Task | Status |
|---|------|--------|
| F1 | Update plan file with completion status | TODO |
| F2 | Manual smoke checklist for user: JMeter 5.6.3 light+dark, live theme switch, streaming, @commands, agent tool calls, code-block copy, CJK/IME input | TODO |

---

## Do NOT touch (regression guardrails)

- `service/` AI providers, `agent/loop`, `agent/tool` logic
- `claudecode/` terminal (intentionally always-dark chrome)
- IME/Enter key handling in messageField (CJK input)
- Streaming re-render offset logic in `onStreamComplete` (except QW4/QW8 additive changes)
- Donate button brand orange (intentional)
