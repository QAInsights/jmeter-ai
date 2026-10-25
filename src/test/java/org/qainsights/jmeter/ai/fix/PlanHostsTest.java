package org.qainsights.jmeter.ai.fix;

import java.util.Set;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanHostsTest {
    @Test
    void collectsHostFromAbsoluteSamplerPath() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setDomain("");
        sampler.setPath("https://target.example.test/api");

        assertEquals(Set.of("target.example.test"), PlanHosts.collect(tree(sampler)));
    }

    @Test
    void skipsMalformedAndVariableSamplerPaths() {
        HTTPSamplerProxy malformed = new HTTPSamplerProxy();
        malformed.setDomain("");
        malformed.setPath("https://bad host.example.test/path");
        HTTPSamplerProxy variable = new HTTPSamplerProxy();
        variable.setDomain("");
        variable.setPath("${TARGET_URL}");
        HashTree root = new ListedHashTree();
        HashTree plan = root.add(new TestPlan("Test Plan"));
        plan.add(malformed);
        plan.add(variable);

        assertEquals(Set.of(), PlanHosts.collect(root));
    }

    private static HashTree tree(HTTPSamplerProxy sampler) {
        HashTree tree = new ListedHashTree();
        tree.add(new TestPlan("Test Plan")).add(sampler);
        return tree;
    }
}
