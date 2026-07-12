package io.github.intisy.ai.core.oauth;

/**
 * Java analog of the JS {@code { access, expires, refresh }} object returned by
 * {@code refreshAccessToken} (see {@code libs/core-auth/src/oauth.ts:99-103}).
 */
public class Refreshed {
    public final String access;
    public final long expires;   // epoch ms
    public final String refresh;

    public Refreshed(String access, long expires, String refresh) {
        this.access = access;
        this.expires = expires;
        this.refresh = refresh;
    }
}
