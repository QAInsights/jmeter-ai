package org.qainsights.jmeter.ai.record;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Controller that owns one recording session and validates every state transition.
 */
public final class RecordingSessionController {

    private static final RecordingSessionController INSTANCE = new RecordingSessionController();

    public static RecordingSessionController getInstance() {
        return INSTANCE;
    }

    private final List<Consumer<RecordingSessionSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private RecordingSessionState state = RecordingSessionState.OFF;
    private long startTime;
    private SessionConfig config;
    private String artifactDirectory;
    private String errorMessage;

    public synchronized RecordingSessionSnapshot getSnapshot() {
        return new RecordingSessionSnapshot(state, startTime, config, artifactDirectory, errorMessage);
    }

    public synchronized void startSession(SessionConfig config, String artifactDir) {
        if (state != RecordingSessionState.OFF) {
            throw new IllegalStateException("Cannot start a new session when current state is " + state);
        }
        this.config = config;
        this.artifactDirectory = artifactDir;
        this.startTime = System.currentTimeMillis();
        this.errorMessage = null;
        transitionTo(RecordingSessionState.ARMED);
    }

    public synchronized void transitionTo(RecordingSessionState newState) {
        transitionTo(newState, null);
    }

    public synchronized void transitionTo(RecordingSessionState newState, String errorMsg) {
        if (!isValidTransition(state, newState)) {
            throw new IllegalStateException("Illegal state transition from " + state + " to " + newState);
        }
        this.state = newState;
        if (errorMsg != null) {
            this.errorMessage = errorMsg;
        }
        if (newState == RecordingSessionState.OFF) {
            resetFields();
        }
        notifyListeners();
    }

    private void resetFields() {
        this.config = null;
        this.artifactDirectory = null;
        this.startTime = 0;
        this.errorMessage = null;
    }

    public static boolean isValidTransition(RecordingSessionState from, RecordingSessionState to) {
        if (to == RecordingSessionState.FAILED || to == RecordingSessionState.CANCELLED) {
            return from != RecordingSessionState.OFF && from != RecordingSessionState.DONE;
        }
        return switch (from) {
            case OFF -> to == RecordingSessionState.ARMED;
            case ARMED -> to == RecordingSessionState.PREFLIGHT || to == RecordingSessionState.OFF;
            case PREFLIGHT -> to == RecordingSessionState.PLANNING;
            case PLANNING -> to == RecordingSessionState.EXECUTING;
            case EXECUTING -> to == RecordingSessionState.FLUSHING_HAR;
            case FLUSHING_HAR -> to == RecordingSessionState.CONVERTING;
            case CONVERTING -> to == RecordingSessionState.LOADING;
            case LOADING -> to == RecordingSessionState.AWAITING_CORRELATION;
            case AWAITING_CORRELATION -> to == RecordingSessionState.REVIEWING_CORRELATION ||
                                         to == RecordingSessionState.SAVING ||
                                         to == RecordingSessionState.DONE;
            case REVIEWING_CORRELATION -> to == RecordingSessionState.SAVING || to == RecordingSessionState.DONE;
            case SAVING -> to == RecordingSessionState.DONE;
            case DONE -> to == RecordingSessionState.OFF;
            case FAILED, CANCELLED -> to == RecordingSessionState.CONVERT_PARTIAL || to == RecordingSessionState.DONE || to == RecordingSessionState.OFF;
            case CONVERT_PARTIAL -> to == RecordingSessionState.CONVERTING || to == RecordingSessionState.DONE;
        };
    }

    public void addListener(Consumer<RecordingSessionSnapshot> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<RecordingSessionSnapshot> listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        RecordingSessionSnapshot snapshot = getSnapshot();
        for (Consumer<RecordingSessionSnapshot> listener : listeners) {
            listener.accept(snapshot);
        }
    }
}
