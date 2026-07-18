package org.qainsights.jmeter.ai.record;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Manages the Playwright lifecycle and action execution on a dedicated worker thread.
 */
public class PlaywrightBrowserSession implements AutoCloseable {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ElementResolver resolver;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private SessionConfig config;

    public PlaywrightBrowserSession() {
        this(null);
    }

    public PlaywrightBrowserSession(ElementResolver resolver) {
        this.resolver = resolver;
    }

    public void start(SessionConfig config, String harPath) {
        this.config = config;
        runOnExecutor(() -> {
            Playwright.CreateOptions opt = new Playwright.CreateOptions();
            String browsersPath = AiConfig.getProperty("jmeter.ai.record.playwright.browsers.path", "");
            if (!browsersPath.isEmpty()) {
                opt.setEnv(java.util.Map.of("PLAYWRIGHT_BROWSERS_PATH", browsersPath));
            }
            playwright = Playwright.create(opt);
            BrowserType.LaunchOptions launchOpts = new BrowserType.LaunchOptions().setHeadless(false);
            if ("firefox".equalsIgnoreCase(config.browser())) {
                browser = playwright.firefox().launch(launchOpts);
            } else {
                browser = playwright.chromium().launch(launchOpts);
            }
            context = browser.newContext(new Browser.NewContextOptions()
                .setRecordHarPath(Paths.get(harPath))
                .setRecordHarOmitContent(false));
            page = context.newPage();
            return null;
        });
    }

    public StepExecutionResult executeStep(BrowserStep step) {
        return runOnExecutor(() -> {
            long start = System.currentTimeMillis();
            try {
                runAction(step);
                long duration = System.currentTimeMillis() - start;
                return new StepExecutionResult(step, true, duration, null, null);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                String screenshotPath = takeScreenshotSafe(step.action());
                return new StepExecutionResult(step, false, duration, screenshotPath, e.getMessage());
            }
        });
    }

    private void runAction(BrowserStep step) throws Exception {
        String action = step.action().toLowerCase();
        if ("navigate".equals(action)) {
            String url = step.value();
            if (url == null || url.trim().isEmpty()) {
                url = step.text();
            }
            validateUrl(url);
            page.navigate(url);
        } else if ("wait".equals(action)) {
            page.waitForTimeout(Double.parseDouble(step.value()));
        } else {
            Locator locator = resolveTarget(page, step);
            executeActionOnLocator(locator, step);
        }
    }

    private void executeActionOnLocator(Locator locator, BrowserStep step) {
        String action = step.action().toLowerCase();
        if ("fill".equals(action)) {
            locator.fill(resolveSecret(step.value()));
        } else if ("click".equals(action)) {
            locator.click();
        } else if ("select".equals(action)) {
            locator.selectOption(step.value());
        } else {
            throw new RecordingException("Unsupported action: " + step.action());
        }
    }

    private Locator resolveTarget(Page page, BrowserStep step) throws Exception {
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        List<Callable<Locator>> strategies = buildResolutionStrategies(page, step);
        String action = step.action().toLowerCase();
        for (Callable<Locator> strategy : strategies) {
            try {
                Locator loc = strategy.call();
                loc.first().waitFor(new Locator.WaitForOptions().setTimeout(2500));
                Locator target = getTargetAtIndex(loc, step.index());
                if ("fill".equals(action)) {
                    if (!target.isEditable() || !isFillableElement(target)) {
                        throw new RecordingException("Resolved element is not a fillable input");
                    }
                }
                return target;
            } catch (Exception ignored) {
                // try next strategy
            }
        }
        return llmAssistedResolve(page, step);
    }

    private List<Callable<Locator>> buildResolutionStrategies(Page page, BrowserStep step) {
        List<Callable<Locator>> strategies = new ArrayList<>();
        String text = step.text();
        String roleStr = step.role();
        String action = step.action() != null ? step.action().toLowerCase() : "";

        if (roleStr != null && !roleStr.trim().isEmpty()) {
            strategies.add(() -> page.getByRole(AriaRole.valueOf(roleStr.toUpperCase().trim()), new Page.GetByRoleOptions().setName(text)));
            addComplementaryRole(strategies, page, roleStr, text);
        }
        strategies.add(() -> page.getByLabel(text));
        strategies.add(() -> page.getByPlaceholder(text));
        if ("fill".equals(action)) {
            strategies.add(() -> page.locator("input[name='" + text + "' i], textarea[name='" + text + "' i]"));
            strategies.add(() -> page.locator("input[id='" + text + "' i], textarea[id='" + text + "' i]"));
        }
        strategies.add(() -> page.getByText(text));
        strategies.add(() -> page.locator(text)); // raw CSS/XPath option
        return strategies;
    }

    /**
     * When the planner guesses the wrong interactive role (e.g. link vs button),
     * add the complementary role as a fallback strategy.
     */
    private void addComplementaryRole(List<Callable<Locator>> strategies, Page page, String roleStr, String text) {
        String upper = roleStr.toUpperCase().trim();
        if ("LINK".equals(upper)) {
            strategies.add(() -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(text)));
        } else if ("BUTTON".equals(upper)) {
            strategies.add(() -> page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(text)));
        }
    }

    private Locator getTargetAtIndex(Locator loc, Integer index) {
        if (index != null) {
            return loc.nth(index);
        }
        if (loc.count() > 1) {
            return loc.first();
        }
        return loc;
    }

    /**
     * Checks whether the resolved element is an actual input-capable element (input, textarea, select)
     * rather than a non-input element like a paragraph or div that happens to match the locator.
     */
    private boolean isFillableElement(Locator target) {
        try {
            String tag = target.evaluate("el => el.tagName").toString().toLowerCase();
            return "input".equals(tag) || "textarea".equals(tag) || "select".equals(tag);
        } catch (Exception e) {
            return true; // permissive fallback for contenteditable etc.
        }
    }

    private Locator llmAssistedResolve(Page page, BrowserStep step) throws Exception {
        if (resolver == null) {
            throw new RecordingException("Element locator resolution failed: no strategy matched.");
        }
        String snapshot = page.ariaSnapshot();
        String selector = resolver.resolve(snapshot, step);
        if (selector == null || selector.trim().isEmpty()) {
            throw new RecordingException("LLM resolution failed: empty selector returned.");
        }
        Locator loc = page.locator(selector);
        loc.first().waitFor(new Locator.WaitForOptions().setTimeout(2000));
        return getTargetAtIndex(loc, step.index());
    }

    private void validateUrl(String url) {
        String allowedOrigins = AiConfig.getProperty("jmeter.ai.record.allowed.origins", "");
        if (!isAllowedOrigin(url, config.baseUri(), allowedOrigins)) {
            throw new SecurityException("Navigation to unauthorized origin: " + url);
        }
    }

    public static boolean isAllowedOrigin(String url, String baseUri, String allowedOriginsStr) {
        try {
            java.net.URI targetUri = new java.net.URI(url);
            java.net.URI base = new java.net.URI(baseUri);
            if (targetUri.getHost() == null) {
                return true;
            }
            if (targetUri.getHost().equalsIgnoreCase(base.getHost())) {
                return true;
            }
            if (allowedOriginsStr != null && !allowedOriginsStr.trim().isEmpty()) {
                for (String allowed : allowedOriginsStr.split(",")) {
                    if (targetUri.getHost().equalsIgnoreCase(allowed.trim())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    public static String resolveSecret(String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}");
        java.util.regex.Matcher matcher = pattern.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String envVal = System.getenv(name);
            if (envVal != null) {
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(envVal));
            } else {
                String propVal = org.apache.jmeter.util.JMeterUtils.getProperty(name);
                if (propVal != null) {
                    matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(propVal));
                } else {
                    throw new RecordingException("Unresolved secret placeholder: ${" + name + "}");
                }
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String takeScreenshotSafe(String actionName) {
        try {
            if (page != null) {
                String name = "failure-" + actionName + "-" + System.currentTimeMillis() + ".png";
                Path screenshotPath = Paths.get(System.getProperty("java.io.tmpdir"), name);
                page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath));
                return screenshotPath.toAbsolutePath().toString();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    @Override
    public void close() {
        runOnExecutor(() -> {
            if (context != null) {
                context.close();
            }
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
            return null;
        });
        executor.shutdown();
    }

    private <T> T runOnExecutor(Callable<T> task) {
        try {
            return executor.submit(task).get();
        } catch (Exception e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw new RecordingException("Browser action execution failed: " + e.getMessage(), e);
        }
    }
}
