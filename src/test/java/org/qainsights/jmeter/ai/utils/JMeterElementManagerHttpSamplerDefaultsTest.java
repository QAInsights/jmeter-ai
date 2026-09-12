package org.qainsights.jmeter.ai.utils;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JMeterElementManagerHttpSamplerDefaultsTest {

    @Test
    void setsStandardHttpSamplerDefaults() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();

        JMeterElementManager.initializeHttpSamplerDefaults(sampler);

        assertEquals("GET", sampler.getMethod());
        assertTrue(sampler.getFollowRedirects());
        assertTrue(sampler.getUseKeepAlive());
    }
}
