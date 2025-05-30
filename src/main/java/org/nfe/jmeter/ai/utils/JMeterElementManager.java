package org.nfe.jmeter.ai.utils;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import javax.swing.tree.TreePath;

/**
 * Utility class for managing JMeter elements (add/delete) in the test plan
 * programmatically.
 */
public class JMeterElementManager {
    private static final Logger log = LoggerFactory.getLogger(JMeterElementManager.class);

    // Class to hold model and GUI class names
    private static class ElementClassInfo {
        String modelClassName;
        String guiClassName;

        ElementClassInfo(String modelClassName, String guiClassName) {
            this.modelClassName = modelClassName;
            this.guiClassName = guiClassName;
        }
    }

    // Map of common element names to their model and GUI class names
    private static final Map<String, ElementClassInfo> ELEMENT_CLASS_MAP = new HashMap<>();

    static {
        // Samplers
        ELEMENT_CLASS_MAP.put("httpsampler",
                new ElementClassInfo("org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy",
                        "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui"));
        
        ELEMENT_CLASS_MAP.put("httptestsample",
                new ElementClassInfo("org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy",
                        "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui"));
        
        ELEMENT_CLASS_MAP.put("httprequest",
                new ElementClassInfo("org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy",
                        "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui"));
        ELEMENT_CLASS_MAP.put("ftprequest", new ElementClassInfo("org.apache.jmeter.protocol.ftp.sampler.FTPSampler",
                "org.apache.jmeter.protocol.ftp.control.gui.FtpTestSamplerGui"));
        ELEMENT_CLASS_MAP.put("jdbcrequest", new ElementClassInfo("org.apache.jmeter.protocol.jdbc.sampler.JDBCSampler",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("javarequest", new ElementClassInfo("org.apache.jmeter.protocol.java.sampler.JavaSampler",
                "org.apache.jmeter.protocol.java.control.gui.JavaTestSamplerGui"));
        ELEMENT_CLASS_MAP.put("ldaprequest", new ElementClassInfo("org.apache.jmeter.protocol.ldap.sampler.LDAPSampler",
                "org.apache.jmeter.protocol.ldap.control.gui.LdapTestSamplerGui"));
        ELEMENT_CLASS_MAP.put("ldapeextendedrequest",
                new ElementClassInfo("org.apache.jmeter.protocol.ldap.sampler.LDAPExtSampler",
                        "org.apache.jmeter.protocol.ldap.control.gui.LdapExtTestSamplerGui"));
        ELEMENT_CLASS_MAP.put("accesslogsampler",
                new ElementClassInfo("org.apache.jmeter.protocol.http.sampler.AccessLogSampler",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("beanshellsampler",
                new ElementClassInfo("org.apache.jmeter.protocol.java.sampler.BeanShellSampler",
                        "org.apache.jmeter.protocol.java.control.gui.BeanShellSamplerGui"));
        ELEMENT_CLASS_MAP.put("jsr223sampler",
                new ElementClassInfo("org.apache.jmeter.protocol.java.sampler.JSR223Sampler",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("tcpsampler", new ElementClassInfo("org.apache.jmeter.protocol.tcp.sampler.TCPSampler",
                "org.apache.jmeter.protocol.tcp.control.gui.TCPSamplerGui"));
        ELEMENT_CLASS_MAP.put("jmspublisher",
                new ElementClassInfo("org.apache.jmeter.protocol.jms.sampler.PublisherSampler",
                        "org.apache.jmeter.protocol.jms.control.gui.JMSPublisherGui"));
        ELEMENT_CLASS_MAP.put("jmssubscriber",
                new ElementClassInfo("org.apache.jmeter.protocol.jms.sampler.SubscriberSampler",
                        "org.apache.jmeter.protocol.jms.control.gui.JMSSubscriberGui"));
        ELEMENT_CLASS_MAP.put("jmspointtopoint",
                new ElementClassInfo("org.apache.jmeter.protocol.jms.sampler.JMSSampler",
                        "org.apache.jmeter.protocol.jms.control.gui.JMSSamplerGui"));
        ELEMENT_CLASS_MAP.put("junitrequest",
                new ElementClassInfo("org.apache.jmeter.protocol.java.sampler.JUnitSampler",
                        "org.apache.jmeter.protocol.java.control.gui.JUnitTestSamplerGui"));
        ELEMENT_CLASS_MAP.put("mailreadersampler",
                new ElementClassInfo("org.apache.jmeter.protocol.mail.sampler.MailReaderSampler",
                        "org.apache.jmeter.protocol.mail.sampler.gui.MailReaderSamplerGui"));
        ELEMENT_CLASS_MAP.put("flowcontrolaction", new ElementClassInfo("org.apache.jmeter.sampler.TestAction",
                "org.apache.jmeter.sampler.gui.TestActionGui"));
        ELEMENT_CLASS_MAP.put("smtpsampler", new ElementClassInfo("org.apache.jmeter.protocol.smtp.sampler.SmtpSampler",
                "org.apache.jmeter.protocol.smtp.sampler.gui.SmtpSamplerGui"));
        ELEMENT_CLASS_MAP.put("osprocesssampler",
                new ElementClassInfo("org.apache.jmeter.protocol.system.SystemSampler",
                        "org.apache.jmeter.protocol.system.gui.SystemSamplerGui"));
        ELEMENT_CLASS_MAP.put("mongodbscript",
                new ElementClassInfo("org.apache.jmeter.protocol.mongodb.sampler.MongoScriptSampler",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("boltrequest", new ElementClassInfo("org.apache.jmeter.protocol.bolt.sampler.BoltSampler",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("graphqlhttprequest",
                new ElementClassInfo("org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy",
                        "org.apache.jmeter.protocol.http.control.gui.GraphQLHTTPSamplerGui"));
        ELEMENT_CLASS_MAP.put("debugsampler", new ElementClassInfo("org.apache.jmeter.sampler.DebugSampler",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("ajpsampler", new ElementClassInfo("org.apache.jmeter.protocol.http.sampler.AjpSampler",
                "org.apache.jmeter.protocol.http.control.gui.AjpSamplerGui"));

        // Thread Groups
        ELEMENT_CLASS_MAP.put("threadgroup", new ElementClassInfo("org.apache.jmeter.threads.ThreadGroup",
                "org.apache.jmeter.threads.gui.ThreadGroupGui"));

        // Assertions
        ELEMENT_CLASS_MAP.put("responseassert", new ElementClassInfo("org.apache.jmeter.assertions.ResponseAssertion",
                "org.apache.jmeter.assertions.gui.AssertionGui"));
        ELEMENT_CLASS_MAP.put("jsonassertion", new ElementClassInfo("org.apache.jmeter.assertions.JSONPathAssertion",
                "org.apache.jmeter.assertions.gui.JSONPathAssertionGui"));
        ELEMENT_CLASS_MAP.put("durationassertion",
                new ElementClassInfo("org.apache.jmeter.assertions.DurationAssertion",
                        "org.apache.jmeter.assertions.gui.DurationAssertionGui"));
        ELEMENT_CLASS_MAP.put("sizeassertion", new ElementClassInfo("org.apache.jmeter.assertions.SizeAssertion",
                "org.apache.jmeter.assertions.gui.SizeAssertionGui"));
        ELEMENT_CLASS_MAP.put("xpathassertion", new ElementClassInfo("org.apache.jmeter.assertions.XPathAssertion",
                "org.apache.jmeter.assertions.gui.XPathAssertionGui"));
        ELEMENT_CLASS_MAP.put("jsr223assertion", new ElementClassInfo("org.apache.jmeter.assertions.JSR223Assertion",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("xpath2assertion", new ElementClassInfo("org.apache.jmeter.assertions.XPath2Assertion",
                "org.apache.jmeter.assertions.gui.XPath2AssertionGui"));
        ELEMENT_CLASS_MAP.put("compareassert", new ElementClassInfo("org.apache.jmeter.assertions.CompareAssertion",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("htmlassertion", new ElementClassInfo("org.apache.jmeter.assertions.HTMLAssertion",
                "org.apache.jmeter.assertions.gui.HTMLAssertionGui"));
        ELEMENT_CLASS_MAP.put("jmespathassert",
                new ElementClassInfo("org.apache.jmeter.assertions.jmespath.JMESPathAssertion",
                        "org.apache.jmeter.assertions.jmespath.gui.JMESPathAssertionGui"));
        ELEMENT_CLASS_MAP.put("md5assertion", new ElementClassInfo("org.apache.jmeter.assertions.MD5HexAssertion",
                "org.apache.jmeter.assertions.gui.MD5HexAssertionGUI"));
        ELEMENT_CLASS_MAP.put("smimeassertion",
                new ElementClassInfo("org.apache.jmeter.assertions.SMIMEAssertionTestElement",
                        "org.apache.jmeter.assertions.gui.SMIMEAssertionGui"));
        ELEMENT_CLASS_MAP.put("xmlassertion", new ElementClassInfo("org.apache.jmeter.assertions.XMLAssertion",
                "org.apache.jmeter.assertions.gui.XMLAssertionGui"));
        ELEMENT_CLASS_MAP.put("xmlschemaassertion",
                new ElementClassInfo("org.apache.jmeter.assertions.XMLSchemaAssertion",
                        "org.apache.jmeter.assertions.gui.XMLSchemaAssertionGUI"));
        ELEMENT_CLASS_MAP.put("beanshellassertion",
                new ElementClassInfo("org.apache.jmeter.assertions.BeanShellAssertion",
                        "org.apache.jmeter.assertions.gui.BeanShellAssertionGui"));

        // Timers
        ELEMENT_CLASS_MAP.put("constanttimer", new ElementClassInfo("org.apache.jmeter.timers.ConstantTimer",
                "org.apache.jmeter.timers.gui.ConstantTimerGui"));
        ELEMENT_CLASS_MAP.put("uniformrandomtimer", new ElementClassInfo("org.apache.jmeter.timers.UniformRandomTimer",
                "org.apache.jmeter.timers.gui.UniformRandomTimerGui"));
        ELEMENT_CLASS_MAP.put("gaussianrandomtimer", new ElementClassInfo(
                "org.apache.jmeter.timers.GaussianRandomTimer", "org.apache.jmeter.timers.gui.GaussianRandomTimerGui"));
        ELEMENT_CLASS_MAP.put("poissonrandomtimer", new ElementClassInfo("org.apache.jmeter.timers.PoissonRandomTimer",
                "org.apache.jmeter.timers.gui.PoissonRandomTimerGui"));
        ELEMENT_CLASS_MAP.put("precisethroughputtimer",
                new ElementClassInfo("org.apache.jmeter.timers.poissonarrivals.PreciseThroughputTimer",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("constantthroughputtimer", new ElementClassInfo(
                "org.apache.jmeter.timers.ConstantThroughputTimer", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jsr223timer", new ElementClassInfo("org.apache.jmeter.timers.JSR223Timer",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("synctimer", new ElementClassInfo("org.apache.jmeter.timers.SyncTimer",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("beanshelltimer", new ElementClassInfo("org.apache.jmeter.timers.BeanShellTimer",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));

        // Pre Processors
        ELEMENT_CLASS_MAP.put("jsr223preprocessor", new ElementClassInfo(
                "org.apache.jmeter.modifiers.JSR223PreProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("userparameters", new ElementClassInfo("org.apache.jmeter.modifiers.UserParameters",
                "org.apache.jmeter.modifiers.gui.UserParametersGui"));
        ELEMENT_CLASS_MAP.put("anchormodifier",
                new ElementClassInfo("org.apache.jmeter.protocol.http.modifier.AnchorModifier",
                        "org.apache.jmeter.protocol.http.modifier.gui.AnchorModifierGui"));
        ELEMENT_CLASS_MAP.put("urlrewritingmodifier",
                new ElementClassInfo("org.apache.jmeter.protocol.http.modifier.URLRewritingModifier",
                        "org.apache.jmeter.protocol.http.modifier.gui.URLRewritingModifierGui"));
        ELEMENT_CLASS_MAP.put("jdbcpreprocessor",
                new ElementClassInfo("org.apache.jmeter.protocol.jdbc.processor.JDBCPreProcessor",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("regexuserparameters",
                new ElementClassInfo("org.apache.jmeter.protocol.http.modifier.RegExUserParameters",
                        "org.apache.jmeter.protocol.http.modifier.gui.RegExUserParametersGui"));
        ELEMENT_CLASS_MAP.put("sampletimeout", new ElementClassInfo("org.apache.jmeter.modifiers.SampleTimeout",
                "org.apache.jmeter.modifiers.gui.SampleTimeoutGui"));
        ELEMENT_CLASS_MAP.put("beanshellpreprocessor", new ElementClassInfo(
                "org.apache.jmeter.modifiers.BeanShellPreProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));

        // Post Processors / Extractors
        ELEMENT_CLASS_MAP.put("regexextractor", new ElementClassInfo("org.apache.jmeter.extractor.RegexExtractor",
                "org.apache.jmeter.extractor.gui.RegexExtractorGui"));
        ELEMENT_CLASS_MAP.put("xpathextractor", new ElementClassInfo("org.apache.jmeter.extractor.XPathExtractor",
                "org.apache.jmeter.extractor.gui.XPathExtractorGui"));
        ELEMENT_CLASS_MAP.put("jsonpostprocessor",
                new ElementClassInfo("org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor",
                        "org.apache.jmeter.extractor.json.jsonpath.gui.JSONPostProcessorGui"));
        ELEMENT_CLASS_MAP.put("jsonpathextractor",
                new ElementClassInfo("org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor",
                        "org.apache.jmeter.extractor.json.jsonpath.gui.JSONPostProcessorGui"));
        ELEMENT_CLASS_MAP.put("boundaryextractor", new ElementClassInfo("org.apache.jmeter.extractor.BoundaryExtractor",
                "org.apache.jmeter.extractor.gui.BoundaryExtractorGui"));
        ELEMENT_CLASS_MAP.put("htmlextractor", new ElementClassInfo("org.apache.jmeter.extractor.HtmlExtractor",
                "org.apache.jmeter.extractor.gui.HtmlExtractorGui"));
        ELEMENT_CLASS_MAP.put("jmespathextractor",
                new ElementClassInfo("org.apache.jmeter.extractor.json.jmespath.JMESPathExtractor",
                        "org.apache.jmeter.extractor.json.jmespath.gui.JMESPathExtractorGui"));
        ELEMENT_CLASS_MAP.put("jsr223postprocessor", new ElementClassInfo(
                "org.apache.jmeter.extractor.JSR223PostProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("debugpostprocessor", new ElementClassInfo(
                "org.apache.jmeter.extractor.DebugPostProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jdbcpostprocessor",
                new ElementClassInfo("org.apache.jmeter.protocol.jdbc.processor.JDBCPostProcessor",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("resultaction", new ElementClassInfo("org.apache.jmeter.reporters.ResultAction",
                "org.apache.jmeter.reporters.gui.ResultActionGui"));
        ELEMENT_CLASS_MAP.put("xpath2extractor", new ElementClassInfo("org.apache.jmeter.extractor.XPath2Extractor",
                "org.apache.jmeter.extractor.gui.XPath2ExtractorGui"));
        ELEMENT_CLASS_MAP.put("beanshellpostprocessor", new ElementClassInfo(
                "org.apache.jmeter.extractor.BeanShellPostProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));

        // Config Elements
        ELEMENT_CLASS_MAP.put("csvdatasetconfig", new ElementClassInfo("org.apache.jmeter.config.CSVDataSet",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("csvdataset", new ElementClassInfo("org.apache.jmeter.config.CSVDataSet",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("headermanager",
                new ElementClassInfo("org.apache.jmeter.protocol.http.control.HeaderManager",
                        "org.apache.jmeter.protocol.http.gui.HeaderPanel"));
        ELEMENT_CLASS_MAP.put("cookiemanager",
                new ElementClassInfo("org.apache.jmeter.protocol.http.control.CookieManager",
                        "org.apache.jmeter.protocol.http.gui.CookiePanel"));
        ELEMENT_CLASS_MAP.put("cachemanager",
                new ElementClassInfo("org.apache.jmeter.protocol.http.control.CacheManager",
                        "org.apache.jmeter.protocol.http.gui.CacheManagerGui"));
        ELEMENT_CLASS_MAP.put("httpdefaults", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui"));
        ELEMENT_CLASS_MAP.put("boltconnection",
                new ElementClassInfo("org.apache.jmeter.protocol.bolt.config.BoltConnectionElement",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("counterconfig", new ElementClassInfo("org.apache.jmeter.modifiers.CounterConfig",
                "org.apache.jmeter.modifiers.gui.CounterConfigGui"));
        ELEMENT_CLASS_MAP.put("dnscachemanager",
                new ElementClassInfo("org.apache.jmeter.protocol.http.control.DNSCacheManager",
                        "org.apache.jmeter.protocol.http.gui.DNSCachePanel"));
        ELEMENT_CLASS_MAP.put("ftpconfig", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.protocol.ftp.config.gui.FtpConfigGui"));
        ELEMENT_CLASS_MAP.put("authmanager", new ElementClassInfo("org.apache.jmeter.protocol.http.control.AuthManager",
                "org.apache.jmeter.protocol.http.gui.AuthPanel"));
        ELEMENT_CLASS_MAP.put("jdbcdatasource",
                new ElementClassInfo("org.apache.jmeter.protocol.jdbc.config.DataSourceElement",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("javaconfig", new ElementClassInfo("org.apache.jmeter.protocol.java.config.JavaConfig",
                "org.apache.jmeter.protocol.java.config.gui.JavaConfigGui"));
        ELEMENT_CLASS_MAP.put("keystoreconfig", new ElementClassInfo("org.apache.jmeter.config.KeystoreConfig",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("ldapextconfig", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.protocol.ldap.config.gui.LdapExtConfigGui"));
        ELEMENT_CLASS_MAP.put("ldapconfig", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.protocol.ldap.config.gui.LdapConfigGui"));
        ELEMENT_CLASS_MAP.put("loginconfig", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.config.gui.LoginConfigGui"));
        ELEMENT_CLASS_MAP.put("randomvariable", new ElementClassInfo("org.apache.jmeter.config.RandomVariableConfig",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("simpleconfig", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.config.gui.SimpleConfigGui"));
        ELEMENT_CLASS_MAP.put("tcpconfig", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.protocol.tcp.config.gui.TCPConfigGui"));
        ELEMENT_CLASS_MAP.put("arguments", new ElementClassInfo("org.apache.jmeter.config.Arguments",
                "org.apache.jmeter.config.gui.ArgumentsPanel"));
        ELEMENT_CLASS_MAP.put("ftprequestdefaults", new ElementClassInfo("org.apache.jmeter.config.ConfigTestElement",
                "org.apache.jmeter.protocol.ftp.config.gui.FtpConfigGui"));
        ELEMENT_CLASS_MAP.put("httpauthorizationmanager",
                new ElementClassInfo("org.apache.jmeter.protocol.http.control.AuthManager",
                        "org.apache.jmeter.protocol.http.gui.AuthPanel"));

        // Listeners
        ELEMENT_CLASS_MAP.put("viewresultstree", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.ViewResultsFullVisualizer"));
        ELEMENT_CLASS_MAP.put("summaryreport", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.SummaryReport"));
        ELEMENT_CLASS_MAP.put("aggregatereport", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.StatVisualizer"));
        ELEMENT_CLASS_MAP.put("backendlistener",
                new ElementClassInfo("org.apache.jmeter.visualizers.backend.BackendListener",
                        "org.apache.jmeter.visualizers.backend.BackendListenerGui"));
        ELEMENT_CLASS_MAP.put("statgraphvisualizer", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.StatGraphVisualizer"));
        ELEMENT_CLASS_MAP.put("assertionvisualizer", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.AssertionVisualizer"));
        ELEMENT_CLASS_MAP.put("comparisonvisualizer", new ElementClassInfo(
                "org.apache.jmeter.reporters.ResultCollector", "org.apache.jmeter.visualizers.ComparisonVisualizer"));
        ELEMENT_CLASS_MAP.put("summariser", new ElementClassInfo("org.apache.jmeter.reporters.Summariser",
                "org.apache.jmeter.reporters.gui.SummariserGui"));
        ELEMENT_CLASS_MAP.put("graphvisualizer", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.GraphVisualizer"));
        ELEMENT_CLASS_MAP.put("jsr223listener", new ElementClassInfo("org.apache.jmeter.visualizers.JSR223Listener",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("mailervisualizer", new ElementClassInfo(
                "org.apache.jmeter.reporters.MailerResultCollector", "org.apache.jmeter.visualizers.MailerVisualizer"));
        ELEMENT_CLASS_MAP.put("resptimegraph", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.RespTimeGraphVisualizer"));
        ELEMENT_CLASS_MAP.put("resultsaver", new ElementClassInfo("org.apache.jmeter.reporters.ResultSaver",
                "org.apache.jmeter.reporters.gui.ResultSaverGui"));
        ELEMENT_CLASS_MAP.put("simpledatawriter", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.SimpleDataWriter"));
        ELEMENT_CLASS_MAP.put("tablevisualizer", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.TableVisualizer"));
        ELEMENT_CLASS_MAP.put("beanshelllistener", new ElementClassInfo(
                "org.apache.jmeter.visualizers.BeanShellListener", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));

        // JSR223 Elements
        ELEMENT_CLASS_MAP.put("jsr223sampler",
                new ElementClassInfo("org.apache.jmeter.protocol.java.sampler.JSR223Sampler",
                        "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jsr223preprocessor", new ElementClassInfo(
                "org.apache.jmeter.modifiers.JSR223PreProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jsr223postprocessor", new ElementClassInfo(
                "org.apache.jmeter.extractor.JSR223PostProcessor", "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jsr223listener", new ElementClassInfo("org.apache.jmeter.visualizers.JSR223Listener",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jsr223assertion", new ElementClassInfo("org.apache.jmeter.assertions.JSR223Assertion",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));
        ELEMENT_CLASS_MAP.put("jsr223timer", new ElementClassInfo("org.apache.jmeter.timers.JSR223Timer",
                "org.apache.jmeter.testbeans.gui.TestBeanGUI"));

        // Controllers
        ELEMENT_CLASS_MAP.put("loopcontroller", new ElementClassInfo("org.apache.jmeter.control.LoopController",
                "org.apache.jmeter.control.gui.LoopControlPanel"));
        ELEMENT_CLASS_MAP.put("ifcontroller", new ElementClassInfo("org.apache.jmeter.control.IfController",
                "org.apache.jmeter.control.gui.IfControllerPanel"));
        ELEMENT_CLASS_MAP.put("whilecontroller", new ElementClassInfo("org.apache.jmeter.control.WhileController",
                "org.apache.jmeter.control.gui.WhileControllerGui"));
        ELEMENT_CLASS_MAP.put("transactioncontroller",
                new ElementClassInfo("org.apache.jmeter.control.TransactionController",
                        "org.apache.jmeter.control.gui.TransactionControllerGui"));
        ELEMENT_CLASS_MAP.put("simplecontroller", new ElementClassInfo("org.apache.jmeter.control.GenericController",
                "org.apache.jmeter.control.gui.LogicControllerPanel"));
        ELEMENT_CLASS_MAP.put("onceonlycontroller", new ElementClassInfo("org.apache.jmeter.control.OnceOnlyController",
                "org.apache.jmeter.control.gui.OnceOnlyControllerPanel"));
        ELEMENT_CLASS_MAP.put("interleavecontroller", new ElementClassInfo(
                "org.apache.jmeter.control.InterleaveControl", "org.apache.jmeter.control.gui.InterleaveControlPanel"));
        ELEMENT_CLASS_MAP.put("randomcontroller", new ElementClassInfo("org.apache.jmeter.control.RandomController",
                "org.apache.jmeter.control.gui.RandomControlPanel"));
        ELEMENT_CLASS_MAP.put("randomordercontroller",
                new ElementClassInfo("org.apache.jmeter.control.RandomOrderController",
                        "org.apache.jmeter.control.gui.RandomOrderControllerPanel"));
        ELEMENT_CLASS_MAP.put("throughputcontroller",
                new ElementClassInfo("org.apache.jmeter.control.ThroughputController",
                        "org.apache.jmeter.control.gui.ThroughputControllerPanel"));
        ELEMENT_CLASS_MAP.put("runtimecontroller",
                new ElementClassInfo("org.apache.jmeter.control.RunTime", "org.apache.jmeter.control.gui.RunTimeGui"));
        ELEMENT_CLASS_MAP.put("switchcontroller", new ElementClassInfo("org.apache.jmeter.control.SwitchController",
                "org.apache.jmeter.control.gui.SwitchControllerPanel"));
        ELEMENT_CLASS_MAP.put("foreachcontroller", new ElementClassInfo("org.apache.jmeter.control.ForeachController",
                "org.apache.jmeter.control.gui.ForeachControlPanel"));
        ELEMENT_CLASS_MAP.put("modulecontroller", new ElementClassInfo("org.apache.jmeter.control.ModuleController",
                "org.apache.jmeter.control.gui.ModuleControllerGui"));
        ELEMENT_CLASS_MAP.put("includecontroller", new ElementClassInfo("org.apache.jmeter.control.IncludeController",
                "org.apache.jmeter.control.gui.IncludeControllerPanel"));
        ELEMENT_CLASS_MAP.put("recordingcontroller", new ElementClassInfo(
                "org.apache.jmeter.control.RecordingController", "org.apache.jmeter.control.gui.RecordController"));
        ELEMENT_CLASS_MAP.put("criticalsectioncontroller",
                new ElementClassInfo("org.apache.jmeter.control.CriticalSectionController",
                        "org.apache.jmeter.control.gui.CriticalSectionControllerPanel"));

        // Additional Listeners
        ELEMENT_CLASS_MAP.put("sampleresultsaveconfiguration",
                new ElementClassInfo("org.apache.jmeter.samplers.SampleSaveConfiguration",
                        "org.apache.jmeter.samplers.SampleSaveConfiguration"));
        ELEMENT_CLASS_MAP.put("graphresults", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.GraphVisualizer"));
        ELEMENT_CLASS_MAP.put("assertionresults", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.AssertionVisualizer"));
        ELEMENT_CLASS_MAP.put("viewresultsintable", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.TableVisualizer"));
        ELEMENT_CLASS_MAP.put("saveresponsestoafile", new ElementClassInfo("org.apache.jmeter.reporters.ResultSaver",
                "org.apache.jmeter.reporters.gui.ResultSaverGui"));
        ELEMENT_CLASS_MAP.put("generatesummaryresults", new ElementClassInfo("org.apache.jmeter.reporters.Summariser",
                "org.apache.jmeter.reporters.gui.SummariserGui"));
        ELEMENT_CLASS_MAP.put("comparisonassertionvisualizer", new ElementClassInfo(
                "org.apache.jmeter.reporters.ResultCollector", "org.apache.jmeter.visualizers.ComparisonVisualizer"));
        ELEMENT_CLASS_MAP.put("aggregategraph", new ElementClassInfo("org.apache.jmeter.reporters.ResultCollector",
                "org.apache.jmeter.visualizers.StatGraphVisualizer"));
    }

    /**
     * A generic method to create a test element using its class and GUI class
     * 
     * @param elementClass The class of the test element
     * @param guiClass     The GUI class of the test element
     * @return The created test element
     */
    private static <T extends TestElement> T createTestElement(Class<T> elementClass,
            Class<? extends JMeterGUIComponent> guiClass) {
        try {
            log.info("Creating test element of class: {} with GUI class: {}", elementClass.getName(),
                    guiClass.getName());

            // Create the element instance
            T element = elementClass.getDeclaredConstructor().newInstance();
            if (element == null) {
                log.error("Failed to instantiate element of class: {}", elementClass.getName());
                throw new RuntimeException("Failed to instantiate element");
            }
            // Set the required properties
            element.setProperty(TestElement.TEST_CLASS, elementClass.getName());
            element.setProperty(TestElement.GUI_CLASS, guiClass.getName());

            // For thread groups, ensure they have a proper name to avoid NPE
            if (element.getClass().getSimpleName().contains("ThreadGroup")) {
                element.setName("Thread Group");
            }

            log.info("Successfully created element: {}", element.getClass().getSimpleName());
            return element;
        } catch (Exception e) {
            log.error("Failed to create test element of class: {} with GUI class: {}",
                    elementClass.getName(), guiClass.getName(), e);
            throw new RuntimeException("Failed to create test element: " + e.getMessage(), e);
        }
    }

    /**
     * Adds a JMeter element to the currently selected node in the test plan.
     * 
     * @param elementType The type of element to add (case-insensitive, spaces
     *                    ignored)
     * @param elementName The name to give the new element (optional, will use
     *                    default if null)
     * @return true if the element was added successfully, false otherwise
     */
    public static boolean addElement(String elementType, String elementName) {
        try {
            log.info("Adding element of type: {} with name: {}", elementType, elementName);

            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                log.error("GuiPackage is null, cannot add element");
                return false;
            }

            // Get the currently selected node
            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
            if (currentNode == null) {
                log.error("No node is currently selected in the test plan");
                return false;
            }
            log.info("Current node: {}", currentNode.getName());

            // Normalize the element type for lookup
            String normalizedType = normalizeElementType(elementType);
            log.info("Normalized element type: {}", normalizedType);

            // Get the class info for the element type
            ElementClassInfo classInfo = ELEMENT_CLASS_MAP.get(normalizedType);
            if (classInfo == null) {
                log.error("Unknown element type: {}", elementType);
                return false;
            }
            log.info("Attempting to create element of type: {} with model class: {} and GUI class: {}", normalizedType,
                    classInfo.modelClassName, classInfo.guiClassName);

            try {
                Class<?> elementClass = Class.forName(classInfo.modelClassName);
                Class<? extends JMeterGUIComponent> guiClass = Class.forName(classInfo.guiClassName)
                        .asSubclass(JMeterGUIComponent.class);
                TestElement newElement = createTestElement(elementClass.asSubclass(TestElement.class), guiClass);

                // Special handling for Thread Group to ensure it has a controller
                // This is necessary because JMeter requires a loop controller for Thread Groups
                if (normalizedType.equals("threadgroup")) {
                    log.info("Initializing Thread Group with a Loop Controller");
                    org.apache.jmeter.threads.ThreadGroup threadGroup = (org.apache.jmeter.threads.ThreadGroup) newElement;

                    // Create and initialize a LoopController
                    org.apache.jmeter.control.LoopController loopController = new org.apache.jmeter.control.LoopController();
                    loopController.setLoops(1);
                    loopController.setFirst(true);
                    loopController.setProperty(TestElement.TEST_CLASS,
                            org.apache.jmeter.control.LoopController.class.getName());
                    loopController.setProperty(TestElement.GUI_CLASS,
                            org.apache.jmeter.control.gui.LoopControlPanel.class.getName());

                    // Set the controller on the Thread Group
                    threadGroup.setSamplerController(loopController);
                    log.info("Loop Controller initialized for Thread Group");
                }

                // Set a name for the element
                if (elementName != null && !elementName.isEmpty()) {
                    newElement.setName(elementName);
                } else {
                    // Use a default name based on the element type
                    newElement.setName(getDefaultNameForElement(normalizedType));
                }

                log.info("Adding element to node: {}", currentNode.getName());

                // Check if the current node is compatible with the element type
                if (!isNodeCompatible(currentNode, elementType)) {
                    log.error("Current node is not compatible with element type: {}", elementType);
                    return false;
                }

                log.info("Adding element to node: {}", currentNode.getName());

                // Add the element to the test plan
                guiPackage.getTreeModel().addComponent(newElement, currentNode);
                log.info("Successfully added element to the tree model");

                // Refresh the tree to show the new element
                guiPackage.getTreeModel().nodeStructureChanged(currentNode);
                log.info("Successfully refreshed the tree");

                return true;
            } catch (Exception e) {
                log.error("Failed to create or add element", e);
                return false;
            }
        } catch (Exception e) {
            log.error("Error adding element to the test plan", e);
            return false;
        }
    }

    /**
     * Checks if the test plan is ready for operations.
     * 
     * @return A TestPlanStatus object indicating if the test plan is ready and any
     *         error message
     */
    public static TestPlanStatus isTestPlanReady() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return new TestPlanStatus(false, "JMeter GUI is not available");
        }

        // Check if a test plan is open
        if (guiPackage.getTreeModel() == null || guiPackage.getTreeModel().getRoot() == null) {
            return new TestPlanStatus(false, "No test plan is currently open");
        }

        return new TestPlanStatus(true, null);
    }

    /**
     * Ensures that a test plan exists, creating one if necessary.
     * 
     * @return true if a test plan exists or was created successfully, false
     *         otherwise
     */
    public static boolean ensureTestPlanExists() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            log.error("GuiPackage is null, cannot ensure test plan exists");
            return false;
        }

        // Check if a test plan is already open
        if (guiPackage.getTreeModel() != null && guiPackage.getTreeModel().getRoot() != null) {
            log.info("Test plan already exists");
            return true;
        }

        try {
            // Create a new test plan directly
            org.apache.jmeter.testelement.TestPlan testPlan = new org.apache.jmeter.testelement.TestPlan();
            testPlan.setName("Test Plan");
            testPlan.setProperty(TestElement.TEST_CLASS, org.apache.jmeter.testelement.TestPlan.class.getName());
            testPlan.setProperty(TestElement.GUI_CLASS, org.apache.jmeter.control.gui.TestPlanGui.class.getName());
            
            // Create a root node with the test plan
            JMeterTreeNode root = new JMeterTreeNode(testPlan, null);
            
            // Add the root node to the tree model
            guiPackage.getTreeModel().setRoot(root);
            
            log.info("Created a new test plan");
            return true;
        } catch (Exception e) {
            log.error("Error creating a new test plan", e);
            return false;
        }
    }

    /**
     * Selects the test plan node in the tree.
     * 
     * @return true if the test plan node was selected successfully, false otherwise
     */
    public static boolean selectTestPlanNode() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            log.error("GuiPackage is null, cannot select test plan node");
            return false;
        }

        try {
            // Get the root node (test plan)
            JMeterTreeNode root = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
            if (root == null) {
                log.error("Root node is null, cannot select test plan node");
                return false;
            }

            // Select the test plan node
            guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(root.getPath()));
            log.info("Selected the test plan node");
            return true;
        } catch (Exception e) {
            log.error("Error selecting the test plan node", e);
            return false;
        }
    }

    /**
     * Normalizes the element type by removing spaces and converting to lowercase.
     * 
     * @param elementType The element type to normalize
     * @return The normalized element type
     */
    public static String normalizeElementType(String elementType) {
        if (elementType == null) {
            return "";
        }
        return elementType.toLowerCase().replaceAll("[\\s-]+", "");
    }

    /**
     * Gets a default name for an element based on its type.
     * 
     * @param elementType The normalized element type
     * @return A default name for the element
     */
    public static String getDefaultNameForElement(String elementType) {
        if (elementType == null) {
            return "New Element";
        }

        String normalizedType = normalizeElementType(elementType);

        switch (normalizedType) {
            case "httptestsample":
                return "HTTP Request";
            case "httpsampler":
                return "HTTP Request";
            case "loopcontroller":
                return "Loop Controller";
            case "ifcontroller":
                return "If Controller";
            case "whilecontroller":
                return "While Controller";
            case "transactioncontroller":
                return "Transaction Controller";
            case "runtimecontroller":
                return "Runtime Controller";
            case "headermanager":
                return "HTTP Header Manager";
            case "csvdataset":
                return "CSV Data Set";
            case "threadgroup":
                return "Thread Group";
            case "responseassert":
                return "Response Assertion";
            case "jsonassertion":
                return "JSON Path Assertion";
            case "durationassertion":
                return "Duration Assertion";
            case "sizeassertion":
                return "Size Assertion";
            case "xpathassertion":
                return "XPath Assertion";
            case "constanttimer":
                return "Constant Timer";
            case "uniformrandomtimer":
                return "Uniform Random Timer";
            case "gaussianrandomtimer":
                return "Gaussian Random Timer";
            case "poissonrandomtimer":
                return "Poisson Random Timer";
            case "regexextractor":
                return "Regular Expression Extractor";
            case "xpathextractor":
                return "XPath Extractor";
            case "jsonpathextractor":
                return "JSON Path Extractor";
            case "boundaryextractor":
                return "Boundary Extractor";
            case "viewresultstree":
                return "View Results Tree";
            case "aggregatereport":
                return "Aggregate Report";
            case "jsr223sampler":
                return "JSR223 Sampler";
            case "jsr223preprocessor":
                return "JSR223 PreProcessor";
            case "jsr223postprocessor":
                return "JSR223 PostProcessor";
            default:
                // Convert camelCase to Title Case with spaces
                String name = normalizedType.replaceAll("([a-z])([A-Z])", "$1 $2");
                name = name.substring(0, 1).toUpperCase() + name.substring(1);

                return name;
        }
    }

    /**
     * Gets the map of element types to class names.
     * 
     * @return The element class map
     */
    public static Map<String, ElementClassInfo> getElementClassMap() {
        return ELEMENT_CLASS_MAP;
    }

    /**
     * Checks if the given element type is supported.
     * 
     * @param elementType The element type to check
     * @return true if the element type is supported, false otherwise
     */
    public static boolean isElementTypeSupported(String elementType) {
        String normalizedType = normalizeElementType(elementType);
        return ELEMENT_CLASS_MAP.containsKey(normalizedType);
    }

    /**
     * Gets a list of all supported element types.
     * 
     * @return A string containing all supported element types
     */
    public static String getSupportedElementTypes() {
        StringBuilder sb = new StringBuilder();

        // Group elements by category
        Map<String, StringBuilder> categories = new HashMap<>();
        categories.put("Samplers", new StringBuilder());
        categories.put("Controllers", new StringBuilder());
        categories.put("Config Elements", new StringBuilder());
        categories.put("Thread Groups", new StringBuilder());
        categories.put("Assertions", new StringBuilder());
        categories.put("Timers", new StringBuilder());
        categories.put("Extractors", new StringBuilder());
        categories.put("Listeners", new StringBuilder());
        categories.put("JSR223 Elements", new StringBuilder());

        // Add elements to their respective categories
        for (Map.Entry<String, ElementClassInfo> entry : ELEMENT_CLASS_MAP.entrySet()) {
            String className = entry.getValue().guiClassName;
            if (className.contains("sampler")) {
                categories.get("Samplers").append("- ").append(getDefaultNameForElement(entry.getKey())).append("\n");
            } else if (className.contains("control")) {
                categories.get("Controllers").append("- ").append(getDefaultNameForElement(entry.getKey()))
                        .append("\n");
            } else if (className.contains("config") || className.contains("manager")) {
                categories.get("Config Elements").append("- ").append(getDefaultNameForElement(entry.getKey()))
                        .append("\n");
            } else if (className.contains("threads")) {
                categories.get("Thread Groups").append("- ").append(getDefaultNameForElement(entry.getKey()))
                        .append("\n");
            } else if (className.contains("assertions")) {
                categories.get("Assertions").append("- ").append(getDefaultNameForElement(entry.getKey())).append("\n");
            } else if (className.contains("timers")) {
                categories.get("Timers").append("- ").append(getDefaultNameForElement(entry.getKey())).append("\n");
            } else if (className.contains("extractor")) {
                categories.get("Extractors").append("- ").append(getDefaultNameForElement(entry.getKey())).append("\n");
            } else if (className.contains("visualizers") || className.contains("report")) {
                categories.get("Listeners").append("- ").append(getDefaultNameForElement(entry.getKey())).append("\n");
            } else if (className.contains("JSR223")) {
                categories.get("JSR223 Elements").append("- ").append(getDefaultNameForElement(entry.getKey()))
                        .append("\n");
            }
        }

        // Build the final string
        for (String category : categories.keySet()) {
            if (categories.get(category).length() > 0) {
                sb.append(category).append(":\n");
                sb.append(categories.get(category));
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Gets the JMeter GUI class for an element type.
     * 
     * @param elementType The element type
     * @return The JMeter GUI class for the element type, or null if not found
     */
    public static Class<?> getJMeterGuiClass(String elementType) {
        String normalizedType = normalizeElementType(elementType);
        ElementClassInfo classInfo = ELEMENT_CLASS_MAP.get(normalizedType);

        if (classInfo == null) {
            return null;
        }

        try {
            return Class.forName(classInfo.guiClassName);
        } catch (ClassNotFoundException e) {
            log.error("Class not found: {}", classInfo.guiClassName, e);
            return null;
        }
    }

    /**
     * Checks if a node is compatible with the given element type based on JMeter's
     * hierarchy rules.
     * 
     * @param currentNode The current JMeter tree node
     * @param elementType The type of element to add
     * @return true if the node is compatible, false otherwise
     */
    private static boolean isNodeCompatible(JMeterTreeNode currentNode, String elementType) {
        String nodeType = currentNode.getTestElement().getClass().getSimpleName();
        String nodeGuiClass = currentNode.getTestElement().getPropertyAsString(TestElement.GUI_CLASS);

        log.info("Checking compatibility: Node type: {}, Node GUI class: {}, Element type: {}",
                nodeType, nodeGuiClass, elementType);

        // Normalize element type for validation
        String normalizedType = normalizeElementType(elementType);
        log.info("Normalized element type: {}", normalizedType);

        // Determine the category of the current node
        boolean isTestPlan = nodeType.equals("TestPlan") || nodeGuiClass.contains("TestPlanGui");
        boolean isThreadGroup = nodeType.equals("ThreadGroup") || nodeGuiClass.contains("ThreadGroupGui");
        boolean isController = nodeType.contains("Controller") || nodeGuiClass.contains("ControllerPanel")
                || nodeGuiClass.contains("ControllerGui");
        boolean isSampler = nodeType.contains("Sampler") || nodeGuiClass.contains("SamplerGui");
        boolean isTimer = nodeType.contains("Timer") || nodeGuiClass.contains("TimerGui");
        boolean isPreProcessor = nodeType.contains("PreProcessor") || nodeGuiClass.contains("PreProcessorGui");
        boolean isPostProcessor = nodeType.contains("PostProcessor") || nodeGuiClass.contains("PostProcessorGui");
        boolean isConfigElement = nodeType.contains("Config") || nodeGuiClass.contains("ConfigGui");
        boolean isListener = nodeType.contains("Listener") || nodeGuiClass.contains("ListenerGui")
                || nodeGuiClass.contains("Visualizer");
        boolean isAssertion = nodeType.contains("Assertion") || nodeGuiClass.contains("AssertionGui");

        log.info(
                "Node categories: TestPlan={}, ThreadGroup={}, Controller={}, Sampler={}, Timer={}, PreProcessor={}, PostProcessor={}, ConfigElement={}, Listener={}, Assertion={}",
                isTestPlan, isThreadGroup, isController, isSampler, isTimer, isPreProcessor, isPostProcessor,
                isConfigElement, isListener, isAssertion);

        // Determine the category of the element being added
        boolean isAddingTestPlan = normalizedType.equals("testplan");
        boolean isAddingThreadGroup = normalizedType.equals("threadgroup");
        boolean isAddingController = normalizedType.contains("controller");
        boolean isAddingSampler = normalizedType.contains("sampler") || normalizedType.contains("request");
        boolean isAddingTimer = normalizedType.contains("timer");
        boolean isAddingPreProcessor = normalizedType.contains("preprocessor");
        boolean isAddingPostProcessor = normalizedType.contains("postprocessor")
                || normalizedType.contains("extractor");
        boolean isAddingConfigElement = normalizedType.contains("config") || normalizedType.contains("manager")
                || normalizedType.contains("dataset");
        boolean isAddingListener = normalizedType.contains("listener") || normalizedType.contains("visualizer")
                || normalizedType.contains("report") || normalizedType.contains("tree")
                || normalizedType.contains("table")
                || normalizedType.contains("graph") || normalizedType.contains("assertion")
                || normalizedType.contains("assertion");
        boolean isAddingAssertion = normalizedType.contains("assertion") || normalizedType.equals("responseassert");

        log.info(
                "Element categories: TestPlan={}, ThreadGroup={}, Controller={}, Sampler={}, Timer={}, PreProcessor={}, PostProcessor={}, ConfigElement={}, Listener={}, Assertion={}",
                isAddingTestPlan, isAddingThreadGroup, isAddingController, isAddingSampler, isAddingTimer,
                isAddingPreProcessor, isAddingPostProcessor, isAddingConfigElement, isAddingListener,
                isAddingAssertion);

        // Apply JMeter hierarchy rules
        if (isTestPlan) {
            // Test plan can have all types of elements
            return true;
        } else if (isThreadGroup) {
            // Thread group can have all types of elements, except test plan and another
            // thread group
            if (isAddingThreadGroup) {
                log.error("Cannot add a Thread Group to another Thread Group");
                return false;
            }
            return !isAddingTestPlan;
        } else if (isSampler) {
            // Samplers can have only assertions, timer, Pre and post processors, config
            // element, listener
            return isAddingAssertion || isAddingTimer || isAddingPreProcessor || isAddingPostProcessor
                    || isAddingConfigElement || isAddingListener;
        } else if (isController) {
            // Controllers can have all types of elements except test plan and thread group
            return !isAddingTestPlan && !isAddingThreadGroup;
        } else if (isTimer || isPreProcessor || isPostProcessor || isConfigElement || isListener || isAssertion) {
            // Timers, Pre processors, post processors, config elements, listeners cannot
            // have any element underneath them
            log.info("Node type does not support adding any elements underneath it");
            return false;
        }

        // Default case - if we can't determine the node type or element type, be
        // conservative and return false
        log.info("Could not determine compatibility for node type: {} and element type: {}", nodeType, normalizedType);
        return false;
    }

    /**
     * Gets a user-friendly description for a JMeter element type.
     * 
     * @param elementType The element type (class name)
     * @return A user-friendly description of the element
     */
    public static String getElementDescription(String elementType) {
        if (elementType == null) {
            return "Unknown element type";
        }
        
        // Map common element types to descriptions
        switch (elementType.toLowerCase()) {
            case "httpsamplerproxy":
                return "HTTP Sampler allows you to send HTTP/HTTPS requests to a web server.";
            case "httpdefaults":
                return "HTTP Request Defaults lets you specify default values for HTTP Request samplers.";
            case "cookiemanager":
                return "HTTP Cookie Manager lets you control and manage HTTP cookies in your test.";
            case "headermanager":
                return "HTTP Header Manager lets you add or override HTTP request headers.";
            case "cachemanager":
                return "HTTP Cache Manager emulates browser cache behavior.";
            case "threadgroup":
                return "Thread Group defines a pool of users that will execute the test plan.";
            case "loopcontroller":
                return "Loop Controller lets you control how many times operations are executed.";
            case "ifcontroller":
                return "If Controller allows you to control whether test elements are executed based on a condition.";
            case "whilecontroller":
                return "While Controller repeatedly executes test elements while a condition is true.";
            case "foreachcontroller":
                return "ForEach Controller lets you loop through a set of variables.";
            case "transactioncontroller":
                return "Transaction Controller generates an additional sample which measures the overall time taken to execute.";
            case "timerwrapper":
                return "Timer controls the time JMeter waits between each request.";
            case "constanttimer":
                return "Constant Timer adds a fixed delay between requests.";
            case "uniformrandomtimer":
                return "Uniform Random Timer adds a random delay with a uniform distribution.";
            case "gaussianrandomtimer":
                return "Gaussian Random Timer adds a random delay with a Gaussian distribution.";
            case "assertion":
                return "Assertion allows you to validate the response of a request.";
            case "responseassertionguiwrapper":
                return "Response Assertion lets you check the content of a server response.";
            case "jsrassertion":
                return "JSR223 Assertion allows you to create custom assertions using scripting languages.";
            case "xmlassertion":
                return "XML Assertion verifies that the response is a well-formed XML document.";
            case "jsonpathassertion":
                return "JSON Path Assertion extracts values from JSON responses for validation.";
            case "xpathassertionguiwrapper":
                return "XPath Assertion allows you to validate XML responses using XPath expressions.";
            case "durationassertion":
                return "Duration Assertion checks that a response was received within a given amount of time.";
            case "sizeassertion":
                return "Size Assertion verifies that the response contains the right number of bytes.";
            case "jsr223sampler":
                return "JSR223 Sampler allows you to create custom requests using scripting languages.";
            case "jdbcsampler":
                return "JDBC Request allows you to send SQL queries to a database.";
            case "ftpsampler":
                return "FTP Request allows you to send FTP requests to an FTP server.";
            case "javasampler":
                return "Java Request allows you to create a custom sampler using Java code.";
            case "ldapsampler":
                return "LDAP Request allows you to send requests to an LDAP server.";
            case "mailreader":
                return "Mail Reader Sampler allows you to read emails from a POP3/IMAP server.";
            case "smtpsampler":
                return "SMTP Sampler allows you to send emails via SMTP.";
            case "soapsampler":
                return "SOAP/XML-RPC Request allows you to send SOAP or XML-RPC requests.";
            case "tcpsampler":
                return "TCP Sampler allows you to send TCP requests.";
            case "testaction":
                return "Test Action allows you to pause or stop a test.";
            case "debugsampler":
                return "Debug Sampler shows JMeter variables and properties.";
            case "jsonsamplerproxy":
                return "JSON Request sends JSON requests to a server.";
            default:
                return "JMeter element of type: " + elementType;
        }
    }

    /**
     * Main method for testing the functionality.
     * 
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: JMeterElementManager <elementType> [elementName]");
            System.out.println("Supported element types:");
            System.out.println(getSupportedElementTypes());
            return;
        }

        String elementType = args[0];
        String elementName = args.length > 1 ? args[1] : null;

        boolean success = addElement(elementType, elementName);

        if (success) {
            System.out.println("Successfully added " + elementType +
                    (elementName != null ? " named \"" + elementName + "\"" : "") +
                    " to the test plan.");
        } else {
            System.out.println("Failed to add " + elementType + " to the test plan.");
        }
    }

    /**
     * Status class for test plan readiness.
     */
    public static class TestPlanStatus {
        private final boolean ready;
        private final String errorMessage;

        public TestPlanStatus(boolean ready, String errorMessage) {
            this.ready = ready;
            this.errorMessage = errorMessage;
        }

        public boolean isReady() {
            return ready;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
