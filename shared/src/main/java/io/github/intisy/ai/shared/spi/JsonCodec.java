package io.github.intisy.ai.shared.spi;

/**
 * JSON boundary SPI. The parsed shape is a plain {@code Object} tree built from
 * {@code java.util.Map}/{@code java.util.List}/{@code String}/{@code Number}/{@code Boolean}/{@code null},
 * matching what gson and JS {@code JSON.parse} both naturally produce.
 */
public interface JsonCodec {
    Object parse(String json);

    String stringify(Object value);
}
