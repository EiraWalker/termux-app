package com.termux.app.privateadb;

import java.util.*;

/** Bounded per-window NSD observations. Never probes or retries an already tried port. */
final class NsdRecoveryPolicy {
    static final int LIMIT = 32;
    static final long SETTLE_MS = 1500;
    final long deadline;
    private final ArrayDeque<Integer> ports = new ArrayDeque<>();
    private final Set<Integer> seen = new HashSet<>();
    private boolean terminal;
    NsdRecoveryPolicy(long deadline) { this.deadline = deadline; }
    synchronized void offer(int port) {
        if (!terminal && port > 0 && port <= 65535 && seen.size() < LIMIT && seen.add(port)) ports.add(port);
    }
    synchronized Integer next(long now) { return terminal || now >= deadline ? null : ports.poll(); }
    synchronized boolean finished(int code) {
        terminal = terminal || code == 0 || code == 2;
        return terminal;
    }
    synchronized void close() { terminal = true; ports.clear(); }

    /** Retain arrivals during an outstanding resolve; re-resolve observed services round-robin. */
    static final class Services<T> {
        private final LinkedHashMap<String,T> items = new LinkedHashMap<>();
        synchronized boolean put(String key, T value) {
            if (!items.containsKey(key) && items.size() >= LIMIT) return false;
            items.put(key, value); return true;
        }
        synchronized T next() {
            if (items.isEmpty()) return null;
            String key = items.keySet().iterator().next();
            T value = items.remove(key); items.put(key, value); return value;
        }
        synchronized void remove(String key) { items.remove(key); }
        synchronized void clear() { items.clear(); }
    }
}
