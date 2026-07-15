package io.github.intisy.ai.jvm.backend.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import io.github.intisy.ai.shared.spi.JsonCodec;

import java.lang.reflect.Type;

/**
 * gson-backed {@link JsonCodec}: the real JVM implementation of the JSON boundary SPI.
 * {@code LONG_OR_DOUBLE} matches JS {@code number} semantics — a whole JSON number
 * deserializes to {@link Long} (so it reserializes without a spurious trailing {@code .0}),
 * a fractional one to {@link Double}. Without this, gson's default {@code ToNumberPolicy.DOUBLE}
 * would turn every parsed number into a {@code Double}, corrupting integer round-trips
 * (e.g. {@code {"count":5}} would come back as {@code {"count":5.0}}).
 *
 * <p>Reference: the old {@code core} module's store gson setup
 * ({@code core/src/main/java/.../store/AccountStore.java}'s {@code GSON} field).
 */
public class GsonJsonCodec implements JsonCodec {

    private static final Type OBJECT_TYPE = new TypeToken<Object>() {
    }.getType();

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create();

    @Override
    public Object parse(String json) {
        if (json == null || json.isEmpty()) return null;
        return GSON.fromJson(json, OBJECT_TYPE);
    }

    @Override
    public String stringify(Object value) {
        return GSON.toJson(value);
    }
}
