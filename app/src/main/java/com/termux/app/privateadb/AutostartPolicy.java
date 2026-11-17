package com.termux.app.privateadb;

import java.net.InetAddress;
import java.util.Collection;

/** Pure recovery policy: heartbeat is local liveness, not remote ADB authentication. */
public final class AutostartPolicy {
    public static final long STALE_MS = 45000, WINDOW_MS = 45000;
    private AutostartPolicy() {}
    public static boolean validBoot(String boot) {
        return boot != null && boot.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    }
    public static boolean healthy(int version, String boot, String currentBoot, long sample,
                                  long now, long watch, long child, String state) {
        return version == 1 && validBoot(boot) && boot.equals(currentBoot) && sample >= 0 &&
            sample <= now && now - sample < STALE_MS && watch > 0 && child > 0 && "healthy".equals(state);
    }
    public static long retryDelay(int failures) {
        if (failures <= 1) return 60000;
        if (failures == 2) return 120000;
        if (failures == 3) return 240000;
        return 300000;
    }
    public static boolean localEndpoint(InetAddress host, int port, Collection<InetAddress> local) {
        return host != null && !host.isAnyLocalAddress() && !host.isMulticastAddress() &&
            port > 0 && port <= 65535 && local.contains(host);
    }
    public static boolean leaseExpired(String leaseBoot, String currentBoot, long deadline, long now) {
        return !validBoot(leaseBoot) || !leaseBoot.equals(currentBoot) || deadline <= now;
    }
}
