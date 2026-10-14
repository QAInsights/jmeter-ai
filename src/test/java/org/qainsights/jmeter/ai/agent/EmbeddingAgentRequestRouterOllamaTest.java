package org.qainsights.jmeter.ai.agent;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.qainsights.jmeter.ai.service.OllamaEmbeddingClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accuracy check against a real Ollama server. Opt-in: set {@code FEATHER_WAND_OLLAMA_EMBED_MODEL}
 * (for example {@code embeddinggemma}) and run Ollama on {@code http://localhost:11434}.
 */
@EnabledIfEnvironmentVariable(named = "FEATHER_WAND_OLLAMA_EMBED_MODEL", matches = ".+")
class EmbeddingAgentRequestRouterOllamaTest {

    private record Case(String message, AgentRequestRouter.Route want) {
    }

    private static final List<Case> CASES = List.of(
            new Case("explain what this test plan does", AgentRequestRouter.Route.INSPECT_EXPLAIN),
            new Case("what is the ramp-up period of the thread group?", AgentRequestRouter.Route.INSPECT_EXPLAIN),
            new Case("show me the headers configured on the login request", AgentRequestRouter.Route.INSPECT_EXPLAIN),
            new Case("summarize the plan structure", AgentRequestRouter.Route.INSPECT_EXPLAIN),
            new Case("which samplers are disabled?", AgentRequestRouter.Route.INSPECT_EXPLAIN),
            new Case("how many threads does this plan use", AgentRequestRouter.Route.INSPECT_EXPLAIN),
            new Case("increase the thread count to 50", AgentRequestRouter.Route.EDIT_TEST_PLAN),
            new Case("add an HTTP header manager with Content-Type application/json", AgentRequestRouter.Route.EDIT_TEST_PLAN),
            new Case("rename the sampler to Login API", AgentRequestRouter.Route.EDIT_TEST_PLAN),
            new Case("disable the checkout request", AgentRequestRouter.Route.EDIT_TEST_PLAN),
            new Case("delete the debug sampler", AgentRequestRouter.Route.EDIT_TEST_PLAN),
            new Case("add a constant timer of 500 ms after each request", AgentRequestRouter.Route.EDIT_TEST_PLAN),
            new Case("duplicate the search request and change the path to /api/v2/search", AgentRequestRouter.Route.EDIT_TEST_PLAN),
            new Case("set the loop count to 10", AgentRequestRouter.Route.EDIT_TEST_PLAN),
            new Case("run the test and tell me what failed", AgentRequestRouter.Route.RUN_DIAGNOSE),
            new Case("execute the plan once and check the response times", AgentRequestRouter.Route.RUN_DIAGNOSE),
            new Case("why are my requests failing with 503?", AgentRequestRouter.Route.RUN_DIAGNOSE),
            new Case("stop the running test", AgentRequestRouter.Route.RUN_DIAGNOSE),
            new Case("start a quick smoke run", AgentRequestRouter.Route.RUN_DIAGNOSE),
            new Case("what was the error rate in the last run", AgentRequestRouter.Route.RUN_DIAGNOSE),
            new Case("find the session token that needs correlation", AgentRequestRouter.Route.CORRELATE),
            new Case("correlate the csrf token from the login response", AgentRequestRouter.Route.CORRELATE),
            new Case("the second request fails because of a dynamic id, extract it", AgentRequestRouter.Route.CORRELATE),
            new Case("add a json extractor for the auth token and reuse it", AgentRequestRouter.Route.CORRELATE),
            new Case("which values in the recorded script change between runs?", AgentRequestRouter.Route.CORRELATE),
            new Case("save the plan", AgentRequestRouter.Route.PLAN_FILES),
            new Case("open /home/me/plans/checkout.jmx", AgentRequestRouter.Route.PLAN_FILES),
            new Case("save this as smoke-test.jmx", AgentRequestRouter.Route.PLAN_FILES),
            new Case("load my existing login plan", AgentRequestRouter.Route.PLAN_FILES),
            new Case("write the current test plan to disk", AgentRequestRouter.Route.PLAN_FILES));

    @Test
    void confidentPicksAreCorrectAndMostRequestsRouteRight() {
        String model = System.getenv("FEATHER_WAND_OLLAMA_EMBED_MODEL");
        EmbeddingAgentRequestRouter router = new EmbeddingAgentRequestRouter(
                new OllamaEmbeddingClient("http://localhost:11434", model, Duration.ofSeconds(120))::embed, 0.75);
        int right = 0;
        int focused = 0;
        int focusedWrong = 0;
        for (Case c : CASES) {
            AgentRequestRouter.Decision d = router.route(c.message());
            assertTrue(d.outcome() != AgentRequestRouter.Outcome.UNAVAILABLE, "Ollama unavailable");
            right += d.route() == c.want() ? 1 : 0;
            if (d.isFocused()) {
                focused++;
                focusedWrong += d.route() == c.want() ? 0 : 1;
            }
            System.out.printf("%-12s %-16s %.2f %s%n", d.outcome(), d.route(), d.confidence(), c.message());
        }
        System.out.printf("%s: %d/%d right, %d focused, %d focused wrong%n", model, right, CASES.size(), focused, focusedWrong);
        assertEquals(0, focusedWrong, "a confident pick was wrong");
        assertTrue(right >= 24, "only " + right + "/" + CASES.size() + " routed right");
    }
}
