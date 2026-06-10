package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GroovyCodeFormatterTest {

    private GroovyCodeFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new GroovyCodeFormatter();
    }

    // --- Edge cases ---

    @Test
    void format_null_returnsNull() {
        assertNull(formatter.format(null));
    }

    @Test
    void format_empty_returnsEmpty() {
        assertEquals("", formatter.format(""));
    }

    @Test
    void format_whitespaceOnly_returnsUnchanged() {
        assertEquals("   \n  \n  ", formatter.format("   \n  \n  "));
    }

    // --- Basic indentation ---

    @ParameterizedTest
    @MethodSource("basicIndentationCases")
    void basicIndentation(String input, String expected) {
        assertEquals(expected, formatter.format(input));
    }

    static Stream<Arguments> basicIndentationCases() {
        return Stream.of(
                Arguments.of(
                        "def foo() {\nprintln 'hi'\n}",
                        "def foo() {\n    println 'hi'\n}"
                ),
                Arguments.of(
                        "if (true) {\nx = 1\n} else {\nx = 2\n}",
                        "if (true) {\n    x = 1\n} else {\n    x = 2\n}"
                ),
                Arguments.of(
                        "try {\ndoSomething()\n} catch (Exception e) {\nlog.error('fail')\n} finally {\ncleanup()\n}",
                        "try {\n    doSomething()\n} catch (Exception e) {\n    log.error('fail')\n} finally {\n    cleanup()\n}"
                ),
                Arguments.of(
                        "def list = [\n1,\n2,\n3\n]",
                        "def list = [\n    1,\n    2,\n    3\n]"
                ),
                Arguments.of(
                        "def map = [\nkey1: 'val1',\nkey2: 'val2'\n]",
                        "def map = [\n    key1: 'val1',\n    key2: 'val2'\n]"
                ),
                Arguments.of(
                        "nested() {\ninner() {\nx = 1\n}\n}",
                        "nested() {\n    inner() {\n        x = 1\n    }\n}"
                )
        );
    }

    // --- String literal preservation ---

    @ParameterizedTest
    @MethodSource("stringLiteralCases")
    void stringLiterals(String input, String expected) {
        assertEquals(expected, formatter.format(input));
    }

    static Stream<Arguments> stringLiteralCases() {
        return Stream.of(
                // Brace inside double-quoted string should not affect indentation
                Arguments.of(
                        "def s = \"hello {world}\"\nprintln s",
                        "def s = \"hello {world}\"\nprintln s"
                ),
                // Brace inside single-quoted string
                Arguments.of(
                        "def s = 'hello {world}'\nprintln s",
                        "def s = 'hello {world}'\nprintln s"
                ),
                // GString with interpolation
                Arguments.of(
                        "def name = 'test'\ndef msg = \"Hello ${name}!\"\nprintln msg",
                        "def name = 'test'\ndef msg = \"Hello ${name}!\"\nprintln msg"
                ),
                // Triple double-quoted string spanning multiple lines
                Arguments.of(
                        "def s = \"\"\"\nhello {\nworld\n\"\"\"\nprintln s",
                        "def s = \"\"\"\nhello {\nworld\n\"\"\"\nprintln s"
                ),
                // Triple single-quoted string spanning multiple lines
                Arguments.of(
                        "def s = '''\nfoo {\nbar\n'''\nprintln s",
                        "def s = '''\nfoo {\nbar\n'''\nprintln s"
                ),
                // Escaped quote in string
                Arguments.of(
                        "def s = \"she said \\\"hi\\\"\"\nprintln s",
                        "def s = \"she said \\\"hi\\\"\"\nprintln s"
                ),
                // Escaped quote in single-quoted string
                Arguments.of(
                        "def s = 'it\\'s fine'\nprintln s",
                        "def s = 'it\\'s fine'\nprintln s"
                )
        );
    }

    // --- Comment preservation ---

    @ParameterizedTest
    @MethodSource("commentCases")
    void comments(String input, String expected) {
        assertEquals(expected, formatter.format(input));
    }

    static Stream<Arguments> commentCases() {
        return Stream.of(
                // Single-line comment with brace should not affect indentation
                Arguments.of(
                        "def foo() {\n// {\nprintln 'hi'\n}",
                        "def foo() {\n    // {\n    println 'hi'\n}"
                ),
                // Multi-line comment with braces
                Arguments.of(
                        "def foo() {\n/* { } */\nprintln 'hi'\n}",
                        "def foo() {\n    /* { } */\n    println 'hi'\n}"
                ),
                // Multi-line comment spanning lines
                Arguments.of(
                        "def foo() {\n/*\nthis is a comment\nwith { braces }\n*/\nprintln 'hi'\n}",
                        "def foo() {\n    /*\nthis is a comment\nwith { braces }\n*/\n    println 'hi'\n}"
                ),
                // Groovydoc comment
                Arguments.of(
                        "/**\n* Groovydoc with { braces }\n*/\ndef foo() {\nprintln 'hi'\n}",
                        "/**\n* Groovydoc with { braces }\n*/\ndef foo() {\n    println 'hi'\n}"
                ),
                // Inline comment after code
                Arguments.of(
                        "def foo() {\nx = 1 // inline { comment\n}",
                        "def foo() {\n    x = 1 // inline { comment\n}"
                )
        );
    }

    // --- Mixed scenarios ---

    @ParameterizedTest
    @MethodSource("mixedCases")
    void mixedCodeStringsAndComments(String input, String expected) {
        assertEquals(expected, formatter.format(input));
    }

    static Stream<Arguments> mixedCases() {
        return Stream.of(
                // Code with string containing brace, followed by real brace
                Arguments.of(
                        "def foo() {\ndef s = \"{not a block}\"\nif (true) {\nprintln s\n}\n}",
                        "def foo() {\n    def s = \"{not a block}\"\n    if (true) {\n        println s\n    }\n}"
                ),
                // Typical JMeter JSR223 script
                Arguments.of(
                        "import org.apache.jmeter.samplers.SampleResult\n\ndef result = new SampleResult()\nresult.sampleStart()\ntry {\ndef response = \"OK\"\nlog.info(\"Response: ${response}\")\nresult.setSuccessful(true)\n} catch (Exception e) {\nlog.error(\"Error: ${e.message}\")\nresult.setSuccessful(false)\n} finally {\nresult.sampleEnd()\n}",
                        "import org.apache.jmeter.samplers.SampleResult\n\ndef result = new SampleResult()\nresult.sampleStart()\ntry {\n    def response = \"OK\"\n    log.info(\"Response: ${response}\")\n    result.setSuccessful(true)\n} catch (Exception e) {\n    log.error(\"Error: ${e.message}\")\n    result.setSuccessful(false)\n} finally {\n    result.sampleEnd()\n}"
                ),
                // Blank lines preserved
                Arguments.of(
                        "def a = 1\n\ndef b = 2\n\ndef c = 3",
                        "def a = 1\n\ndef b = 2\n\ndef c = 3"
                )
        );
    }

    // --- Groovy-specific syntax ---

    @Test
    void closureWithEach() {
        String input = "def items = [1, 2, 3]\nitems.each {\nprintln it\n}";
        String expected = "def items = [1, 2, 3]\nitems.each {\n    println it\n}";
        assertEquals(expected, formatter.format(input));
    }

    @Test
    void closureReturningMap() {
        String input = "def result = items.collect {\n[key: it.value]\n}";
        String expected = "def result = items.collect {\n    [key: it.value]\n}";
        assertEquals(expected, formatter.format(input));
    }

    @Test
    void nestedClosures() {
        String input = "outer() {\ninner() {\nx = 1\n}\ny = 2\n}";
        String expected = "outer() {\n    inner() {\n        x = 1\n    }\n    y = 2\n}";
        assertEquals(expected, formatter.format(input));
    }

    @Test
    void multiLineStringPreservesInternalIndentation() {
        String input = "def sql = \"\"\"\n    SELECT * FROM users\n    WHERE id = 1\n\"\"\"";
        String expected = "def sql = \"\"\"\n    SELECT * FROM users\n    WHERE id = 1\n\"\"\"";
        assertEquals(expected, formatter.format(input));
    }

    @Test
    void alreadyFormattedCodeRemainsStable() {
        String input = "def foo() {\n    println 'hello'\n    if (true) {\n        println 'world'\n    }\n}";
        String expected = "def foo() {\n    println 'hello'\n    if (true) {\n        println 'world'\n    }\n}";
        assertEquals(expected, formatter.format(input));
    }

    @Test
    void divisionOperatorNotTreatedAsSlashyString() {
        String input = "def x = 10 / 2\ndef y = 20 / 4\nprintln x + y";
        String expected = "def x = 10 / 2\ndef y = 20 / 4\nprintln x + y";
        assertEquals(expected, formatter.format(input));
    }

    @Test
    void parenthesizedExpressions() {
        String input = "def result = (\na + b\n) * c";
        String expected = "def result = (\n    a + b\n) * c";
        assertEquals(expected, formatter.format(input));
    }
}
