package org.qainsights.jmeter.ai.mcp;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PlaywrightTools}. The safety assertions here are the point: they fail
 * loudly if a code-execution tool is ever added to the allow-list.
 */
class PlaywrightToolsTest {

    /** Verified against a live server by PlaywrightMcpIntegrationTest. */
    private static final Set<String> ADVERTISED_BY_SERVER = Set.of(
            "browser_close", "browser_resize", "browser_console_messages", "browser_handle_dialog",
            "browser_evaluate", "browser_file_upload", "browser_drop", "browser_find",
            "browser_fill_form", "browser_press_key", "browser_type", "browser_navigate",
            "browser_navigate_back", "browser_network_requests", "browser_network_request",
            "browser_run_code_unsafe", "browser_take_screenshot", "browser_snapshot",
            "browser_click", "browser_drag", "browser_hover", "browser_select_option",
            "browser_tabs", "browser_wait_for");

    @Test
    void should_neverExposeCodeExecutionTools() {
        assertFalse(PlaywrightTools.RECORDING_ALLOW_LIST.contains("browser_run_code_unsafe"),
                "arbitrary code execution must never be reachable by the agent");
        assertFalse(PlaywrightTools.RECORDING_ALLOW_LIST.contains("browser_evaluate"),
                "page-script evaluation is a prompt-injection route");
    }

    @Test
    void should_notLetTheAgentCloseTheBrowser() {
        assertFalse(PlaywrightTools.RECORDING_ALLOW_LIST.contains("browser_close"),
                "closing the browser mid-recording would strand the proxy");
    }

    @Test
    void should_notRouteBulkNetworkCaptureThroughTheModel() {
        assertFalse(PlaywrightTools.RECORDING_ALLOW_LIST.contains("browser_network_requests"),
                "ProxyControl captures traffic; the model must not be asked to");
        assertTrue(PlaywrightTools.RECORDING_ALLOW_LIST.contains("browser_network_request"),
                "inspecting one request is still useful for correlation");
    }

    @Test
    void should_allowTheCoreObserveActLoop() {
        assertTrue(PlaywrightTools.RECORDING_ALLOW_LIST.contains("browser_snapshot"));
        assertTrue(PlaywrightTools.RECORDING_ALLOW_LIST.contains("browser_navigate"));
        assertTrue(PlaywrightTools.RECORDING_ALLOW_LIST.contains("browser_click"));
        assertTrue(PlaywrightTools.RECORDING_ALLOW_LIST.contains("browser_type"));
    }

    @Test
    void should_classifyEveryToolTheServerAdvertises() {
        Set<String> classified = new HashSet<>(PlaywrightTools.RECORDING_ALLOW_LIST);
        classified.addAll(PlaywrightTools.WITHHELD);

        Set<String> unclassified = new HashSet<>(ADVERTISED_BY_SERVER);
        unclassified.removeAll(classified);

        assertTrue(unclassified.isEmpty(),
                "new server tools must be explicitly allowed or withheld, not silently ignored: "
                        + unclassified);
    }

    @Test
    void should_notContradictItself() {
        Set<String> overlap = new HashSet<>(PlaywrightTools.RECORDING_ALLOW_LIST);
        overlap.retainAll(PlaywrightTools.WITHHELD);

        assertTrue(overlap.isEmpty(), "a tool cannot be both allowed and withheld: " + overlap);
    }

    @Test
    void should_beImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> PlaywrightTools.RECORDING_ALLOW_LIST.add("browser_run_code_unsafe"));
        assertThrows(UnsupportedOperationException.class,
                () -> PlaywrightTools.WITHHELD.clear());
    }
}
