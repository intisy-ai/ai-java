package io.github.intisy.ai.jvm.backend;

import io.github.intisy.ai.jvm.backend.env.Env;
import io.github.intisy.ai.shared.logic.Notifier;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Random;
import io.github.intisy.ai.shared.spi.Store;

/** Immutable {@link Backend} produced by {@link Backend.Builder#build()}. */
final class ImmutableBackend implements Backend {
    private final Store store;
    private final HttpClient httpClient;
    private final JsonCodec jsonCodec;
    private final Clock clock;
    private final Random random;
    private final Logger logger;
    private final Notifier notifier; // nullable by contract
    private final Env env;

    ImmutableBackend(Store store, HttpClient httpClient, JsonCodec jsonCodec, Clock clock,
                     Random random, Logger logger, Notifier notifier, Env env) {
        this.store = store;
        this.httpClient = httpClient;
        this.jsonCodec = jsonCodec;
        this.clock = clock;
        this.random = random;
        this.logger = logger;
        this.notifier = notifier;
        this.env = env;
    }

    @Override public Store store() { return store; }
    @Override public HttpClient httpClient() { return httpClient; }
    @Override public JsonCodec jsonCodec() { return jsonCodec; }
    @Override public Clock clock() { return clock; }
    @Override public Random random() { return random; }
    @Override public Logger logger() { return logger; }
    @Override public Notifier notifier() { return notifier; }
    @Override public Env env() { return env; }
}
