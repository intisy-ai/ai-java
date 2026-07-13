package io.github.intisy.ai.shared.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java analog of the JS {@code AccountPool} (see {@code libs/core-auth/src/types.ts:45-49}).
 * Field order matches the JS object literal construction order in accounts.ts's
 * {@code saveAccounts}/{@code updateAccounts} for JSON byte-compatibility.
 */
public class AccountPool {
    public List<Account> accounts;
    public int activeIndex;                          // sticky selection when no lane is given
    public Map<String, Integer> activeIndexByLane;

    public AccountPool() {
        this.accounts = new ArrayList<>();
        this.activeIndex = 0;
        this.activeIndexByLane = new LinkedHashMap<>();
    }

    public AccountPool(List<Account> accounts, int activeIndex, Map<String, Integer> activeIndexByLane) {
        this.accounts = accounts != null ? accounts : new ArrayList<>();
        this.activeIndex = activeIndex;
        this.activeIndexByLane = activeIndexByLane != null ? activeIndexByLane : new LinkedHashMap<>();
    }
}
