package io.github.intisy.ai.core.manager;

import io.github.intisy.ai.core.store.Account;

/** Java analog of the JS {@code { account, access }} object returned by {@code AccountManager#acquire}. */
public class Acquired {
    public final Account account;
    public final String access;

    public Acquired(Account account, String access) {
        this.account = account;
        this.access = access;
    }
}
