package io.github.intisy.ai.core.store;

import java.util.Map;

/**
 * Java analog of the JS {@code CoreAccount} (see {@code libs/core-auth/src/types.ts:29-43}).
 *
 * Field names and declaration order match the JS source EXACTLY for JSON byte-compatibility:
 * the on-disk {@code accounts.json} must be readable/writable by both this class (via gson)
 * and the JS library. Boxed reference types (not primitives) are used throughout so an unset
 * field serializes as "absent" (gson omits null fields by default, same as JS
 * {@code JSON.stringify} omitting {@code undefined} properties) rather than as a noisy default.
 */
public class Account {
    public String id;                              // stable identity (usually the account email)
    public String email;
    public String refresh;                         // OAuth refresh token (the durable credential)
    public String access;
    public Long expires;                            // epoch ms
    public Long addedAt;
    public Long lastUsed;
    public Boolean enabled;                         // user-disabled accounts are skipped by selection
    public Map<String, Long> rateLimitResetTimes;   // lane -> epoch ms the lane is rate-limited until
    public Long coolingDownUntil;                   // epoch ms; transient backoff across all lanes
    public String cooldownReason;                   // transient (raw error text) - never shown in UI rows
    public String disabledReason;                   // why the SYSTEM disabled the account
    public Map<String, Object> meta;                // provider extras, opaque to the harness
}
