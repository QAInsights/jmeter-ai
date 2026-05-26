package org.qainsights.jmeter.ai.correlation;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import java.awt.event.ActionEvent;
import java.nio.file.Path;

public class CorrelationMenuItem extends JMenuItem {
    private static final Logger log = LoggerFactory.getLogger(CorrelationMenuItem.class);

    public CorrelationMenuItem() {
        super(new CorrelationAction());
    }

    private static final class CorrelationAction extends AbstractAction {
        private CorrelationAction() {
            super("Correlation Engine");
            putValue(Action.ACTION_COMMAND_KEY, "correlation_engine");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            GuiPackage guiPackage = GuiPackage.getInstance();
            MainFrame mainFrame = guiPackage == null ? null : guiPackage.getMainFrame();
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select XML JTL with Response Data");
            int result = chooser.showOpenDialog(mainFrame);
            if (result != JFileChooser.APPROVE_OPTION) {
                return;
            }
            Path jtlPath = chooser.getSelectedFile().toPath();
            runAnalysis(mainFrame, jtlPath);
        }

        private void runAnalysis(MainFrame mainFrame, Path jtlPath) {
            JOptionPane.showMessageDialog(mainFrame, "Correlation analysis will run in the background.", "Correlation", JOptionPane.INFORMATION_MESSAGE);
            SwingWorker<CorrelationAnalysisResult, Void> worker = new SwingWorker<>() {
                @Override
                protected CorrelationAnalysisResult doInBackground() {
                    return new CorrelationAnalyzer().analyze(jtlPath);
                }

                @Override
                protected void done() {
                    try {
                        CorrelationAnalysisResult result = get();
                        CorrelationReviewDialog dialog = new CorrelationReviewDialog(mainFrame, result);
                        dialog.setVisible(true);
                    } catch (Exception e) {
                        Throwable rootCause = rootCause(e);
                        if (rootCause instanceof PluginException) {
                            log.warn("Correlation analysis input validation failed: {}", rootCause.getMessage());
                            JOptionPane.showMessageDialog(mainFrame, rootCause.getMessage(), "Correlation Input", JOptionPane.WARNING_MESSAGE);
                        } else {
                            log.error("Correlation analysis failed", e);
                            JOptionPane.showMessageDialog(mainFrame, message(rootCause, e), "Correlation Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            };
            worker.execute();
        }

        private static Throwable rootCause(Exception e) {
            Throwable current = e;
            while (current.getCause() != null) {
                current = current.getCause();
            }
            return current;
        }

        private static String message(Throwable rootCause, Exception fallback) {
            return rootCause.getMessage() == null ? fallback.toString() : rootCause.getMessage();
        }
    }
}
