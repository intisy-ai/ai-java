package io.github.intisy.ai.js;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests locking {@link SimpleJsonCodec}'s exact output/parse behavior where it must be
 * byte-compatible with the JVM's gson-backed {@code GsonJsonCodec} (disableHtmlEscaping +
 * LONG_OR_DOUBLE): null/empty/whitespace-only input, and control-character escaping. See
 * {@link JsonCodecParityTest} for the cross-codec parametric comparison against the real
 * {@code GsonJsonCodec}.
 *
 * <p>Control characters below are built via {@code char} casts (not source-literal escapes)
 * so the exact byte value is unambiguous regardless of this file's own text encoding.
 */
class SimpleJsonCodecTest {

    private static final char BACKSPACE = (char) 0x08;
    private static final char FORM_FEED = (char) 0x0C;
    private static final char TAB = (char) 0x09;
    private static final char NEWLINE = (char) 0x0A;
    private static final char CARRIAGE_RETURN = (char) 0x0D;
    private static final char SOH = (char) 0x01; // no named gson escape -> plain unicode escape
    private static final char UNIT_SEPARATOR = (char) 0x1F; // no named gson escape -> plain unicode escape
    private static final char NUL = (char) 0x00; // no named gson escape -> plain unicode escape
    private static final char SPACE = (char) 0x20;

    @Test
    void nullAndEmptyInputParseToNull() {
        SimpleJsonCodec codec = new SimpleJsonCodec();
        assertNull(codec.parse(null));
        assertNull(codec.parse(""));
    }

    @Test
    void whitespaceOnlyInputParsesToNull() {
        // Matches gson: JsonReader.peek() on a whitespace-only reader hits EOFException before
        // any token is read, so Gson.fromJson's isEmpty flag stays true and it returns null.
        SimpleJsonCodec codec = new SimpleJsonCodec();
        assertNull(codec.parse(String.valueOf(SPACE)));
        assertNull(codec.parse(SPACE + "" + NEWLINE + TAB + SPACE + SPACE));
    }

    @Test
    void namedControlEscapesMatchGson() {
        SimpleJsonCodec codec = new SimpleJsonCodec();

        assertEquals("\"\\b\"", codec.stringify(String.valueOf(BACKSPACE)));
        assertEquals("\"\\f\"", codec.stringify(String.valueOf(FORM_FEED)));
        assertEquals("\"\\t\"", codec.stringify(String.valueOf(TAB)));
        assertEquals("\"\\n\"", codec.stringify(String.valueOf(NEWLINE)));
        assertEquals("\"\\r\"", codec.stringify(String.valueOf(CARRIAGE_RETURN)));
    }

    @Test
    void unnamedControlCharsGetUnicodeEscape() {
        // A control char with no named escape gets a unicode escape: lowercase hex, 4 digits.
        SimpleJsonCodec codec = new SimpleJsonCodec();

        assertEquals("\"\\u0001\"", codec.stringify(String.valueOf(SOH)));
        assertEquals("\"\\u001f\"", codec.stringify(String.valueOf(UNIT_SEPARATOR)));
        assertEquals("\"\\u0000\"", codec.stringify(String.valueOf(NUL)));
    }

    @Test
    void htmlMetaCharsAndSlashAreNotEscaped() {
        // gson's disableHtmlEscaping means '<' '>' '&' '=' '\'' stay literal; '/' is never
        // escaped by gson either way.
        SimpleJsonCodec codec = new SimpleJsonCodec();
        String input = "<a>&'=\"/\"";
        String out = codec.stringify(input);
        assertEquals("\"<a>&'=\\\"/\\\"\"", out);
        assertFalse(out.contains("\\u003c"));
        assertFalse(out.contains("\\u0026"));
    }

    @Test
    void parseStringDecodesNamedAndUnicodeControlEscapes() {
        SimpleJsonCodec codec = new SimpleJsonCodec();
        assertEquals(String.valueOf(BACKSPACE), codec.parse("\"\\b\""));
        assertEquals(String.valueOf(FORM_FEED), codec.parse("\"\\f\""));
        assertEquals(String.valueOf(SOH), codec.parse("\"\\u0001\""));
    }

    @Test
    void controlCharRoundTripsThroughStringifyThenParse() {
        SimpleJsonCodec codec = new SimpleJsonCodec();
        String original = "a" + BACKSPACE + "b" + FORM_FEED + "cd" + SOH;
        String json = codec.stringify(original);
        Object parsedBack = codec.parse(json);
        assertEquals(original, parsedBack);
    }

    @Test
    void wholeNumberRoundTripsWithoutTrailingZero() {
        SimpleJsonCodec codec = new SimpleJsonCodec();

        Object parsed = codec.parse("{\"count\":5}");
        assertTrue(parsed instanceof Map);
        Object count = ((Map<?, ?>) parsed).get("count");
        assertTrue(count instanceof Long, "expected Long, got " + (count == null ? "null" : count.getClass()));
        assertEquals(5L, count);

        String out = codec.stringify(parsed);
        assertTrue(out.contains("\"count\":5"), out);
        assertFalse(out.contains("\"count\":5.0"), out);
    }

    @Test
    void longRangeNumberRoundTripsExactly() {
        SimpleJsonCodec codec = new SimpleJsonCodec();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("expires", 1752345678901L);
        String out = codec.stringify(m);
        assertEquals("{\"expires\":1752345678901}", out);

        Object parsed = codec.parse(out);
        assertEquals(1752345678901L, ((Map<?, ?>) parsed).get("expires"));
    }
}
