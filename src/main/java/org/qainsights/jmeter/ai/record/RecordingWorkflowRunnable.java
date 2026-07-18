package org.qainsights.jmeter.ai.record;

import org.qainsights.jmeter.ai.gui.CommandCallback;

/**
 * Interface to trigger the recording workflow execution asynchronously.
 */
public interface RecordingWorkflowRunnable {
    void run(String prompt, CommandCallback cb);
}
