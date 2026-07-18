package org.qainsights.jmeter.ai.record;

import java.io.File;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jorphan.collections.HashTree;

/**
 * Validates generated JMX structures using SaveService.loadTree.
 */
public final class JmxValidator {

    public void validate(File jmxFile) throws RecordingException {
        if (!jmxFile.isFile() || jmxFile.length() == 0) {
            throw new RecordingException("JMX file is empty or does not exist.");
        }
        try {
            HashTree tree = SaveService.loadTree(jmxFile);
            if (tree == null || tree.isEmpty()) {
                throw new RecordingException("Generated JMX plan is empty.");
            }
            if (!hasHttpSamplers(tree)) {
                throw new RecordingException("Generated JMX plan contains no HTTP samplers.");
            }
        } catch (Exception e) {
            throw new RecordingException("Failed to validate JMX structure: " + e.getMessage(), e);
        }
    }

    private boolean hasHttpSamplers(HashTree tree) {
        for (Object key : tree.keySet()) {
            if (key instanceof HTTPSamplerBase) {
                return true;
            }
            HashTree subTree = tree.get(key);
            if (subTree != null && hasHttpSamplers(subTree)) {
                return true;
            }
        }
        return false;
    }
}
