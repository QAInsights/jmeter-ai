package org.qainsights.jmeter.ai.load;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.timers.ConstantTimer;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.visualizers.ViewResultsFullVisualizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class WorkloadPreflightTest {
    @TempDir Path directory;

    @Test
    void checksInheritedSettingsButSkipsDisabledBranchesAndOtherGroups() {
        JMeterTreeNode root = node(new TestPlan());
        JMeterTreeNode group = node(new ThreadGroup());
        root.add(group);
        JMeterTreeNode timer = node(new ConstantTimer());
        root.add(timer);
        ResultCollector listener = new ResultCollector();
        listener.setProperty(TestElement.GUI_CLASS, ViewResultsFullVisualizer.class.getName());
        JMeterTreeNode listenerNode = node(listener);
        group.add(listenerNode);
        JMeterTreeNode disabled = node(new GenericController());
        disabled.setEnabled(false);
        disabled.add(node(new ConstantTimer()));
        group.add(disabled);
        JMeterTreeNode other = node(new ThreadGroup());
        other.add(node(new ConstantTimer()));
        root.add(other);
        List<WorkloadPreflight.Finding> result = check(group, 1);
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(f -> f.node() == timer));
        assertTrue(result.stream().anyMatch(f -> f.node() == listenerNode));
    }

    @Test
    void detectsMissingAndDynamicCsvWithoutReadingContents() throws Exception {
        JMeterTreeNode group = node(new ThreadGroup());
        group.add(csv("missing.csv"));
        group.add(csv("${dataFile}"));
        Files.writeString(directory.resolve("present.csv"), "id\n1");
        group.add(csv("present.csv"));
        List<WorkloadPreflight.Finding> result = check(group, 1);
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(f -> f.title().contains("missing")));
        assertTrue(result.stream().anyMatch(f -> f.severity() == WorkloadPreflight.Severity.NOT_VERIFIED));
    }

    @Test
    void neverUsesLocalFilesToVerifyRemoteReadiness() {
        JMeterTreeNode group = node(new ThreadGroup());
        group.add(csv("missing.csv"));
        List<WorkloadPreflight.Finding> result = check(group, 2);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(f -> f.severity() == WorkloadPreflight.Severity.NOT_VERIFIED));
    }

    @Test
    void capturesSettingsBeforeBackgroundInspection() {
        JMeterTreeNode group = node(new ThreadGroup());
        JMeterTreeNode csv = csv("missing.csv");
        group.add(csv);
        WorkloadPreflight.Inspection inspection = WorkloadPreflight.prepare(group, plan(1),
                directory, directory, "~/");
        csv.getTestElement().setProperty("filename", "${changed}");
        assertTrue(inspection.run().get(0).title().contains("missing"));
    }

    @Test
    void excludesOnlyDirectDesignerManagedTimers() {
        JMeterTreeNode group = node(new ThreadGroup());
        ConstantTimer managed = new ConstantTimer();
        managed.setName(WorkloadApplier.THINK_NAME);
        group.add(node(managed));
        assertTrue(check(group, 1).isEmpty());
        JMeterTreeNode controller = node(new GenericController());
        group.add(controller);
        controller.add(node(managed));
        assertEquals(1, check(group, 1).size());
    }

    private List<WorkloadPreflight.Finding> check(JMeterTreeNode group, int agents) {
        return WorkloadPreflight.prepare(group, plan(agents), directory, directory, "~/").run();
    }

    @Test
    void warnsWhenEnduranceUsersWillStopAtEof() {
        JMeterTreeNode group = node(new ThreadGroup());
        JMeterTreeNode csv = csv("${data}");
        csv.getTestElement().setProperty("recycle", false);
        csv.getTestElement().setProperty("stopThread", true);
        group.add(csv);
        WorkloadShapeConfig shape = new WorkloadShapeConfig(WorkloadShape.ENDURANCE,
                null, null, null, null, null, null, null, null, null, null, List.of(), null,
                ArrivalMode.RANDOM);
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 20, null, 500L, 0L, 60L, 3600L,
                1, 1, "load", "plan.jmx", 1.0, null, List.of(), List.of(), List.of(),
                null, null, null, false, shape));
        assertTrue(WorkloadPreflight.prepare(group, plan, directory, directory, "~/").run().stream()
                .anyMatch(f -> f.detail().contains("Users will stop")));
    }

    private static WorkloadPlan plan(int agents) {
        return WorkloadCalculator.calculate(new WorkloadInputs(WorkloadInputs.TargetType.REQUEST_RATE,
                20, null, 500L, 0L, 60L, 600L, agents, 1, "load", "plan.jmx"));
    }

    private static JMeterTreeNode csv(String filename) {
        CSVDataSet csv = new CSVDataSet();
        csv.setProperty("filename", filename);
        return node(csv);
    }

    private static JMeterTreeNode node(TestElement element) {
        element.setEnabled(true);
        return new JMeterTreeNode(element, null);
    }
}
