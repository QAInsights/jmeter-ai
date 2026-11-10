package org.qainsights.jmeter.ai.param;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParameterSampleRowsTest {
    @TempDir Path directory;

    @Test
    void typesAreInferredLocallyAndOnlyNamesAndTypesReachMockProvider() throws Exception {
        List<String> secrets = List.of("real.person@private.example", "123456789",
                "550e8400-e29b-41d4-a716-446655440000", "2026-11-10",
                "https://internal.private.example/api/alice", "private-element-name");
        List<ParameterSampleRows.Column> columns = List.of(
                ParameterSampleRows.column("email", secrets.get(0)),
                ParameterSampleRows.column("id", secrets.get(1)),
                ParameterSampleRows.column("uuid", secrets.get(2)),
                ParameterSampleRows.column("date", secrets.get(3)),
                ParameterSampleRows.column("url", secrets.get(4)),
                ParameterSampleRows.column("label", secrets.get(5)));
        assertEquals(List.of("email", "number", "uuid", "date", "string", "string"),
                columns.stream().map(ParameterSampleRows.Column::type).toList());
        @SuppressWarnings("unchecked") Function<String, String> provider = mock(Function.class);
        when(provider.apply(anyString())).thenAnswer(call -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            String prompt = call.getArgument(0);
            for (String secret : secrets) {
                assertFalse(prompt.contains(secret), secret);
            }
            assertFalse(prompt.contains("internal.private.example"));
            assertTrue(prompt.contains("email"));
            return "[[\"test@example.com\",\"4567\",\"uuid\",\"2027-01-01\",\"fictional\",\"label\"]]";
        });
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<List<List<String>>> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> new ParameterSampleRequest(provider,
                ParameterSampleRows.prompt(columns, 1), 1, columns.size(), 5000, rows -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    result.set(rows);
                    completed.countDown();
                }, message -> fail(message)).start());
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals("test@example.com", result.get().get(0).get(0));
        verify(provider).apply(anyString());
    }

    @Test
    void rejectsEmptyMalformedWrongSizeExpressionsAndUnsafeCells() {
        for (String response : List.of("", "not json", "[]", "{}", "[[\"one\",\"two\"]]",
                "[[null]]", "[[1234]]", "[[\"${secret}\"]]", "[[\"=SUM(A1)\"]]",
                "[[\"line\\nline\"]]", "[[\"a\\\"quote\"]]", "[[\"a\\\\slash\"]]",
                "[[\"ok\"]] [[\"extra\"]]")) {
            assertThrows(IllegalArgumentException.class, () -> ParameterSampleRows.parse(response, 1, 1), response);
        }
        assertThrows(IllegalArgumentException.class, () -> ParameterSampleRows.prompt(List.of(), 10));
        for (int count : List.of(0, 501)) {
            assertThrows(IllegalArgumentException.class, () -> ParameterSampleRows.prompt(
                    List.of(ParameterSampleRows.column("id", "1234")), count));
        }
        assertEquals(List.of(List.of("-1234", "a,b")),
                ParameterSampleRows.parse("[[\"-1234\",\"a,b\"]]", 1, 2));
    }

    @Test
    void acceptsMarkdownFencedSampleRows() {
        assertEquals(List.of(List.of("test@example.com", "1234")),
                ParameterSampleRows.parse("```json\n[[\"test@example.com\",\"1234\"]]\n```", 1, 2));
    }

    @Test
    void acceptsProseAroundTheOutermostArrayWithoutRelaxingValidation() {
        assertEquals(List.of(List.of("bracket ] inside a cell")),
                ParameterSampleRows.parse(
                        "Here are your sample rows:\n[[\"bracket ] inside a cell\"]]\nHope this helps.", 1, 1));
        for (String payload : List.of("[[\"${secret}\"]]", "[[\"one\",\"two\"]]", "[]",
                "[[\"ok\"],]", "[[1234]]")) {
            assertThrows(IllegalArgumentException.class, () -> ParameterSampleRows.parse(
                    "Here are the rows:\n```json\n" + payload + "\n```\nDone.", 1, 1));
        }
    }

    @Test
    void sampleRowsFollowCurrentValuesAndReuseOriginalColumnDefaults() throws Exception {
        Path csv = directory.resolve("samples.csv");
        List<List<String>> samples = ParameterSampleRows.parse("[[\"a,b\"],[\"second\"]]", 2, 1);
        ParameterCsv.write(csv, ',', List.of("user"), List.of("alice"), List.of(), true, samples);
        assertEquals("\"user\"\n\"alice\"\n\"a,b\"\n\"second\"\n", Files.readString(csv));
        Files.writeString(csv, "old\r\nfirst\r\nsecond");
        ParameterCsv.write(csv, ',', List.of("user"), List.of("alice"), List.of("old"), true, samples);
        assertEquals("old,\"user\"\r\nfirst,\"alice\"\r\nsecond,\"alice\"\r\n"
                + "\"first\",\"a,b\"\r\n\"first\",\"second\"\r\n", Files.readString(csv));
        assertThrows(IllegalArgumentException.class, () -> ParameterCsv.write(csv, ',',
                List.of("user"), List.of("alice"), List.of(), true, List.of(List.of("one", "two"))));
        String before = Files.readString(csv);
        assertThrows(IllegalStateException.class, () -> ParameterTransaction.apply(csv, () -> {
            ParameterCsv.write(csv, ',', List.of("user"), List.of("alice"), List.of(), true, samples);
            return 0;
        }, () -> {
            throw new IllegalStateException("plan mutation failed");
        }));
        assertEquals(before, Files.readString(csv));
    }

    @Test
    void timeoutAndProviderErrorsReturnOnEdtWithoutSuccess() throws Exception {
        for (boolean timeout : List.of(false, true)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> error = new AtomicReference<>();
            Function<String, String> provider = prompt -> {
                if (!timeout) {
                    throw new IllegalStateException("provider private error");
                }
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "[[\"late\"]]";
            };
            SwingUtilities.invokeAndWait(() -> new ParameterSampleRequest(provider, "metadata", 1, 1,
                    timeout ? 30 : 5000, rows -> fail("Unexpected success"), message -> {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        error.set(message);
                        done.countDown();
                    }).start());
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(error.get().contains(timeout ? "timed out" : "Could not generate"));
            assertFalse(error.get().contains("private error"));
        }
    }

    @Test
    void cancellationDiscardsLateProviderResults() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        @SuppressWarnings("unchecked") java.util.function.Consumer<List<List<String>>> success =
                mock(java.util.function.Consumer.class);
        @SuppressWarnings("unchecked") java.util.function.Consumer<String> failure =
                mock(java.util.function.Consumer.class);
        AtomicReference<ParameterSampleRequest> request = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            request.set(new ParameterSampleRequest(prompt -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                returned.countDown();
                return "[[\"late\"]]";
            }, "metadata", 1, 1, 5000, success, failure));
            request.get().start();
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> request.get().cancel());
        release.countDown();
        assertTrue(returned.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {});
        verifyNoInteractions(success, failure);
    }
}
