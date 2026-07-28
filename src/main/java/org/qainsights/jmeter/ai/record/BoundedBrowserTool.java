package org.qainsights.jmeter.ai.record;

import java.util.Map;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * Caps the size of a browser tool's output before it enters the conversation.
 * <p>
 * This exists because of an unbounded-growth failure that killed a real recording with
 * "prompt is too long: 3653143 tokens > 1000000 maximum". A Playwright MCP page snapshot
 * is an accessibility tree of the whole document, which on a content-heavy page runs to
 * hundreds of kilobytes. {@code ChatModel} implementations keep every turn and re-send the
 * whole history on each request, so an N-step recording re-sends N snapshots: cost grows
 * quadratically and the context window is exhausted long before a real scenario finishes.
 * <p>
 * Truncation is safe for the recording use case specifically. The model only needs enough
 * of the tree to choose the next element to interact with, and the recorded test plan is
 * built by JMeter's proxy from real traffic - nothing in the output depends on the model
 * having seen the entire page. The head of a snapshot is kept rather than the tail because
 * MCP emits page metadata and the interactive elements nearest the top first.
 */
public final class BoundedBrowserTool implements Tool {

    /**
     * Chosen so a long recording stays well inside a 1M-token window: the agent may take
     * ~60 turns, and 8000 characters is roughly 2000 tokens, so the worst case is ~120k
     * tokens of tool output rather than the 3.6M that failed.
     */
    public static final int DEFAULT_MAX_CHARS = 8000;

    private final Tool delegate;
    private final int maxChars;

    public BoundedBrowserTool(Tool delegate) {
        this(delegate, DEFAULT_MAX_CHARS);
    }

    public BoundedBrowserTool(Tool delegate, int maxChars) {
        if (delegate == null) {
            throw new IllegalArgumentException("BoundedBrowserTool needs a delegate");
        }
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        this.delegate = delegate;
        this.maxChars = maxChars;
    }

    @Override
    public ToolSpec getSpec() {
        return delegate.getSpec();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        ToolResult result = delegate.execute(arguments);
        if (!result.isSuccess()) {
            // Error messages are short and are what the model self-corrects from.
            return result;
        }
        String data = result.getData();
        if (data == null || data.length() <= maxChars) {
            return result;
        }
        return ToolResult.ok(truncate(data));
    }

    /**
     * The notice must be explicit: a silently cut tree would make the model believe it had
     * seen the whole page and conclude a missing element does not exist.
     */
    private String truncate(String data) {
        int omitted = data.length() - maxChars;
        return data.substring(0, maxChars)
                + "\n\n[... " + omitted + " more characters omitted to stay within the context "
                + "limit. This is a partial view of the page. If the element you need is not "
                + "shown, scroll or interact with what is visible to reveal it, rather than "
                + "assuming it is absent. ...]";
    }
}
