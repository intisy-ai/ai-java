package io.github.intisy.ai.jvm.backend;

import io.github.intisy.ai.jvm.backend.env.Env;
import io.github.intisy.ai.jvm.backend.env.SystemEnv;
import io.github.intisy.ai.jvm.backend.clock.SystemClock;
import io.github.intisy.ai.jvm.backend.http.UrlConnectionHttpClient;
import io.github.intisy.ai.jvm.backend.json.GsonJsonCodec;
import io.github.intisy.ai.jvm.backend.log.SimpleLoggerAdapter;
import io.github.intisy.ai.jvm.backend.random.SecureRandomAdapter;
import io.github.intisy.ai.shared.logic.Notifier;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Random;
import io.github.intisy.ai.shared.spi.Store;

/**
 * The whole platform as one swappable unit: every SPI a server needs, bundled so a caller can
 * hand ai-java a single object that <i>is</i> the backend rather than threading each dependency
 * through by hand. {@link #store()} is the one required member (no default — storage is never
 * silently chosen); {@link #notifier()} may be {@code null}, meaning "let the host resolve the
 * store-derived default" (see {@code AiJava}). Build one with {@link #builder()} or take the JVM
 * defaults from {@link Backends#defaults(Store)}.
 */
public interface Backend {

    Store store();

    HttpClient httpClient();

    JsonCodec jsonCodec();

    Clock clock();

    Random random();

    Logger logger();

    /** May be {@code null} — the host then applies its store-derived default notifier. */
    Notifier notifier();

    Env env();

    static Builder builder() {
        return new Builder();
    }

    /**
     * Composes a {@link Backend}, defaulting every unset platform SPI to its JVM implementation.
     * {@link #store(Store)} must be called before {@link #build()}.
     */
    final class Builder {
        private Store store;
        private HttpClient httpClient;
        private JsonCodec jsonCodec;
        private Clock clock;
        private Random random;
        private Logger logger;
        private Notifier notifier;
        private Env env;

        private Builder() {
        }

        public Builder store(Store store) { this.store = store; return this; }
        public Builder httpClient(HttpClient httpClient) { this.httpClient = httpClient; return this; }
        public Builder jsonCodec(JsonCodec jsonCodec) { this.jsonCodec = jsonCodec; return this; }
        public Builder clock(Clock clock) { this.clock = clock; return this; }
        public Builder random(Random random) { this.random = random; return this; }
        public Builder logger(Logger logger) { this.logger = logger; return this; }
        public Builder notifier(Notifier notifier) { this.notifier = notifier; return this; }
        public Builder env(Env env) { this.env = env; return this; }

        public Backend build() {
            if (store == null) {
                throw new IllegalStateException(
                        "storage backend is required; use Storage.file/memory/jdbc or your own Store");
            }
            HttpClient http = httpClient != null ? httpClient : new UrlConnectionHttpClient();
            JsonCodec json = jsonCodec != null ? jsonCodec : new GsonJsonCodec();
            Clock clk = clock != null ? clock : new SystemClock();
            Random rnd = random != null ? random : new SecureRandomAdapter();
            Logger log = logger != null ? logger : new SimpleLoggerAdapter();
            Env environment = env != null ? env : new SystemEnv();
            return new ImmutableBackend(store, http, json, clk, rnd, log, notifier, environment);
        }
    }
}
