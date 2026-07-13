package io.github.intisy.ai.js;

import io.github.intisy.ai.jvm.GsonJsonCodec;
import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Cross-codec byte-compat lock: runs the SAME inputs through {@link SimpleJsonCodec} (the JS/
 * TeaVM-side hand-rolled codec) and the JVM's gson-backed {@code GsonJsonCodec}
 * (disableHtmlEscaping + LONG_OR_DOUBLE), and asserts both produce IDENTICAL output strings.
 * This is a {@code testImplementation project(":jvm")} dependency declared in {@code
 * js/build.gradle} — test-only, so gson never reaches the "main" classpath TeaVM transpiles.
 */
class JsonCodecParityTest {

    private final JsonCodec simple = new SimpleJsonCodec();
    private final JsonCodec gson = new GsonJsonCodec();

    @Test
    void emptyAndNullInputBothParseToNull() {
        assertNull(simple.parse(null));
        assertNull(gson.parse(null));
        assertNull(simple.parse(""));
        assertNull(gson.parse(""));
    }

    @ParameterizedTest
    @MethodSource("stringifyVectors")
    void stringifyProducesIdenticalOutput(Object value) {
        assertEquals(gson.stringify(value), simple.stringify(value),
                "SimpleJsonCodec.stringify diverged from GsonJsonCodec for: " + value);
    }

    static Stream<Arguments> stringifyVectors() {
        char backspace = (char) 0x08;
        char formFeed = (char) 0x0C;
        char tab = (char) 0x09;
        char newline = (char) 0x0A;
        char carriageReturn = (char) 0x0D;
        char soh = (char) 0x01;

        Map<String, Object> withControlChars = new LinkedHashMap<>();
        withControlChars.put("field", "a" + backspace + "b" + formFeed + "c" + tab + "d" + newline
                + "e" + carriageReturn + "f" + soh + "g");

        Map<String, Object> withHtmlMeta = new LinkedHashMap<>();
        withHtmlMeta.put("url", "https://a.com/x=1&y=2<foo>'bar'");

        Map<String, Object> wholeNumber = new LinkedHashMap<>();
        wholeNumber.put("count", 5L);

        Map<String, Object> longRangeNumber = new LinkedHashMap<>();
        longRangeNumber.put("expires", 1752345678901L);

        Map<String, Object> fractional = new LinkedHashMap<>();
        fractional.put("fraction", 0.5);

        return Stream.of(
                Arguments.of(String.valueOf(backspace)),
                Arguments.of(String.valueOf(formFeed)),
                Arguments.of(String.valueOf(tab)),
                Arguments.of(String.valueOf(newline)),
                Arguments.of(String.valueOf(carriageReturn)),
                Arguments.of(String.valueOf(soh)),
                Arguments.of("<>&'=/ plain text"),
                Arguments.of(withControlChars),
                Arguments.of(withHtmlMeta),
                Arguments.of(wholeNumber),
                Arguments.of(longRangeNumber),
                Arguments.of(fractional)
        );
    }

    @ParameterizedTest
    @MethodSource("roundTripVectors")
    void parseThenStringifyRoundTripsIdenticallyThroughBothCodecs(String json) {
        Object simpleParsed = simple.parse(json);
        Object gsonParsed = gson.parse(json);
        assertEquals(gson.stringify(gsonParsed), simple.stringify(simpleParsed),
                "round-trip diverged for input: " + json);
    }

    static Stream<Arguments> roundTripVectors() {
        return Stream.of(
                Arguments.of("{\"count\":5}"),
                Arguments.of("{\"expires\":1752345678901}"),
                Arguments.of("{\"fraction\":0.5}"),
                Arguments.of("{\"url\":\"https://a.com/x=1&y=2\"}"),
                Arguments.of("[1,2,3]"),
                Arguments.of("\"line1\\nline2\\ttab\\bBS\\fFF\""),
                Arguments.of("\"\\u0001\\u001f\"")
        );
    }
}
