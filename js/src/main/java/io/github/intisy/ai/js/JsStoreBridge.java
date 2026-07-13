package io.github.intisy.ai.js;

import io.github.intisy.ai.shared.spi.Store;

import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.jso.core.JSObjects;
import org.teavm.jso.core.JSString;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Phase 3 Task 1: the LIVE {@link Store} — a {@code shared} {@code Store} implementation that
 * delegates every call straight through to a JS-provided store object, instead of
 * {@link InMemoryStore}'s one-shot snapshot (see {@link AiJavaJs#seedStore}, which this bridge
 * replaces for {@link AiJavaJs#routeJsonAsync}). Any mutation shared's routing/selection/
 * rate-limit logic makes during a call (e.g. {@code Selection}'s round-robin cursor advance,
 * {@code AccountManager}'s {@code coolingDownUntil}/{@code rateLimitResetTimes} writes) lands
 * directly on the JS object the caller supplied, so it is visible to the NEXT call that reuses
 * the same JS store instance — the whole point of "live" vs. "snapshot".
 */
public final class JsStoreBridge implements Store {

    /**
     * The JS-provided live store: {@code get}/{@code put}/{@code exists}/{@code delete}/
     * {@code listKeys}, all SYNCHRONOUS. This is intentionally NOT async (contrast
     * {@link JsHttpClientBridge.JsHttpSend}, which must be async because {@code fetch} is):
     * JS is single-threaded and has no preemption, so two synchronous calls back into the same
     * JS object with no {@code await}/yield point between them can never be interleaved by any
     * other JS code — see {@link #update} for why that is exactly the atomicity {@link Store}'s
     * contract asks for, with no actual lock needed.
     *
     * <p>Every method here is a plain (non-generic) JSO interface method, so TeaVM's normal
     * String/array marshalling would apply at these boundaries too — but per the Task-5 gotcha
     * (see {@link JsHttpClientBridge.JsHttpSend}'s javadoc), values that also cross a generic
     * boundary elsewhere (e.g. inside a lambda closure, or if this type is ever used through a
     * generic callback) are safest carried as {@link JSString} throughout, so String conversion
     * happens only at the explicit {@code JSString.valueOf}/{@code .stringValue()} edges in
     * {@link JsStoreBridge}'s own method bodies below. Consistent with {@code JsHttpSend}.
     */
    public interface JsStore extends JSObject {
        /** Returns the stored JSON string for {@code key}, or {@code null}/{@code undefined} when absent. */
        JSString get(JSString key);

        void put(JSString key, JSString value);

        boolean exists(JSString key);

        void delete(JSString key);

        /** Returns every key starting with {@code prefix} as a plain JS array of strings. */
        JSArrayReader<JSString> listKeys(JSString prefix);
    }

    private final JsStore jsStore;

    public JsStoreBridge(JsStore jsStore) {
        this.jsStore = jsStore;
    }

    @Override
    public String get(String key) {
        return toJavaStringOrNull(jsStore.get(JSString.valueOf(key)));
    }

    @Override
    public void put(String key, String value) {
        jsStore.put(JSString.valueOf(key), JSString.valueOf(value));
    }

    @Override
    public boolean exists(String key) {
        return jsStore.exists(JSString.valueOf(key));
    }

    @Override
    public void delete(String key) {
        jsStore.delete(JSString.valueOf(key));
    }

    /**
     * {@code get} then {@code put} — two separate synchronous round trips into the JS store,
     * with no suspension point (no {@code @Async}, no promise, no callback) between them. Since
     * JS has no preemptive concurrency, nothing else on the event loop can observe or mutate the
     * key in between: the read-modify-write is atomic with respect to every other call this
     * bridge (or anything else running in the same JS runtime) can make, exactly matching
     * {@link Store#update}'s "must be atomic; that is the implementation's concern" contract —
     * satisfied here by the single-threaded host rather than by an actual lock.
     */
    @Override
    public void update(String key, UnaryOperator<String> mutator) {
        String current = get(key);
        String next = mutator.apply(current);
        put(key, next);
    }

    @Override
    public List<String> listKeys(String prefix) {
        JSArrayReader<JSString> arr = jsStore.listKeys(JSString.valueOf(prefix));
        List<String> out = new ArrayList<>();
        if (arr == null || JSObjects.isUndefined(arr)) return out;
        int n = arr.getLength();
        for (int i = 0; i < n; i++) {
            String s = toJavaStringOrNull(arr.get(i));
            if (s != null) out.add(s);
        }
        return out;
    }

    // A JS store's get() may return either `null` or `undefined` for an absent key depending on
    // how the caller implemented it (e.g. a bare `Map.get` returns `undefined`, a defensive
    // ternary returns `null`) — shared's Store SPI only knows "absent" as Java `null`, so both
    // collapse to that here.
    private static String toJavaStringOrNull(JSString value) {
        if (value == null || JSObjects.isUndefined(value)) return null;
        return value.stringValue();
    }
}
