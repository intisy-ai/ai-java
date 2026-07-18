package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.exampleserver.discovery.ProviderRegistryHolder;
import io.github.intisy.ai.jvm.backend.store.FileStore;
import io.github.intisy.ai.shared.routing.AccountQuota;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.routing.QuotaBar;
import io.github.intisy.ai.shared.routing.QuotaProvider;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UI-safe quota administration: resolves an installed provider and, if it implements the
 * {@link QuotaProvider} capability, calls its typed {@code quota} method directly, returning the
 * built {@code {accounts:[...]}} map. Mirrors {@link RoutingAdmin}'s shape (encapsulates the
 * {@link Store}; {@code ManagementApi} never sees it directly).
 */
public final class QuotaAdmin {
    private final ProviderRegistryHolder holder;
    private final JsonCodec json;
    private final Logger log;
    private final String configDir;
    private final Store store;

    public QuotaAdmin(Store store, JsonCodec json, ProviderRegistryHolder holder, Logger log) {
        this.holder = holder;
        this.json = json;
        this.log = log;
        this.configDir = store instanceof FileStore ? ((FileStore) store).configFolder().toString() : "";
        this.store = store;
    }

    /**
     * Resolve the provider + call its typed {@code quota()} if it implements {@link QuotaProvider};
     * returns the built {@code {accounts:[...]}} map. Each account is preserved even when it has no
     * bars (an errored account whose quota couldn't be fetched) -- that's the whole point of the
     * per-account SPI shape.
     *
     * @throws IllegalArgumentException if the provider id is unknown
     */
    public Map<String, Object> refresh(String providerId) {
        Provider p = holder.get(providerId);
        if (p == null) {
            throw new IllegalArgumentException("unknown provider: " + providerId);
        }

        List<Map<String, Object>> accounts = new ArrayList<>();
        if (p instanceof QuotaProvider) {
            HandlerCtx ctx = new HandlerCtx(configDir, store, log, null);
            List<AccountQuota> quotas = ((QuotaProvider) p).quota(ctx);
            if (quotas != null) {
                for (AccountQuota aq : quotas) {
                    accounts.add(accountToWire(aq));
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accounts", accounts);
        return result;
    }

    // Preserves the CURRENT wire fields the dashboard + combined() read: id/status/email(optional)/
    // quota[]. An account with no bars still appears with an empty quota[] -- never dropped.
    private static Map<String, Object> accountToWire(AccountQuota aq) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", aq.accountId);
        account.put("status", aq.accountStatus);
        if (aq.accountEmail != null) account.put("email", aq.accountEmail);

        List<Map<String, Object>> bars = new ArrayList<>();
        if (aq.bars != null) {
            for (QuotaBar bar : aq.bars) {
                Map<String, Object> barMap = new LinkedHashMap<>();
                barMap.put("label", bar.label);
                barMap.put("remainingFraction", bar.remainingFraction);
                barMap.put("resetTime", bar.resetTime);
                bars.add(barMap);
            }
        }
        account.put("quota", bars);
        return account;
    }

    /**
     * {@link #refresh} plus a per-label aggregate across the provider's accounts: for each pool
     * {@code label}, {@code remainingFraction} is the MEAN across accounts that report it
     * (error/no-quota accounts are excluded from the mean but still counted in
     * {@code accountCount}). Backward-compatible superset -- the raw {@code accounts} array is
     * untouched.
     */
    public Map<String, Object> combined(String providerId) {
        Map<String, Object> raw = refresh(providerId);
        Object accountsObj = raw.get("accounts");
        List<?> accounts = accountsObj instanceof List ? (List<?>) accountsObj : Collections.emptyList();

        Map<String, double[]> byLabel = new LinkedHashMap<>(); // label -> {fractionSum, accountCount}
        for (Object a : accounts) {
            if (!(a instanceof Map)) continue;
            Object q = ((Map<?, ?>) a).get("quota");
            if (!(q instanceof List)) continue;
            for (Object pool : (List<?>) q) {
                if (!(pool instanceof Map)) continue;
                Map<?, ?> p = (Map<?, ?>) pool;
                Object label = p.get("label");
                Object frac = p.get("remainingFraction");
                if (!(label instanceof String) || !(frac instanceof Number)) continue;
                double[] acc = byLabel.computeIfAbsent((String) label, k -> new double[2]);
                acc[0] += ((Number) frac).doubleValue();
                acc[1] += 1;
            }
        }

        List<Map<String, Object>> pools = new ArrayList<>();
        for (Map.Entry<String, double[]> e : byLabel.entrySet()) {
            Map<String, Object> pool = new LinkedHashMap<>();
            pool.put("label", e.getKey());
            pool.put("remainingFraction", e.getValue()[1] > 0 ? e.getValue()[0] / e.getValue()[1] : 0.0);
            pool.put("accounts", (int) e.getValue()[1]);
            pools.add(pool);
        }

        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put("pools", pools);
        combined.put("accountCount", accounts.size());

        Map<String, Object> result = new LinkedHashMap<>(raw);
        result.put("combined", combined);
        return result;
    }
}
