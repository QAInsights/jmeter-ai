package org.qainsights.jmeter.ai.load;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.processor.PostProcessor;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.PostThreadGroup;
import org.apache.jmeter.threads.SetupThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;

/** Resolves the Thread Group and sampler count for an {@code @load} dialog. */
public final class LoadCommandHandler {
    public static final String NO_GUI = "The JMeter GUI is not available, so I can't design a workload.";
    public static final String NO_THREAD_GROUP = "Add a Thread Group with samples first, then run `@load` again.";
    public static final String NO_REQUESTS = "Add samples to the Thread Group first.";

    private LoadCommandHandler() {
    }

    public static JMeterTreeNode selectedNode() {
        GuiPackage gui = GuiPackage.getInstance();
        return gui == null || gui.getTreeListener() == null ? null : gui.getTreeListener().getCurrentNode();
    }

    public static Preparation prepare(String message, JMeterTreeNode selected, JMeterTreeModel model) {
        return prepare(message, selected, model, key -> JMeterUtils.getPropDefault(key, ""));
    }

    static Preparation prepare(String message, JMeterTreeNode selected, JMeterTreeModel model,
                               Function<String, String> propertyLookup) {
        if (model == null) {
            return Preparation.error(NO_GUI);
        }
        WorkloadSpec spec = WorkloadParser.parse(message);
        List<JMeterTreeNode> groups = eligibleThreadGroups(model);
        if (groups.isEmpty()) {
            return Preparation.error(NO_THREAD_GROUP);
        }
        JMeterTreeNode selectedGroup = containingGroup(selected, groups);
        JMeterTreeNode defaultGroup = selectedGroup != null
                && WorkloadApplier.countEnabledSamplers(selectedGroup) > 0
                ? selectedGroup : groups.stream()
                .filter(group -> WorkloadApplier.countEnabledSamplers(group) > 0)
                .findFirst().orElse(null);
        if (defaultGroup == null) {
            return Preparation.error(NO_REQUESTS);
        }
        List<String> hostsFromProperties = remoteHosts(propertyLookup.apply("remote_hosts"), spec.agents());
        return new Preparation(null, spec, groups, defaultGroup,
                groups.size(), model, testPlanFileName(), hostsFromProperties, !hostsFromProperties.isEmpty());
    }

    public static List<JMeterTreeNode> eligibleThreadGroups(JMeterTreeModel model) {
        List<JMeterTreeNode> groups = new ArrayList<>();
        if (model != null) {
            collectGroups((JMeterTreeNode) model.getRoot(), groups);
        }
        return List.copyOf(groups);
    }

    public static String prefixFor(JMeterTreeNode node, int eligibleCount) {
        return WorkloadApplier.defaultPrefix(node, eligibleCount);
    }

    public static List<Class<?>> enabledSamplerClasses(JMeterTreeNode group) {
        List<Class<?>> classes = new ArrayList<>();
        collectSamplerClasses(group, true, classes);
        return List.copyOf(classes);
    }

    public static List<JdbcPool> jdbcPools(JMeterTreeNode group) {
        List<JdbcPool> pools = new ArrayList<>();
        collectJdbcPools(group, true, pools);
        return List.copyOf(pools);
    }

    public static boolean scriptHeavy(JMeterTreeNode group) {
        return containsScriptElement(group, true);
    }

    public static WorkloadAiAssistant.PlanSummary planSummary(JMeterTreeNode group) {
        StructureCounts counts = new StructureCounts();
        List<Class<?>> samplerClasses = new ArrayList<>();
        collectStructure(group, true, counts, samplerClasses);
        SamplerMix mix = SamplerMix.classify(samplerClasses);
        return new WorkloadAiAssistant.PlanSummary(mix, mix.total(), counts.extractors,
                counts.postProcessors, counts.scriptElements, counts.csvDataSets, counts.timers,
                counts.assertions, counts.httpUsesHttps, counts.jdbcPoolMaxValues);
    }

    static List<String> remoteHosts(String propertyValue, Integer requestedAgents) {
        if (propertyValue == null || propertyValue.isBlank()) {
            return List.of();
        }
        List<String> hosts = java.util.Arrays.stream(propertyValue.split(",", -1))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .toList();
        if (hosts.isEmpty() || hosts.stream().allMatch(LoadCommandHandler::isLocalHost)
                || hosts.stream().anyMatch(host -> !WorkloadCalculator.validGeneratorHost(host))
                || requestedAgents != null && requestedAgents != hosts.size()) {
            return List.of();
        }
        return hosts;
    }

    private static boolean isLocalHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost") || normalized.matches("localhost:\\d+")
                || normalized.equals("127.0.0.1") || normalized.matches("127\\.0\\.0\\.1:\\d+");
    }

    private static boolean containsScriptElement(JMeterTreeNode node, boolean ancestorsEnabled) {
        if (node == null || !ancestorsEnabled || !node.isEnabled()) {
            return false;
        }
        String className = node.getTestElement().getClass().getName().toLowerCase(Locale.ROOT);
        boolean script = className.contains("jsr223") || className.contains("beanshell");
        if (script && (className.contains("sampler") || className.contains("preprocessor")
                || className.contains("postprocessor"))) {
            return true;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            if (containsScriptElement((JMeterTreeNode) node.getChildAt(index), true)) {
                return true;
            }
        }
        return false;
    }

    private static void collectStructure(JMeterTreeNode node, boolean ancestorsEnabled,
                                        StructureCounts counts, List<Class<?>> samplerClasses) {
        if (node == null || !ancestorsEnabled || !node.isEnabled()) {
            return;
        }
        TestElement element = node.getTestElement();
        if (element != null) {
            String className = element.getClass().getName();
            String lowerName = className.toLowerCase(Locale.ROOT);
            if (element instanceof Sampler sampler) {
                samplerClasses.add(sampler.getClass());
                if (element instanceof HTTPSamplerBase http
                        && "https".equalsIgnoreCase(http.getProtocol())) {
                    counts.httpUsesHttps = true;
                }
            }
            if (lowerName.startsWith("org.apache.jmeter.extractor.")) {
                counts.extractors++;
            }
            if (element instanceof PostProcessor) {
                counts.postProcessors++;
            }
            if (lowerName.contains("jsr223") || lowerName.contains("beanshell")) {
                counts.scriptElements++;
            }
            if (className.equals("org.apache.jmeter.config.CSVDataSet")) {
                counts.csvDataSets++;
            }
            if (element instanceof Timer) {
                counts.timers++;
            }
            if (element instanceof Assertion) {
                counts.assertions++;
            }
            if (className.equals("org.apache.jmeter.protocol.jdbc.config.DataSourceElement")) {
                counts.jdbcPoolMaxValues.add(element.getPropertyAsInt("poolMax"));
            }
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            collectStructure((JMeterTreeNode) node.getChildAt(index), true, counts, samplerClasses);
        }
    }

    private static final class StructureCounts {
        private int extractors;
        private int postProcessors;
        private int scriptElements;
        private int csvDataSets;
        private int timers;
        private int assertions;
        private boolean httpUsesHttps;
        private final List<Integer> jdbcPoolMaxValues = new ArrayList<>();
    }

    private static void collectSamplerClasses(JMeterTreeNode node, boolean ancestorsEnabled,
                                              List<Class<?>> classes) {
        if (node == null || !ancestorsEnabled || !node.isEnabled()) {
            return;
        }
        if (node.getTestElement() instanceof Sampler sampler) {
            classes.add(sampler.getClass());
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            collectSamplerClasses((JMeterTreeNode) node.getChildAt(index), true, classes);
        }
    }

    private static void collectJdbcPools(JMeterTreeNode node, boolean ancestorsEnabled,
                                         List<JdbcPool> pools) {
        if (node == null || !ancestorsEnabled || !node.isEnabled()) {
            return;
        }
        TestElement element = node.getTestElement();
        if ("org.apache.jmeter.protocol.jdbc.config.DataSourceElement".equals(element.getClass().getName())) {
            String variable = element.getPropertyAsString("dataSource");
            int poolMax = element.getPropertyAsInt("poolMax");
            pools.add(new JdbcPool(variable == null || variable.isBlank() ? "(unnamed)" : variable, poolMax));
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            collectJdbcPools((JMeterTreeNode) node.getChildAt(index), true, pools);
        }
    }

    private static void collectGroups(JMeterTreeNode node, List<JMeterTreeNode> groups) {
        if (node.isEnabled() && node.getTestElement() instanceof ThreadGroup
                && !(node.getTestElement() instanceof SetupThreadGroup)
                && !(node.getTestElement() instanceof PostThreadGroup)) {
            groups.add(node);
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            collectGroups((JMeterTreeNode) node.getChildAt(index), groups);
        }
    }

    private static JMeterTreeNode containingGroup(JMeterTreeNode node, List<JMeterTreeNode> groups) {
        for (JMeterTreeNode current = node; current != null; current = (JMeterTreeNode) current.getParent()) {
            if (groups.contains(current)) {
                return current;
            }
        }
        return null;
    }

    private static String testPlanFileName() {
        GuiPackage gui = GuiPackage.getInstance();
        String path = gui == null ? null : gui.getTestPlanFile();
        if (path == null || path.isBlank()) {
            return "plan.jmx";
        }
        return java.nio.file.Path.of(path).getFileName().toString();
    }

    public record Preparation(String error, WorkloadSpec spec, List<JMeterTreeNode> threadGroups,
                              JMeterTreeNode selectedGroup, int eligibleCount, JMeterTreeModel model,
                              String planFileName, List<String> generatorHosts,
                              boolean hostsFromProperties) {
        public Preparation {
            threadGroups = threadGroups == null ? List.of() : List.copyOf(threadGroups);
            generatorHosts = generatorHosts == null ? List.of() : List.copyOf(generatorHosts);
        }

        public Preparation(String error, WorkloadSpec spec, List<JMeterTreeNode> threadGroups,
                           JMeterTreeNode selectedGroup, int eligibleCount, JMeterTreeModel model,
                           String planFileName) {
            this(error, spec, threadGroups, selectedGroup, eligibleCount, model, planFileName, List.of(), false);
        }

        private static Preparation error(String message) {
            return new Preparation(message, null, List.of(), null, 0, null, "plan.jmx", List.of(), false);
        }
    }
}
