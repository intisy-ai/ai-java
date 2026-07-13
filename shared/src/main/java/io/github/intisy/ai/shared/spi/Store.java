package io.github.intisy.ai.shared.spi;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Key/value store of JSON strings (e.g. keys like {@code accounts.json}, {@code models.json},
 * {@code auth.json}). {@code update} must be atomic; that is the implementation's concern.
 */
public interface Store {
    String get(String key);

    void put(String key, String value);

    boolean exists(String key);

    void delete(String key);

    void update(String key, UnaryOperator<String> mutator);

    List<String> listKeys(String prefix);
}
