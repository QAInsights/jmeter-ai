package org.qainsights.jmeter.ai.record;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The tools through which the agent structures a recording: it declares where each
 * business step starts and stops, and when it is done.
 * <p>
 * These are the only part of the finished test plan the model actually authors - the step
 * <em>names</em>. Every request, header and parameter comes from the proxy, so nothing the
 * model says can invent traffic that did not happen.
 * <p>
 * {@code finish_recording} exists because {@link org.qainsights.jmeter.ai.agent.loop.AgentLoop}
 * has no early-stop signal: it runs until the model emits a turn with no tool calls. Rather
 * than change the shared loop for one caller, finishing is recorded as state and every
 * later step call is rejected with an instruction to stop, which reliably drives the model
 * to its closing message.
 */
public final class RecordingStepTools {

    public static final String BEGIN_STEP = "begin_business_step";
    public static final String END_STEP = "end_business_step";
    public static final String FINISH = "finish_recording";

    public static final String ERR_ALREADY_FINISHED = "recording_already_finished";
    public static final String ERR_STEP_FAILED = "recording_step_failed";

    private final RecordingSession session;

    public RecordingStepTools(RecordingSession session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        this.session = session;
    }

    /** @return the three control tools, in the order the model should meet them */
    public List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        tools.add(beginStepTool());
        tools.add(endStepTool());
        tools.add(finishTool());
        return tools;
    }

    private Tool beginStepTool() {
        ToolSpec spec = ToolSpec.builder(BEGIN_STEP)
                .description("Start a named business step. Every request the browser makes from "
                        + "now on is recorded into a Transaction Controller with this name. Call "
                        + "this immediately BEFORE the browser actions that make up the step, not "
                        + "after. Use short business language, e.g. 'Search For Product', "
                        + "'Add To Cart', 'Checkout'.")
                .addParameter(ToolParameter.builder("name", ParamType.STRING)
                        .description("The business step name, e.g. 'Add To Cart'")
                        .required(true)
                        .build())
                .build();
        return tool(spec, args -> {
            int count = session.beginStep(String.valueOf(args.get("name")));
            return ToolResult.ok("Recording into step '" + session.currentStepName()
                    + "'. Samplers captured so far: " + count + ".");
        });
    }

    private Tool endStepTool() {
        ToolSpec spec = ToolSpec.builder(END_STEP)
                .description("Close the current business step. Waits for any in-flight requests "
                        + "to finish so they are filed under the correct step. Call this once the "
                        + "page has settled, before starting the next step.")
                .build();
        return tool(spec, args -> {
            int count = session.endStep();
            return ToolResult.ok("Step closed. Samplers captured so far: " + count + ".");
        });
    }

    private Tool finishTool() {
        ToolSpec spec = ToolSpec.builder(FINISH)
                .description("Declare the recording complete. Call this once you have performed "
                        + "every part of the user's scenario. After calling it, stop using tools "
                        + "and reply with a short summary of what was recorded.")
                .addParameter(ToolParameter.builder("summary", ParamType.STRING)
                        .description("A one-paragraph account of the journey you recorded")
                        .required(false)
                        .build())
                .build();
        return tool(spec, args -> {
            Object summary = args.get("summary");
            int count = session.finish(summary == null ? "" : String.valueOf(summary));
            return ToolResult.ok("Recording finished with " + count + " samplers across "
                    + session.stepNames().size() + " business steps: "
                    + String.join(", ", session.stepNames())
                    + ". Do not call any more tools; reply to the user with your summary.");
        });
    }

    /**
     * Wraps a handler with the guards every control tool shares: refuse to act once the
     * recording is finished, and turn a {@link RecordingException} into a readable result
     * instead of letting it abort the run.
     */
    private Tool tool(ToolSpec spec, Handler handler) {
        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                if (session.isFinished()) {
                    return ToolResult.error(ERR_ALREADY_FINISHED,
                            "The recording is already finished. Do not call any more tools; "
                                    + "reply to the user with your summary.");
                }
                try {
                    return handler.handle(arguments);
                } catch (RecordingException e) {
                    return ToolResult.error(ERR_STEP_FAILED, e.getMessage());
                }
            }
        };
    }

    @FunctionalInterface
    private interface Handler {
        ToolResult handle(Map<String, Object> arguments);
    }
}
