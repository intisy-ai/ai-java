package io.github.intisy.ai.exampleserver.admin;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.store.AccountStore;

import java.util.ArrayList;
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
