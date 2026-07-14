package io.github.intisy.ai.examples;

import io.github.intisy.ai.examples.support.AdjustableClock;
import io.github.intisy.ai.examples.support.CapturingLogger;
import io.github.intisy.ai.examples.support.CollectingNotifier;
import io.github.intisy.ai.examples.support.DemoProfiles;
import io.github.intisy.ai.examples.support.DemoSeeds;
import io.github.intisy.ai.examples.support.FixedRandom;
import io.github.intisy.ai.examples.support.InProcessProviders;
import io.github.intisy.ai.examples.support.MapEnv;
import io.github.intisy.ai.examples.support.RecordingJsonCodec;
import io.github.intisy.ai.examples.support.Requests;
import io.github.intisy.ai.examples.support.Section;
import io.github.intisy.ai.jvm.AiJava;
import io.github.intisy.ai.jvm.GsonJsonCodec;
import io.github.intisy.ai.jvm.Storage;
import io.github.intisy.ai.shared.manager.AccountManager;
import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.RoutingProfile;
import io.github.intisy.ai.shared.spi.Store;
import io.github.intisy.ai.shared.store.AccountStore;

import java.util.Collections;
import java.util.List;

/**
 * Shows every platform concern being injected instead of assumed: a custom {@code Logger}, a fixed
 * {@code Clock}, a seeded {@code Random}, a custom {@code Notifier}, and a custom {@code Env}. Two
 * behaviors are made visible BECAUSE of the injected SPIs: the custom logger/notifier capture the
 * router's fallback decision, and the fixed clock + seeded random make the account manager's backoff
 * cooldown a single, predictable timestamp.
 */
public final class CustomSpiDemo {

    private static final String CONFIG_FILE = "examples-custom-spi.json";

    /** Fixed clock origin and random draw — chosen so the deterministic backoff below is reproducible. */
    public static final long CLOCK_START_MS = 1_700_000_000_000L;
    public static final double RANDOM_VALUE = 0.5d;

    private CustomSpiDemo() {
    }

    /** Everything the injected SPIs let us observe, so a test can assert on it without parsing stdout. */
    public static final class Result {
        public final List<String> logLines;
        public final List<CollectingNotifier.Notice> notices;
        public final String envValue;
        public final long backoffResumeAt;
        public final int jsonParseCount;

        public Result(List<String> logLines, List<CollectingNotifier.Notice> notices, String envValue,
                      long backoffResumeAt, int jsonParseCount) {
            this.logLines = logLines;
            this.notices = notices;
            this.envValue = envValue;
            this.backoffResumeAt = backoffResumeAt;
            this.jsonParseCount = jsonParseCount;
        }
    }

    public static void run() {
        Result result = execute();

        Section.header("CustomSpiDemo — every SPI injected (logger/clock/random/notifier/env)");
        Section.detail("custom Env returned APP_REGION=" + result.envValue);
        Section.detail("custom Notifier collected " + result.notices.size() + " notice(s) during routing:");
        for (CollectingNotifier.Notice notice : result.notices) {
            Section.detail("  [" + notice.level + "] " + notice.message);
        }
        Section.detail("custom Logger captured " + result.logLines.size() + " router log line(s):");
        for (String line : result.logLines) {
            Section.detail("  " + line);
        }
        Section.detail("custom JsonCodec (delegating to Gson) handled " + result.jsonParseCount + " parse call(s)");
        Section.detail("deterministic backoff (fixed clock " + CLOCK_START_MS + " + seeded random "
                + RANDOM_VALUE + ") -> reportError set coolingDownUntil=" + result.backoffResumeAt);
    }

    /** Builds an AiJava with all SPIs swapped, exercises routing + backoff, and returns what they captured. */
    public static Result execute() {
        Store store = Storage.memory();
        CapturingLogger logger = new CapturingLogger("[router] ");
        CollectingNotifier notifier = new CollectingNotifier();
        AdjustableClock clock = new AdjustableClock(CLOCK_START_MS);
        FixedRandom random = new FixedRandom(RANDOM_VALUE);
        RecordingJsonCodec json = new RecordingJsonCodec(new GsonJsonCodec());

        AiJava app = AiJava.builder()
                .storage(store)
                .jsonCodec(json)
                .logger(logger)
                .clock(clock)
                .random(random)
                .notifier(notifier)
                .env(new MapEnv(Collections.singletonMap("APP_REGION", "eu-central")))
                .build();

        DemoSeeds.seedInProcessFallback(store, app.jsonCodec(), CONFIG_FILE);

        // Routing through rl (429) -> ok forces a fallback, which the injected logger/notifier record.
        RoutingProfile profile = DemoProfiles.multiTier(CONFIG_FILE, "ok");
        AiJava.WiredRouter router = app.router(profile, InProcessProviders.resolver(), InProcessProviders::ids);
        router.route(Requests.messages("claude-opus-4-1"));

        // Deterministic backoff: with a fixed clock and a seeded random, reportError computes exactly
        // one resume time (clock.now() + jittered backoff), reproducible on every run.
        AccountManager manager = app.accountManager("demo-provider", null);
        seedAccount(store, app.jsonCodec());
        manager.reportError("acct-1", 0, "simulated transient error");
        Account account = firstAccount(store, app.jsonCodec());

        return new Result(logger.lines(), notifier.notices(),
                app.env().get("APP_REGION"), account.coolingDownUntil, json.parseCount());
    }

    private static void seedAccount(Store store, io.github.intisy.ai.shared.spi.JsonCodec json) {
        Account account = new Account();
        account.id = "acct-1";
        account.email = "acct-1@example.com";
        account.enabled = Boolean.TRUE;
        new AccountStore(store, json).add("demo-provider", account);
    }

    private static Account firstAccount(Store store, io.github.intisy.ai.shared.spi.JsonCodec json) {
        return new AccountStore(store, json).list("demo-provider").get(0);
    }
}
