package io.github.intisy.ai.jvm;

import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GsonJsonCodecTest {

    @Test
    void wholeNumberRoundTripsWithoutTrailingZero() {
        GsonJsonCodec codec = new GsonJsonCodec();

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
    void fractionalNumberKeepsDecimalPoint() {
        GsonJsonCodec codec = new GsonJsonCodec();

        Object parsed = codec.parse("{\"fraction\":0.5}");
        Object fraction = ((Map<?, ?>) parsed).get("fraction");
        assertTrue(fraction instanceof Double);
        assertEquals(0.5, fraction);

        String out = codec.stringify(parsed);
        assertTrue(out.contains("\"fraction\":0.5"), out);
    }

    @Test
    void nestedArraysAndObjectsParseToPlainCollections() {
        GsonJsonCodec codec = new GsonJsonCodec();

        Object parsed = codec.parse("{\"list\":[1,2,{\"a\":\"b\"}]}");
        Map<?, ?> map = (Map<?, ?>) parsed;
        Object list = map.get("list");
        assertTrue(list instanceof java.util.List);
        java.util.List<?> l = (java.util.List<?>) list;
        assertEquals(1L, l.get(0));
        assertEquals(2L, l.get(1));
        assertTrue(l.get(2) instanceof Map);
    }

    @Test
    void nullAndEmptyInputParseToNull() {
        GsonJsonCodec codec = new GsonJsonCodec();
        assertNull(codec.parse(null));
        assertNull(codec.parse(""));
    }

    @Test
    void stringifyDoesNotHtmlEscape() {
        GsonJsonCodec codec = new GsonJsonCodec();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", "https://a.com/x=1&y=2");
        String out = codec.stringify(m);
        assertTrue(out.contains("&"), out);
        assertFalse(out.contains("\\u0026"), out);
    }
}
