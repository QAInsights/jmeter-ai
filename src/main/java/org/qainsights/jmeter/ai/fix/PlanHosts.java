package org.qainsights.jmeter.ai.fix;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

/** Collects HTTP sampler and HTTP Request Defaults domains for confirmation. */
public final class PlanHosts {
    private PlanHosts() {
    }

    public static Set<String> live() {
        GuiPackage gui = GuiPackage.getInstance();
        return collect(gui == null || gui.getTreeModel() == null ? null : gui.getTreeModel().getTestPlan());
    }

    public static Set<String> collect(HashTree tree) {
        Set<String> hosts = new TreeSet<>();
        walk(tree, hosts);
        return Set.copyOf(hosts);
    }

    private static void walk(HashTree tree, Collection<String> hosts) {
        if (tree == null) {
            return;
        }
        for (Object key : tree.list()) {
            TestElement element = key instanceof JMeterTreeNode
                    ? ((JMeterTreeNode) key).getTestElement()
                    : key instanceof TestElement ? (TestElement) key : null;
            if (element instanceof HTTPSamplerBase) {
                HTTPSamplerBase sampler = (HTTPSamplerBase) element;
                add(hosts, sampler.getDomain());
                addAbsoluteUrlHost(hosts, sampler.getPath());
            } else if (element != null) {
                add(hosts, element.getPropertyAsString(HTTPSamplerBase.DOMAIN));
            }
            walk(tree.getTree(key), hosts);
        }
    }

    private static void add(Collection<String> hosts, String domain) {
        if (domain != null && !domain.isBlank()) {
            hosts.add(domain.trim());
        }
    }

    private static void addAbsoluteUrlHost(Collection<String> hosts, String path) {
        if (path == null || !(path.regionMatches(true, 0, "http://", 0, 7)
                || path.regionMatches(true, 0, "https://", 0, 8))) {
            return;
        }
        try {
            String host = new URI(path).getHost();
            if (host != null && !host.contains("${")) {
                add(hosts, host);
            }
        } catch (URISyntaxException ignored) {
            // Ignore invalid sampler paths; JMeter will report them during the check run.
        }
    }
}
