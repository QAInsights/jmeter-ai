package org.apache.jmeter.protocol.jdbc.sampler;

import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.AbstractTestElement;

public final class FakeJDBCSampler extends AbstractTestElement implements Sampler {
    @Override
    public SampleResult sample(Entry entry) {
        return new SampleResult();
    }
}
