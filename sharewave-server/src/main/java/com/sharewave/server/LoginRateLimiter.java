package com.sharewave.server;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection for the login endpoint.
 *
 * Tracks failed login attempts per remote IP address. After
 * MAX_FAILURES consecutive failures within the tracking window the IP
 * is locked out for LOCKOUT_SECONDS. A successful login resets that
 * IP's counter immediately.
 *
 * All state is in-memory — counters reset on server restart. That's
 * intentional: a restart is a legitimate admin action, and persisting
 * lockouts to disk adds complexity with little security gain for a
 * personal/small-team deployment.
 */
public final class LoginRateLimiter {

    /** Max consecutive failures before lockout. */
    private static final int  MAX_FAILURES     = 5;

    /** How long a locked-out IP must wait (seconds). */
    private static final long LOCKOUT_SECONDS  = 5 * 60; // 5 minutes

    private record Bucket(int failures, Instant lockedUntil) {}

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Call this before processing a login attempt.
     *
     * @param ip the remote IP address (from {@code request.getRemoteAddr()})
     * @return {@code true} if the IP is currently locked out (caller should
     *         return 429 immediately without checking credentials)
     */
    public boolean isBlocked(String ip) {
        Bucket b = buckets.get(ip);
        if (b == null) return false;
        if (b.lockedUntil() != null && Instant.now().isBefore(b.lockedUntil())) return true;
        // Lockout expired — clean up so the bucket doesn't linger forever
        if (b.lockedUntil() != null) buckets.remove(ip);
        return false;
    }

    /**
     * Record a failed login attempt for this IP. Applies a lockout once
     * the failure count reaches {@code MAX_FAILURES}.
     */
    public void recordFailure(String ip) {
        buckets.compute(ip, (k, existing) -> {
            int failures = (existing == null || existing.lockedUntil() != null)
                    ? 1 : existing.failures() + 1;
            if (failures >= MAX_FAILURES) {
                return new Bucket(failures, Instant.now().plusSeconds(LOCKOUT_SECONDS));
            }
            return new Bucket(failures, null);
        });
    }

    /**
     * Reset the failure counter for this IP after a successful login.
     */
    public void recordSuccess(String ip) {
        buckets.remove(ip);
    }

    /**
     * Returns how many seconds remain on a lockout for the given IP,
     * or 0 if not locked out. Used to include the wait time in the
     * 429 error message shown to the user.
     */
    public long secondsRemaining(String ip) {
        Bucket b = buckets.get(ip);
        if (b == null || b.lockedUntil() == null) return 0;
        long remaining = b.lockedUntil().getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, remaining);
    }

    /** Returns the configured max failure count (for use in error messages). */
    public static int maxFailures() { return MAX_FAILURES; }

    /** Returns the current failure count for an IP (0 if none recorded). */
    public int getFailures(String ip) {
        Bucket b = buckets.get(ip);
        return b == null ? 0 : b.failures();
    }
}
