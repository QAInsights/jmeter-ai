package org.qainsights.jmeter.ai.extract;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractPlacementTest {

    private JMeterTreeModel model;
    private JMeterTreeNode root;
    private JMeterTreeNode login;
    private JMeterTreeNode orders;
    private JMeterTreeNode headers;
    private JMeterTreeNode plan;

    private static ThreadGroup threadGroup(String name) {
        ThreadGroup tg = new ThreadGroup();
        tg.setName(name);
        tg.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        return tg;
    }

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    private static HTTPSamplerProxy http(String name, String path) {
        HTTPSamplerProxy s = new HTTPSamplerProxy();
        s.setName(name);
        s.setPath(path);
        s.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        return s;
    }

    @BeforeEach
    void setUp() throws Exception {
        model = new JMeterTreeModel();
        root = (JMeterTreeNode) model.getRoot();
        plan = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) root.getChildAt(i);
            if (child.getTestElement() instanceof TestPlan) {
                plan = child;
            }
        }
        JMeterTreeNode tgNode = model.addComponent(threadGroup("Users"), plan);
        login = model.addComponent(http("Login", "/login?seed=abcd1234"), tgNode);
        orders = model.addComponent(http("Orders", "/orders?token=abcd1234"), tgNode);
        HeaderManager hm = new HeaderManager();
        hm.setName("Auth");
        hm.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.gui.HeaderPanel");
        hm.add(new Header("Authorization", "Bearer abcd1234"));
        headers = model.addComponent(hm, orders);
        ConfigTestElement unrelated = new ConfigTestElement();
        unrelated.setName("abcd1234");
        unrelated.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.config.gui.SimpleConfigGui");
        model.addComponent(unrelated, tgNode);
    }

    @Test
    void findsSamplerByLabelAndRedirectSuffix() {
        assertEquals(List.of(login), ExtractPlacement.findSamplers(root, "Login"));
        assertEquals(List.of(login), ExtractPlacement.findSamplers(root, "Login-0"));
        assertTrue(ExtractPlacement.findSamplers(root, "Logout").isEmpty());
        assertEquals("Users > Login", ExtractPlacement.pathOf(login));
    }

    @Test
    void sameNamedSamplersAreAllReturnedForTheUserToChoose() throws Exception {
        JMeterTreeNode other = model.addComponent(threadGroup("Admins"), plan);
        JMeterTreeNode adminLogin = model.addComponent(http("Login", "/admin"), other);
        assertEquals(List.of(login, adminLogin), ExtractPlacement.findSamplers(root, "Login"));
        assertEquals("Admins > Login", ExtractPlacement.pathOf(adminLogin));
    }

    @Test
    void laterUsesStayInsideTheSourceThreadGroup() throws Exception {
        JMeterTreeNode other = model.addComponent(threadGroup("Admins"), plan);
        model.addComponent(http("Admin orders", "/orders?token=abcd1234"), other);
        assertEquals(List.of(orders, headers), ExtractPlacement.laterUses(root, login, "abcd1234"));
    }

    @Test
    void laterUsesSkipTheSourceAndElementNames() {
        List<JMeterTreeNode> uses = ExtractPlacement.laterUses(root, login, "abcd1234");
        assertEquals(List.of(orders, headers), uses);
    }

    @Test
    void replaceSwapsInTheVariableEverywhere() {
        List<JMeterTreeNode> uses = ExtractPlacement.laterUses(root, login, "abcd1234");
        assertEquals(2, ExtractPlacement.replace(uses, "abcd1234", "token"));
        assertEquals("${token}", ((HTTPSamplerProxy) orders.getTestElement()).getArguments().getArgument(0).getValue());
        assertEquals("Bearer ${token}", ((HeaderManager) headers.getTestElement()).getHeader(0).getValue());
        assertEquals("abcd1234", ((HTTPSamplerProxy) login.getTestElement()).getArguments().getArgument(0).getValue());
        assertTrue(ExtractPlacement.laterUses(root, login, "abcd1234").isEmpty());
    }

    @Test
    void shortValuesAreNeverReplaced() {
        assertTrue(ExtractPlacement.laterUses(root, login, "abc").isEmpty());
    }
}
