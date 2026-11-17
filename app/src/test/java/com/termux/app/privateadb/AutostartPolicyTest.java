package com.termux.app.privateadb;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutostartPolicyTest {
    private static final String BOOT = "01234567-89ab-cdef-0123-456789abcdef";
    @Test public void heartbeatMustMatchBootAndFreshMonotonicTime() {
        assertTrue(AutostartPolicy.healthy(1, BOOT, BOOT, 1000, 45999, 7, 8, "healthy"));
        assertFalse(AutostartPolicy.healthy(1, BOOT, BOOT, 1000, 46000, 7, 8, "healthy"));
        assertFalse(AutostartPolicy.healthy(1, "old", BOOT, 1000, 1001, 7, 8, "healthy"));
        assertFalse(AutostartPolicy.healthy(1, BOOT, BOOT, 1001, 1000, 7, 8, "healthy"));
        assertFalse(AutostartPolicy.healthy(1, BOOT, BOOT, 1000, 1001, 0, 8, "healthy"));
        assertFalse(AutostartPolicy.healthy(1, BOOT, BOOT, 1000, 1001, 7, 8, "failure"));
    }
    @Test public void retryBackoffIsBoundedAndNeverBelowOneMinute() {
        assertEquals(60000, AutostartPolicy.retryDelay(1));
        assertEquals(120000, AutostartPolicy.retryDelay(2));
        assertEquals(240000, AutostartPolicy.retryDelay(3));
        assertEquals(300000, AutostartPolicy.retryDelay(4));
        assertEquals(300000, AutostartPolicy.retryDelay(100));
    }
    @Test public void leaseExpiresAcrossBootOrAtDeadline() {
        assertFalse(AutostartPolicy.leaseExpired(BOOT, BOOT, 45000, 44999));
        assertTrue(AutostartPolicy.leaseExpired(BOOT, BOOT, 45000, 45000));
        assertTrue(AutostartPolicy.leaseExpired("invalid", BOOT, 45000, 0));
        assertTrue(AutostartPolicy.leaseExpired(BOOT, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 45000, 0));
    }
    @Test public void nsdRejectsForeignWildcardAndInvalidPorts() throws Exception {
        java.net.InetAddress own = java.net.InetAddress.getByAddress(new byte[]{10,0,0,2});
        java.util.List<java.net.InetAddress> local = java.util.Collections.singletonList(own);
        assertTrue(AutostartPolicy.localEndpoint(own, 37655, local));
        assertFalse(AutostartPolicy.localEndpoint(java.net.InetAddress.getByAddress(new byte[]{10,0,0,3}), 37655, local));
        assertFalse(AutostartPolicy.localEndpoint(own, 0, local));
        assertFalse(AutostartPolicy.localEndpoint(own, 65536, local));
        assertFalse(AutostartPolicy.localEndpoint(null, 37655, local));
        java.net.InetAddress any = java.net.InetAddress.getByAddress(new byte[]{0,0,0,0});
        assertFalse(AutostartPolicy.localEndpoint(any, 1, java.util.Collections.singletonList(any)));
    }
}
