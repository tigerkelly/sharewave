package com.sharewave.server;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory session store. Sessions expire after a configurable
 * period of inactivity (default 5 minutes — see ServerConfig.getSessionTimeoutMinutes).
 */
public class SessionManager {

    private final long timeoutMs;

    /** Uses the default timeout (5 minutes). */
    public SessionManager() {
        this(5);
    }

    /** @param timeoutMinutes inactivity timeout, in minutes; must be positive */
    public SessionManager(int timeoutMinutes) {
        if (timeoutMinutes <= 0) timeoutMinutes = 5;
        this.timeoutMs = timeoutMinutes * 60L * 1000;
    }

    /** Inactivity timeout, in seconds — exposed so clients can show an accurate countdown. */
    public long timeoutSeconds() { return timeoutMs / 1000; }

    private record Session(int userId, long lastAccess) {}

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    /** Creates a session token for the given user id. */
    public String createSession(int userId) {
        byte[] bytes = new byte[24];
        rng.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(userId, System.currentTimeMillis()));
        return token;
    }

    /**
     * Resolves a token to a user id, refreshing its last-access timestamp.
     * Returns -1 if missing or expired.
     */
    public int resolve(String token) {
        if (token == null || token.isBlank()) return -1;
        Session s = sessions.get(token);
        if (s == null) return -1;
        if (System.currentTimeMillis() - s.lastAccess() > timeoutMs) {
            sessions.remove(token);
            return -1;
        }
        sessions.put(token, new Session(s.userId(), System.currentTimeMillis()));
        return s.userId();
    }

    /** Invalidates a token (logout). */
    public void invalidate(String token) {
        sessions.remove(token);
    }

    /** Returns current active session count. */
    public int activeCount() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().lastAccess() > timeoutMs);
        return sessions.size();
    }
}
