package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.qainsights.jmeter.ai.agent.jmeter.ElementLoader;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.utils.PlanReplacementGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Imports a complete JMX document as an untitled test plan.
 */
public final class ImportPlanContentHandler {

    public static final String IMPORT_PLAN_CONTENT = "import_plan_content";
    public static final int MAX_CONTENT_BYTES = 1_048_576;

    public static final String ERR_MISSING_CONTENT = "missing_content";
    public static final String ERR_CONTENT_TOO_LARGE = "content_too_large";
    public static final String ERR_INVALID_JMX = "invalid_jmx";
    public static final String ERR_TEST_RUNNING = "test_running";
    public static final String ERR_CONFIRM_REQUIRED = "confirm_required";
    public static final String ERR_IMPORT_FAILED = "import_failed";

    private static final Logger log = LoggerFactory.getLogger(ImportPlanContentHandler.class);

    private final BooleanSupplier dirtySupplier;
    private final TestRunController controller;
    private final ElementLoader loader;

    public ImportPlanContentHandler() {
        this(ImportPlanContentHandler::liveIsDirty, TestRunController.live(), ElementLoader.live());
    }

    public ImportPlanContentHandler(BooleanSupplier dirtySupplier, TestRunController controller, ElementLoader loader) {
        this.dirtySupplier = dirtySupplier;
        this.controller = controller;
        this.loader = loader;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(IMPORT_PLAN_CONTENT)
                .description("Replaces the entire current test plan with a complete JMX document passed as text "
                        + "(the XML a .jmx file contains, root element <jmeterTestPlan>). Use it to create a whole "
                        + "plan in one step instead of many add_element calls. The imported plan has no file until "
                        + "save_plan is called with a file_path. If the current plan has unsaved changes, call "
                        + "save_plan first, or re-call this with force=true to discard them. Fails while a test is "
                        + "running - call stop_test first. Content is limited to 1 MiB.")
                .addParameter(ToolParameter.builder("jmx_content", ParamType.STRING)
                        .description("Complete JMX XML document with root element <jmeterTestPlan>. Must not "
                                + "contain a DOCTYPE.")
                        .required(true).build())
                .addParameter(ToolParameter.builder("force", ParamType.BOOLEAN)
                        .description("Set true to discard unsaved changes in the current test plan and open "
                                + "anyway.")
                        .build())
                .addPrecondition("no test may currently be running (see stop_test)")
                .build();

        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return handle(arguments);
            }
        };
    }

    private ToolResult handle(Map<String, Object> args) {
        String content = stripFence(string(args == null ? null : args.get("jmx_content")));
        if (content.isBlank()) {
            return ToolResult.error(ERR_MISSING_CONTENT, "jmx_content is required.");
        }

        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        if (contentBytes.length > MAX_CONTENT_BYTES) {
            return ToolResult.error(ERR_CONTENT_TOO_LARGE,
                    "JMX content exceeds the 1 MiB limit (" + MAX_CONTENT_BYTES + " bytes).");
        }

        String parseError = validateJmx(content);
        if (parseError != null) {
            return ToolResult.error(ERR_INVALID_JMX, "Invalid JMX document: " + parseError);
        }

        PlanReplacementGuard guard = new PlanReplacementGuard(dirtySupplier, controller, loader);
        try {
            guard.checkCanReplace(bool(args == null ? null : args.get("force")));
        } catch (IllegalStateException e) {
            if (controller.isRunning()) {
                return ToolResult.error(ERR_TEST_RUNNING, e.getMessage());
            }
            return ToolResult.error(ERR_CONFIRM_REQUIRED, e.getMessage());
        }

        Path temp = null;
        try {
            temp = createTempFile();
            Files.write(temp, contentBytes);
            if (!loader.loadUntitled(temp.toFile())) {
                return ToolResult.error(ERR_IMPORT_FAILED, "Could not import the plan - no live JMeter GUI available.");
            }
            long kb = Math.max(1L, (contentBytes.length + 1023L) / 1024L);
            return ToolResult.ok("Imported the test plan from the provided JMX (" + kb
                    + " KB). It is not saved yet - call save_plan with a file_path to save it. "
                    + "Call get_tree_state to see the new tree.");
        } catch (IOException | IllegalUserActionException e) {
            return ToolResult.error(ERR_IMPORT_FAILED, "Failed to import the provided JMX: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error(ERR_IMPORT_FAILED, "Failed to import the provided JMX: " + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("Could not delete temporary JMX import file {}", temp, e);
                }
            }
        }
    }

    private static String validateJmx(String content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                }

                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            Document document = builder.parse(new InputSource(new StringReader(content)));
            Element root = document.getDocumentElement();
            String rootName = root.getTagName();
            if (!"jmeterTestPlan".equals(rootName)) {
                return "Expected root element <jmeterTestPlan>, found <" + rootName + ">.";
            }
            Element hashTree = firstElementChild(root);
            Element firstElement = hashTree == null ? null : firstElementChild(hashTree);
            if (hashTree == null || !"hashTree".equals(hashTree.getTagName())
                    || firstElement == null || !"TestPlan".equals(firstElement.getTagName())) {
                return "Expected a complete test plan: the first element under <jmeterTestPlan><hashTree> "
                        + "must be <TestPlan> (selection/fragment exports are not supported).";
            }
            return null;
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException e) {
            String message = e.getMessage();
            return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        }
    }

    private static Element firstElementChild(Element parent) {
        Node child = parent.getFirstChild();
        while (child != null) {
            if (child instanceof Element element) {
                return element;
            }
            child = child.getNextSibling();
        }
        return null;
    }

    private static Path createTempFile() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            return Files.createTempFile("feather-wand-import-", ".jmx",
                    PosixFilePermissions.asFileAttribute(permissions));
        }
        return Files.createTempFile("feather-wand-import-", ".jmx");
    }

    private static String stripFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int openingLineEnd = trimmed.indexOf('\n');
        int closingLineStart = trimmed.lastIndexOf('\n');
        if (openingLineEnd < 0 || closingLineStart <= openingLineEnd
                || !"```".equals(trimmed.substring(closingLineStart + 1).trim())) {
            return trimmed;
        }
        return trimmed.substring(openingLineEnd + 1, closingLineStart).trim();
    }

    private static boolean liveIsDirty() {
        GuiPackage gui = GuiPackage.getInstance();
        return gui != null && gui.isDirty();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
