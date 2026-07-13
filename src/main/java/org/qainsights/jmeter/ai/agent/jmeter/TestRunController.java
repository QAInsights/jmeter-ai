package org.qainsights.jmeter.ai.agent.jmeter;

import java.awt.event.ActionEvent;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.threads.JMeterContextService;

/**
 * Seam over "dispatch a JMeter run/stop action" - the same {@link ActionRouter}
 * path JMeter's own Start/Stop/Shutdown toolbar buttons use. Kept behind an
 * interface so the {@code run_test}/{@code stop_test} tool logic can be
 * unit-tested without a running JMeter GUI or {@code ActionRouter}.
 */
public interface TestRunController {

    /**
     * Dispatches the given JMeter action (one of
     * {@code ActionNames.ACTION_START}, {@code ACTION_STOP} or
     * {@code ACTION_SHUTDOWN}).
     *
     * @param actionName the JMeter action name to dispatch
     * @return {@code true} if a live JMeter GUI was available to dispatch to,
     *         {@code false} otherwise. This does not confirm the action had
     *         any effect - e.g. stopping when nothing is running is a silent
     *         no-op in JMeter itself.
     */
    boolean dispatch(String actionName);

    /**
     * @return {@code true} if at least one sampler thread is currently active
     *         anywhere in the JVM. Used to guard against dispatching
     *         {@code ACTION_START} while a run is already in progress (which
     *         would silently orphan the first run's engine reference, since
     *         JMeter's own {@code Start} action keeps only the latest one),
     *         and to give {@code stop_test} an accurate answer instead of
     *         always claiming a no-op is possible.
     */
    boolean isRunning();

    /**
     * Live controller: dispatches through {@link ActionRouter#actionPerformed}
     * on the EDT, exactly like JMeter's own Start/Stop/Shutdown toolbar
     * buttons, and reports running state via
     * {@link JMeterContextService#getNumberOfThreads()} - the same active-
     * thread counter JMeter's own status bar uses.
     */
    static TestRunController live() {
        EdtExecutor edt = EdtExecutor.swing();
        return new TestRunController() {
            @Override
            public boolean dispatch(String actionName) {
                GuiPackage gui = GuiPackage.getInstance();
                if (gui == null) {
                    return false;
                }
                edt.run(() -> ActionRouter.getInstance().actionPerformed(
                        new ActionEvent(TestRunController.class, ActionEvent.ACTION_PERFORMED, actionName)));
                return true;
            }

            @Override
            public boolean isRunning() {
                return JMeterContextService.getNumberOfThreads() > 0;
            }
        };
    }
}
