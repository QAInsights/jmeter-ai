**Feather Wand -- Correlation Engine Implementation Plan**

---

**Overview**

User runs a JMeter test with one thread and saves the output as XML JTL with response data enabled. Feather Wand reads that JTL, detects dynamic values, matches them across requests, and suggests JMeter extractors via LLM. User reviews and confirms in a Swing UI.

---

**Step 1 -- JTL Parsing**

- Accept XML JTL file via file chooser in the plugin UI
- Use a streaming XML parser (`javax.xml.stream.XMLStreamReader`) -- do not load the whole file into memory
- For each `<sample>` or `<httpSample>` element, extract:
  - Sample label
  - Request URL
  - Request headers
  - Request body
  - Response headers
  - Response body
  - Timestamp (for ordering)
- Store as an ordered list of `SampleRecord` objects

---

**Step 2 -- Dynamic Value Detection**

For each sample's response headers and response body, extract candidate values using these rules:

**Pattern matching**
- UUID: `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`
- Base64: `[A-Za-z0-9+/]{20,}={0,2}`
- Long hex: `[0-9a-fA-F]{16,}`
- JWT: three Base64 segments separated by dots

**Known token names**
- `JSESSIONID`, `sessionId`, `_token`, `csrf`, `access_token`, `id_token`, `code`, `state`, `nonce`

**Entropy check**
- For any value longer than 10 characters, compute Shannon entropy
- Flag if entropy is above 3.5 bits per character

**Exclusions**
- Pure numeric values under 10 digits
- Dictionary words
- Known static strings (`application/json`, `en-US`, `true`, `false`, `null`)

---

**Step 3 -- Cross-Request Matching**

For each detected candidate value in response N:

- URL-decode the value for normalization
- Search in all subsequent requests (N+1 onward) across:
  - URL query parameters
  - Request headers
  - Request body (form-encoded, JSON, XML)
- If found, record:
  - Source sample (where value came from in response)
  - Target sample (where value is used in request)
  - Location in response (header name or JSON path or regex context)
  - Location in request (query param name, header name, body field name)

---

**Step 4 -- Swing Review UI**

Display a table with one row per correlation candidate:

| Sampler | Variable Name | Extractor Type | Expression | Source Location | Status |
|---|---|---|---|---|---|
| Login | csrf_token | Regex | `token":"(.+?)"` | Response Body | Pending |

Controls per row:
- Edit expression inline
- Edit variable name inline
- Change extractor type via dropdown
- Approve or reject checkbox

Bottom actions:
- Apply Approved -- injects extractors into the JMX open in JMeter
- Export as CSV

---

**Step 5 -- Extractor Injection**

For each approved candidate:
- Locate the source sampler in the active JMeter test plan via `GuiPackage`
- Add the appropriate extractor as a child element:
  - `RegexExtractor`
  - `JSONPathExtractor`
  - `BoundaryExtractor`
- Set variable name, expression, match number
- Refresh the JMeter tree UI

---

**Data Model**

```java
class SampleRecord {
    String label;
    String requestUrl;
    String requestHeaders;
    String requestBody;
    String responseHeaders;
    String responseBody;
    long timestamp;
}

class CorrelationCandidate {
    String value;
    String variableName;
    SampleRecord sourceResponse;
    SampleRecord targetRequest;
    String responseLocation;
    String requestLocation;
    ExtractorSuggestion suggestion;
    CandidateStatus status; // PENDING, APPROVED, REJECTED
}

class ExtractorSuggestion {
    String extractorType;
    String expression;
    String variableName;
    String matchNo;
}
```

---

**Tech Constraints**

- Java only, no external libraries beyond what JMeter bundles
- LLM call goes through Feather Wand's existing API client
- Streaming XML parser to handle large JTL files
- Single thread JTL assumption -- no thread isolation logic needed

---
 