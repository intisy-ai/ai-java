package io.github.intisy.ai.core.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Generic per-provider account store, keyed by provider id. Java analog of
 * {@code libs/core-auth/src/accounts.ts}: writes use a cross-process {@code .lock} file +
 * atomic temp-write-then-rename so a Java process and a JS client/CLI sharing the same
 * {@code configFolder} don't clobber each other.
 *
 * <p>On-disk shape (must match the JS store exactly): {@code {"version":1,"providers":
 * {"<id>":{"accounts":[...],"activeIndex":0,"activeIndexByLane":{}}}}}.
 */
public class AccountStore {
    private static final String DEFAULT_FILE = "accounts.json";
    private static final String LEGACY_FILE = "core-auth-accounts.json"; // pre-rename; read-only fallback

    private static final long LOCK_STALE_MS = 15_000;
    private static final long LOCK_WAIT_MS = 5_000;
    private static final long LOCK_POLL_MS = 25;

    // LONG_OR_DOUBLE matches JS `number` semantics: whole numbers deserialize to Long (so they
    // reserialize without a trailing ".0"), fractional numbers to Double. Without this, Gson's
    // default ToNumberPolicy.DOUBLE deserializes every JSON number in the opaque Account.meta
    // map to java.lang.Double, so a whole number like {"count":5} round-trips as {"count":5.0}
    // and corrupts byte-compatibility with the JS core-auth store on every AccountStore write.
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
            .create();

    private final Path configFolder;

    public AccountStore(Path configFolder) {
        this.configFolder = configFolder;
    }

    /** The top-level store document: {@code {version, providers}} (field order matters for parity). */
    private static class Store {
        int version = 1;
        Map<String, AccountPool> providers = new LinkedHashMap<>();
    }

    private Path storeFile() {
        return configFolder.resolve(DEFAULT_FILE);
    }

    private void ensureDir() {
        try {
            if (!Files.exists(configFolder)) Files.createDirectories(configFolder);
        } catch (IOException ignored) {
            // best-effort, mirrors JS's unguarded mkdirSync being the only failure point
        }
    }

    // best-effort exclusive lock; degrades to running unlocked rather than deadlocking if it can't be acquired.
    private <T> T withLock(Supplier<T> fn) {
        ensureDir();
        Path lockPath = storeFile().resolveSibling(storeFile().getFileName().toString() + ".lock");
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MS;
        boolean acquired = false;
        while (!acquired) {
            try {
                Files.createFile(lockPath);
                acquired = true;
            } catch (FileAlreadyExistsException e) {
                try {
                    FileTime mtime = Files.getLastModifiedTime(lockPath);
                    if (System.currentTimeMillis() - mtime.toMillis() > LOCK_STALE_MS) {
                        Files.deleteIfExists(lockPath);
                        continue;
                    }
                } catch (IOException ignored) {
                    // stale-check is best-effort; fall through to the wait/deadline check below
                }
                if (System.currentTimeMillis() > deadline) break;
                try {
                    Thread.sleep(LOCK_POLL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (IOException e) {
                break; // can't create the lock file at all; degrade to unlocked
            }
        }
        try {
            return fn.get();
        } finally {
            if (acquired) {
                try {
                    Files.deleteIfExists(lockPath);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Store readStore() {
        try {
            Path file = storeFile();
            // migrate: if the renamed store isn't there yet, read the legacy file (the next
            // write goes to the new name).
            if (!Files.exists(file)) {
                Path legacy = configFolder.resolve(LEGACY_FILE);
                if (Files.exists(legacy)) file = legacy;
            }
            if (Files.exists(file)) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Store store = GSON.fromJson(content, Store.class);
                if (store != null) return store;
            }
        } catch (Exception ignored) {
            // swallow-all, mirrors the JS readStore's try/catch degrading to an empty store
        }
        return new Store();
    }

    private void writeStore(Store store) {
        ensureDir();
        Path file = storeFile();
        Path tmp = file.resolveSibling(file.getFileName().toString() + "." + randomHex(6) + ".tmp");
        try {
            Files.write(tmp, GSON.toJson(store).getBytes(StandardCharsets.UTF_8));
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // POSIX permissions aren't supported on this filesystem (e.g. Windows); not fatal
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("failed to write account store " + file, e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static AccountPool poolFrom(Store store, String provider) {
        AccountPool p = store.providers != null ? store.providers.get(provider) : null;
        if (p == null || p.accounts == null) return new AccountPool();
        return new AccountPool(p.accounts, p.activeIndex, p.activeIndexByLane);
    }

    private static AccountPool normalize(AccountPool pool) {
        return new AccountPool(pool.accounts, pool.activeIndex, pool.activeIndexByLane);
    }

    public AccountPool load(String provider) {
        return poolFrom(readStore(), provider);
    }

    public void save(String provider, AccountPool pool) {
        withLock(() -> {
            Store store = readStore();
            store.version = 1;
            if (store.providers == null) store.providers = new LinkedHashMap<>();
            store.providers.put(provider, normalize(pool));
            writeStore(store);
            return null;
        });
    }

    /** Atomic read-modify-write: mutator mutates the freshly-read pool in place. */
    public AccountPool update(String provider, Consumer<AccountPool> mutator) {
        return withLock(() -> {
            Store store = readStore();
            store.version = 1;
            if (store.providers == null) store.providers = new LinkedHashMap<>();
            AccountPool current = poolFrom(store, provider);
            mutator.accept(current);
            store.providers.put(provider, normalize(current));
            writeStore(store);
            return current;
        });
    }

    public java.util.List<Account> list(String provider) {
        return load(provider).accounts;
    }

    /** Upsert by {@code id}, else by {@code refresh}; merges non-null incoming fields onto the existing record. */
    public void add(String provider, Account account) {
        update(provider, pool -> {
            int idx = -1;
            for (int i = 0; i < pool.accounts.size(); i++) {
                Account a = pool.accounts.get(i);
                boolean idMatch = account.id != null && account.id.equals(a.id);
                boolean refreshMatch = account.refresh != null && account.refresh.equals(a.refresh);
                if (idMatch || refreshMatch) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) pool.accounts.set(idx, mergeAccount(pool.accounts.get(idx), account));
            else pool.accounts.add(account);
        });
    }

    public void remove(String provider, String id) {
        update(provider, pool -> pool.accounts.removeIf(a -> Objects.equals(a.id, id)));
    }

    /**
     * Java analog of the JS {@code {...existing, ...incoming}} object-spread merge. JS spread
     * overwrites only keys present on {@code incoming} (absent keys are skipped entirely); Java
     * fields always exist, so "absent" is approximated as "null" — only incoming's non-null
     * fields overwrite the existing record. This matches every real call site (partial updates
     * that never intend to explicitly null out a field).
     */
    private static Account mergeAccount(Account existing, Account incoming) {
        Account merged = new Account();
        merged.id = incoming.id != null ? incoming.id : existing.id;
        merged.email = incoming.email != null ? incoming.email : existing.email;
        merged.refresh = incoming.refresh != null ? incoming.refresh : existing.refresh;
        merged.access = incoming.access != null ? incoming.access : existing.access;
        merged.expires = incoming.expires != null ? incoming.expires : existing.expires;
        merged.addedAt = incoming.addedAt != null ? incoming.addedAt : existing.addedAt;
        merged.lastUsed = incoming.lastUsed != null ? incoming.lastUsed : existing.lastUsed;
        merged.enabled = incoming.enabled != null ? incoming.enabled : existing.enabled;
        merged.rateLimitResetTimes = incoming.rateLimitResetTimes != null ? incoming.rateLimitResetTimes : existing.rateLimitResetTimes;
        merged.coolingDownUntil = incoming.coolingDownUntil != null ? incoming.coolingDownUntil : existing.coolingDownUntil;
        merged.cooldownReason = incoming.cooldownReason != null ? incoming.cooldownReason : existing.cooldownReason;
        merged.disabledReason = incoming.disabledReason != null ? incoming.disabledReason : existing.disabledReason;
        merged.meta = incoming.meta != null ? incoming.meta : existing.meta;
        return merged;
    }
}
