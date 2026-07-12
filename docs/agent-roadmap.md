# JMeter AI Agent — Roadmap & Remaining Tasks

Status snapshot and prioritized backlog for the agentic tool-calling feature
(`org.qainsights.jmeter.ai.agent.*`). Branch: `feature/jmeter-ai-agent`.

---

## 1. Current status (done)

**Architecture**

- Provider-neutral agent loop: `AgentLoop`, `AssistantTurn`, `ToolOutcome`, `ChatModel`.
- Claude integration: `ClaudeChatModel` + `ClaudeToolAdapter` (Anthropic SDK).
- Tool framework: `ToolSpec`/`ToolParameter`/`ToolResult`, `ToolRegistry`, `ToolExecutor`.
- Schema grounding: `SchemaGrounding` + curated `ElementPropertyCatalog`.
- EDT-safe tree mutations via `JMeterTreeMutator` + `EdtExecutor`.
- Stable tree-path element ids (`ElementIdResolver`); internal wrapper root excluded.

**Tools (15)**

- Read: `get_tree_state`, `get_element_config`, `get_element_children`, `get_element_schema`.
- Write: `add_element`, `update_element_property`, `set_property_list`, `set_structured_property_list`,
  `delete_element`, `toggle_element`, `move_element`, `duplicate_element`, `rename_element`.
- Run: `run_test`, `stop_test` - dispatch through the same `ActionRouter` path as JMeter's own Start/Stop/
  Shutdown toolbar buttons; fire-and-forget (see Phase C, C1).

`update_element_property`/`JMeterTreeMutator.updateProperty` refuse to overwrite a
property that is currently a `CollectionProperty`/`MapProperty`/`TestElementProperty`
(a list, map or nested element) with a plain string - doing so used to corrupt the
element (reproduced with a live `ResponseAssertion`'s `Asserion.test_strings`,
crashing `AssertionGui.configure()` on every subsequent open). `set_property_list`
is the safe way to write a *flat string-list* property in full (currently just
`ResponseAssertion`'s `Asserion.test_strings`); `set_structured_property_list` does
the same for *structured* lists (`HeaderManager.headers`, `Arguments.arguments`,
`AuthManager.auth_list`), where each entry is an object, not a plain string.

**Integration**

- Wired into chat via `CommandDispatcher` behind feature flag `jmeter.ai.agent.enabled`
  (Claude-only), runs on a background `SwingWorker`, streams tool call/result lines,
  degrades to a plain AI answer on error.
- Multi-turn memory: prior chat turns are seeded into `ClaudeChatModel` on each run
  (capped to the last 10 pairs).
- Destructive tools (`delete_element`, `move_element`) are gated behind a blocking
  Swing confirmation dialog (`ToolConfirmationGate` / `SwingToolConfirmationGate`),
  controlled by `jmeter.ai.agent.confirm.destructive` (default `true`).
- The agent's final answer is replayed token-by-token into the chat (`TextChunker` +
  the existing `appendStreamToken`/`onStreamComplete` UI), gated by the same
  `jmeter.ai.streaming.enabled` flag used by the plain chat path. Tool call/result
  lines still stream as they happen; this only affects the final summary.

**Quality**

- 691 unit tests passing. End-to-end smoke test in live JMeter confirmed working
  (add/update/delete/toggle/move all verified live, including the Response
  Assertion property-corruption fix and `set_property_list`).

---

## 2. Remaining tasks (prioritized)

Effort key: **S** = small (<0.5d), **M** = medium (~1d), **L** = large (>1d).

### Phase A — Ship what exists

| #  | Task                                                                             | Effort | Notes                                       |
|----|----------------------------------------------------------------------------------|--------|---------------------------------------------|
| A1 | Commit toggle/move + schema-catalog work on the branch                           | S      | Currently uncommitted.                      |
| A2 | `mvn install` to `lib/ext`; live smoke test of `toggle_element` + `move_element` | S      | Verify EDT refresh + id changes after move. |

### Phase B — Reliability & UX of existing tools

| #  | Task                                                       | Effort | Notes                                                                                                                                                                                                                                     |
|----|-------------------------------------------------------------|--------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| B1 | ~~Multi-turn conversation memory for the agent~~ **Done**   | M      | `JMeterAgent.run(message, priorConversationTurns, progress)` seeds `ClaudeChatModel` with the chat panel's prior turns (last 10 pairs, trailing unpaired turn dropped); wired from `CommandDispatcher.handleAgentCommand`.                |
| B2 | ~~Human confirmation for destructive ops~~ **Done**         | M      | `ToolExecutor` gates `delete_element`/`move_element` behind a `ToolConfirmationGate`; `SwingToolConfirmationGate` shows a blocking Yes/No dialog on the EDT. Setting `jmeter.ai.agent.confirm.destructive` (default `true`) toggles it. |
| B3 | ~~Stream the agent's final text token-by-token~~ **Done**   | S      | Simulated streaming: `TextChunker` replays the already-computed final answer via the existing `appendStreamToken`/`onStreamComplete` UI, gated by `jmeter.ai.streaming.enabled`. (Real SSE streaming was considered but rejected — the loop needs the full response anyway to detect tool calls, so it wouldn't reduce latency, only add risk.) |
| B4 | ~~Expand `ElementPropertyCatalog` coverage~~ **Done (scoped)** | M | Added JSR223Sampler/Pre/PostProcessor, DurationAssertion, SizeAssertion, JSONPathAssertion, GaussianRandomTimer (7 types, all scalar properties). Dropped HeaderManager/AuthManager/Arguments/JDBC-scope from this pass — their real content is a `CollectionProperty` (header/auth/variable list) that `update_element_property` can't set at all (scalar-only); cataloging them would be a no-op. JDBC also deferred at the user's request. Editing collection-shaped properties would need a new tool, tracked as a fresh backlog item if needed. |
| B5 | ~~Enumerate allowed values for enum-like keys~~ **Done** | S | Added `Property.allowedValues` to `ElementPropertyCatalog`, rendered as an `Allowed: ...` line in `describe()`/`get_element_schema`. Covers `HTTPSampler.method`/`.protocol`, `CSVDataSet.shareMode`, `ConstantThroughputTimer.calcMode`, `ResponseAssertion` `test_field`/`test_type`, `SizeAssertion.operator`, JSR223 `scriptLanguage`. Also fixed two pre-existing inaccuracies found during the audit: `test_type` was missing `MATCH=1` and the `OR=32` modifier; `test_field` only had 2 of 8 real values (verified against JMeter source). |
| B6 | ~~Integrate mutations with JMeter Undo/Redo~~ **Done** | M | Turned out to need no mutator changes: `JMeterTreeMutator` already fires the same standard `JMeterTreeModel` events (`nodeChanged`/`insertNodeInto`/`removeNodeFromParent`) that JMeter's own GUI actions fire, and `org.apache.jmeter.gui.UndoHistory` listens generically as a `TreeModelListener` - so agent edits were already undoable *whenever undo history is enabled*. The catch: `UndoHistory.isEnabled()` is `undo.history.size > 0`, which **defaults to 0 (disabled)** in stock JMeter, for native GUI edits too. Added a one-time `JMeterAgent` chat nudge (`maybeWarnAboutUndoHistory`) telling the user to set `undo.history.size` if it's off. Known caveat: `move_element` is 2 undo-recordable steps (remove + insert) since JMeter's compound-transaction API (`beginUndoTransaction`/`endUndoTransaction`) is package-private to `org.apache.jmeter.gui` and unreachable from our plugin - matches JMeter's own tree drag-and-drop move. Regression-tested against a real, headless `JMeterTreeModel` (`JMeterTreeMutatorUndoIntegrationTest`). |
| B7 | ~~Fix property-type corruption + add flat string-list support~~ **Done** | M | Root cause: `JMeterTreeMutator.updateProperty` called `setProperty(key, value)` unconditionally, so a guessed/uncataloged key (e.g. `Asserion.test_strings`) silently replaced a `CollectionProperty` with a `StringProperty`, corrupting the element and crashing its GUI panel. Fixed generically (checks the *existing* property's runtime type before writing, protects every element/property, cataloged or not - regression-tested against real `ResponseAssertion`, `HeaderManager`, `Arguments`). Added `set_property_list` (+ `ParamType.STRING_ARRAY`) so the agent can actually populate flat string lists like assertion patterns, gated by an explicit `ElementPropertyCatalog.isFlatStringListProperty` allowlist. |
| B8 | ~~Support structured list properties (headers, arguments, auth)~~ **Done** | M | Added `set_structured_property_list` (+ `ParamType.OBJECT_ARRAY`) alongside `JMeterTreeMutator.replaceStructuredPropertyList`, gated by `ElementPropertyCatalog.isStructuredListProperty`. Covers `HeaderManager.headers` and `Arguments.arguments` (entries shaped `{name, value}` → `Header`/`Argument`) and `AuthManager.auth_list` (entries shaped `{url, username, password, domain, realm, mechanism}` → `Authorization`, with `mechanism` validated against `AuthManager.Mechanism`). Same corruption guard as `set_property_list`: refuses to overwrite an existing property unless it's absent or already a collection of nested test elements. Regression-tested against real `HeaderManager`/`Arguments`/`AuthManager` instances. |

### Phase C — New action tools

| #  | Task                                       | Effort | Notes                                                                                                                                          |
|----|--------------------------------------------|--------|------------------------------------------------------------------------------------------------------------------------------------------------|
| C1 | ~~`run_test` / `stop_test`~~ **Done (scoped)** | L→S | Scoped down at the user's request: reuses the exact same `ActionRouter.actionPerformed(ActionNames.ACTION_START/STOP/SHUTDOWN)` path JMeter's own Start/Stop/Shutdown toolbar buttons use (`TestRunController.live()`), instead of running a private `StandardJMeterEngine` and collecting results ourselves. `run_test` and `stop_test` (`force` param: `false`→graceful Stop, `true`→Shutdown) dispatch and return immediately - they don't wait for the test or report pass/fail, since there's no public API to query "is a test running" outside the GUI's own package-private `Start` action state, and results depend on whatever listener (View Results Tree, Summary Report, ...) is already in the plan. A richer "collect and summarize results" tool is a natural follow-up if needed (tracked as C6 below). |
| C2 | ~~`duplicate_element` (copy subtree)~~ **Done** | M | Deep-clones a node's subtree (new `TreeNodeCloner`, mirroring JMeter's own `Copy.cloneTreeNode`/`cloneChildren`) and inserts it as the next sibling immediately after the original, under the same parent - matching JMeter's own Copy+Paste/Duplicate menu command, but without touching live tree selection (unlike the native `Duplicate` action, which operates on `JMeterTreeListener`'s selected nodes). New `ElementDuplicator` seam + `DuplicateElementHandler`, same pattern as `move_element`. Guard: Test Plan/root cannot be duplicated. |
| C3 | ~~`rename_element`~~ **Done** | S | New `JMeterTreeMutator.renameElement` calls `JMeterTreeNode.setName` (which delegates straight to `TestElement.setName`) and fires `nodeChanged`, mirroring `setEnabled`'s pattern. New `ElementRenamer` seam + `RenameElementHandler`. Renaming changes the element's tree-path id, so the new id is reported back for follow-up calls. Unlike move/delete/duplicate, the Test Plan/root can be renamed - it's non-destructive. |
| C4 | ~~`save_plan` / `open_plan`~~ **Done** | M | `save_plan`: new `ElementSaver` seam writes the tree via `SaveService.saveTree` and records the file via `GuiPackage.setTestPlanFile` - the same persistence JMeter's own Save/Save As use, minus the `JFileChooser` dialog. `file_path` is optional (falls back to the plan's already-associated file). `open_plan`: new `ElementLoader` seam reads via `SaveService.loadTree` and hands the tree to JMeter's own public `Load.insertLoadedTree` (clears + replaces the tree, exactly like the native Open menu item). Guarded: refuses to run while a test is active (reuses `TestRunController.isRunning()`), and refuses to discard unsaved changes (`GuiPackage.isDirty()`) unless `force=true` - plus registered in `JMeterAgent.DESTRUCTIVE_TOOLS` for the Swing confirmation dialog, same as `delete_element`/`move_element`. |
| C5 | ~~`reorder_element` (index within parent)~~ **Done** | S | New `JMeterTreeMutator.reorderElement` repositions a node among its *current* siblings to a 0-based `new_index`, without changing its parent - a complement to `move_element` (which always appends as the last child, possibly of a different parent). Implemented as remove-then-reinsert-at-index on the same `JMeterTreeModel`, same EDT-mutation pattern as `moveElement`. New `ElementReorderer` seam + `ReorderElementHandler`; guarded against reordering the Test Plan/root and out-of-range indices. |
| C6 | ~~`get_test_results` (collect + summarize)~~ **Done** | L | New `TestPlanRunner` runs the current plan through a private `StandardJMeterEngine` (same embedding pattern as `CorrelationEngine.replayTestPlan`) with its own `SampleListener`/`TestStateListener` pair, blocking until the run finishes or `timeout_seconds` elapses (then `stopTest(true)`), and reports back total/passed/failed counts plus up to `TestRunSummary.MAX_FAILURES_KEPT` failure details - independent of whatever listener happens to be in the plan. Runs Thread Groups exactly as configured (no forced thread/loop overrides, per user decision) - a real run, hence the timeout bound. Gotcha: JMeter reflectively clones `SampleListener`/`TestStateListener` test elements per-thread via their no-arg constructor (`AbstractTestElement.clone()`), so results/latch must be coordinated via **static** state reset per run (`SampleCollector.prepare()`/`RunEndedListener.prepare()`), mirroring `CorrelationEngine.Collector`/`Ender`'s already-proven pattern - an instance field (e.g. a constructor-injected latch) silently doesn't survive that clone. New `TestResultsRunner` seam + `GetTestResultsHandler`; guarded by the same `TestRunController.isRunning()` used by `run_test`/`stop_test`. Regression-tested against a real headless `StandardJMeterEngine` run (`TestPlanRunnerTest`, using a no-network `DebugSampler`) plus fake-seam handler/summary tests. |

### Phase D — Platform & providers

| #  | Task                                        | Effort | Notes                                                                                      |
|----|---------------------------------------------|--------|--------------------------------------------------------------------------------------------|
| D1 | OpenAI tool-calling adapter                 | M      | Implement `ChatModel` for OpenAI; lift the Claude-only restriction in `CommandDispatcher`. |
| D2 | Gemini / Ollama / DeepSeek adapters         | L      | One `ChatModel` per provider.                                                              |
| D3 | Usage/telemetry integration                 | S      | Fold agent token usage into existing `@usage` tracking.                                    |
| D4 | Configurable limits surfaced in settings UI | S      | `max.iterations`, `max.tokens`, enable flag in the options panel.                          |

### Phase E — Dev experience & docs

| #  | Task                                                      | Effort | Notes                                                                  |
|----|-----------------------------------------------------------|--------|------------------------------------------------------------------------|
| E1 | Dev menu items for `toggle_element` / `move_element`      | S      | Skipped during build; add for isolated manual testing (mirror delete). |
| E2 | User-facing docs: enabling agent mode, settings, examples | S      | Add to `README.md` / `docs/`.                                          |
| E3 | Lightweight E2E/integration harness                       | M      | Scripted `MessageService` fixtures exercising multi-tool flows.        |

---

## 3. Known gaps / risks

- **Claude-only** — other configured providers silently fall back to non-agent path (D1/D2).
- **`move_element` appends** as last child; no positional control yet (C5).
- **Property catalog is representative, not exhaustive** — uncurated types rely on
  `get_element_config` live inspection (B4/B5).
- **Undo/redo** does not yet capture agent mutations (B6).
- **Confirmation dialog blocks the background worker thread** while waiting for the
  user's answer (expected/intended, but the chat UI doesn't show a distinct "waiting for
  confirmation" state — the loading indicator was already removed at that point).

---

## 4. Suggested next step

Phases A and B are done. C1 (`run_test`/`stop_test`), C2 (`duplicate_element`), and
C3 (`rename_element`) are done. Next up in Phase C: **C5 (`reorder_element`)** as a
quick win, or **C6 (`get_test_results`)** as the larger follow-up to C1.
