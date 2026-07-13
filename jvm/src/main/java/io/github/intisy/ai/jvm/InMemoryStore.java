package io.github.intisy.ai.jvm;

import io.github.intisy.ai.shared.spi.Store;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Thread-safe, ephemeral {@link Store}: a {@link ConcurrentHashMap} with no I/O and no
 * persistence. For servers/tests that want a non-file {@link Store} backend — e.g. a
 * short-lived server process, or a default when no {@code configFolder} is configured.
 * All state is lost when the process exits.
 *
 * <p>{@link #update} is atomic per key via {@link ConcurrentHashMap#compute}, which
 * ConcurrentHashMap guarantees runs the remapping function under a lock held for that
 * key's bin, so concurrent {@code update} calls on the same key never lose a write
 * (mirrors {@link FileStore}'s cross-process file-lock guarantee, but in-process).
 *
 * <p>Semantics match {@link FileStore}: {@link #get} returns {@code null} for an absent
 * key, {@link #delete} of an absent key is a no-op, {@link #listKeys} returns keys
 * starting with {@code prefix}.
 */
public class InMemoryStore implements Store {

    private final ConcurrentHashMap<String, String> data = new ConcurrentHashMap<>();

    @Override
    public String get(String key) {
        return data.get(key);
    }

    @Override
    public void put(String key, String value) {
        data.put(key, value);
    }

    @Override
    public boolean exists(String key) {
        return data.containsKey(key);
    }

    @Override
    public void delete(String key) {
        data.remove(key);
    }

    @Override
    public void update(String key, UnaryOperator<String> mutator) {
        // compute() is atomic per key: ConcurrentHashMap holds the bin lock for the
        // duration of the remapping function, so no concurrent update on the same key
        // can interleave and lose a write. ConcurrentHashMap.compute() removes the
        // mapping if the function returns null, but FileStore.update() treats a null
        // result as writing an empty string (the key keeps existing) — match that here.
        data.compute(key, (k, current) -> {
            String next = mutator.apply(current);
            return next != null ? next : "";
        });
    }

    @Override
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        for (String k : data.keySet()) {
            if (k.startsWith(prefix)) keys.add(k);
        }
        return keys;
    }
}
