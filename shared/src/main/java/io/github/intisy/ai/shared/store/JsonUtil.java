package io.github.intisy.ai.shared.store;

import java.util.List;
import java.util.Map;

/**
 * Narrowing helpers over the {@code JsonCodec} parsed shape ({@code Map}/{@code List}/
 * {@code String}/{@code Number}/{@code Boolean}/{@code null}). No reflection, no gson: every
 * store in this package hand-rolls its {@code Map<String,Object>} <-> POJO conversion through
 * these helpers so the code stays transpilable.
 */
final class JsonUtil {
    private JsonUtil() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    static Boolean asBoolean(Object o) {
        return o instanceof Boolean ? (Boolean) o : null;
    }

    static Long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : null;
    }

    static Integer asInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }
}
