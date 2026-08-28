package io.github.intisy.ai.jvm.backend;

import io.github.intisy.ai.jvm.backend.env.Env;
import io.github.intisy.ai.jvm.backend.env.SystemEnv;
import io.github.intisy.ai.seam.jvm.SystemClock;
import io.github.intisy.ai.seam.jvm.UrlConnectionHttpClient;
import io.github.intisy.ai.seam.jvm.GsonJsonCodec;
import io.github.intisy.ai.seam.jvm.SimpleLoggerAdapter;
import io.github.intisy.ai.seam.jvm.SecureRandomAdapter;
import io.github.intisy.ai.shared.logic.Notifier;
import io.github.intisy.ai.api.seam.Clock;
import io.github.intisy.ai.api.seam.HttpClient;
import io.github.intisy.ai.api.seam.JsonCodec;
import io.github.intisy.ai.api.seam.Logger;
import io.github.intisy.ai.api.seam.Random;
import io.github.intisy.ai.api.seam.Store;

/**
 * The whole platform as one swappable unit: every SPI a server needs, bundled so a caller can
 * hand ai-java a single object that <i>is</i> the backend rather than threading each dependency
 * through by hand. {@link #store()} is the one required member (no default; storage is never
 * silently chosen); {@link #notifier()} may be {@code null}, meaning "let the host resolve the
 * store-derived default" (see {@code AiJava}). Build one with {@link #builder()} or take the JVM
 * defaults from {@link Backends#defaults(Store)}.
 */
public interface Backend {

    /** {@return where accounts and settings live; the one member with no default} */
    Store store();

    /** {@return the HTTP client every wired object shares} */
    HttpClient httpClient();

    /** {@return the JSON codec every wired object shares} */
    JsonCodec jsonCodec();

    /** {@return the clock every wired object reads time from} */
    Clock clock();

    /** {@return the randomness source token generation and backoff jitter draw from} */
    Random random();

    /** {@return the logger every wired object writes to} */
    Logger logger();

    /** {@return the notifier, or {@code null} to let the host apply its store-derived default} */
    Notifier notifier();

    /** {@return the environment lookup, so nothing reads process state directly} */
    Env env();

    /** {@return a fresh builder, which requires a store and defaults everything else} */
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

        /**
         * REQUIRED: storage is never chosen for the caller.
         *
         * @param store where accounts and settings live
         * @return this builder
         */
        public Builder store(Store store) { this.store = store; return this; }

        /**
         * @param httpClient the client every wired object shares
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) { this.httpClient = httpClient; return this; }

        /**
         * @param jsonCodec the codec every wired object shares
         * @return this builder
         */
        public Builder jsonCodec(JsonCodec jsonCodec) { this.jsonCodec = jsonCodec; return this; }

        /**
         * @param clock the clock every wired object reads time from
         * @return this builder
         */
        public Builder clock(Clock clock) { this.clock = clock; return this; }

        /**
         * @param random the source token generation and backoff jitter draw from
         * @return this builder
         */
        public Builder random(Random random) { this.random = random; return this; }

        /**
         * @param logger the logger every wired object writes to
         * @return this builder
         */
        public Builder logger(Logger logger) { this.logger = logger; return this; }

        /**
         * @param notifier the notifier, or {@code null} to keep the store-derived default
         * @return this builder
         */
        public Builder notifier(Notifier notifier) { this.notifier = notifier; return this; }

        /**
         * @param env the environment lookup
         * @return this builder
         */
        public Builder env(Env env) { this.env = env; return this; }

        /**
         * Composes the backend, defaulting every unset SPI to its JVM implementation.
         *
         * @return the composed backend
         * @throws IllegalStateException when no store was set
         */
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
