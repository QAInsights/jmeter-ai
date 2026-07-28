package org.qainsights.jmeter.ai.mcp;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The curated subset of Playwright MCP tools exposed to the recording agent.
 * <p>
 * The server advertises 24 tools; handing all of them to a model is neither safe nor
 * useful, so this is an explicit allow-list passed to
 * {@link McpToolBridge#registerInto(org.qainsights.jmeter.ai.agent.tool.ToolRegistry,
 * java.util.Collection)}. Anything not listed is unreachable by construction rather than
 * by prompt instruction, which a model can ignore.
 */
public final class PlaywrightTools {

    /**
     * Tools the recording agent may call: navigate, observe, and interact.
     */
    public static final Set<String> RECORDING_ALLOW_LIST = immutableSetOf(
            // Observe - the snapshot is what the model reasons over between actions.
            "browser_snapshot",
            "browser_find",
            "browser_console_messages",
            // Navigate.
            "browser_navigate",
            "browser_navigate_back",
            "browser_wait_for",
            // Interact.
            "browser_click",
            "browser_type",
            "browser_fill_form",
            "browser_select_option",
            "browser_press_key",
            "browser_hover",
            "browser_handle_dialog",
            "browser_file_upload",
            "browser_tabs",
            // Inspecting a single request is useful when hunting a correlation value.
            "browser_network_request");

    /**
     * Tools deliberately withheld, and why. Kept as data so the reasoning is visible at the
     * call site and testable, rather than living only in a commit message.
     */
    public static final Set<String> WITHHELD = immutableSetOf(
            // Arbitrary JavaScript / code execution in the page: a prompt-injected page
            // could use these to do anything the browser can do.
            "browser_run_code_unsafe",
            "browser_evaluate",
            // Would end the session mid-recording and strand the proxy.
            "browser_close",
            // Bulk network dumps are exactly what ProxyControl captures properly; routing
            // them through the model costs a fortune in tokens and yields prose, not data.
            "browser_network_requests",
            // Not useful to a headless recording workflow, and screenshots are expensive.
            "browser_take_screenshot",
            "browser_resize",
            "browser_drag",
            "browser_drop");

    private PlaywrightTools() {
    }

    private static Set<String> immutableSetOf(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }
}
