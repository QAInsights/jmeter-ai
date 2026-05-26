package org.qainsights.jmeter.ai.correlation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

public class JtlParser {
    private static final Logger log = LoggerFactory.getLogger(JtlParser.class);
    private static final int PROBE_BYTE_COUNT = 512;
    private static final int ENTITY_BUFFER_SIZE = 32;
    private static final char INVALID_XML_CHARACTER_REPLACEMENT = ' ';

    public List<SampleRecord> parse(Path jtlPath) {
        Objects.requireNonNull(jtlPath, "jtlPath");
        validateXmlJtl(jtlPath);
        List<SampleRecord> samples = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setFactoryProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setFactoryProperty(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        setFactoryProperty(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setFactoryProperty(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        try (InputStream inputStream = Files.newInputStream(jtlPath);
             Reader xmlInput = new SanitizingXmlReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            XMLStreamReader reader = factory.createXMLStreamReader(xmlInput);
            Deque<SampleBuilder> sampleStack = new ArrayDeque<>();

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (isSampleElement(name)) {
                        SampleBuilder current = new SampleBuilder(getAttribute(reader, "lb"), parseLong(getAttribute(reader, "ts")));
                        String url = getAttribute(reader, "url");
                        if (!url.isEmpty()) {
                            current.requestUrl = url;
                        }
                        sampleStack.push(current);
                    } else if (!sampleStack.isEmpty()) {
                        readSampleChild(reader, sampleStack.peek(), name);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && !sampleStack.isEmpty() && isSampleElement(reader.getLocalName())) {
                    SampleBuilder current = sampleStack.pop();
                    current.deriveRequestFields();
                    samples.add(current.build());
                }
            }
            reader.close();
        } catch (PluginException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse XML JTL: {}", jtlPath, e);
            throw new PluginException("Failed to parse XML JTL file: " + e.getMessage(), e);
        }

        samples.sort(Comparator.comparingLong(SampleRecord::getTimestamp));
        return samples;
    }

    private static void validateXmlJtl(Path jtlPath) {
        try (InputStream inputStream = Files.newInputStream(jtlPath)) {
            byte[] probe = inputStream.readNBytes(PROBE_BYTE_COUNT);
            if (probe.length == 0) {
                throw new PluginException("The selected JTL file is empty.");
            }
            String prefix = new String(probe, StandardCharsets.UTF_8)
                    .replaceFirst("^\\uFEFF", "")
                    .stripLeading();
            if (prefix.startsWith("<")) {
                return;
            }
            if (looksLikeCsvJtl(prefix)) {
                throw new PluginException("The selected JTL file is CSV. Correlation Engine requires an XML JTL saved with request and response data. Re-run the test with Save as XML enabled and include URL, request headers, request body, response headers, and response data.");
            }
            throw new PluginException("The selected file does not appear to be an XML JTL. Correlation Engine requires an XML JTL saved with request and response data.");
        } catch (PluginException e) {
            throw e;
        } catch (Exception e) {
            throw new PluginException("Unable to inspect JTL file: " + e.getMessage(), e);
        }
    }

    private static boolean looksLikeCsvJtl(String prefix) {
        String lower = prefix.toLowerCase();
        return lower.startsWith("timestamp,") || lower.contains(",elapsed,") || lower.contains(",responsecode,");
    }

    private static void readSampleChild(XMLStreamReader reader, SampleBuilder current, String name) throws Exception {
        switch (name) {
            case "requestHeader":
                current.requestHeaders = reader.getElementText();
                break;
            case "responseHeader":
                current.responseHeaders = reader.getElementText();
                break;
            case "responseData":
                current.responseBody = reader.getElementText();
                break;
            case "samplerData":
                current.samplerData = reader.getElementText();
                break;
            case "queryString":
                current.requestBody = reader.getElementText();
                break;
            case "url":
            case "java.net.URL":
                current.requestUrl = reader.getElementText();
                break;
            default:
                break;
        }
    }

    private static boolean isSampleElement(String name) {
        return "sample".equals(name) || "httpSample".equals(name);
    }

    private static String getAttribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static void setFactoryProperty(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException e) {
            log.debug("XMLInputFactory property is not supported: {}", name);
        }
    }

    private static boolean isValidXmlCharacter(int codePoint) {
        return codePoint == 0x9
                || codePoint == 0xA
                || codePoint == 0xD
                || codePoint >= 0x20 && codePoint <= 0xD7FF
                || codePoint >= 0xE000 && codePoint <= 0xFFFD
                || codePoint >= 0x10000 && codePoint <= 0x10FFFF;
    }

    private static final class SanitizingXmlReader extends Reader {
        private final PushbackReader reader;
        private String pending = "";
        private int pendingIndex;

        private SanitizingXmlReader(Reader reader) {
            this.reader = new PushbackReader(reader, ENTITY_BUFFER_SIZE);
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws java.io.IOException {
            if (length == 0) {
                return 0;
            }
            int count = 0;
            while (count < length) {
                int value = read();
                if (value < 0) {
                    return count == 0 ? -1 : count;
                }
                buffer[offset + count] = (char) value;
                count++;
            }
            return count;
        }

        @Override
        public int read() throws java.io.IOException {
            if (pendingIndex < pending.length()) {
                return pending.charAt(pendingIndex++);
            }
            pending = "";
            pendingIndex = 0;

            int value = reader.read();
            if (value < 0) {
                return -1;
            }
            if (value == '&') {
                return readEntityOrAmpersand();
            }
            return isValidXmlCharacter(value) ? value : INVALID_XML_CHARACTER_REPLACEMENT;
        }

        @Override
        public void close() throws java.io.IOException {
            reader.close();
        }

        private int readEntityOrAmpersand() throws java.io.IOException {
            int next = reader.read();
            if (next < 0) {
                return '&';
            }
            if (next != '#') {
                reader.unread(next);
                return '&';
            }

            StringBuilder entity = new StringBuilder();
            entity.append('&').append((char) next);
            while (entity.length() < ENTITY_BUFFER_SIZE) {
                int value = reader.read();
                if (value < 0) {
                    break;
                }
                entity.append((char) value);
                if (value == ';') {
                    return resolveNumericEntity(entity.toString());
                }
            }

            unreadAfterAmpersand(entity);
            return '&';
        }

        private int resolveNumericEntity(String entity) {
            try {
                int codePoint;
                if (entity.startsWith("&#x") || entity.startsWith("&#X")) {
                    codePoint = Integer.parseInt(entity.substring(3, entity.length() - 1), 16);
                } else {
                    codePoint = Integer.parseInt(entity.substring(2, entity.length() - 1));
                }
                if (!isValidXmlCharacter(codePoint)) {
                    return INVALID_XML_CHARACTER_REPLACEMENT;
                }
                pending = entity;
                pendingIndex = 1;
                return entity.charAt(0);
            } catch (NumberFormatException e) {
                pending = entity;
                pendingIndex = 1;
                return entity.charAt(0);
            }
        }

        private void unreadAfterAmpersand(StringBuilder entity) throws java.io.IOException {
            for (int i = entity.length() - 1; i > 0; i--) {
                reader.unread(entity.charAt(i));
            }
        }
    }

    private static final class SampleBuilder {
        private final String label;
        private final long timestamp;
        private String requestUrl = "";
        private String requestHeaders = "";
        private String requestBody = "";
        private String responseHeaders = "";
        private String responseBody = "";
        private String samplerData = "";

        private SampleBuilder(String label, long timestamp) {
            this.label = label;
            this.timestamp = timestamp;
        }

        private void deriveRequestFields() {
            if (samplerData == null || samplerData.isEmpty()) {
                return;
            }
            String[] lines = samplerData.split("\\R", -1);
            if (requestUrl.isEmpty() && lines.length > 0) {
                String firstLine = lines[0].trim();
                int firstSpace = firstLine.indexOf(' ');
                int secondSpace = firstSpace < 0 ? -1 : firstLine.indexOf(' ', firstSpace + 1);
                if (firstSpace >= 0 && secondSpace > firstSpace) {
                    requestUrl = firstLine.substring(firstSpace + 1, secondSpace).trim();
                } else if (firstLine.startsWith("http://") || firstLine.startsWith("https://")) {
                    requestUrl = firstLine;
                }
            }
            if (requestBody.isEmpty()) {
                int separator = samplerData.indexOf("\n\n");
                if (separator >= 0 && separator + 2 < samplerData.length()) {
                    requestBody = samplerData.substring(separator + 2).trim();
                }
            }
        }

        private SampleRecord build() {
            return new SampleRecord(label, requestUrl, requestHeaders, requestBody, responseHeaders, responseBody, timestamp);
        }
    }
}
