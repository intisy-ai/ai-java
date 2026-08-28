package io.github.intisy.ai.jvm;

import io.github.intisy.ai.jvm.backend.Backend;
import io.github.intisy.ai.jvm.backend.Backends;
import io.github.intisy.ai.jvm.backend.env.Env;
import io.github.intisy.ai.jvm.backend.notify.JsonlNotifier;
import io.github.intisy.ai.seam.jvm.FileStore;
import io.github.intisy.ai.jvm.provider.ProviderRegistry;
import io.github.intisy.ai.shared.logic.Notifier;
import io.github.intisy.ai.shared.logic.Router;
import io.github.intisy.ai.shared.logic.RouterOptions;
import io.github.intisy.ai.shared.manager.AccountManager;
import io.github.intisy.ai.shared.manager.ManagerOptions;
import io.github.intisy.ai.shared.oauth.OAuthConfig;
import io.github.intisy.ai.shared.routing.HandlerResolver;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.api.seam.Clock;
import io.github.intisy.ai.api.seam.HttpClient;
import io.github.intisy.ai.api.seam.JsonCodec;
import io.github.intisy.ai.api.seam.Logger;
import io.github.intisy.ai.api.seam.Random;
import io.github.intisy.ai.api.seam.Store;
import io.github.intisy.ai.api.seam.HttpRequest;
import io.github.intisy.ai.api.seam.HttpResponse;
import io.github.intisy.ai.shared.store.AccountStore;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Configurable JVM assembly: a server picks its {@link Store}/{@link HttpClient}/
 * {@link JsonCodec}/etc. implementations through {@link Builder} instead of being forced into
 * any one of them. The single hard requirement is {@link Builder#storage(Store)}; every other
 * dependency has a sane JVM default, but storage never silently defaults to JSON files (or
 * anything else): the caller must choose via {@link Storage#file}, {@link Storage#memory},
 * {@link Storage#jdbc}, or its own {@link Store} implementation.
 *
 * <p>Once built, {@link #router} and {@link #accountManager} hand back fully-wired
 * {@code shared} objects so a server never has to thread the individual SPIs through by hand.
 *
 * <p>{@link AiJava} owns the {@link #providerRegistry()} it builds (and the
 * {@link java.net.URLClassLoader} that registry keeps open for its provider jars; see
 * {@link ProviderRegistry}), so it implements {@link Closeable}: call {@link #close()} once this
 * {@link AiJava} instance is discarded (e.g. before rebuilding a fresh one to pick up swapped
 * provider jars) so that loader's resources are released. Never call {@link #close()} while a
 * {@link WiredRouter} obtained from this instance might still be routing a request to a
 * jar-provided {@link io.github.intisy.ai.auth.contracts.Provider}.
 */
public class AiJava implements Closeable {

    private final Store store;
    private final HttpClient httpClient;
    private final JsonCodec json;
    private final Clock clock;
    private final Logger logger;
    private final Random random;
    private final Env env;
    private final Notifier notifier;
    private final ManagerOptions managerOptions;
    private final ProviderRegistry providerRegistry;

    private AiJava(Builder b, Store resolvedStore, Backend base) {
        this.store = resolvedStore;
        this.httpClient = b.httpClient != null ? b.httpClient : base.httpClient();
        this.json = b.json != null ? b.json : base.jsonCodec();
        this.clock = b.clock != null ? b.clock : base.clock();
        this.logger = b.logger != null ? b.logger : base.logger();
        this.random = b.random != null ? b.random : base.random();
        this.env = b.env != null ? b.env : base.env();
        this.notifier = b.notifier != null ? b.notifier
                : (base.notifier() != null ? base.notifier() : defaultNotifierFor(resolvedStore));
        this.managerOptions = b.managerOptions;
        this.providerRegistry = b.providerRegistry != null
                ? b.providerRegistry
                : (b.providersDir != null ? ProviderRegistry.fromDirectory(b.providersDir) : ProviderRegistry.empty());
    }

    /** {@return a fresh builder, whose only hard requirement is a storage choice} */
    public static Builder builder() {
        return new Builder();
    }

    // -- plain accessors, in case a caller wants the chosen SPIs directly -----

    /** {@return the storage the caller chose, which never defaults} */
    public Store store() {
        return store;
    }

    /** {@return the HTTP client every wired object shares} */
    public HttpClient httpClient() {
        return httpClient;
    }

    /** {@return the JSON codec every wired object shares} */
    public JsonCodec jsonCodec() {
        return json;
    }

    /** {@return the clock every wired object reads time from} */
    public Clock clock() {
        return clock;
    }

    /** {@return the logger every wired object writes to} */
    public Logger logger() {
        return logger;
    }

    /** {@return the randomness source token generation and backoff jitter draw from} */
    public Random random() {
        return random;
    }

    /** {@return the environment lookup, so a caller never reads process state directly} */
    public Env env() {
        return env;
    }

    /** {@return the notifier, which is the storage-derived default when none was supplied} */
    public Notifier notifier() {
        return notifier;
    }

    /**
     * {@return the registry discovered from {@link Builder#providersDir}, injected directly via
     * {@link Builder#providerRegistry(ProviderRegistry)}, or an empty one}
     */
    public ProviderRegistry providerRegistry() {
        return providerRegistry;
    }

    /**
     * Releases the {@link ProviderRegistry}'s provider-jar {@link java.net.URLClassLoader} (a
     * no-op when {@link Builder#providersDir} was never set, since {@link ProviderRegistry#close}
     * itself is a no-op for {@link ProviderRegistry#empty()}). See the class javadoc for when
     * it's safe to call this.
     *
     * @throws IOException when the registry's class loader cannot be released
     */
    @Override
    public void close() throws IOException {
        providerRegistry.close();
    }

    // -- wired construction -----------------------------------------------

    /**
     * A {@link Router} pre-wired with this {@link AiJava}'s store/json/clock/logger/notifier,
     * whose handlers come from this {@link AiJava}'s {@link #providerRegistry()} (the
     * {@link ProviderRegistry} discovered from {@link Builder#providersDir(Path)}, injected
     * directly via {@link Builder#providerRegistry(ProviderRegistry)}, or an empty one), so
     * callers don't need to hand-assemble a resolver themselves. Use the three-argument
     * {@link #router(RoutingProfile, HandlerResolver, Supplier)} overload instead when a caller
     * needs to supply its own {@link HandlerResolver} (e.g. a test double).
     *
     * @param profile the routing profile the wired router routes against
     * @return a router wired to this instance's own dependencies
     */
    public WiredRouter router(RoutingProfile profile) {
        return router(profile, providerRegistry.asHandlerResolver(), providerRegistry::listProviderIds);
    }

    /**
     * A {@link Router} pre-wired with this {@link AiJava}'s store/json/clock/logger/notifier.
     * {@code configDir} is derived from the store when it's a {@link FileStore} (so handlers
     * that read {@link io.github.intisy.ai.ir.spi.HandlerCtx#configDir} still see the
     * right directory); it's empty for non-file backends, which carry no filesystem notion.
     *
     * @param profile the routing profile the wired router routes against
     * @param resolveHandler how a request's chosen provider id becomes a handler
     * @param listProviders every provider id routing may choose from; null reads as none
     * @return a router wired to this instance's own dependencies
     */
    public WiredRouter router(RoutingProfile profile, HandlerResolver resolveHandler,
                               Supplier<List<String>> listProviders) {
        RouterOptions opts = new RouterOptions();
        opts.profile = profile;
        opts.resolveHandler = resolveHandler;
        opts.store = store;
        opts.json = json;
        opts.clock = clock;
        opts.log = logger;
        opts.notify = notifier;
        opts.listProviders = listProviders != null ? listProviders : Collections::emptyList;
        opts.configDir = store instanceof FileStore ? ((FileStore) store).configFolder().toString() : "";
        return new WiredRouter(opts);
    }

    /**
     * An {@link AccountManager} pre-wired with this {@link AiJava}'s store/httpClient/clock/
     * random/json. {@code oauth} is per-provider (each provider has its own token endpoint), so
     * it's layered onto a copy of the builder's {@link ManagerOptions} rather than mutating it:
     * calling this repeatedly for different providers never lets one provider's oauth config
     * leak into another's.
     *
     * @param providerId the provider whose accounts the manager owns
     * @param oauth that provider's own OAuth configuration
     * @return a manager wired to this instance's own dependencies
     */
    public AccountManager accountManager(String providerId, OAuthConfig oauth) {
        AccountStore accountStore = new AccountStore(store, json);
        return new AccountManager(providerId, accountStore, httpClient, clock, random, json,
                managerOptionsWithOAuth(oauth));
    }

    private ManagerOptions managerOptionsWithOAuth(OAuthConfig oauth) {
        ManagerOptions base = managerOptions != null ? managerOptions : new ManagerOptions();
        ManagerOptions copy = new ManagerOptions();
        copy.strategy = base.strategy;
        copy.oauth = oauth;
        copy.backoffBaseMs = base.backoffBaseMs;
        copy.backoffMaxMs = base.backoffMaxMs;
        copy.extraAvailable = base.extraAvailable;
        return copy;
    }

    /**
     * No storage-specific notification path exists for non-file backends ({@code memory}/
     * {@code jdbc} carry no directory), so the sane default there is a no-op {@link Notifier}
     * rather than guessing a location to write to. Only a {@link FileStore} gets the real
     * {@link JsonlNotifier}, written next to the files it's already managing.
     */
    private static Notifier defaultNotifierFor(Store store) {
        if (store instanceof FileStore) {
            return new JsonlNotifier(((FileStore) store).configFolder());
        }
        return (message, level) -> {
        };
    }

    /**
     * Thin wrapper over {@link Router#route}: {@code shared}'s {@link Router} is a stateless
     * utility class (a static {@code route(request, options)} method, no instance state), so
     * there is nothing to "construct" there; this holds the {@link RouterOptions} this
     * {@link AiJava} wired up and exposes them as an instance-shaped {@code route(request)} call.
     */
    public static final class WiredRouter {
        private final RouterOptions options;

        private WiredRouter(RouterOptions options) {
            this.options = options;
        }

        /**
         * Routes one request through the options this instance wired up.
         *
         * @param request the inbound request
         * @return the response the routed handler produced
         */
        public HttpResponse route(HttpRequest request) {
            return Router.route(request, options);
        }

        /**
         * {@return the raw wired options, an escape hatch for a caller that needs to pass them on,
         * for example to {@link Router#routeJson}}
         */
        public RouterOptions options() {
            return options;
        }
    }

    /**
     * Builder for {@link AiJava}. A store must come from either {@link #storage(Store)} or a
     * {@link #backend(Backend)} that carries one; everything else has a JVM-sane default so
     * callers only override what they care about.
     */
    public static final class Builder {
        private Store store;
        private Backend backend;
        private HttpClient httpClient; // null = unset -> resolved from backend/defaults
        private JsonCodec json;
        private Clock clock;
        private Logger logger;
        private Random random;
        private Env env;
        private Notifier notifier; // resolved lazily in build(): depends on the chosen store
        private ManagerOptions managerOptions;
        private Path providersDir; // unset -> ProviderRegistry.empty(), never forced/guessed
        private ProviderRegistry providerRegistry;

        private Builder() {
        }

        /**
         * Hand ai-java one object that IS the entire platform; per-SPI setters still override it.
         *
         * @param backend the object carrying every SPI at once
         * @return this builder
         */
        public Builder backend(Backend backend) {
            this.backend = backend;
            return this;
        }

        /**
         * REQUIRED unless a {@link #backend(Backend)} carrying a store is supplied.
         *
         * @param store where accounts and settings live
         * @return this builder
         */
        public Builder storage(Store store) {
            this.store = store;
            return this;
        }

        /**
         * Overrides the HTTP client a {@link #backend(Backend)} would otherwise supply.
         *
         * @param httpClient the client every wired object shares
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Overrides the JSON codec a {@link #backend(Backend)} would otherwise supply.
         *
         * @param json the codec every wired object shares
         * @return this builder
         */
        public Builder jsonCodec(JsonCodec json) {
            this.json = json;
            return this;
        }

        /**
         * Overrides the clock a {@link #backend(Backend)} would otherwise supply.
         *
         * @param clock the clock every wired object reads time from
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Overrides the logger a {@link #backend(Backend)} would otherwise supply.
         *
         * @param logger the logger every wired object writes to
         * @return this builder
         */
        public Builder logger(Logger logger) {
            this.logger = logger;
            return this;
        }

        /**
         * Overrides the randomness source a {@link #backend(Backend)} would otherwise supply.
         *
         * @param random the source token generation and backoff jitter draw from
         * @return this builder
         */
        public Builder random(Random random) {
            this.random = random;
            return this;
        }

        /**
         * Overrides the environment lookup a {@link #backend(Backend)} would otherwise supply.
         *
         * @param env the lookup, so nothing reads process state directly
         * @return this builder
         */
        public Builder env(Env env) {
            this.env = env;
            return this;
        }

        /**
         * Overrides the notifier, which otherwise follows the chosen storage.
         *
         * @param notifier the notifier to use instead of the storage-derived default
         * @return this builder
         */
        public Builder notifier(Notifier notifier) {
            this.notifier = notifier;
            return this;
        }

        /**
         * Sets the account-manager options every {@link AiJava#accountManager} call starts from.
         *
         * @param managerOptions the options a per-provider OAuth config is layered onto
         * @return this builder
         */
        public Builder managerOptions(ManagerOptions managerOptions) {
            this.managerOptions = managerOptions;
            return this;
        }

        /**
         * Directory {@link ProviderRegistry#fromDirectory} scans for provider {@code *.jar}s at
         * {@link #build()} time, backing the zero-arg {@link AiJava#router(RoutingProfile)}.
         * Unset (default) yields an empty registry; no directory is guessed or forced.
         *
         * @param providersDir the directory to scan for provider jars
         * @return this builder
         */
        public Builder providersDir(Path providersDir) {
            this.providersDir = providersDir;
            return this;
        }

        /**
         * Inject a pre-built {@link ProviderRegistry} instead of discovering one from a directory. Use
         * this for in-process providers or a custom discovery strategy. Mutually exclusive with
         * {@link #providersDir(Path)}.
         *
         * @param providerRegistry the registry to use instead of discovering one
         * @return this builder
         */
        public Builder providerRegistry(ProviderRegistry providerRegistry) {
            this.providerRegistry = providerRegistry;
            return this;
        }

        /**
         * Assembles the instance, resolving every unset dependency to its JVM default.
         *
         * @return the assembled instance, which owns the provider registry it built
         * @throws IllegalStateException when no storage was chosen, or when both a providers
         *                               directory and a pre-built registry were
         */
        public AiJava build() {
            Store resolvedStore = store != null ? store : (backend != null ? backend.store() : null);
            if (resolvedStore == null) {
                throw new IllegalStateException(
                        "storage backend is required; use Storage.file/memory/jdbc or your own Store");
            }
            if (providerRegistry != null && providersDir != null) {
                throw new IllegalStateException(
                        "set either providerRegistry(...) or providersDir(...), not both");
            }
            Backend base = backend != null ? backend : Backends.defaults(resolvedStore);
            return new AiJava(this, resolvedStore, base);
        }
    }
}
