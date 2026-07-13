package io.github.intisy.ai.shared.store;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.model.AccountPool;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Generic per-provider account store, keyed by provider id. Java analog of
 * {@code libs/core-auth/src/accounts.ts}, rewired onto the {@link Store} + {@link JsonCodec}
 * SPIs (no direct nio/gson) so this class stays transpilable: JSON is built/read as plain
 * {@code Map}/{@code List} trees via {@link JsonUtil}, and atomicity of read-modify-write is
 * the {@code Store} implementation's concern, not this class's.
 *
 * <p>On-disk shape (must match the JS store exactly): {@code {"version":1,"providers":
 * {"<id>":{"accounts":[...],"activeIndex":0,"activeIndexByLane":{}}}}}, under the key
 * {@code "accounts.json"}.
 */
public class AccountStore {
    private static final String KEY = "accounts.json";

    private final Store store;
    private final JsonCodec json;

    public AccountStore(Store store, JsonCodec json) {
        this.store = store;
        this.json = json;
    }

    private Map<String, Object> parseOrDefault(String raw) {
        if (raw != null) {
            try {
                Map<String, Object> doc = JsonUtil.asMap(json.parse(raw));
                if (doc != null) return doc;
            } catch (Exception ignored) {
                // swallow-all, mirrors the JS readStore's try/catch degrading to an empty store
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1);
        doc.put("providers", new LinkedHashMap<String, Object>());
        return doc;
    }

    private static Map<String, Object> providersOf(Map<String, Object> doc) {
        Map<String, Object> providers = JsonUtil.asMap(doc.get("providers"));
        if (providers == null) {
            providers = new LinkedHashMap<>();
            doc.put("providers", providers);
        }
        return providers;
    }

    private static AccountPool poolFromEntry(Object entry) {
        Map<String, Object> m = JsonUtil.asMap(entry);
        if (m == null) return new AccountPool();

        List<Object> rawAccounts = JsonUtil.asList(m.get("accounts"));
        List<Account> accounts = new ArrayList<>();
        if (rawAccounts != null) {
            for (Object o : rawAccounts) {
                Map<String, Object> am = JsonUtil.asMap(o);
                if (am != null) accounts.add(accountFromMap(am));
            }
        }

        Integer activeIndex = JsonUtil.asInt(m.get("activeIndex"));

        Map<String, Integer> activeIndexByLane = new LinkedHashMap<>();
        Map<String, Object> laneRaw = JsonUtil.asMap(m.get("activeIndexByLane"));
        if (laneRaw != null) {
            for (Map.Entry<String, Object> e : laneRaw.entrySet()) {
                Integer v = JsonUtil.asInt(e.getValue());
                if (v != null) activeIndexByLane.put(e.getKey(), v);
            }
        }

        return new AccountPool(accounts, activeIndex != null ? activeIndex : 0, activeIndexByLane);
    }

    private static Map<String, Object> poolToMap(AccountPool pool) {
        Map<String, Object> m = new LinkedHashMap<>();

        List<Object> accounts = new ArrayList<>();
        if (pool.accounts != null) {
            for (Account a : pool.accounts) accounts.add(accountToMap(a));
        }
        m.put("accounts", accounts);
        m.put("activeIndex", pool.activeIndex);

        Map<String, Object> lane = new LinkedHashMap<>();
        if (pool.activeIndexByLane != null) {
            for (Map.Entry<String, Integer> e : pool.activeIndexByLane.entrySet()) {
                lane.put(e.getKey(), e.getValue());
            }
        }
        m.put("activeIndexByLane", lane);
        return m;
    }

    private static Account accountFromMap(Map<String, Object> m) {
        Account a = new Account();
        a.id = JsonUtil.asString(m.get("id"));
        a.email = JsonUtil.asString(m.get("email"));
        a.refresh = JsonUtil.asString(m.get("refresh"));
        a.access = JsonUtil.asString(m.get("access"));
        a.expires = JsonUtil.asLong(m.get("expires"));
        a.addedAt = JsonUtil.asLong(m.get("addedAt"));
        a.lastUsed = JsonUtil.asLong(m.get("lastUsed"));
        a.enabled = JsonUtil.asBoolean(m.get("enabled"));

        Map<String, Object> rlrt = JsonUtil.asMap(m.get("rateLimitResetTimes"));
        if (rlrt != null) {
            Map<String, Long> conv = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : rlrt.entrySet()) {
                Long v = JsonUtil.asLong(e.getValue());
                if (v != null) conv.put(e.getKey(), v);
            }
            a.rateLimitResetTimes = conv;
        }

        a.coolingDownUntil = JsonUtil.asLong(m.get("coolingDownUntil"));
        a.cooldownReason = JsonUtil.asString(m.get("cooldownReason"));
        a.disabledReason = JsonUtil.asString(m.get("disabledReason"));
        a.meta = JsonUtil.asMap(m.get("meta"));
        return a;
    }

    /** Builds the wire map with only present (non-null) fields, mirroring JS omitting {@code undefined}. */
    private static Map<String, Object> accountToMap(Account a) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a.id != null) m.put("id", a.id);
        if (a.email != null) m.put("email", a.email);
        if (a.refresh != null) m.put("refresh", a.refresh);
        if (a.access != null) m.put("access", a.access);
        if (a.expires != null) m.put("expires", a.expires);
        if (a.addedAt != null) m.put("addedAt", a.addedAt);
        if (a.lastUsed != null) m.put("lastUsed", a.lastUsed);
        if (a.enabled != null) m.put("enabled", a.enabled);
        if (a.rateLimitResetTimes != null) {
            Map<String, Object> rl = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : a.rateLimitResetTimes.entrySet()) rl.put(e.getKey(), e.getValue());
            m.put("rateLimitResetTimes", rl);
        }
        if (a.coolingDownUntil != null) m.put("coolingDownUntil", a.coolingDownUntil);
        if (a.cooldownReason != null) m.put("cooldownReason", a.cooldownReason);
        if (a.disabledReason != null) m.put("disabledReason", a.disabledReason);
        if (a.meta != null) m.put("meta", a.meta);
        return m;
    }

    public AccountPool load(String provider) {
        Map<String, Object> providers = providersOf(parseOrDefault(store.get(KEY)));
        return poolFromEntry(providers.get(provider));
    }

    public List<Account> list(String provider) {
        return load(provider).accounts;
    }

    public void save(String provider, AccountPool pool) {
        store.update(KEY, current -> {
            Map<String, Object> doc = parseOrDefault(current);
            doc.put("version", 1);
            providersOf(doc).put(provider, poolToMap(pool));
            return json.stringify(doc);
        });
    }

    /** Atomic read-modify-write: mutator mutates the freshly-read pool in place. */
    public AccountPool update(String provider, Consumer<AccountPool> mutator) {
        AccountPool[] result = new AccountPool[1];
        store.update(KEY, current -> {
            Map<String, Object> doc = parseOrDefault(current);
            doc.put("version", 1);
            Map<String, Object> providers = providersOf(doc);
            AccountPool pool = poolFromEntry(providers.get(provider));
            mutator.accept(pool);
            providers.put(provider, poolToMap(pool));
            result[0] = pool;
            return json.stringify(doc);
        });
        return result[0];
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
     * fields overwrite the existing record.
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
