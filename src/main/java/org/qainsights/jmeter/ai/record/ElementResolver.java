package org.qainsights.jmeter.ai.record;

/**
 * Interface to resolve an element target using LLM and accessibility snapshot.
 */
public interface ElementResolver {
    String resolve(String snapshot, BrowserStep step) throws Exception;
}
