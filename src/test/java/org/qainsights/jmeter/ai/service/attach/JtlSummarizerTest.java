package org.qainsights.jmeter.ai.service.attach;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JtlSummarizer}: CSV parsing (incl. quoted commas),
 * headline aggregates, per-label breakdown, slowest/failing samples, and
 * detection heuristics.
 */
class JtlSummarizerTest {

    private static final String HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect";

    private static String jtl(String... rows) {
        return HEADER + "\n" + String.join("\n", rows);
    }

    @Test
    void looksLikeJtlByHeader() {
        assertTrue(JtlSummarizer.looksLikeJtl("results.csv", jtl("1,2,L,200,OK,t,text,true,,0,0,1,1,u,2,0,0")));
        assertFalse(JtlSummarizer.looksLikeJtl("notes.txt", "hello\nworld"));
        assertFalse(JtlSummarizer.looksLikeJtl("results.jtl", ""));
    }

    @Test
    void looksLikeJtlByExtensionWithCsvBody() {
        assertTrue(JtlSummarizer.looksLikeJtl("results.jtl", "a,b,c\n1,2,3"));
    }

    @Test
    void summarizesHeadlineAggregates() {
        String out = JtlSummarizer.summarize(jtl(
                "1000,100,GET /a,200,OK,t,text,true,,0,0,1,1,u,100,0,0",
                "2000,300,GET /a,200,OK,t,text,true,,0,0,1,1,u,300,0,0",
                "3000,500,POST /b,500,Server Error,t,text,false,javax.net.ssl.SSLException,0,0,1,1,u,500,0,0"));

        assertTrue(out.contains("Samples: 3"));
        assertTrue(out.contains("Errors: 1 (33.33%)"));
        assertTrue(out.contains("median 300"));
        assertTrue(out.contains("min 100"));
        assertTrue(out.contains("max 500"));
        assertTrue(out.contains("Throughput:"));
    }

    @Test
    void perLabelBreakdownCountsErrors() {
        String out = JtlSummarizer.summarize(jtl(
                "1000,100,GET /a,200,OK,t,text,true,,0,0,1,1,u,100,0,0",
                "2000,200,GET /a,200,OK,t,text,true,,0,0,1,1,u,200,0,0",
                "3000,900,POST /b,500,Err,t,text,false,boom,0,0,1,1,u,900,0,0"));

        assertTrue(out.contains("GET /a: 2, 0, 150, 200"));
        assertTrue(out.contains("POST /b: 1, 1, 900, 900"));
    }

    @Test
    void slowestAndFailingSamplesListed() {
        String out = JtlSummarizer.summarize(jtl(
                "1000,100,fast,200,OK,t,text,true,,0,0,1,1,u,100,0,0",
                "2000,9999,slow,200,OK,t,text,true,,0,0,1,1,u,9999,0,0",
                "3000,50,broken,404,Not Found,t,text,false,resource missing,0,0,1,1,u,50,0,0"));

        assertTrue(out.contains("## Slowest samples"));
        assertTrue(out.contains("- slow, 9999, 200"));
        assertTrue(out.contains("## Failing samples"));
        assertTrue(out.contains("- broken, 404, Not Found, resource missing"));
    }

    @Test
    void quotedFieldsWithCommasParse() {
        String out = JtlSummarizer.summarize(jtl(
                "1000,100,\"GET /a, with comma\",200,OK,t,text,true,,0,0,1,1,u,100,0,0"));
        assertTrue(out.contains("GET /a, with comma: 1, 0, 100, 100"));
    }

    @Test
    void rawSampleRowsIncluded() {
        String row = "1000,100,GET /a,200,OK,t,text,true,,0,0,1,1,u,100,0,0";
        String out = JtlSummarizer.summarize(jtl(row));
        assertTrue(out.contains("## Sample raw rows"));
        assertTrue(out.contains(row));
    }

    @Test
    void headerOnlyAndEmptyInputs() {
        assertEquals("Empty or header-only results file.", JtlSummarizer.summarize(HEADER));
        // header + blank lines: parses but finds no samples
        assertEquals("No sample rows found in the results file.", JtlSummarizer.summarize(HEADER + "\n\n"));
    }

    @Test
    void labelOverflowShowsRemainderCount() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            rows.append("1000,100,label-").append(i).append(",200,OK,t,text,true,,0,0,1,1,u,100,0,0\n");
        }
        String out = JtlSummarizer.summarize(HEADER + "\n" + rows);
        assertTrue(out.contains("label-0"));
        assertTrue(out.contains("... and 2 more labels"), "labels beyond the cap must be summarized");
    }

    @Test
    void slowestSamplesCappedAtFive() {
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            rows.append("1000,").append(i * 100).append(",slow-").append(i)
                    .append(",200,OK,t,text,true,,0,0,1,1,u,100,0,0\n");
        }
        String out = JtlSummarizer.summarize(HEADER + "\n" + rows);
        int slowestSection = out.indexOf("## Slowest samples");
        int failingSection = out.indexOf("## Sample raw rows");
        String slowest = out.substring(slowestSection, failingSection);
        assertTrue(slowest.contains("slow-8"), "the slowest must lead");
        assertFalse(slowest.contains("slow-2"), "only the top 5 slowest are listed");
        assertFalse(slowest.contains("slow-1"));
    }

    @Test
    void nonNumericTimestampRowsSkipped() {
        String out = JtlSummarizer.summarize(jtl(
                "notatimestamp,100,GET /a,200,OK,t,text,true,,0,0,1,1,u,100,0,0",
                "1000,abc,GET /b,200,OK,t,text,true,,0,0,1,1,u,100,0,0",
                "2000,100,GET /c,200,OK,t,text,true,,0,0,1,1,u,100,0,0"));
        assertTrue(out.contains("Samples: 1"));
        assertTrue(out.contains("GET /c"));
    }

    @Test
    void headerWithoutOptionalColumnsStillSummarizes() {
        // no responseMessage / failureMessage columns
        String header = "timeStamp,elapsed,label,responseCode,threadName,dataType,success,bytes";
        String out = JtlSummarizer.summarize(header + "\n"
                + "1000,100,GET /a,200,t,text,true,0\n"
                + "2000,200,GET /b,500,t,text,false,0\n");
        assertTrue(out.contains("Samples: 2"));
        assertTrue(out.contains("Errors: 1 (50.00%)"));
        assertTrue(out.contains("## Failing samples"));
    }

    @Test
    void headerMissingRequiredColumnSkipsAllRows() {
        // no success column: every row must be skipped safely
        String header = "timeStamp,elapsed,label,responseCode,threadName,dataType,bytes";
        String out = JtlSummarizer.summarize(header + "\n" + "1000,100,GET /a,200,t,text,0");
        assertEquals("No sample rows found in the results file.", out);
    }

    @Test
    void malformedRowsSkipped() {
        String out = JtlSummarizer.summarize(jtl(
                "not,a,valid,row",
                "1000,100,GET /a,200,OK,t,text,true,,0,0,1,1,u,100,0,0"));
        assertTrue(out.contains("Samples: 1"));
    }

    @Test
    void splitCsvHandlesQuotes() {
        String[] fields = JtlSummarizer.splitCsv("a,\"b,c\",\"d\"\"e\"");
        assertArrayEquals(new String[]{"a", "b,c", "d\"e"}, fields);
    }
}
