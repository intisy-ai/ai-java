package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.store.AccountStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UI-safe account administration over an AccountStore. Provides status views
 * (hiding secrets like refresh/access tokens and transient reasons) and
 * management operations (enable/disable/remove).
 */
public class AccountAdmin {
    private final AccountStore store;
    private final Clock clock;

    public AccountAdmin(AccountStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * List all accounts for a provider as UI-safe views, including computed status.
     */
    public List<AccountView> list(String providerId) {
        List<Account> accounts = store.list(providerId);
        List<AccountView> views = new ArrayList<>();
        if (accounts != null) {
            long now = clock.now();
            for (Account account : accounts) {
                views.add(toView(account, now));
            }
        }
        return views;
    }

    /**
     * Enable or disable an account by id.
     */
    public void setEnabled(String providerId, String accountId, boolean enabled) {
        store.update(providerId, pool -> {
            for (Account account : pool.accounts) {
                if (accountId.equals(account.id)) {
                    account.enabled = enabled;
                    break;
                }
            }
        });
    }

    /**
     * Remove an account by id.
     */
    public void remove(String providerId, String accountId) {
        store.remove(providerId, accountId);
    }

    /**
     * Seed an account from a pasted OAuth refresh token (the JVM login MVP). Writes the
     * DECISION-FLAG-C account shape that {@code AccountManager} and installed providers read:
     * the RAW refresh token in {@code account.refresh} (never packed as {@code "token|projectId"})
     * and project ids in {@code account.meta}. Note this only becomes visible to an installed
     * provider when the server runs with a {@code FileStore} backing the same {@code accounts.json}
     * (i.e. {@code -Dexampleserver.store=file -Dexampleserver.configDir=<dir>}); under the default
     * in-memory store the seeded account is admin-visible only.
     *
     * @throws IllegalArgumentException if {@code refresh} is blank, or both {@code email} and
     *                                   {@code id} are blank
     */
    public AccountView addToken(String providerId, String id, String email, String refresh,
                                 String projectId, String managedProjectId) {
        if (isBlank(refresh)) {
            throw new IllegalArgumentException("refresh is required");
        }
        if (isBlank(email) && isBlank(id)) {
            throw new IllegalArgumentException("at least one of email/id is required");
        }

        String resolvedId = !isBlank(id) ? id : email;
        String resolvedEmail = !isBlank(email) ? email : id;

        Account account = new Account();
        account.id = resolvedId;
        account.email = resolvedEmail;
        account.refresh = refresh;
        account.enabled = true;
        account.addedAt = clock.now();
        account.meta = buildMeta(projectId, managedProjectId);

        store.add(providerId, account);
        return toView(account, clock.now());
    }

    private static Map<String, Object> buildMeta(String projectId, String managedProjectId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (!isBlank(projectId)) meta.put("projectId", projectId);
        if (!isBlank(managedProjectId)) meta.put("managedProjectId", managedProjectId);
        return meta.isEmpty() ? null : meta;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Convert an Account to an AccountView, computing status based on the current time.
     */
    private AccountView toView(Account account, long now) {
        AccountView view = new AccountView();
        view.id = account.id;
        view.email = account.email;
        view.enabled = account.enabled != null ? account.enabled : true;
        view.status = deriveStatus(account, now, view.enabled);
        return view;
    }

    /**
     * Derive status: disabled, cooling, rate-limited, or ready.
     */
    private String deriveStatus(Account account, long now, boolean enabled) {
        if (!enabled) {
            return "disabled";
        }
        if (account.coolingDownUntil != null && account.coolingDownUntil > now) {
            return "cooling";
        }
        if (account.rateLimitResetTimes != null) {
            for (Long resetTime : account.rateLimitResetTimes.values()) {
                if (resetTime != null && resetTime > now) {
                    return "rate-limited";
                }
            }
        }
        return "ready";
    }

    /**
     * UI-safe view of an account, exposing only id, email, enabled status, and
     * computed status. Never exposes secrets (refresh, access tokens) or transient
     * reasons (cooldownReason, disabledReason).
     */
    public static final class AccountView {
        public String id;
        public String email;
        public String status;
        public boolean enabled;
    }
}
