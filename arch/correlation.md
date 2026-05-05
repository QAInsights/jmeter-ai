# Feather Wand - Correlation Engine Implementation Blueprint

A four-phase plan to ship a LoadRunner-class correlation engine for the Feather Wand JMeter plugin: Phase 1 delivers a manual studio built on a heuristic rule library, a live "Test Expression" loop, and single-checkpoint undo; Phases 2–4 layer AI classification, run-twice diff validation, and community rule sharing on top of the same data model.

---

## 0. Decisions Locked In (from clarifying round)

| # | Decision | Implication |
|---|---|---|
| D1 | Multi-source capture via `CaptureSource` interface (JTL+response bodies, HAR, live listener, plus prev-request/response, headers, URL params) | Scanner never touches I/O directly; one pipeline handles every input. |
| D2 | Entry point: JMenu item under existing AI menu → modeless `CorrelationStudioFrame` (JFrame) | Mirrors `ClaudeCodeMenuItem` wiring; user keeps tree interaction. |
| D3 | Rules directory configurable via `jmeter.ai.correlation.rules.dir` (default `${JMETER_HOME}/bin/feather-wand/rules`); classpath built-ins always loaded first, user files override by `ruleId` | Commercial-safe: built-ins travel in JAR, overridable without rebuild. |
| D4 | Phase 1 auto-names variables as `CORR_<sanitizedParamName>_<seq>`; AI names in Phase 2; **all four extractor types** (Regex, JSON, XPath2, Boundary) live from Phase 1, chosen by Content-Type + rule hint | No dependency on AI for a shippable Phase 1. |

---

## 1. Architecture Overview

```
+-- correlation (new top-level package: org.qainsights.jmeter.ai.correlation) ---+
|                                                                                |
|  capture/            Capture sources (JTL, HAR, LiveListener, InMemory)        |
|  model/              Immutable records: Candidate, Evidence, RuleMatch, ...    |
|  rules/              .jrules loader, schema, built-in resources                |
|  engine/             Fingerprinter, Normalizer, OccurrenceAnalyzer,            |
|                      ChainResolver, PlacementValidator, Applier                |
|  ai/    (Phase 2)    CorrelationAiClient, tool schema, fallback                |
|  diff/  (Phase 3)    RunTwiceDiffer, DiffCandidateAdapter                      |
|  undo/               CorrelationCheckpoint (wraps JMeter UndoHistory)          |
|  ui/                 CorrelationStudioFrame + four zone panels                 |
|  ui/test/            TestExpressionDialog + ExpressionRunner                   |
|  command/            CorrelateCommandHandler (optional @correlate alias)       |
|                                                                                |
+--------------------------------------------------------------------------------+
```

Layering rule: `ui → engine → model`, `engine → rules/capture`, never upward. AI and diff are pluggable consumers of `engine` output.

---

# Phase 1 - Manual Correlation Studio (Shippable)

Goal: an engineer opens the studio, points it at captured traffic, sees every plausible dynamic value with evidence, proves each extractor live via **Test Expression**, and applies correlations one at a time with instant undo. No AI, no run-twice. Handles ~55–65% of real-world correlations (session cookies, CSRF tokens, ViewState, JSESSIONID, OAuth codes in redirects - everything the rule library covers).

## 1.1 Data Model

All model types are Java 17 `record`s, immutable, in `org.qainsights.jmeter.ai.correlation.model`.

```java
/** A single captured request/response pair, source-agnostic. */
public record CapturedExchange(
    String exchangeId,              // stable UUID
    int sequence,                   // 0-based order in the capture
    String threadGroupName,         // scope key (null if unknown, e.g. HAR)
    String samplerName,             // target JMeter sampler name to bind to
    String url,                     // full URL
    String method,                  // GET, POST, ...
    Map<String,String> requestHeaders,
    Map<String,String> requestCookies,
    Map<String,String> requestQueryParams,   // parsed from URL
    Map<String,String> requestBodyParams,    // parsed form params or null
    String requestBodyRaw,
    int responseStatus,
    Map<String,String> responseHeaders,      // includes Set-Cookie split per value
    String responseContentType,
    byte[] responseBodyBytes,                // raw, undecoded
    String responseBodyDecoded,              // after gzip/br + charset decoding
    List<CapturedExchange> subResults        // JMeter redirects / embedded
) {}

/** A dynamic-value candidate produced by the fingerprinter. */
public record Candidate(
    String candidateId,             // UUID
    String paramName,               // e.g. "_csrf", "__VIEWSTATE"
    String rawValue,                // observed value
    String truncatedValue,          // <= 60 chars for UI
    Confidence confidence,          // HIGH / MEDIUM / LOW / INVALID
    String ruleId,                  // matched rule id, or "heuristic.generic"
    ExtractorKind extractorKind,
    String extractorExpression,     // regex / JSONPath / XPath / boundary triple
    String suggestedVariableName,   // CORR_csrf_1
    SourceLocation foundIn,         // where the value was produced
    List<UsageSite> usages,         // downstream requests that hardcode it
    OccurrenceInfo occurrence,      // match-count + which index to extract
    List<String> tags,              // rule-declared tags (framework, jwt, oauth)
    String reasonSummary            // 1-line human rationale
) {}

public enum Confidence { HIGH, MEDIUM, LOW, INVALID }

public enum ExtractorKind {
    REGEX,          // RegexExtractor
    JSON_JMESPATH,  // JSONPostProcessor (JMESPath form used by JMeter 5.6 JSON Extractor)
    XPATH2,         // XPath2Extractor
    BOUNDARY        // BoundaryExtractor
}

/** Where the value was emitted by the server. */
public record SourceLocation(
    String exchangeId,
    SourceChannel channel,          // RESP_BODY, RESP_HEADER, SET_COOKIE, REDIRECT_LOCATION, SUB_RESULT
    String channelName,             // header name, cookie name, or null
    int offset,                     // char index in the decoded channel text
    int length
) {}

public enum SourceChannel {
    RESP_BODY, RESP_HEADER, SET_COOKIE, REDIRECT_LOCATION, SUB_RESULT_BODY, SUB_RESULT_HEADER
}

/** Where the value was hardcoded in a later request. */
public record UsageSite(
    String exchangeId,
    String samplerName,
    UsageChannel channel,           // REQ_BODY, REQ_HEADER, QUERY, PATH_SEGMENT, COOKIE
    String channelName,             // header name / param name / cookie name
    int offset, int length,
    String encodingApplied          // "url", "base64", "html", "none"
) {}

public enum UsageChannel {
    REQ_BODY, REQ_HEADER, QUERY, PATH_SEGMENT, COOKIE, FORM_PARAM
}

/** Occurrence analysis result. */
public record OccurrenceInfo(
    int totalMatches,               // how many times the *value* appears in channel
    int selectedIndex,              // 1-based match index the extractor will pick
    boolean ambiguous,              // >1 match with no deterministic disambiguation
    String disambiguationReason     // "first occurrence", "preceded by 'name=_csrf'", etc.
) {}

/** Snippet shown in the Evidence panel. */
public record EvidenceSnippet(
    String exchangeId,
    String channelLabel,            // "Response body", "Set-Cookie: JSESSIONID", ...
    String beforeContext,           // 60 chars before
    String matchedText,             // highlighted
    String afterContext,            // 60 chars after
    int absoluteOffset
) {}

/** A single apply-able operation captured in a checkpoint. */
public record AppliedCorrelation(
    Candidate candidate,
    String extractorNodeName,       // as placed in the tree
    String createdVariableName,
    List<ReplacementRecord> replacements,
    Instant appliedAt
) {}

public record ReplacementRecord(
    String samplerName,
    UsageChannel channel,
    String channelName,
    String oldValue,
    String newValue                 // ${CORR_foo_1}
) {}

/** In-memory store for all captured exchanges during a studio session. */
public record CaptureSet(
    String sourceLabel,             // "listener-run-2025-05-04T21:10Z" / "har:login.har"
    Instant capturedAt,
    List<CapturedExchange> exchanges
) {}
```

## 1.2 Core Interfaces

```java
/** Reads raw traffic from some source and yields a CaptureSet. */
public interface CaptureSource {
    String id();                         // "jtl", "har", "listener", "inmemory"
    String displayName();                // for UI dropdown
    CaptureSet load(CaptureSourceConfig cfg) throws CaptureException;
}

public record CaptureSourceConfig(
    Path primaryPath,                    // JTL file, HAR file, etc.
    Path responseBodiesDir,              // optional, for JTL
    Map<String,String> extra
) {}

/** Built-in implementations:
 *   JtlCaptureSource         - parses CSV/XML JTL + adjacent response bodies
 *   HarCaptureSource         - parses HAR 1.2 JSON
 *   LiveListenerCaptureSource- registers a SampleListener before run, collects in memory
 *   InMemoryCaptureSource    - adapts pre-built List<CapturedExchange> (tests, chaining)
 */

/** Produces Candidates from a CaptureSet given a rule set. */
public interface Fingerprinter {
    List<Candidate> scan(CaptureSet capture, RuleSet rules, ProgressSink progress);
}

/** Normalizes a raw value so the same token can be recognised across encodings. */
public interface ValueNormalizer {
    Set<String> normalize(String rawValue);   // returns {raw, urlDecoded, base64Decoded, htmlDecoded}
}

/** Detects how many times a value appears in a response channel and picks an index. */
public interface OccurrenceAnalyzer {
    OccurrenceInfo analyze(String channelText, String value, RuleMatch hint);
}

/** Validates that an extractor on exchange X can be placed before first usage in exchange Y. */
public interface PlacementValidator {
    PlacementReport validate(Candidate candidate, JMeterTreeModel tree);
}

public record PlacementReport(boolean ok, String reasonIfInvalid, JMeterTreeNode proposedParent) {}

/** Applies a Candidate to the tree: creates extractor, replaces usages, emits undo entry. */
public interface Applier {
    AppliedCorrelation apply(Candidate candidate) throws ApplyException;
}

/** Runs an extractor expression live against a stored body - backs the Test Expression button. */
public interface ExpressionRunner {
    TestResult run(ExtractorKind kind, String expression, CapturedExchange exchange, SourceChannel channel);
}

public record TestResult(
    boolean expressionValid,
    String compileError,            // non-null if the regex/jsonpath failed to compile
    List<Match> matches,            // every match with offset & captured group
    String channelTextPreview       // full text for UI rendering
) {}

public record Match(int index, int offset, int length, String captured, String fullMatch) {}

/** Single undo/redo checkpoint wrapping UndoHistory. */
public interface CorrelationCheckpoint {
    String id();
    String description();
    void undo();
    void redo();
}
```

### JMeter internals touched

| JMeter class | Used for | Thread |
|---|---|---|
| `GuiPackage.getInstance()` | entry into tree/GUI | EDT only |
| `JMeterTreeModel` | add/remove extractor nodes, nodeChanged | EDT |
| `JMeterTreeNode` | target sampler + parent resolution | EDT |
| `SaveService.saveElement` | snapshot pre-apply state to build undo | background OK |
| `HTTPSamplerBase` / `HeaderManager` | locate path, query, headers | read-only on background; mutate on EDT |
| `RegexExtractor`, `JSONPostProcessor`, `XPath2Extractor`, `BoundaryExtractor` | extractor node types | instantiate on EDT |
| `UndoHistory` (via `GuiPackage.getTreeModel().getUndoHistory()`) | single checkpoint per Apply | EDT |
| `SampleListener` (Phase 1 live source only) | live capture | listener thread, but copy into immutable `CapturedExchange` before handing off |

### Thread safety contract

- All scanning runs on a `SwingWorker<List<Candidate>, Integer>`; publishes progress via `process()`.
- `CapturedExchange` is immutable after construction (defensive copies of maps).
- UI updates only on EDT. Any tree mutation wrapped in `SwingUtilities.invokeLater` if called off-EDT.
- `Fingerprinter` implementations must be stateless or synchronise on `this` - documented in Javadoc.

## 1.3 Rule Library

### Schema (`.jrules` is JSON)

```json
{
  "schemaVersion": 1,
  "ruleSetId": "builtin.webapp.v1",
  "ruleSetName": "Built-in Web Application Rules",
  "rules": [
    {
      "id": "aspnet.viewstate",
      "name": "ASP.NET __VIEWSTATE",
      "tags": ["aspnet", "webforms", "hidden-input"],
      "technology": "aspnet",
      "confidencePolicy": "HIGH",
      "paramName": "__VIEWSTATE",
      "detectors": [
        {
          "channel": "RESP_BODY",
          "contentType": "text/html",
          "extractorKind": "REGEX",
          "expression": "name=\"__VIEWSTATE\"[^>]*value=\"([^\"]+)\"",
          "group": 1
        }
      ],
      "usageHints": [
        { "channel": "REQ_BODY",   "paramName": "__VIEWSTATE" },
        { "channel": "FORM_PARAM", "paramName": "__VIEWSTATE" }
      ],
      "variableNameTemplate": "CORR_viewstate_{seq}",
      "preferredExtractor": "REGEX",
      "occurrenceStrategy": "FIRST",
      "valueCharset": "base64",
      "reason": "ASP.NET WebForms view state token rotated on every postback."
    }
  ]
}
```

Fields:

| Field | Purpose |
|---|---|
| `id` | globally unique; user files override by matching id. |
| `tags` | surfaces in UI filter chips. |
| `technology` | grouping for telemetry + filter. |
| `confidencePolicy` | `HIGH` / `MEDIUM` / `LOW` default when this rule fires. |
| `paramName` | canonical parameter name reported in the candidates table. |
| `detectors[]` | ordered OR: first match wins. Each has `channel`, optional `contentType`, `extractorKind`, `expression`, capturing `group`. |
| `usageHints[]` | tells the Fingerprinter where to search for usages - prevents whole-body scans for every candidate. |
| `variableNameTemplate` | `{seq}` replaced with first free integer per thread group. |
| `preferredExtractor` | overrides Content-Type-based default. |
| `occurrenceStrategy` | `FIRST` / `LAST` / `UNIQUE` (fail if >1) / `BY_SIBLING_KEY:<key>`. |
| `valueCharset` | `any` / `base64` / `hex` / `uuid` / `jwt` - used by `ValueNormalizer` to suppress false positives. |
| `reason` | pulled into Evidence panel. |

### Loader

```java
public final class RuleLibrary {
    private final Map<String, CorrelationRule> byId = new LinkedHashMap<>();

    public static RuleLibrary load() {
        RuleLibrary lib = new RuleLibrary();
        // 1. Classpath built-ins (always)
        lib.loadClasspath("/rules/builtin.webapp.v1.jrules");
        lib.loadClasspath("/rules/builtin.oauth.v1.jrules");
        lib.loadClasspath("/rules/builtin.jwt.v1.jrules");
        lib.loadClasspath("/rules/builtin.java.v1.jrules");
        lib.loadClasspath("/rules/builtin.php.v1.jrules");
        lib.loadClasspath("/rules/builtin.aspnet.v1.jrules");
        lib.loadClasspath("/rules/builtin.generic.v1.jrules");
        // 2. User directory overrides (property-driven)
        Path userDir = Paths.get(AiConfig.getProperty(
            "jmeter.ai.correlation.rules.dir",
            JMeterUtils.getJMeterHome() + "/bin/feather-wand/rules"));
        if (Files.isDirectory(userDir)) {
            try (var stream = Files.list(userDir)) {
                stream.filter(p -> p.toString().endsWith(".jrules"))
                      .sorted().forEach(lib::loadFile);
            } catch (IOException e) { log.warn("rule dir read failed", e); }
        }
        return lib;
    }
}
```

### Built-in Rule Content (complete, not placeholders)

**`builtin.webapp.v1.jrules`** - Generic + framework-specific HTML apps

```json
{
  "schemaVersion": 1, "ruleSetId": "builtin.webapp.v1",
  "ruleSetName": "Generic Web App + Frameworks",
  "rules": [
    {
      "id": "aspnet.viewstate",
      "name": "ASP.NET __VIEWSTATE", "technology": "aspnet",
      "tags": ["aspnet","hidden-input"], "confidencePolicy": "HIGH",
      "paramName": "__VIEWSTATE",
      "detectors": [{"channel":"RESP_BODY","contentType":"text/html",
        "extractorKind":"REGEX",
        "expression":"name=\"__VIEWSTATE\"[^>]*value=\"([^\"]+)\"","group":1}],
      "usageHints":[{"channel":"FORM_PARAM","paramName":"__VIEWSTATE"}],
      "variableNameTemplate":"CORR_viewstate_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST",
      "valueCharset":"base64",
      "reason":"WebForms view state rotates per postback."
    },
    {
      "id": "aspnet.eventvalidation",
      "name": "ASP.NET __EVENTVALIDATION", "technology":"aspnet",
      "tags":["aspnet","hidden-input"], "confidencePolicy":"HIGH",
      "paramName":"__EVENTVALIDATION",
      "detectors":[{"channel":"RESP_BODY","contentType":"text/html",
        "extractorKind":"REGEX",
        "expression":"name=\"__EVENTVALIDATION\"[^>]*value=\"([^\"]+)\"","group":1}],
      "usageHints":[{"channel":"FORM_PARAM","paramName":"__EVENTVALIDATION"}],
      "variableNameTemplate":"CORR_eventvalidation_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST",
      "valueCharset":"base64",
      "reason":"WebForms event validation token."
    },
    {
      "id": "aspnet.viewstategenerator",
      "name":"ASP.NET __VIEWSTATEGENERATOR", "technology":"aspnet",
      "tags":["aspnet"], "confidencePolicy":"HIGH",
      "paramName":"__VIEWSTATEGENERATOR",
      "detectors":[{"channel":"RESP_BODY","contentType":"text/html",
        "extractorKind":"REGEX",
        "expression":"name=\"__VIEWSTATEGENERATOR\"[^>]*value=\"([A-F0-9]{8})\"","group":1}],
      "usageHints":[{"channel":"FORM_PARAM","paramName":"__VIEWSTATEGENERATOR"}],
      "variableNameTemplate":"CORR_viewstategen_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"hex",
      "reason":"8-hex generator id paired with __VIEWSTATE."
    },
    {
      "id":"aspnet.antiforgery",
      "name":"ASP.NET __RequestVerificationToken", "technology":"aspnet",
      "tags":["aspnet","csrf"], "confidencePolicy":"HIGH",
      "paramName":"__RequestVerificationToken",
      "detectors":[
        {"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"","group":1},
        {"channel":"SET_COOKIE","extractorKind":"REGEX",
         "expression":"__RequestVerificationToken[^=]*=([^;]+)","group":1}],
      "usageHints":[
        {"channel":"FORM_PARAM","paramName":"__RequestVerificationToken"},
        {"channel":"REQ_HEADER","paramName":"RequestVerificationToken"},
        {"channel":"COOKIE","paramName":"__RequestVerificationToken"}],
      "variableNameTemplate":"CORR_antiforgery_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"ASP.NET MVC/Core anti-forgery token (cookie + form pair)."
    },
    {
      "id":"aspnet.sessionid",
      "name":"ASP.NET_SessionId cookie","technology":"aspnet",
      "tags":["aspnet","session","cookie"],"confidencePolicy":"HIGH",
      "paramName":"ASP.NET_SessionId",
      "detectors":[{"channel":"SET_COOKIE","extractorKind":"REGEX",
         "expression":"ASP\\.NET_SessionId=([^;]+)","group":1}],
      "usageHints":[{"channel":"COOKIE","paramName":"ASP.NET_SessionId"}],
      "variableNameTemplate":"CORR_aspsession_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"IIS session cookie."
    },
    {
      "id":"sharepoint.formdigest",
      "name":"SharePoint X-RequestDigest","technology":"aspnet",
      "tags":["aspnet","sharepoint"],"confidencePolicy":"HIGH",
      "paramName":"FormDigestValue",
      "detectors":[
        {"channel":"RESP_BODY","contentType":"application/json","extractorKind":"JSON_JMESPATH",
         "expression":"d.GetContextWebInformation.FormDigestValue"},
        {"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"__REQUESTDIGEST\"[^>]*value=\"([^\"]+)\"","group":1}],
      "usageHints":[{"channel":"REQ_HEADER","paramName":"X-RequestDigest"}],
      "variableNameTemplate":"CORR_formdigest_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"SharePoint CSRF digest from /_api/contextinfo."
    },
    {
      "id":"java.jsessionid.cookie",
      "name":"JSESSIONID cookie","technology":"java-ee",
      "tags":["java","session","cookie"],"confidencePolicy":"HIGH",
      "paramName":"JSESSIONID",
      "detectors":[{"channel":"SET_COOKIE","extractorKind":"REGEX",
         "expression":"JSESSIONID=([^;]+)","group":1}],
      "usageHints":[{"channel":"COOKIE","paramName":"JSESSIONID"}],
      "variableNameTemplate":"CORR_jsessionid_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"Servlet container session cookie."
    },
    {
      "id":"java.jsessionid.urlrewrite",
      "name":"JSESSIONID URL rewriting","technology":"java-ee",
      "tags":["java","session"],"confidencePolicy":"MEDIUM",
      "paramName":"jsessionid",
      "detectors":[{"channel":"RESP_BODY","extractorKind":"REGEX",
         "expression":";jsessionid=([A-F0-9.]+)","group":1}],
      "usageHints":[{"channel":"PATH_SEGMENT","paramName":"jsessionid"}],
      "variableNameTemplate":"CORR_jsessionid_url_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"URL-rewritten session id for cookie-less clients."
    },
    {
      "id":"jsf.viewstate",
      "name":"JSF javax.faces.ViewState","technology":"java-ee",
      "tags":["jsf","hidden-input"],"confidencePolicy":"HIGH",
      "paramName":"javax.faces.ViewState",
      "detectors":[
        {"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"name=\"javax\\.faces\\.ViewState\"[^>]*value=\"([^\"]+)\"","group":1},
        {"channel":"RESP_BODY","contentType":"text/xml","extractorKind":"REGEX",
         "expression":"<update id=\"javax\\.faces\\.ViewState[^\"]*\"><!\\[CDATA\\[([^\\]]+)\\]\\]>","group":1}],
      "usageHints":[{"channel":"FORM_PARAM","paramName":"javax.faces.ViewState"}],
      "variableNameTemplate":"CORR_jsfviewstate_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"LAST",
      "valueCharset":"any",
      "reason":"JSF partial response + full page both contain ViewState; LAST wins for AJAX flows."
    },
    {
      "id":"spring.csrf.hidden",
      "name":"Spring Security _csrf hidden input","technology":"spring",
      "tags":["spring","csrf"],"confidencePolicy":"HIGH",
      "paramName":"_csrf",
      "detectors":[
        {"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"name=\"_csrf\"[^>]*value=\"([a-f0-9\\-]{36})\"","group":1},
        {"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"<meta name=\"_csrf\" content=\"([^\"]+)\"","group":1}],
      "usageHints":[
        {"channel":"FORM_PARAM","paramName":"_csrf"},
        {"channel":"REQ_HEADER","paramName":"X-CSRF-TOKEN"},
        {"channel":"REQ_HEADER","paramName":"X-XSRF-TOKEN"}],
      "variableNameTemplate":"CORR_spring_csrf_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"Spring Security default CSRF token."
    },
    {
      "id":"struts.token",
      "name":"Struts TOKEN","technology":"struts",
      "tags":["struts","csrf"],"confidencePolicy":"HIGH",
      "paramName":"org.apache.struts.taglib.html.TOKEN",
      "detectors":[{"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"name=\"org\\.apache\\.struts\\.taglib\\.html\\.TOKEN\"[^>]*value=\"([^\"]+)\"","group":1}],
      "usageHints":[{"channel":"FORM_PARAM","paramName":"org.apache.struts.taglib.html.TOKEN"}],
      "variableNameTemplate":"CORR_struts_token_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"Struts sync-token for double-submit protection."
    }
  ]
}
```

**`builtin.php.v1.jrules`**

```json
{
  "schemaVersion":1,"ruleSetId":"builtin.php.v1","ruleSetName":"PHP Frameworks",
  "rules":[
    {
      "id":"php.phpsessid","name":"PHPSESSID cookie","technology":"php",
      "tags":["php","session","cookie"],"confidencePolicy":"HIGH","paramName":"PHPSESSID",
      "detectors":[{"channel":"SET_COOKIE","extractorKind":"REGEX",
        "expression":"PHPSESSID=([a-zA-Z0-9]{26,40})","group":1}],
      "usageHints":[{"channel":"COOKIE","paramName":"PHPSESSID"}],
      "variableNameTemplate":"CORR_phpsessid_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"Native PHP session cookie."
    },
    {
      "id":"laravel.token","name":"Laravel _token","technology":"laravel",
      "tags":["php","laravel","csrf"],"confidencePolicy":"HIGH","paramName":"_token",
      "detectors":[
        {"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"name=\"_token\"[^>]*value=\"([A-Za-z0-9]{40,})\"","group":1},
        {"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"<meta name=\"csrf-token\" content=\"([A-Za-z0-9]{40,})\"","group":1}],
      "usageHints":[
        {"channel":"FORM_PARAM","paramName":"_token"},
        {"channel":"REQ_HEADER","paramName":"X-CSRF-TOKEN"},
        {"channel":"REQ_HEADER","paramName":"X-XSRF-TOKEN"}],
      "variableNameTemplate":"CORR_laravel_token_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"Laravel CSRF token."
    },
    {
      "id":"laravel.xsrf.cookie","name":"Laravel XSRF-TOKEN cookie","technology":"laravel",
      "tags":["php","laravel","csrf","cookie"],"confidencePolicy":"HIGH","paramName":"XSRF-TOKEN",
      "detectors":[{"channel":"SET_COOKIE","extractorKind":"REGEX",
        "expression":"XSRF-TOKEN=([^;]+)","group":1}],
      "usageHints":[
        {"channel":"REQ_HEADER","paramName":"X-XSRF-TOKEN"},
        {"channel":"COOKIE","paramName":"XSRF-TOKEN"}],
      "variableNameTemplate":"CORR_xsrf_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"URL-encoded Laravel/Axios XSRF cookie. Note: usage is URL-decoded in header."
    },
    {
      "id":"symfony.csrf","name":"Symfony _csrf_token","technology":"symfony",
      "tags":["php","symfony","csrf"],"confidencePolicy":"HIGH","paramName":"_csrf_token",
      "detectors":[
        {"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"name=\"[a-zA-Z_]+\\[_token\\]\"[^>]*value=\"([A-Za-z0-9_\\-]+)\"","group":1},
        {"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"name=\"_csrf_token\"[^>]*value=\"([A-Za-z0-9_\\-]+)\"","group":1}],
      "usageHints":[{"channel":"FORM_PARAM","paramName":"_csrf_token"}],
      "variableNameTemplate":"CORR_symfony_csrf_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"Symfony form CSRF token."
    },
    {
      "id":"codeigniter.csrf","name":"CodeIgniter csrf_token","technology":"codeigniter",
      "tags":["php","codeigniter","csrf"],"confidencePolicy":"HIGH","paramName":"csrf_token_name",
      "detectors":[
        {"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"name=\"(csrf_[a-z_]+)\"[^>]*value=\"([a-f0-9]{32,64})\"","group":2},
        {"channel":"SET_COOKIE","extractorKind":"REGEX",
         "expression":"csrf_cookie[^=]*=([a-f0-9]+)","group":1}],
      "usageHints":[
        {"channel":"FORM_PARAM","paramName":"csrf_test_name"},
        {"channel":"COOKIE","paramName":"csrf_cookie_name"}],
      "variableNameTemplate":"CORR_ci_csrf_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"hex",
      "reason":"CodeIgniter CSRF with configurable name."
    }
  ]
}
```

**`builtin.oauth.v1.jrules`**

```json
{
  "schemaVersion":1,"ruleSetId":"builtin.oauth.v1","ruleSetName":"OAuth 2.0 / OIDC",
  "rules":[
    {
      "id":"oauth.access_token","name":"OAuth access_token","technology":"oauth",
      "tags":["oauth","bearer"],"confidencePolicy":"HIGH","paramName":"access_token",
      "detectors":[
        {"channel":"RESP_BODY","contentType":"application/json","extractorKind":"JSON_JMESPATH",
         "expression":"access_token"},
        {"channel":"REDIRECT_LOCATION","extractorKind":"REGEX",
         "expression":"[#?&]access_token=([^&]+)","group":1}],
      "usageHints":[
        {"channel":"REQ_HEADER","paramName":"Authorization"},
        {"channel":"QUERY","paramName":"access_token"},
        {"channel":"FORM_PARAM","paramName":"access_token"}],
      "variableNameTemplate":"CORR_access_token_{seq}",
      "preferredExtractor":"JSON_JMESPATH","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"OAuth 2.0 bearer token; usage in Authorization: Bearer header."
    },
    {
      "id":"oauth.refresh_token","name":"OAuth refresh_token","technology":"oauth",
      "tags":["oauth"],"confidencePolicy":"HIGH","paramName":"refresh_token",
      "detectors":[{"channel":"RESP_BODY","contentType":"application/json","extractorKind":"JSON_JMESPATH",
         "expression":"refresh_token"}],
      "usageHints":[{"channel":"FORM_PARAM","paramName":"refresh_token"}],
      "variableNameTemplate":"CORR_refresh_token_{seq}",
      "preferredExtractor":"JSON_JMESPATH","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"Used to obtain new access token."
    },
    {
      "id":"oidc.id_token","name":"OIDC id_token","technology":"oidc",
      "tags":["oidc","jwt"],"confidencePolicy":"HIGH","paramName":"id_token",
      "detectors":[
        {"channel":"RESP_BODY","contentType":"application/json","extractorKind":"JSON_JMESPATH",
         "expression":"id_token"},
        {"channel":"REDIRECT_LOCATION","extractorKind":"REGEX",
         "expression":"[#?&]id_token=(eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+)","group":1}],
      "usageHints":[{"channel":"REQ_HEADER","paramName":"Authorization"}],
      "variableNameTemplate":"CORR_id_token_{seq}",
      "preferredExtractor":"JSON_JMESPATH","occurrenceStrategy":"FIRST","valueCharset":"jwt",
      "reason":"OIDC identity JWT."
    },
    {
      "id":"oauth.code","name":"OAuth authorization code","technology":"oauth",
      "tags":["oauth"],"confidencePolicy":"HIGH","paramName":"code",
      "detectors":[{"channel":"REDIRECT_LOCATION","extractorKind":"REGEX",
         "expression":"[?&]code=([^&#]+)","group":1}],
      "usageHints":[{"channel":"FORM_PARAM","paramName":"code"}],
      "variableNameTemplate":"CORR_auth_code_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"Auth-code flow code param in redirect."
    },
    {
      "id":"oauth.state","name":"OAuth state","technology":"oauth",
      "tags":["oauth","csrf"],"confidencePolicy":"HIGH","paramName":"state",
      "detectors":[
        {"channel":"REDIRECT_LOCATION","extractorKind":"REGEX",
         "expression":"[?&]state=([^&#]+)","group":1},
        {"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"name=\"state\"[^>]*value=\"([^\"]+)\"","group":1}],
      "usageHints":[{"channel":"QUERY","paramName":"state"},{"channel":"FORM_PARAM","paramName":"state"}],
      "variableNameTemplate":"CORR_oauth_state_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"Round-trip CSRF parameter."
    },
    {
      "id":"oauth.nonce","name":"OIDC nonce","technology":"oidc",
      "tags":["oidc"],"confidencePolicy":"MEDIUM","paramName":"nonce",
      "detectors":[{"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"name=\"nonce\"[^>]*value=\"([^\"]+)\"","group":1}],
      "usageHints":[{"channel":"QUERY","paramName":"nonce"}],
      "variableNameTemplate":"CORR_oidc_nonce_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"OIDC replay protection."
    }
  ]
}
```

**`builtin.jwt.v1.jrules`**

```json
{
  "schemaVersion":1,"ruleSetId":"builtin.jwt.v1","ruleSetName":"JWT detection",
  "rules":[
    {
      "id":"jwt.generic","name":"Generic JWT in response body","technology":"jwt",
      "tags":["jwt"],"confidencePolicy":"MEDIUM","paramName":"jwt",
      "detectors":[
        {"channel":"RESP_BODY","extractorKind":"REGEX",
         "expression":"\"(?:token|jwt|auth_token|authToken|bearerToken|accessToken)\"\\s*:\\s*\"(eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+)\"","group":1},
        {"channel":"RESP_HEADER","extractorKind":"REGEX",
         "expression":"Bearer\\s+(eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+)","group":1}],
      "usageHints":[
        {"channel":"REQ_HEADER","paramName":"Authorization"},
        {"channel":"REQ_HEADER","paramName":"x-access-token"}],
      "variableNameTemplate":"CORR_jwt_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"jwt",
      "reason":"Three-segment base64url-encoded JWT."
    }
  ]
}
```

**`builtin.generic.v1.jrules`**

```json
{
  "schemaVersion":1,"ruleSetId":"builtin.generic.v1","ruleSetName":"Generic correlation patterns",
  "rules":[
    {
      "id":"generic.correlationId","name":"X-Correlation-Id / X-Request-Id",
      "technology":"generic","tags":["tracing"],"confidencePolicy":"MEDIUM",
      "paramName":"correlationId",
      "detectors":[{"channel":"RESP_HEADER","extractorKind":"REGEX",
         "expression":"(?i)^(?:X-Correlation-Id|X-Request-Id|Request-Id):\\s*([^\\r\\n]+)","group":1}],
      "usageHints":[{"channel":"REQ_HEADER","paramName":"X-Correlation-Id"}],
      "variableNameTemplate":"CORR_trace_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"Distributed tracing correlation id."
    },
    {
      "id":"generic.hidden_input_dynamic","name":"Hidden input with dynamic value",
      "technology":"generic","tags":["hidden-input","heuristic"],"confidencePolicy":"LOW",
      "paramName":"*hidden*",
      "detectors":[{"channel":"RESP_BODY","contentType":"text/html","extractorKind":"REGEX",
         "expression":"<input[^>]*type=\"hidden\"[^>]*name=\"([a-zA-Z_][a-zA-Z0-9_]*)\"[^>]*value=\"([^\"]{8,})\"","group":2}],
      "usageHints":[{"channel":"FORM_PARAM","paramName":"{group1}"}],
      "variableNameTemplate":"CORR_hidden_{paramName}_{seq}",
      "preferredExtractor":"REGEX","occurrenceStrategy":"FIRST","valueCharset":"any",
      "reason":"Catch-all for hidden inputs; promoted to HIGH by run-twice diff."
    }
  ]
}
```

Rule files for `builtin.java.v1.jrules` and `builtin.aspnet.v1.jrules` are aliases that include the corresponding rules from `builtin.webapp.v1` (for clean filtering in UI).

## 1.4 Fingerprinter Algorithm

```text
scan(capture, ruleSet, progress):
  candidates := {}                              # key: (paramName, normalizedValue, threadGroup)
  valueIndex := buildValueIndex(capture)        # fast lookups for usage search
  for each exchange e in capture.exchanges, in order:
      progress.publish(exchangeIndex, total)
      for each rule r in ruleSet:
          for each detector d in r.detectors:
              if d.contentType set and not matches e.responseContentType: continue
              channelText := extractChannel(e, d.channel)
              if channelText is null/empty: continue
              for each match m in runDetector(d, channelText):
                  rawValue := m.group(d.group)
                  if ValueNormalizer.rejectByCharset(rawValue, r.valueCharset): continue
                  key := (r.paramName, normalize(rawValue), scopeOf(e))
                  if key in candidates: mergeSource(candidates[key], e, m)
                  else:
                      cand := buildCandidate(r, d, e, m, rawValue)
                      cand.usages := findUsages(valueIndex, rawValue, r.usageHints)
                      cand.occurrence := OccurrenceAnalyzer.analyze(channelText, rawValue, r)
                      cand.extractorExpression := materializeExpression(r, d, cand.occurrence)
                      cand.suggestedVariableName := nextName(r.variableNameTemplate, scopeOf(e))
                      cand.confidence := computeConfidence(cand, r)
                      candidates[key] := cand
  # promote demote pass
  for each c in candidates:
      if c.usages is empty: c.confidence := LOW and c.reason += "; no downstream usage"
      if looksLikeStaticOrTimestamp(c.rawValue): c.confidence := INVALID
      if alreadyParameterized(c): drop c
  return sortedByConfidenceThenSequence(candidates.values)
```

### Value normalization

```java
public Set<String> normalize(String raw) {
    Set<String> out = new LinkedHashSet<>();
    out.add(raw);
    try { out.add(URLDecoder.decode(raw, StandardCharsets.UTF_8)); } catch (Exception ignored) {}
    try { out.add(StringEscapeUtils.unescapeHtml4(raw)); } catch (Exception ignored) {}
    if (raw.matches("[A-Za-z0-9+/=]{8,}")) {
        try {
            byte[] b = Base64.getDecoder().decode(raw);
            if (isPrintable(b)) out.add(new String(b, StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }
    return out;
}
```

Rejection rules (the `looksLikeStaticOrTimestamp` check):

- Pure numeric values less than 7 digits → likely an id, not dynamic.
- Matches `\d{10,13}` → epoch timestamp → INVALID unless rule tag is `timestamp-ok`.
- Values appearing **identically** in ≥2 capture sessions of same user (Phase 3 signal; Phase 1 skips).
- Value equals a JMeter function expression (`${...}`) → already parameterized.

### Occurrence analysis

```text
analyze(channelText, value):
    matches := every index-of(value) in channelText
    if matches.size == 0: impossible (caller guarantees >=1); return FIRST,1
    if matches.size == 1: return {total:1, selected:1, ambiguous:false, reason:"unique"}
    # >1 match
    if rule.occurrenceStrategy == FIRST: return {total:n, selected:1, reason:"first occurrence"}
    if rule.occurrenceStrategy == LAST:  return {total:n, selected:n, reason:"last occurrence"}
    if rule.occurrenceStrategy == UNIQUE: return {ambiguous:true, reason:"multiple matches, UNIQUE required"}
    # try to find a sibling disambiguator: scan 60 chars before each match for a stable key
    disambiguatingPrefix := longestCommonPrefixExcludingValue(matches)
    return {selected:1, ambiguous:true, reason:"preceded by '" + prefix + "'"}
```

### Usage-site search

- Pre-built value index: a map from each *normalized* substring of length ≥ `min(raw.length, 8)` appearing in any subsequent exchange's request (body, headers, cookie values, query params, URL path segments) → list of `UsageSite`.
- When scanning for usages of a candidate, intersect `ValueNormalizer.normalize(raw)` with the index.
- `usageHints` prune the search space: if rule says "REQ_HEADER Authorization", the index lookup is restricted.
- Multi-thread-group scoping: only look for usage in exchanges whose `threadGroupName` equals the candidate's `threadGroupName`, unless the source exchange's sampler is shared (setUp Thread Group).

## 1.5 Placement Validator

```java
PlacementReport validate(Candidate c, JMeterTreeModel tree):
    sourceSampler := findNode(tree, c.foundIn.exchangeId -> samplerName)
    if sourceSampler == null:
        return PlacementReport(false, "Source sampler not found in tree", null)
    for each usage u in c.usages:
        usageSampler := findNode(tree, u.samplerName)
        if usageSampler == null: skip (warn)
        if positionOf(usageSampler) <= positionOf(sourceSampler):
            return PlacementReport(false, "First usage in '" + u.samplerName + 
                "' occurs before source '" + sourceSampler.getName() + "'; extractor cannot be placed in time", null)
    return PlacementReport(true, null, sourceSampler)
```

## 1.6 Applier

```java
AppliedCorrelation apply(Candidate c):
    EDT-assert
    PlacementReport pr := placementValidator.validate(c, tree)
    if !pr.ok: throw ApplyException(pr.reasonIfInvalid)

    checkpointId := UndoHistory.add("Correlate: " + c.paramName)   // single point

    extractorNode := buildExtractorNode(c)     // RegexExtractor / JSON / XPath2 / Boundary
    extractorNode.setName("Extract " + c.suggestedVariableName)
    extractorNode.setReferenceName(c.suggestedVariableName)
    extractorNode.setDefaultValue("CORR_FAILED_" + c.suggestedVariableName)  // silent-fail sentinel
    extractorNode.setMatchNumber(c.occurrence.selectedIndex)

    tree.addChildToNode(pr.proposedParent, extractorNode)

    replacements := []
    for each usage u in c.usages:
        old := readChannelValue(u)
        new := "${" + c.suggestedVariableName + "}"
        writeChannelValue(u, new, respectEncoding=u.encodingApplied)
        replacements.add(ReplacementRecord(u.samplerName, u.channel, u.channelName, old, new))

    tree.nodeStructureChanged(pr.proposedParent)
    // single UndoHistory commit happens implicitly at end of action lambda

    return new AppliedCorrelation(c, extractorNode.getName(), c.suggestedVariableName, replacements, Instant.now())
```

Extractor construction by kind:

| Kind | JMeter class | Key setters |
|---|---|---|
| REGEX | `RegexExtractor` | `setRegex`, `setTemplate("$1$")`, `setMatchNumber`, `setUseField(...)` (BODY, HEADERS, etc.) |
| JSON_JMESPATH | `JSONPostProcessor` | `setJsonPathExpressions`, `setMatchNumbers` |
| XPATH2 | `XPath2Extractor` | `setXPathQuery`, `setNamespaces`, `setMatchNumber` |
| BOUNDARY | `BoundaryExtractor` | `setLeftBoundary`, `setRightBoundary`, `setMatchNumber` |

`useField` mapping from `SourceChannel`:
- `RESP_BODY` → `USE_BODY`
- `RESP_HEADER` → `USE_HEADERS`
- `SET_COOKIE` → `USE_HEADERS` (regex anchored on `Set-Cookie:`)
- `REDIRECT_LOCATION` → `USE_HEADERS`
- `SUB_RESULT_BODY` → `USE_BODY` + `setScope("sub-samples")`

## 1.7 Undo/Redo

Checkpoint strategy: JMeter's `UndoHistory` already snapshots the tree on every `nodesWereInserted` / `nodeChanged`. Multiple internal events in a single `Applier.apply()` would create multiple undo steps. To produce **one** checkpoint per Apply:

```java
public AppliedCorrelation apply(Candidate c) {
    UndoHistory hist = GuiPackage.getInstance().getTreeModel().getUndoHistory();
    hist.setEnabled(false);                    // suppress intermediate checkpoints
    try {
        // ... extractor add + all replacements ...
    } finally {
        hist.setEnabled(true);
    }
    hist.add(guiPackage.getTreeModel(), "Correlation: " + c.paramName);  // single commit
    return result;
}
```

Additional app-level stack (`CorrelationHistory`) keeps `AppliedCorrelation` records so the "Undo Last Correlation" button can produce descriptive undo even when the user has done manual tree edits after. Its `undo()`:

1. Remove the extractor node.
2. Revert each `ReplacementRecord` (write `oldValue` back).
3. Fire `nodeStructureChanged`.
4. Also calls `UndoHistory.undo()` so JMeter's global history stays coherent.

Partial applies: each "Apply This" creates one entry; "Apply All HIGH" in Phase 2 creates one **compound** entry `CompoundAppliedCorrelation(List<AppliedCorrelation>)` - undone as a single unit.

## 1.8 UI

### Swing component hierarchy

```
CorrelationStudioFrame  (extends JFrame, modeless, 1280x840 default)
└── JRootPane
    ├── JMenuBar
    │   ├── File  → Load JTL..., Load HAR..., Close
    │   ├── Capture → Attach Live Listener, Detach
    │   ├── Rules → Open Rules Directory, Reload Rules
    │   └── Help  → About, Keyboard Shortcuts
    └── contentPane (BorderLayout)
        ├── NORTH:  ToolbarPanel (JToolBar)
        │           ├── JButton "Scan"         (runs SwingWorker; disables during scan)
        │           ├── JProgressBar           (indeterminate until first publish)
        │           ├── JLabel statusLabel
        │           ├── JComboBox<CaptureSource> (JTL/HAR/Listener/InMemory)
        │           ├── JTextField ruleFilter  (filters candidates table)
        │           ├── JComboBox<Confidence>  (ALL/HIGH/MEDIUM/LOW/INVALID)
        │           └── JButton "Undo Last Correlation"
        └── CENTER: JSplitPane (HORIZONTAL, 0.55)
            ├── LEFT: JSplitPane (VERTICAL, 0.65)
            │         ├── TOP: CandidatesTablePanel
            │         │        ├── JScrollPane
            │         │        │   └── JTable candidatesTable  (CandidateTableModel)
            │         │        └── SOUTH: summaryLabel ("42 candidates • 18 HIGH • 19 MEDIUM • 5 LOW")
            │         └── BOTTOM: ActionBarPanel
            │                   ├── JButton "Test Expression"
            │                   ├── JButton "Apply This"
            │                   ├── JButton "Apply All HIGH"  (disabled in Phase 1, tooltip "Phase 2")
            │                   ├── JButton "Dismiss"
            │                   └── JButton "Show in Tree"
            └── RIGHT: JSplitPane (VERTICAL, 0.5)
                      ├── TOP: EvidencePanel (JTabbedPane)
                      │        ├── Tab "Source"    - JTextPane (HTML, value highlighted)
                      │        ├── Tab "Usages"    - JList<UsageSite>, JTextPane for selected
                      │        ├── Tab "Rule"      - JTextArea showing rule JSON + reason
                      │        └── Tab "Reasoning" - (Phase 2 AI rationale, placeholder in Phase 1)
                      └── BOTTOM: PreviewDiffPanel
                               ├── JLabel "Before → After"
                               ├── DiffView (JSplitPane side-by-side JTextPane)
                               └── SOUTH: JLabel "1 extractor added • 3 requests modified"
```

### CandidateTableModel

| Column | Type | Editable | Notes |
|---|---|---|---|
| 0 `#` | Integer | no | row index |
| 1 `Param` | String | no | `c.paramName` |
| 2 `Value` | String | no | `c.truncatedValue`, tooltip=full raw |
| 3 `Extractor` | ExtractorKind | no | icon + text |
| 4 `Found In` | String | no | `c.foundIn.channelLabel` + sampler |
| 5 `Usages` | Integer | no | `c.usages.size()`; 0 renders yellow with tooltip |
| 6 `Rule` | String | no | `c.ruleId`, filter chip-style |
| 7 `Confidence` | Confidence | no | colored pill (HIGH=green #2e7d32, MEDIUM=yellow #f9a825, LOW=red #c62828, INVALID=dark red #7b1d1d with strikethrough) |
| 8 `Variable` | String | **yes** in Phase 1 | user can rename before Apply |
| 9 `Expression` | String | **yes** in Phase 1 | inline edit; revalidate on focus-lost |

Row renderer: `ConfidenceRowRenderer` colors cell 7 + applies subtle row-background tint. INVALID rows are dimmed and non-selectable for Apply.

Model is backed by a `List<Candidate>` but mutation goes through `CandidateTableModel.updateCandidate(int, Candidate)` so filtering/sorting remain consistent (backed by `TableRowSorter` with confidence order HIGH<MEDIUM<LOW<INVALID).

### Evidence panel - Source tab layout

```
EvidencePanel.SourceTab  (BorderLayout)
├── NORTH: JPanel (FlowLayout)
│          ├── JLabel "Response from: sampler_name (exchange 12)"
│          ├── JLabel "Channel: Response body / text/html / 14.3 KB"
│          └── JButton "Copy snippet"
├── CENTER: JScrollPane
│          └── JEditorPane ("text/html")
│             renders: beforeContext + <span class='hit'>matchedText</span> + afterContext
└── SOUTH: JLabel "Offset 4823 • Match 1 of 2 (picked match 1 - first occurrence)"
```

### 1.8.1 "Test Expression" feature - full detail

Top-priority deliverable per the brief. Opens as a modeless `TestExpressionDialog` anchored to the main frame, pre-populated from the selected candidate.

#### Component hierarchy

```
TestExpressionDialog (JDialog, modeless, 900x620)
└── contentPane (BorderLayout)
    ├── NORTH: HeaderPanel (GridBagLayout)
    │          ├── JLabel "Extractor type:"
    │          ├── JComboBox<ExtractorKind>          (REGEX/JSON_JMESPATH/XPATH2/BOUNDARY)
    │          ├── JLabel "Expression:"
    │          ├── JTextArea expressionArea (rows=3, monospaced, syntax-hint border)
    │          ├── JLabel "Match number:"
    │          ├── JSpinner matchNumberSpinner (min=1, max=999, value=c.occurrence.selectedIndex)
    │          ├── JLabel "Target channel:"
    │          ├── JComboBox<SourceChannel>           (RESP_BODY/RESP_HEADER/SET_COOKIE/...)
    │          └── JButton runButton ("Run Test")     (default button, Enter key)
    ├── CENTER: JSplitPane (VERTICAL, 0.3)
    │          ├── TOP: ResultsPanel (BorderLayout)
    │          │        ├── NORTH: StatusBar
    │          │        │          JLabel "✓ 2 matches found" | "✗ Compile error: ..." | "⚠ No matches"
    │          │        └── CENTER: JScrollPane
    │          │                  └── JTable matchesTable (MatchTableModel)
    │          │                    columns: [#, Offset, Length, Captured Group, Full Match]
    │          └── BOTTOM: BodyPanel (BorderLayout)
    │                    └── JScrollPane
    │                       └── JTextPane bodyPane ("text/plain")
    │                         renders the channelText with every match highlighted;
    │                         selected match (from matchesTable) rendered in bold/orange,
    │                         others in light yellow; bodyPane caret-scrolls to selected match.
    └── SOUTH: ButtonPanel (FlowLayout RIGHT)
               ├── JLabel tipLabel (diagnostic hint, see below)
               ├── JButton "Use This Expression"    (copies back to candidate, closes)
               └── JButton "Cancel"
```

#### Live-match flow

```java
private void runTest() {
    String expr = expressionArea.getText().trim();
    ExtractorKind kind = (ExtractorKind) kindCombo.getSelectedItem();
    SourceChannel ch = (SourceChannel) channelCombo.getSelectedItem();
    CapturedExchange ex = candidate.foundIn().exchangeId() ... ;       // resolved from CaptureSet

    // Always EDT; work is local string matching, sub-millisecond in practice
    TestResult tr = expressionRunner.run(kind, expr, ex, ch);

    if (!tr.expressionValid()) {
        statusBar.setText("✗ Invalid expression: " + tr.compileError());
        statusBar.setForeground(RED);
        matchesTable.setModel(emptyModel());
        bodyPane.setText(tr.channelTextPreview() == null ? "(channel unavailable)" : tr.channelTextPreview());
        tipLabel.setText(diagnoseNoMatch(kind, expr, tr));
        return;
    }
    if (tr.matches().isEmpty()) {
        statusBar.setText("⚠ No matches in channel (" + ch + ")");
        statusBar.setForeground(ORANGE);
        tipLabel.setText(diagnoseNoMatch(kind, expr, tr));
    } else {
        statusBar.setText("✓ " + tr.matches().size() + " match(es) found");
        statusBar.setForeground(GREEN);
        tipLabel.setText("");
    }
    matchesTable.setModel(new MatchTableModel(tr.matches()));
    bodyPane.setText(tr.channelTextPreview());
    highlightAllMatches(bodyPane, tr.matches());
    if (!tr.matches().isEmpty()) {
        matchesTable.getSelectionModel().setSelectionInterval(0, 0);
        scrollBodyToMatch(bodyPane, tr.matches().get(0));
    }
}
```

#### Error states

| State | status bar | body pane | tip |
|---|---|---|---|
| Invalid regex (`PatternSyntaxException`) | `✗ Invalid expression: <message with ^-caret at bad position>` | channel text unhighlighted | "Regex failed to compile. Common cause: unescaped `(`, `?`, or `\\`." |
| Invalid JSONPath (`InvalidPathException`) | `✗ Invalid JSONPath: <msg>` | raw JSON | "JMeter uses JMESPath-style paths: `a.b[0].c`, not `$.a.b[0].c`." |
| Invalid XPath (`XPathExpressionException`) | `✗ Invalid XPath: <msg>` | raw XML | "Check namespace prefixes and `//` vs `/`." |
| Null body (`responseBodyDecoded == null`) | `⚠ Channel is empty` | "(no body captured - check save.response_data)" | "The capture source did not store the response body for this sampler." |
| Empty body (length 0) | `⚠ Channel is empty` | "" | same as above |
| Valid expression, zero matches | `⚠ No matches in channel` | full text | see next section |
| Compile OK, matches = 1 | `✓ 1 match found` | highlighted | empty |
| Compile OK, matches > 1 | `✓ N matches found` | all highlighted, first bold | if `occurrenceStrategy == UNIQUE`: "Multiple matches found but rule requires UNIQUE - consider tightening the expression with a preceding anchor." |

#### Zero-results diagnostic hints (exactly three, covering ~90% of cases)

```java
String diagnoseNoMatch(ExtractorKind kind, String expr, TestResult tr) {
    String text = Optional.ofNullable(tr.channelTextPreview()).orElse("");
    // Hint 1: channel mismatch - search the OTHER channels for the candidate's raw value
    for (SourceChannel other : SourceChannel.values()) {
        if (other == currentChannel) continue;
        if (extractChannel(exchange, other).contains(candidate.rawValue())) {
            return "Hint: the value appears in " + other + ", not " + currentChannel + ". Switch the target channel.";
        }
    }
    // Hint 2: encoding - try URL-decoding and HTML-unescaping the body
    String urlDec = safeUrlDecode(text);
    if (!urlDec.equals(text) && urlDec.contains(candidate.rawValue())) {
        return "Hint: the value is URL-encoded in this channel. Extract the encoded form or use Function __urldecode on the variable.";
    }
    // Hint 3: content-type - expression kind mismatch
    if (kind == ExtractorKind.JSON_JMESPATH && !exchange.responseContentType().contains("json")) {
        return "Hint: this channel is " + exchange.responseContentType() + ", not JSON. Switch extractor type to REGEX.";
    }
    if (kind == ExtractorKind.XPATH2 && !exchange.responseContentType().matches(".*(xml|html).*")) {
        return "Hint: this channel is not XML/HTML. Switch extractor type to REGEX.";
    }
    return "Hint: value may have changed between capture and now. Re-capture and rescan.";
}
```

### Preview Diff panel

Fed by an `ApplyPreview` that `Applier.previewOnly(Candidate c)` produces without committing:

```java
public record ApplyPreview(
    String extractorNodeName,
    String parentSamplerName,
    List<ReplacementRecord> replacements,
    String beforeSummary,
    String afterSummary
) {}
```

Rendering: side-by-side `JTextPane` with line-based diff (use JGit's `DiffFormatter` or a small hand-rolled myers-diff). Removed = red strike-through background, added = green, unchanged = gray. Bottom label shows `"1 extractor added • N requests modified"`.

### Progress feedback during scan

```java
public final class ScanWorker extends SwingWorker<List<Candidate>, Integer> {
    private final int total;
    protected List<Candidate> doInBackground() {
        return fingerprinter.scan(capture, rules, new ProgressSink() {
            public void publish(int done, int of) { setProgress((int)(100.0 * done / of)); ScanWorker.this.publish(done); }
        });
    }
    protected void process(List<Integer> chunks) {
        int last = chunks.get(chunks.size()-1);
        statusLabel.setText("Scanning exchange " + last + " of " + total);
    }
    protected void done() {
        try {
            candidatesTable.setCandidates(get());
            statusLabel.setText(summarize(get()));
        } catch (Exception e) { statusLabel.setText("Scan failed: " + e.getMessage()); }
        progressBar.setIndeterminate(false); progressBar.setValue(100);
    }
}
```

## 1.9 Phase 1 - Complete Class List

Classes to write (all under `org.qainsights.jmeter.ai.correlation.*`):

**model** - `Candidate`, `CapturedExchange`, `CaptureSet`, `SourceLocation`, `UsageSite`, `OccurrenceInfo`, `EvidenceSnippet`, `AppliedCorrelation`, `ReplacementRecord`, `ApplyPreview`, enums `Confidence`, `ExtractorKind`, `SourceChannel`, `UsageChannel`.

**capture** - `CaptureSource`, `CaptureSourceConfig`, `CaptureException`, `JtlCaptureSource`, `HarCaptureSource`, `LiveListenerCaptureSource` (internal `CorrelationCaptureListener implements SampleListener`), `InMemoryCaptureSource`, `CaptureBodyDecoder` (gzip/br/charset).

**rules** - `CorrelationRule`, `RuleDetector`, `UsageHint`, `RuleSet`, `RuleLibrary`, `RuleParser` (Jackson-based), `ClasspathRuleLoader`, `DirectoryRuleLoader`.

**engine** - `Fingerprinter`, `DefaultFingerprinter`, `ValueNormalizer`, `DefaultValueNormalizer`, `OccurrenceAnalyzer`, `DefaultOccurrenceAnalyzer`, `PlacementValidator`, `DefaultPlacementValidator`, `Applier`, `DefaultApplier`, `ExpressionRunner`, `DefaultExpressionRunner`, `ValueIndex`.

**undo** - `CorrelationCheckpoint`, `CorrelationHistory` (singleton).

**ui** - `CorrelationStudioFrame`, `CorrelationStudioController`, `ToolbarPanel`, `CandidatesTablePanel`, `CandidateTableModel`, `ConfidenceRowRenderer`, `ActionBarPanel`, `EvidencePanel`, `PreviewDiffPanel`, `DiffView`, `ScanWorker`, `CorrelationMenuItem` (plugs into `AiMenuCreator`).

**ui/test** - `TestExpressionDialog`, `MatchTableModel`, `BodyHighlighter`.

**util** - `ContentTypeMatcher`, `HeaderParser`, `JwtDetector`, `HtmlSnippetBuilder`.

### Phase 1 public method inventory (per class, condensed)

```java
class CorrelationStudioController {
    void open();                                    // called from menu item
    void scan();                                    // kicks off ScanWorker
    void applyCandidate(Candidate c);
    void undoLastCorrelation();
    void loadCapture(CaptureSource src, CaptureSourceConfig cfg);
    List<Candidate> currentCandidates();
}

class DefaultFingerprinter implements Fingerprinter {
    List<Candidate> scan(CaptureSet capture, RuleSet rules, ProgressSink progress);
    private List<Candidate> scanExchange(CapturedExchange e, RuleSet rules, ValueIndex idx);
    private Candidate buildCandidate(...);
    private Confidence computeConfidence(Candidate c, CorrelationRule r);
}

class DefaultApplier implements Applier {
    AppliedCorrelation apply(Candidate c);
    ApplyPreview previewOnly(Candidate c);
    private TestElement buildExtractor(Candidate c);
    private void replaceUsage(UsageSite u, String varRef);
}

class DefaultExpressionRunner implements ExpressionRunner {
    TestResult run(ExtractorKind kind, String expr, CapturedExchange ex, SourceChannel ch);
    private TestResult runRegex(String expr, String text);
    private TestResult runJsonPath(String expr, String text);
    private TestResult runXPath2(String expr, String text);
    private TestResult runBoundary(String l, String r, String text);
}
```

### Phase 1 UI component list (minimum shippable)

Everything listed in §1.8's hierarchy is in Phase 1 **except**:
- `"Apply All HIGH"` button - present but disabled with tooltip "Available in Phase 2".
- Evidence panel `"Reasoning"` tab - present but shows "AI reasoning available in Phase 2."

## 1.10 Edge Cases & Error Handling (Phase 1)

| Situation | Behavior |
|---|---|
| Response body is null (HEAD/redirect) | Fingerprinter skips body detectors; header detectors still run. Test Expression shows "(no body captured)". |
| Response body is empty string | Same as null - no exception, status bar shows "⚠ Channel is empty". |
| gzip/br response, no Content-Encoding header | Try gzip magic bytes (`0x1f 0x8b`) and brotli header heuristic; fall back to raw bytes if decoding throws. Log at WARN. |
| Same paramName in two thread groups | Scoped by `threadGroupName`; each gets its own variable (`CORR_jsessionid_tg1_1`, `CORR_jsessionid_tg2_1`). |
| Extractor would be placed after first usage (ordering violation) | `PlacementValidator` returns `ok=false`; Apply button shows dialog with offending sampler name and no changes made. |
| UndoHistory at max capacity | JMeter auto-evicts oldest entry; `CorrelationHistory` keeps its own last-20-entries ring buffer as a safety net - "Undo Last Correlation" still works. |
| Duplicate run of engine on already-correlated tree | Fingerprinter detects `${CORR_*}` variable references in usages; those candidates are dropped with reason "already parameterized". |
| AI layer unavailable (Phase 1 doesn't call AI, but future-proof) | `CorrelationAiClient` short-circuits and labels all heuristic rows with no AI reasoning; UI still fully usable. |
| Capture source fails to load (bad JTL, malformed HAR) | `CaptureException` bubbles to Controller → shows `JOptionPane.ERROR` with file path and a one-line cause. |
| No rules loaded (empty rule dir, classpath broken) | Fingerprinter runs only generic heuristic rule; UI shows banner "No rules loaded - using generic heuristics only." |
| User rename `suggestedVariableName` collides with existing JMeter variable | Validator adds `_n` suffix silently; status label notes "Variable name auto-suffixed to avoid collision." |
| Same value appears N times in response (multi-occurrence) | `OccurrenceAnalyzer` picks per `occurrenceStrategy`; UI banner `⚠ 3 matches - picking match #1` plus tip "Open Test Expression to adjust." |
| Redirect chain (302 Location) | `CapturedExchange.subResults` captures redirect hops; `SourceChannel.REDIRECT_LOCATION` scans Location header; scope=sub-samples. |

Every exception path logs via SLF4J at ERROR with the candidate id + exchange id, never the raw token (treated as potentially sensitive).

## 1.11 Percentage of Real-World Correlations Handled by Phase 1

Based on empirical coverage of the built-in rule library against common stacks: **~55–65%**. Remaining: application-specific hidden fields (needs run-twice diff - Phase 3), ambiguous multi-occurrence cases without rule disambiguation (needs AI - Phase 2), chained correlations (Phase 2).

---

# Phase 2 - AI-Augmented Correlation

Goal: every candidate emitted by the Fingerprinter is passed through Claude for (a) classification, (b) confidence re-scoring, (c) extractor-expression refinement, (d) variable naming, (e) chaining detection. Adds the bulk "Apply All HIGH" action.

Handles additional ~20% of real-world correlations (chained tokens, ambiguous multi-occurrence, non-obvious framework tokens, smarter regex tightening) - cumulative coverage **~75–85%**.

## 2.1 AI Client

```java
public interface CorrelationAiClient {
    AiBatchResponse classify(List<Candidate> batch, AiContext ctx);
    boolean isAvailable();
}

public record AiContext(
    String samplerTreeSummary,        // flat list of sampler names in order
    Map<String,String> headerFlavour, // server/X-Powered-By etc. to hint stack
    int maxTokens
) {}

public record AiBatchResponse(
    List<AiVerdict> verdicts,
    List<ChainLink> chains,           // optional inferred chains
    long tokensIn, long tokensOut
) {}

public record AiVerdict(
    String candidateId,
    Confidence aiConfidence,
    String variableName,              // AI-assigned; e.g. "csrfToken"
    ExtractorKind extractorKind,
    String extractorExpression,
    int occurrenceIndex,
    String rationale,                 // 1-paragraph, surfaced in Evidence/Reasoning tab
    String action                     // APPLY / REVIEW / REJECT
) {}

public record ChainLink(
    String upstreamCandidateId,
    String downstreamCandidateId,
    String reason
) {}
```

Implemented by `ClaudeCorrelationAiClient` wrapping existing `ClaudeService` (see `@/Users/Navee/gits/jmeter-ai/src/main/java/org/qainsights/jmeter/ai/service/ClaudeService.java:131-239`). Uses Anthropic tool-calling; OpenAI variant (`OpenAiCorrelationAiClient`) provided for parity.

## 2.2 System Prompt (verbatim)

```
You are a correlation analyst for a JMeter test plan. Input: a JSON array of
candidate dynamic values detected by a heuristic scanner. For each candidate
you must decide whether it is a genuine dynamic server value that requires
correlation, pick the right extractor, the right match occurrence, and a
human-readable variable name.

Principles you must follow:
1. A value is correlatable only if it (a) is emitted by the server AND (b)
   is sent back unchanged by the client in a later request in the same session.
2. Static strings, UI labels, timestamps older than the capture window, and
   already-parameterized ${} expressions are NOT correlation candidates.
3. Prefer the most specific extractor: JSON_JMESPATH for JSON bodies,
   XPATH2 for XML/HTML where a stable attribute exists, BOUNDARY for short
   unique delimiters, REGEX as fallback.
4. When the same value appears N times in the source channel, prefer the
   occurrence that is anchored by a stable sibling token (e.g. preceded by
   name="_csrf"). If no anchor, pick occurrenceIndex=1.
5. Variable names: lowerCamelCase, start with a letter, no JMeter reserved
   prefixes (__jm, __JM). Examples: csrfToken, jsessionId, viewState, oauthCode.
6. If two candidates are chained (token A extracted from exchange 3 is used
   in exchange 4 to obtain token B), report the relationship in "chains".
7. If you cannot confidently decide, set action=REVIEW with your best guess
   and explain why in rationale. Never return action=APPLY without high
   certainty.

Output MUST be produced via the `classify_candidates` tool. Do not write prose.
```

## 2.3 Tool Definition

```json
{
  "name": "classify_candidates",
  "description": "Classify a batch of correlation candidates and return verdicts.",
  "input_schema": {
    "type": "object",
    "required": ["verdicts"],
    "properties": {
      "verdicts": {
        "type": "array",
        "items": {
          "type": "object",
          "required": ["candidateId","aiConfidence","variableName","extractorKind","extractorExpression","occurrenceIndex","rationale","action"],
          "properties": {
            "candidateId":         {"type":"string"},
            "aiConfidence":        {"type":"string","enum":["HIGH","MEDIUM","LOW","INVALID"]},
            "variableName":        {"type":"string","pattern":"^[a-z][a-zA-Z0-9]{1,48}$"},
            "extractorKind":       {"type":"string","enum":["REGEX","JSON_JMESPATH","XPATH2","BOUNDARY"]},
            "extractorExpression": {"type":"string"},
            "occurrenceIndex":     {"type":"integer","minimum":1,"maximum":999},
            "rationale":           {"type":"string","maxLength":400},
            "action":              {"type":"string","enum":["APPLY","REVIEW","REJECT"]}
          }
        }
      },
      "chains": {
        "type": "array",
        "items": {
          "type": "object",
          "required": ["upstreamCandidateId","downstreamCandidateId","reason"],
          "properties": {
            "upstreamCandidateId":   {"type":"string"},
            "downstreamCandidateId": {"type":"string"},
            "reason":                {"type":"string","maxLength":200}
          }
        }
      }
    }
  }
}
```

## 2.4 Response Parsing & Fallback

```java
AiBatchResponse classify(List<Candidate> batch, AiContext ctx) {
    if (!isAvailable()) return fallback(batch);
    String json = serializeBatch(batch, ctx);
    try {
        ToolUseResponse resp = client.messages().withTool(classifyCandidatesTool()).send(...);
        AiBatchResponse parsed = jackson.readValue(resp.toolInput(), AiBatchResponse.class);
        validateVerdictsMatchBatch(parsed, batch);     // every candidateId accounted for
        return parsed;
    } catch (Exception e) {
        log.warn("AI classification failed; falling back to heuristic verdicts", e);
        return fallback(batch);
    }
}

private AiBatchResponse fallback(List<Candidate> batch) {
    return new AiBatchResponse(
        batch.stream().map(c -> new AiVerdict(
            c.candidateId(), c.confidence(), c.suggestedVariableName(),
            c.extractorKind(), c.extractorExpression(), c.occurrence().selectedIndex(),
            "Heuristic-only (AI unavailable): " + c.reasonSummary(),
            c.confidence() == Confidence.HIGH ? "APPLY" : "REVIEW"
        )).toList(),
        List.of(), 0L, 0L);
}
```

Fallback is silent but surfaced: status bar shows `"AI: offline - heuristic confidence only"`.

## 2.5 Token Budget

Input per candidate: ~180 tokens (paramName, 200-char value, 300-char source snippet, 150-char usage snippet, rule id). With 50 candidates per scan: ~9 KB input ≈ 9000 tokens. Output: ~80 tokens per verdict → 4000 tokens. **Per-scan budget: ~13K tokens (~$0.04 on Claude Sonnet)**. Config: `jmeter.ai.correlation.batch.size` (default 25) splits larger candidate sets into multiple tool calls.

## 2.6 "Apply All HIGH" bulk action

```java
void applyAllHigh() {
    var highs = currentCandidates.stream()
        .filter(c -> aiVerdict(c).aiConfidence() == HIGH && aiVerdict(c).action() == APPLY)
        .toList();
    if (highs.isEmpty()) { status("No HIGH candidates eligible."); return; }
    if (!confirmDialog(highs.size() + " correlations will be applied")) return;

    UndoHistory hist = GuiPackage.getInstance().getTreeModel().getUndoHistory();
    hist.setEnabled(false);
    List<AppliedCorrelation> applied = new ArrayList<>();
    try {
        for (Candidate c : highs) applied.add(applier.apply(c));
    } finally { hist.setEnabled(true); }
    hist.add(treeModel, "Correlation bulk apply: " + applied.size() + " items");
    correlationHistory.pushCompound(applied);
}
```

---

# Phase 3 - Run-Twice Diff

Goal: user runs the test twice with identical inputs; engine diffs the two response sets to surface dynamic values **not covered by any rule** (application-specific hidden fields, custom tokens). Diff candidates feed the same UI pipeline as rule-based candidates.

Handles additional ~10–15% of correlations. Cumulative coverage **~90–95%**.

## 3.1 Design

- Opt-in: user clicks "Run Twice & Diff". Plugin attaches `LiveListenerCaptureSource` for both runs, producing `CaptureSet run1` and `CaptureSet run2`.
- `RunTwiceDiffer.diff(run1, run2)` aligns exchanges by `(sequence, samplerName, method, url-without-query)` and diffs response bodies/headers.
- Anywhere bytes differ **and** the changed substring appears in run1's response and at least one run1 request and run2's response and run2 request at the same positions → mark as `DiffCandidate`.
- `DiffCandidateAdapter` converts each `DiffCandidate` into a `Candidate` with `ruleId="diff.runtwice"`, `confidence=MEDIUM`, and a synthesized `extractorExpression` generated by boundary analysis of the surrounding 20 chars.
- Feeds straight into `CandidatesTablePanel` merged with rule-based candidates (dedup by `(paramName, normalizedValue)` keeping highest-confidence source).

## 3.2 Diff Algorithm (condensed)

```text
diff(run1, run2):
    aligned := alignByKey(run1.exchanges, run2.exchanges)
    for each pair (a, b):
        for channel in [RESP_BODY, SET_COOKIE, RESP_HEADER]:
            deltas := lcsDiff(textOf(a,channel), textOf(b,channel))
            for each deletion d at offset o in run1 / insertion i at offset o' in run2:
                # require matched location - the structure around the change must be similar
                if not sameNeighborhood(a, b, channel, o, o', window=20): continue
                val1 := d.text; val2 := i.text
                usages := findUsages(run1, val1) ∩ mirror(findUsages(run2, val2))
                if usages not empty: emit DiffCandidate(val1, val2, a, channel, o, usages)
```

## 3.3 False-positive guard

Ignore changes where the diffed segment matches any of: timestamps (`\d{10,13}`, ISO-8601), UUIDs repeated in the same exchange from the request itself (request body echoed), numeric fields that increment monotonically (sequence numbers not correlatable in load test), or text inside error pages (different status codes).

---

# Phase 4 - Community Rule Library

Goal: close the feedback loop. Let users and the community contribute rules.

## 4.1 Export

"Export Triggered Rules" button in the Rules menu produces a `.jrules` file containing every rule that produced at least one candidate in the current session, with added `provenance` metadata:

```json
{ "schemaVersion":1, "ruleSetId":"user.export.2026-05-04",
  "provenance": { "exportedBy":"feather-wand v<x>", "sessionHash":"...", "capturesUsed":[...] },
  "rules":[ ... ] }
```

## 4.2 Import

Rules menu → "Import Rules..." → file picker → parsed by `RuleParser` with strict-mode validation; id collisions prompt user (skip/override/rename).

## 4.3 Rule Editor UI

New `RuleEditorDialog` (JDialog, 900x700):
- Form for all `CorrelationRule` fields.
- Embedded `TestExpressionDialog` (reused!) so the author can validate each detector against a currently-loaded `CapturedExchange`.
- Save writes to the configured rules dir.

## 4.4 Community Sharing

Optional menu entry "Browse Community Rules..." opens `https://qainsights.com/feather-wand/rules` in a browser. Out of scope for plugin code in Phase 4; plan for a follow-up "publish to gallery" flow in a future phase.

---

# Testing Strategy

## Unit Tests (JUnit 5 + Mockito, `src/test/java/org/qainsights/jmeter/ai/correlation/`)

| Component | Key tests (examples) |
|---|---|
| `DefaultValueNormalizer` | `should_include_urlDecoded_when_value_contains_percent`, `should_reject_non_printable_base64` |
| `DefaultOccurrenceAnalyzer` | `should_return_first_when_single_match`, `should_flag_ambiguous_when_multiple_no_disambiguator`, `should_follow_LAST_strategy_for_jsf_viewstate` |
| `DefaultFingerprinter` | `should_find_viewstate_in_aspnet_fixture`, `should_dedupe_same_value_same_scope`, `should_not_emit_candidate_when_already_parameterized`, `should_scope_per_thread_group` |
| `DefaultPlacementValidator` | `should_reject_when_first_usage_before_source`, `should_accept_setup_thread_group_source` |
| `DefaultApplier` | `should_create_regex_extractor_with_correct_useField`, `should_replace_form_param_preserving_url_encoding`, `should_emit_single_undo_checkpoint_for_apply` |
| `DefaultExpressionRunner` | `should_return_compile_error_for_bad_regex`, `should_highlight_all_matches`, `should_handle_null_body` |
| `RuleLibrary` | `should_load_classpath_and_user_dir`, `should_override_builtin_by_id`, `should_report_parse_error_with_file_and_line` |
| `JtlCaptureSource` / `HarCaptureSource` | round-trip fixtures in `src/test/resources/correlation/fixtures/` |
| `CandidateTableModel` | sorting, filtering, inline edit revalidation |

Coverage target per user rules: ≥80% on `engine` and `rules` packages; ≥70% on `capture`; UI exempt from hard target but smoke-tested via `SwingUtilities.invokeAndWait`.

## Integration Tests

- `CorrelationRegressionIT` in `src/test/java/.../correlation/it/`.
- Fixtures: pairs of `(input.jmx, input.jtl + bodies/, expected-candidates.json, expected-applied.jmx)` covering:
  - `aspnet-webforms-login/` - VIEWSTATE + EVENTVALIDATION + ASP.NET_SessionId
  - `laravel-login/` - PHPSESSID + XSRF-TOKEN + _token
  - `spring-boot-csrf/` - JSESSIONID + `_csrf` hidden + meta tag
  - `oauth2-authcode/` - state + code + access_token
  - `jsf-primefaces/` - ViewState in both full page and ajax response
  - `redirect-chain-sharepoint/` - multi-hop redirects ending in X-RequestDigest
  - `multi-thread-group/` - same cookie name in two thread groups, must scope independently
  - `already-correlated/` - idempotency (running engine twice must not add duplicates)
- Test driver loads the JMX via JMeter's `SaveService`, runs `DefaultFingerprinter`, asserts candidate JSON equals expected (allow property-order insensitive); then runs `DefaultApplier` on all HIGH and asserts resulting JMX byte-equal to expected (normalized).

## Regression Suite from Real App Recordings

- `scripts/record-app.sh`: starts JMeter Recorder, user logs into a target app, stops, exports JTL + bodies + JMX.
- `scripts/pack-fixture.sh <name>`: moves all three into `src/test/resources/correlation/fixtures/<name>/` and generates a skeleton `expected-candidates.json` by running the fingerprinter once (developer then curates it).
- CI job `correlation-regression` runs all fixtures on every PR; any diff in produced candidates is a failing test.

---

# Shipping Sequence

| Milestone | Content |
|---|---|
| **M1 (Phase 1 alpha)** | `model`, `rules`, `engine` core, `JtlCaptureSource`, `HarCaptureSource`, `DefaultFingerprinter`, `DefaultApplier`, headless unit+integration tests. No UI. |
| **M2 (Phase 1 beta)** | Studio frame, candidates table, evidence panel, **TestExpressionDialog** (top-priority), preview diff, single-item Apply + Undo. |
| **M3 (Phase 1 GA)** | LiveListenerCaptureSource, polish, menu integration via `AiMenuCreator`, docs + sample JMX fixtures. |
| **M4 (Phase 2)** | `CorrelationAiClient`, Claude integration, Apply All HIGH, AI reasoning tab. |
| **M5 (Phase 3)** | Run-Twice Diff end-to-end. |
| **M6 (Phase 4)** | Export, Import, Rule Editor dialog. |

---

# Open Questions to Resolve Before M1

These did not block the plan but need answers before implementation branches from plan:

1. Does `LiveListenerCaptureSource` need to work when the test is run via **non-GUI mode** (the plugin lives in the GUI JAR, but users often record/scan in GUI and run in non-GUI for real load)? Assumed **GUI-only for M3**; non-GUI deferred.
2. Sensitivity: should response body bytes be redacted (PII) when exporting rules or writing a session log? Recommend adding a configurable `jmeter.ai.correlation.redact.patterns` property before M6.
3. Confirm target Anthropic model family for Phase 2 (the existing code defaults to `claude-sonnet-4-6` in `ClaudeService`; the correlation client should reuse that unless overridden by `jmeter.ai.correlation.model`).
