package org.qainsights.jmeter.ai.record;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.function.Consumer;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Installs Playwright browser binaries by executing com.microsoft.playwright.CLI in a child JVM.
 */
public class PlaywrightBrowserInstaller {

    public void install(Consumer<String> progressListener, Runnable onComplete, Consumer<Exception> onError) {
        new Thread(() -> {
            try {
                Process process = startInstallProcess();
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (progressListener != null) {
                            progressListener.accept(line);
                        }
                    }
                }
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("Installation failed with exit code " + exitCode);
                }
                if (onComplete != null) {
                    onComplete.run();
                }
            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
            }
        }).start();
    }

    Process startInstallProcess() throws Exception {
        String java = getJavaExecutable();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(
            java, "-cp", classpath, "com.microsoft.playwright.CLI", "install", "chromium", "firefox"
        );
        String browsersPath = AiConfig.getProperty("jmeter.ai.record.playwright.browsers.path", "");
        if (!browsersPath.isEmpty()) {
            pb.environment().put("PLAYWRIGHT_BROWSERS_PATH", browsersPath);
        }
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        String extension = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
        return new File(javaHome, "bin/java" + extension).getAbsolutePath();
    }
}
