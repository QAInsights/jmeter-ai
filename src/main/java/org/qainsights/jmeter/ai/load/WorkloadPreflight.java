package org.qainsights.jmeter.ai.load;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.timers.ConstantThroughputTimer;
import org.apache.jmeter.timers.ConstantTimer;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.visualizers.ViewResultsFullVisualizer;
import org.apache.jmeter.visualizers.TableVisualizer;

/** Snapshots the Swing tree before checking local file metadata off the event thread. */
public final class WorkloadPreflight {
    private WorkloadPreflight() {
    }

    public enum Severity {
        WARNING("Warning"), NOT_VERIFIED("Not verified");

        private final String label;

        Severity(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record Finding(Severity severity, String title, String detail,
                          JMeterTreeNode node, String elementPath) {
        @Override
        public String toString() {
            return severity + ": " + title;
        }
    }

    private record CsvFile(JMeterTreeNode node, String elementPath, String filename) {
    }

    public static final class Inspection {
        private final List<Finding> findings;
        private final List<CsvFile> files;
        private final Path baseDirectory;
        private final Path workingDirectory;
        private final String basePrefix;

        private Inspection(List<Finding> findings, List<CsvFile> files, Path baseDirectory,
                           Path workingDirectory, String basePrefix) {
            this.findings = List.copyOf(findings);
            this.files = List.copyOf(files);
            this.baseDirectory = baseDirectory;
            this.workingDirectory = workingDirectory;
            this.basePrefix = basePrefix;
        }

        public List<Finding> run() {
            List<Finding> result = new ArrayList<>(findings);
            for (CsvFile csv : files) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                checkFile(csv, result);
            }
            return List.copyOf(result);
        }

        private void checkFile(CsvFile csv, List<Finding> result) {
            String filename = csv.filename();
            if (filename.isBlank()) {
                result.add(fileFinding(csv, Severity.WARNING, "CSV filename is blank",
                        "Set a CSV filename before running the test."));
                return;
            }
            if (filename.contains("${") || filename.startsWith("\\\\") || filename.startsWith("//")
                    || (java.io.File.separatorChar != '\\' && filename.matches("^[A-Za-z]:.*"))) {
                result.add(fileFinding(csv, Severity.NOT_VERIFIED, "CSV path cannot be checked locally",
                        "This path uses a variable, function, network share or another OS's drive syntax. "
                                + "Resolve and verify it on the execution host. No expression was evaluated."));
                return;
            }
            try {
                Path path;
                if (!basePrefix.isEmpty() && filename.startsWith(basePrefix)) {
                    path = baseDirectory.resolve(filename.substring(basePrefix.length()));
                } else {
                    path = Path.of(filename);
                    if (!path.isAbsolute()) {
                        Path workingPath = workingDirectory.resolve(path);
                        path = Files.exists(workingPath) ? workingPath : baseDirectory.resolve(path);
                    }
                }
                path = path.toAbsolutePath().normalize();
                if (Files.notExists(path)) {
                    result.add(fileFinding(csv, Severity.WARNING, "Local CSV file is missing",
                            "No file was found at " + path + ". JMeter checks the working directory "
                                    + "before its file-server base for ordinary relative filenames. "
                                    + "Check the filename and execution directory."));
                } else if (!Files.exists(path)) {
                    result.add(fileFinding(csv, Severity.NOT_VERIFIED, "CSV availability is unknown",
                            "The filesystem could not confirm this path. Verify access on the execution host."));
                } else if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                    result.add(fileFinding(csv, Severity.WARNING, "Local CSV is not a readable file",
                            "Check the file type and read permissions at " + path + "."));
                }
            } catch (InvalidPathException | SecurityException e) {
                result.add(fileFinding(csv, Severity.NOT_VERIFIED, "CSV path could not be inspected",
                        "Check the path syntax and permissions on the execution host."));
            }
        }
    }

    public static Inspection prepare(JMeterTreeNode group, WorkloadPlan plan, Path baseDirectory,
                                     Path workingDirectory, String basePrefix) {
        List<Finding> findings = new ArrayList<>();
        List<CsvFile> files = new ArrayList<>();
        boolean distributed = plan.agents() > 1 || !plan.generatorHosts().isEmpty();
        if (!group.isEnabled()) {
            add(findings, group, Severity.NOT_VERIFIED, "Thread Group is disabled",
                    "These checks inspect its enabled children, but the disabled Thread Group will not run. "
                            + "Review which groups should be enabled before execution.");
        }
        inspectChildren(group, group, plan, distributed, findings, files);
        for (JMeterTreeNode ancestor = (JMeterTreeNode) group.getParent(); ancestor != null;
             ancestor = (JMeterTreeNode) ancestor.getParent()) {
            for (int index = 0; index < ancestor.getChildCount(); index++) {
                JMeterTreeNode sibling = (JMeterTreeNode) ancestor.getChildAt(index);
                if (sibling.isEnabled()) {
                    inspectElement(sibling, group, plan, distributed, findings, files);
                }
            }
        }
        if (distributed) {
            add(findings, group, Severity.NOT_VERIFIED, "Remote generators were not checked",
                    "Verify CSV paths, JMeter/Java/plugin versions, RMI connectivity and heap on every "
                            + "generator. This check does not connect to remote machines or validate their capacity.");
        }
        return new Inspection(findings, files, baseDirectory, workingDirectory, basePrefix);
    }

    private static void inspectChildren(JMeterTreeNode parent, JMeterTreeNode group, WorkloadPlan plan,
                                         boolean distributed, List<Finding> findings, List<CsvFile> files) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(index);
            if (child.isEnabled()) {
                inspectElement(child, group, plan, distributed, findings, files);
                inspectChildren(child, group, plan, distributed, findings, files);
            }
        }
    }

    private static void inspectElement(JMeterTreeNode node, JMeterTreeNode group, WorkloadPlan plan,
                                        boolean distributed, List<Finding> findings, List<CsvFile> files) {
        TestElement element = node.getTestElement();
        String guiClass = element.getPropertyAsString(TestElement.GUI_CLASS);
        if (guiClass.equals(ViewResultsFullVisualizer.class.getName())
                || guiClass.equals(TableVisualizer.class.getName())) {
            add(findings, node, Severity.WARNING, "Heavy results listener is enabled",
                    "View Results Tree and View Results in Table retain sample data and can increase "
                            + "generator memory and CPU use. Disable them for load runs and write results to a file.");
        }
        if (element instanceof CSVDataSet) {
            String filename = element.getPropertyAsString("filename");
            if (distributed) {
                add(findings, node, Severity.NOT_VERIFIED, "CSV availability on generators is unknown",
                        "Verify this CSV's resolved path and read permissions on every remote generator. "
                                + "Its presence or absence on this GUI machine does not verify remote availability.");
            } else {
                files.add(new CsvFile(node, elementPath(node), filename));
            }
            if (plan.shapeConfig().shape() == WorkloadShape.ENDURANCE) {
                String recycle = element.getPropertyAsString("recycle", "true");
                String stop = element.getPropertyAsString("stopThread", "false");
                if (recycle.contains("${") || stop.contains("${")) {
                    add(findings, node, Severity.NOT_VERIFIED, "CSV EOF behavior is dynamic",
                            "Resolve Recycle on EOF and Stop thread on EOF on the execution host. "
                                    + "Their runtime values can change the endurance workload.");
                } else if (!Boolean.parseBoolean(recycle)) {
                    add(findings, node, Severity.WARNING, "CSV can run out during endurance testing",
                            Boolean.parseBoolean(stop)
                                    ? "Recycle on EOF is off and Stop thread on EOF is on. Users will stop when "
                                            + "data runs out, reducing the intended load. Supply enough unique rows "
                                            + "or deliberately choose a safe recycling strategy."
                                    : "Recycle on EOF and Stop thread on EOF are off. Later iterations can receive "
                                            + "the EOF marker instead of valid data. Supply enough rows or deliberately "
                                            + "choose a safe recycling strategy.");
                }
            }
        }
        if (element instanceof Timer && !managedTimer(node, group)) {
            boolean throughput = element instanceof ConstantThroughputTimer
                    || element.getClass().getName().equals("org.apache.jmeter.timers.poissonarrivals.PreciseThroughputTimer");
            add(findings, node, Severity.WARNING,
                    throughput ? "Existing throughput timer needs review" : "Existing timer adds unmodeled delay",
                    throughput
                            ? "This timer is not managed by Workload Designer. Its pacing may compete with "
                                    + "the proposed rate or open-model arrival schedule. Review its scope and settings."
                            : "JMeter adds applicable timer delays. This timer is not included in the designer's "
                                    + "response/think-time inputs, so it can lower achieved throughput or increase "
                                    + "open-model concurrency. Review its scope before adding more think time.");
        }
    }

    private static boolean managedTimer(JMeterTreeNode node, JMeterTreeNode group) {
        return node.getParent() == group && ((node.getTestElement() instanceof ConstantTimer
                && WorkloadApplier.THINK_NAME.equals(node.getName()))
                || (node.getTestElement() instanceof ConstantThroughputTimer
                && WorkloadApplier.CTT_NAME.equals(node.getName())));
    }

    private static void add(List<Finding> findings, JMeterTreeNode node, Severity severity,
                            String title, String detail) {
        findings.add(new Finding(severity, title, detail, node, elementPath(node)));
    }

    private static Finding fileFinding(CsvFile csv, Severity severity, String title, String detail) {
        return new Finding(severity, title, detail, csv.node(), csv.elementPath());
    }

    private static String elementPath(JMeterTreeNode node) {
        List<String> names = new ArrayList<>();
        for (Object item : node.getPath()) {
            if (item instanceof JMeterTreeNode pathNode && pathNode.getParent() != null) {
                names.add(pathNode.getName());
            }
        }
        return String.join(" > ", names);
    }
}
