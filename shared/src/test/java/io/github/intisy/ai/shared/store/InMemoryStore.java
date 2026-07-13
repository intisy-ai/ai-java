package io.github.intisy.ai.shared.store;

import io.github.intisy.ai.shared.spi.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** In-memory {@link Store} fake for tests: a plain {@code Map<String,String>}, no I/O. */
public class InMemoryStore implements Store {
    private final Map<String, String> data = new LinkedHashMap<>();

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
        data.put(key, mutator.apply(data.get(key)));
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
