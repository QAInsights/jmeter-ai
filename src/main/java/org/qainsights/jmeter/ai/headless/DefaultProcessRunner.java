package org.qainsights.jmeter.ai.headless;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Real {@link ProcessRunner} backed by {@link ProcessBuilder}. Streams combined
 * stdout/stderr and enforces a wall-clock timeout.
 */
public class DefaultProcessRunner implements ProcessRunner {

    @Override
    public Result run(List<String> command, File workingDir, long timeoutSeconds,
                      Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir);
        }
        pb.redirectErrorStream(true);
        if (env != null) {
            pb.environment().putAll(env);
        }

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new Result(124, output.toString(), true);
        }
        return new Result(process.exitValue(), output.toString(), false);
    }
}
