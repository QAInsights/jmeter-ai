package org.qainsights.jmeter.ai.fix;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;

/** Collects HTTP sampler and HTTP Request Defaults domains for confirmation. */
public final class PlanHosts {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^{}]+)}");

    private PlanHosts() {
    }

    public static Set<String> live() {
        GuiPackage gui = GuiPackage.getInstance();
        return collect(gui == null || gui.getTreeModel() == null ? null : gui.getTreeModel().getTestPlan());
    }

    public static Set<String> collect(HashTree tree) {
        Set<String> hosts = new TreeSet<>();
        Map<String, String> variables = new LinkedHashMap<>();
        collectVariables(tree, variables);
        walk(tree, hosts, variables);
        return Set.copyOf(hosts);
    }

    private static void collectVariables(HashTree tree, Map<String, String> variables) {
        if (tree == null) {
            return;
        }
        for (Object key : tree.list()) {
            TestElement element = elementOf(key);
            if (element instanceof TestPlan testPlan) {
                Map<String, String> testPlanVariables = testPlan.getUserDefinedVariables();
                if (testPlanVariables != null) {
                    testPlanVariables.forEach(variables::putIfAbsent);
                }
            }
            if (element instanceof Arguments arguments) {
                for (int index = 0; index < arguments.getArgumentCount(); index++) {
                    Argument argument = arguments.getArgument(index);
                    variables.putIfAbsent(argument.getName(), argument.getValue());
                }
            }
            collectVariables(tree.getTree(key), variables);
        }
    }

    private static void walk(HashTree tree, Collection<String> hosts, Map<String, String> variables) {
        if (tree == null) {
            return;
        }
        for (Object key : tree.list()) {
            TestElement element = elementOf(key);
            if (element instanceof HTTPSamplerBase) {
                HTTPSamplerBase sampler = (HTTPSamplerBase) element;
                add(hosts, sampler.getDomain(), variables);
                addAbsoluteUrlHost(hosts, sampler.getPath(), variables);
            } else if (element != null) {
                add(hosts, element.getPropertyAsString(HTTPSamplerBase.DOMAIN), variables);
            }
            walk(tree.getTree(key), hosts, variables);
        }
    }

    private static TestElement elementOf(Object key) {
        return key instanceof JMeterTreeNode
                ? ((JMeterTreeNode) key).getTestElement()
                : key instanceof TestElement ? (TestElement) key : null;
    }

    private static void add(Collection<String> hosts, String domain, Map<String, String> variables) {
        if (domain != null && !domain.isBlank()) {
            String resolved = resolve(domain, variables).trim();
            if (!resolved.isBlank()) {
                hosts.add(resolved);
            }
        }
    }

    private static String resolve(String value, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String replacement = variables.get(matcher.group(1));
            matcher.appendReplacement(resolved,
                    Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static void addAbsoluteUrlHost(Collection<String> hosts, String path, Map<String, String> variables) {
        if (path == null || !(path.regionMatches(true, 0, "http://", 0, 7)
                || path.regionMatches(true, 0, "https://", 0, 8))) {
            return;
        }
        try {
            String host = new URI(resolve(path, variables)).getHost();
            if (host != null && !host.contains("${")) {
                add(hosts, host, variables);
            }
        } catch (URISyntaxException ignored) {
            // Ignore invalid sampler paths; JMeter will report them during the check run.
        }
    }
}
