package org.qainsights.jmeter.ai.record;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns one live recording: the scaffold being filled in and the proxy filling it.
 * <p>
 * Its whole job is the business-step boundary. Samplers are delivered to
 * {@code ProxyControl} asynchronously, so re-pointing the capture target the instant the
 * agent says "next step" would file still-in-flight requests under the wrong controller.
 * Every boundary therefore waits for the traffic to go quiet first.
 * <p>
 * The agent is treated as unreliable by design: it may begin a step without ending the
 * previous one, or name two steps identically. Those are normalised here rather than
 * rejected, because failing a tool call mid-recording costs a whole session while the
 * browser sits on a live page.
 */
public final class RecordingSession {

    private static final Logger log = LoggerFactory.getLogger(RecordingSession.class);

    /** How long traffic must be silent before a boundary is considered safe. */
    public static final long DEFAULT_QUIET_MILLIS = 1_500L;

    /** Upper bound on any single boundary wait. */
    public static final long DEFAULT_QUIESCENCE_TIMEOUT_MILLIS = 20_000L;

    private final RecordingScaffold scaffold;
    private final JMeterProxyRecorder recorder;
    private final long quietMillis;
    private final long quiescenceTimeoutMillis;
    private final List<String> stepNames = new ArrayList<>();

    private String currentStepName;
    private boolean finished;
    private String summary = "";

    public RecordingSession(RecordingScaffold scaffold, JMeterProxyRecorder recorder) {
        this(scaffold, recorder, DEFAULT_QUIET_MILLIS, DEFAULT_QUIESCENCE_TIMEOUT_MILLIS);
    }

    public RecordingSession(RecordingScaffold scaffold, JMeterProxyRecorder recorder,
                            long quietMillis, long quiescenceTimeoutMillis) {
        if (scaffold == null || recorder == null) {
            throw new RecordingException("A recording session needs both a scaffold and a recorder");
        }
        this.scaffold = scaffold;
        this.recorder = recorder;
        this.quietMillis = quietMillis;
        this.quiescenceTimeoutMillis = quiescenceTimeoutMillis;
    }

    /**
     * Opens a business step and routes subsequent traffic into it.
     * <p>
     * If a step is already open it is closed first: the agent forgetting
     * {@code end_business_step} should not nest or lose a step.
     *
     * @param name the step name, e.g. {@code "Add To Cart"}
     * @return the sampler count at the moment the step opened
     * @throws RecordingException if the session is finished or the name is blank
     */
    public synchronized int beginStep(String name) {
        requireActive();
        if (name == null || name.trim().isEmpty()) {
            throw new RecordingException("A business step needs a name");
        }
        String trimmed = name.trim();
        if (currentStepName != null) {
            log.debug("Step '{}' was still open; closing it before starting '{}'",
                    currentStepName, trimmed);
        }
        // Let in-flight requests land in the previous step before the target moves.
        awaitQuiet();

        JMeterTreeNode stepNode = scaffold.addBusinessStep(uniqueName(trimmed));
        recorder.setTarget(stepNode);
        currentStepName = trimmed;
        stepNames.add(trimmed);
        log.info("Recording business step '{}'", trimmed);
        return recorder.sampleCount();
    }

    /**
     * Closes the current step, waiting for its traffic to arrive first.
     *
     * @return the total sampler count once the step is closed
     * @throws RecordingException if the session is finished
     */
    public synchronized int endStep() {
        requireActive();
        awaitQuiet();
        currentStepName = null;
        return recorder.sampleCount();
    }

    /**
     * Marks the recording complete. Further step calls are rejected.
     *
     * @param summary the agent's account of what it recorded
     * @return the final sampler count
     */
    public synchronized int finish(String summary) {
        requireActive();
        awaitQuiet();
        this.summary = summary == null ? "" : summary;
        this.currentStepName = null;
        this.finished = true;
        return recorder.sampleCount();
    }

    /**
     * Disambiguates a repeated step name, so two "Search" steps do not become
     * indistinguishable controllers in the finished plan.
     */
    private String uniqueName(String name) {
        if (!stepNames.contains(name)) {
            return name;
        }
        int occurrence = 2;
        while (stepNames.contains(name + " " + occurrence)) {
            occurrence++;
        }
        return name + " " + occurrence;
    }

    private void awaitQuiet() {
        if (!recorder.awaitQuiescence(quietMillis, quiescenceTimeoutMillis)) {
            // Not fatal: a long-polling or streaming endpoint may never go quiet. The step
            // boundary is then approximate, which is better than stalling the session.
            log.warn("Traffic did not go quiet within {}ms; the step boundary may be imprecise",
                    quiescenceTimeoutMillis);
        }
    }

    private void requireActive() {
        if (finished) {
            throw new RecordingException("The recording has already been finished");
        }
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    public synchronized String summary() {
        return summary;
    }

    /** The step name currently open, or null if none. */
    public synchronized String currentStepName() {
        return currentStepName;
    }

    /** Step names in the order they were opened, before de-duplication. */
    public synchronized List<String> stepNames() {
        return Collections.unmodifiableList(new ArrayList<>(stepNames));
    }

    public synchronized int sampleCount() {
        return recorder.sampleCount();
    }

    public RecordingScaffold scaffold() {
        return scaffold;
    }
}
