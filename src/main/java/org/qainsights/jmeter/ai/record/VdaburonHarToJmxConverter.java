package org.qainsights.jmeter.ai.record;

import io.github.vdaburon.jmeter.har.HarForJMeter;
import java.io.File;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * In-process implementation of {@link HarToJmxConverter} calling the vdaburon converter API directly.
 */
public final class VdaburonHarToJmxConverter implements HarToJmxConverter {

    private static final String DEFAULT_EXCLUDE_REGEX = 
        "(?i).*\\.(bmp|css|gif|ico|jpe?g|png|swf|woff2?|js|json|xml|txt|svg)(\\?.*)?|.*analytics.*|.*beacon.*";

    @Override
    public HarConversionResult convert(HarConversionRequest request) throws RecordingException {
        validatePaths(request);
        try {
            String filterInclude = AiConfig.getProperty("jmeter.ai.record.filter.include", "");
            String filterExclude = AiConfig.getProperty("jmeter.ai.record.filter.exclude", DEFAULT_EXCLUDE_REGEX);

            HarForJMeter.generateJmxAndRecord(
                request.harPath(),
                HarForJMeter.K_JACKSON_PARSER_STRING_MAX_DEFAULT,
                request.jmxOutputPath(),
                3600000L,
                false,
                true,
                true,
                filterInclude,
                filterExclude,
                request.recordXmlPath(),
                1,
                1,
                request.stepMarkersPath(),
                false,
                false,
                ""
            );
            return new HarConversionResult(true, null, 0, 0);
        } catch (Exception e) {
            throw new RecordingException("Failed to convert HAR to JMX: " + e.getMessage(), e);
        }
    }

    private void validatePaths(HarConversionRequest request) {
        if (request.harPath() == null || !new File(request.harPath()).isFile()) {
            throw new RecordingException("Invalid input HAR path: " + request.harPath());
        }
        if (request.jmxOutputPath() == null || request.recordXmlPath() == null) {
            throw new RecordingException("Output paths are required.");
        }
    }
}
