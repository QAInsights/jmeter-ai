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
        String resolvedPath = resolve(path, variables);
        String authority;
        try {
            URI uri = new URI(resolvedPath);
            String host = uri.getHost();
            if (host != null) {
                add(hosts, host, variables);
                return;
            }
            authority = uri.getRawAuthority();
        } catch (URISyntaxException ignored) {
            authority = rawAuthority(resolvedPath);
            if (authority == null || !authority.contains("${")) {
                return;
            }
        }
        String host = hostFromAuthority(authority);
        if (host != null && !host.isBlank() && !host.contains("%")) {
            add(hosts, host, variables);
        }
    }

    private static String rawAuthority(String absoluteUrl) {
        int schemeEnd = absoluteUrl.indexOf("://");
        if (schemeEnd < 0) {
            return null;
        }
        int start = schemeEnd + 3;
        int end = absoluteUrl.length();
        for (char delimiter : new char[] {'/', '?', '#'}) {
            int index = absoluteUrl.indexOf(delimiter, start);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        return absoluteUrl.substring(start, end);
    }

    private static String hostFromAuthority(String authority) {
        if (authority == null || authority.isBlank()) {
            return null;
        }
        String host = authority.substring(authority.lastIndexOf('@') + 1);
        if (host.startsWith("[")) {
            int closingBracket = host.indexOf(']');
            if (closingBracket >= 0) {
                return host.substring(0, closingBracket + 1);
            }
        }
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(':') == colon) {
            String port = host.substring(colon + 1);
            if (port.isEmpty() || port.chars().allMatch(Character::isDigit)) {
                return host.substring(0, colon);
            }
        }
        return host;
    }
}
