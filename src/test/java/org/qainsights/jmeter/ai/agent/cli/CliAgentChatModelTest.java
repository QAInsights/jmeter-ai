package org.qainsights.jmeter.ai.agent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.cli.CliAuthState;
import org.qainsights.jmeter.ai.cli.SubscriptionCliProvider;

/**
 * The CLI-backed agent path: the prompt-level tool protocol is parsed into the
 * same {@link AssistantTurn}s the SDK models produce, and a model that ignores
 * the protocol still ends the run with a plain answer instead of failing it.
 */
class CliAgentChatModelTest {

    private static final ToolSpec SPEC = ToolSpec.builder("add_element")
            .description("Adds an element")
            .addParameter(org.qainsights.jmeter.ai.agent.tool.ToolParameter
                    .builder("type", org.qainsights.jmeter.ai.agent.tool.ParamType.STRING)
                    .description("Element type")
                    .required(true)
                    .build())
            .build();

    @Test
    void parsesToolCallsFromTheProtocolReply() {
        FakeProvider provider = new FakeProvider(
                "{\"tool_calls\":[{\"name\":\"add_element\",\"arguments\":{\"type\":\"ConstantTimer\"}}]}");
        CliAgentChatModel model = new CliAgentChatModel(provider, List.of(SPEC), "system", List.of());

        AssistantTurn turn = model.start("add a timer");

        assertEquals(1, turn.getToolCalls().size());
        assertEquals("add_element", turn.getToolCalls().get(0).getName());
        assertEquals("ConstantTimer", turn.getToolCalls().get(0).getArguments().get("type"));
        // the tools and the protocol reach the CLI through the prompt
        assertTrue(provider.prompts.get(0).contains("add_element"));
        assertTrue(provider.prompts.get(0).contains("tool_calls"));
        assertTrue(provider.prompts.get(0).contains("add a timer"));
    }

    @Test
    void toolResultsAreReplayedOnTheNextTurn() {
        FakeProvider provider = new FakeProvider(
                "{\"tool_calls\":[{\"name\":\"add_element\",\"arguments\":{\"type\":\"ConstantTimer\"}}]}",
                "{\"final\":\"Timer added.\"}");
        CliAgentChatModel model = new CliAgentChatModel(provider, List.of(SPEC), "system", List.of());
        model.start("add a timer");

        AssistantTurn turn = model.next(List.of(new ToolOutcome("call_0", "add_element", "added", false)));

        assertEquals("Timer added.", turn.getText());
        assertTrue(turn.getToolCalls().isEmpty());
        assertTrue(provider.prompts.get(1).contains("add_element: added"));
    }

    @Test
    void aNonJsonReplyBecomesTheFinalAnswer() {
        FakeProvider provider = new FakeProvider("Use a Constant Timer of 500 ms.");
        CliAgentChatModel model = new CliAgentChatModel(provider, List.of(SPEC), "system", List.of());

        AssistantTurn turn = model.start("how do I pace requests?");

        assertEquals("Use a Constant Timer of 500 ms.", turn.getText());
        assertTrue(turn.getToolCalls().isEmpty());
    }

    @Test
    void priorTurnsAreReplayedAsConversation() {
        FakeProvider provider = new FakeProvider("{\"final\":\"ok\"}");
        CliAgentChatModel model = new CliAgentChatModel(provider, List.of(SPEC), "system",
                List.of("earlier question", "earlier answer"));

        model.start("follow up");

        assertTrue(provider.prompts.get(0).contains("User: earlier question"));
        assertTrue(provider.prompts.get(0).contains("Assistant: earlier answer"));
    }

    /** Replays canned CLI replies and records the prompts it was given. */
    private static final class FakeProvider implements SubscriptionCliProvider {

        private final Deque<String> replies = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();

        FakeProvider(String... replies) {
            this.replies.addAll(List.of(replies));
        }

        @Override
        public String displayName() {
            return "Fake CLI";
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean isInstalled() {
            return true;
        }

        @Override
        public CliAuthState getAuthStatus() {
            return signedIn();
        }

        @Override
        public CliAuthState login() {
            return signedIn();
        }

        @Override
        public CliAuthState logout() {
            return signedIn();
        }

        @Override
        public String execute(String prompt) {
            prompts.add(prompt);
            String reply = replies.poll();
            return reply == null ? "{\"final\":\"done\"}" : reply;
        }

        @Override
        public String installHint() {
            return "install it";
        }

        @Override
        public String signInActionLabel() {
            return "Sign in";
        }

        @Override
        public String modelPrefix() {
            return "fake:";
        }

        @Override
        public String getModel() {
            return "";
        }

        @Override
        public void setModel(String model) {
        }

        private static CliAuthState signedIn() {
            return new CliAuthState() {
                @Override
                public String label() {
                    return "Signed in";
                }

                @Override
                public boolean isInstalled() {
                    return true;
                }

                @Override
                public boolean canRunPrompts() {
                    return true;
                }
            };
        }
    }
}
