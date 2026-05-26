package org.qainsights.jmeter.ai.correlation;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class CorrelationAnalyzer {
    private final JtlParser parser;
    private final DynamicValueDetector detector;
    private final CrossRequestMatcher matcher;
    private final CorrelationAiSuggestionService suggestionService;

    public CorrelationAnalyzer() {
        this(new JtlParser(), new DynamicValueDetector(), new CrossRequestMatcher(), new CorrelationAiSuggestionService());
    }

    CorrelationAnalyzer(JtlParser parser, DynamicValueDetector detector, CrossRequestMatcher matcher,
                        CorrelationAiSuggestionService suggestionService) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.suggestionService = Objects.requireNonNull(suggestionService, "suggestionService");
    }

    public CorrelationAnalysisResult analyze(Path jtlPath) {
        List<SampleRecord> samples = parser.parse(jtlPath);
        List<CorrelationCandidate> candidates = matcher.findMatches(samples, detector);
        suggestionService.enhance(candidates);
        return new CorrelationAnalysisResult(samples, candidates);
    }
}
