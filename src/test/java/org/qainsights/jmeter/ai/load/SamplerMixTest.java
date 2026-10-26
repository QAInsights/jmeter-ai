package org.qainsights.jmeter.ai.load;

import java.util.List;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SamplerMixTest {

    @Test
    void classifiesProtocolPackagesAndSpecialSamplerTypes() {
        SamplerMix mix = SamplerMix.classify(List.of(
                HTTPSamplerProxy.class, HTTPSamplerProxy.class,
                KafkaProducerSampler.class,
                String.class));

        assertEquals("2 HTTP, 1 Kafka, 1 Other", mix.display());
        assertEquals(SamplerMix.Kind.JSR223,
                SamplerMix.classifyClassName("org.apache.jmeter.protocol.java.sampler.JSR223Sampler"));
        assertEquals(SamplerMix.Kind.JDBC,
                SamplerMix.classifyClassName("org.apache.jmeter.protocol.jdbc.sampler.JDBCSampler"));
        assertEquals(SamplerMix.Kind.JMS,
                SamplerMix.classifyClassName("org.apache.jmeter.protocol.jms.sampler.JMSSampler"));
        assertEquals(SamplerMix.Kind.SMTP,
                SamplerMix.classifyClassName("org.apache.jmeter.protocol.smtp.sampler.SmtpSampler"));
        assertEquals(SamplerMix.Kind.TCP,
                SamplerMix.classifyClassName("org.apache.jmeter.protocol.tcp.sampler.TCPSampler"));
    }

    private static final class KafkaProducerSampler {
    }
}
